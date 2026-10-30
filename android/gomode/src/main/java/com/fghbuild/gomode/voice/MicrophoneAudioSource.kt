// Owns continuous echo-cancelled microphone PCM and bounded recognizer audio delivery.
package com.fghbuild.gomode.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.FileInputStream

private const val RETAINED_AUDIO_BYTES = 16_000

// The microphone never waits for recognition. A slow or restarting recognizer gets
// at most 500 ms of recent PCM; overflow drops oldest samples, preserving alignment.
internal class MicrophoneAudioSource(
    private val scope: CoroutineScope,
) {
    @Volatile
    var assistantSpeaking = false
    private val mutableRms = MutableStateFlow(0f)
    val rms = mutableRms.asStateFlow()
    private val captureLock = Mutex()
    private val deliveryLock = Any()
    private val delivery = RecognitionAudioBuffer()
    private var capture: Job? = null
    private var onCaptureError: (String) -> Unit = {}
    private var failure: String? = null

    @Suppress("TooGenericExceptionCaught") // Platform capture and pipe failures are reported to the session owner.
    fun open(onError: (String) -> Unit): ParcelFileDescriptor {
        check(failure == null) { failure.orEmpty() }
        onCaptureError = onError
        val source = synchronized(deliveryLock) { delivery.openSource() }
        try {
            if (capture == null) {
                capture =
                    scope.launch(Dispatchers.IO) {
                        try {
                            // A muted/replaced capture releases AudioRecord before its successor opens it.
                            captureLock.withLock { record() }
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            synchronized(deliveryLock) { mutableRms.value = 0f }
                            withContext(Dispatchers.Main) {
                                val message = "Microphone capture failed: ${e.message}"
                                // A terminal recognizer callback may already have retired its
                                // error handler. Fail the next open too until the owner stops.
                                failure = message
                                onCaptureError(message)
                            }
                        }
                    }
            }
            return source
        } catch (e: Exception) {
            synchronized(deliveryLock) { delivery.close() }
            throw e
        }
    }

    // Only the recognizer source ends at an utterance boundary. Capture stays live.
    fun finishUtterance() {
        synchronized(deliveryLock) { delivery.finishUtterance() }
    }

    fun stop() {
        capture?.cancel()
        capture = null
        failure = null
        synchronized(deliveryLock) {
            delivery.close()
            mutableRms.value = 0f
        }
    }

    @SuppressLint("MissingPermission") // The voice-session owner obtains RECORD_AUDIO before starting.
    private suspend fun record() {
        val minimum = AudioRecord.getMinBufferSize(16_000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "16 kHz microphone capture is unavailable" }
        val recorder =
            AudioRecord
                .Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat
                        .Builder()
                        .setSampleRate(16_000)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                ).setBufferSizeInBytes(maxOf(minimum, 6_400))
                .build()
        var echo: AcousticEchoCanceler? = null
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone initialization failed" }
            if (AcousticEchoCanceler.isAvailable()) {
                echo =
                    checkNotNull(AcousticEchoCanceler.create(recorder.audioSessionId)) {
                        "Microphone echo canceller initialization failed"
                    }
                check(echo.setEnabled(true) == 0) { "Microphone echo canceller could not be enabled" }
            }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start" }
            val frame = ByteArray(640)
            val meter = MicrophoneRmsWindow()
            val gain = MicrophoneGainRamp(16_000, assistantSpeaking)
            val captureContext = kotlin.coroutines.coroutineContext
            while (captureContext.isActive) {
                val count = recorder.read(frame, 0, frame.size, AudioRecord.READ_NON_BLOCKING)
                check(count >= 0) { "Microphone read failed ($count)" }
                synchronized(deliveryLock) {
                    if (captureContext.isActive) {
                        gain.setSpeaking(assistantSpeaking)
                        gain.processPcm(frame, count)
                        delivery.append(frame, count)
                        meter.append(frame, count)?.let { mutableRms.value = it }
                    }
                }
                delay(10)
            }
        } finally {
            if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            echo?.release()
            recorder.release()
        }
    }
}

// The recognizer gets a blocking pipe, while a separately opened nonblocking read
// end evicts stale PCM without blocking if the recognizer consumes it first.
// Total app and kernel retention is bounded to 500 ms.
internal class RecognitionAudioBuffer : java.io.Closeable {
    private val pending = PcmBuffer(RETAINED_AUDIO_BYTES)
    private val scratch = ByteArray(RETAINED_AUDIO_BYTES + 2)
    private var pipe: Array<ParcelFileDescriptor>? = null
    private var evictionReader: FileInputStream? = null

    @Suppress("TooGenericExceptionCaught") // Descriptor setup must close both endpoints on failure.
    fun openSource(): ParcelFileDescriptor {
        closePipe()
        val endpoints = ParcelFileDescriptor.createPipe()
        try {
            val writer = endpoints[1].fileDescriptor
            Os.fcntlInt(
                writer,
                OsConstants.F_SETFL,
                Os.fcntlInt(writer, OsConstants.F_GETFL, 0) or OsConstants.O_NONBLOCK,
            )
            // Opening /proc/self/fd creates a separate file description, so its
            // O_NONBLOCK flag does not change the recognizer's blocking read end.
            evictionReader =
                FileInputStream(
                    Os.open("/proc/self/fd/${endpoints[0].fd}", OsConstants.O_RDONLY or OsConstants.O_NONBLOCK, 0),
                )
            pipe = endpoints
            return endpoints[0]
        } catch (e: Exception) {
            try {
                endpoints[0].close()
            } finally {
                endpoints[1].close()
            }
            throw e
        }
    }

    fun append(
        frame: ByteArray,
        count: Int,
    ) {
        val endpoints = pipe
        if (endpoints != null) discardStale(pending.byteCount + count)
        pending.append(frame, count)
        if (endpoints != null) {
            pending.drain { bytes, offset, length -> Os.write(endpoints[1].fileDescriptor, bytes, offset, length) }
        }
    }

    private fun discardStale(appBytes: Int) {
        val reader = checkNotNull(evictionReader)
        while (true) {
            val queued = reader.available()
            val excess = queued + appBytes - RETAINED_AUDIO_BYTES
            if (excess <= 0 || queued == 0) return
            val count = minOf(queued, ((excess + 1) / 2) * 2)
            val discarded = receive(reader, count)
            check(discarded % 2 == 0) { "Recognizer consumed an incomplete PCM sample" }
            if (discarded == 0) return
        }
    }

    private fun receive(
        reader: FileInputStream,
        count: Int,
    ): Int =
        try {
            Os.read(reader.fd, scratch, 0, count)
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.EAGAIN) 0 else throw e
        }

    fun finishUtterance() {
        // Retiring a recognizer more than once must preserve PCM already
        // captured for the next recognition request.
        if (pipe != null) {
            closePipe()
            pending.clear()
        }
    }

    override fun close() {
        try {
            closePipe()
        } finally {
            pending.clear()
        }
    }

    private fun closePipe() {
        val endpoints = pipe ?: return
        pipe = null
        try {
            evictionReader?.close()
        } finally {
            evictionReader = null
            try {
                endpoints[0].close()
            } finally {
                endpoints[1].close()
            }
        }
    }
}

internal class PcmBuffer(
    capacity: Int,
) {
    init {
        require(capacity > 0 && capacity % 2 == 0) { "PCM capacity must contain whole 16-bit samples" }
    }

    private val bytes = ByteArray(capacity)
    private var size = 0
    val byteCount: Int get() = size

    fun clear() {
        size = 0
    }

    fun append(
        frame: ByteArray,
        count: Int,
    ) {
        require(count % 2 == 0) { "PCM frames must contain whole 16-bit samples" }
        val kept = minOf(count, bytes.size)
        val retained = minOf(size, bytes.size - kept)
        bytes.copyInto(bytes, 0, size - retained, size)
        frame.copyInto(bytes, retained, count - kept, count)
        size = retained + kept
    }

    fun drain(write: (ByteArray, Int, Int) -> Int) {
        if (size == 0) return
        val written =
            try {
                write(bytes, 0, minOf(size, 4_096))
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EAGAIN) return else throw e
            }
        check(written % 2 == 0) { "Recognition audio delivery split a PCM sample" }
        bytes.copyInto(bytes, 0, written, size)
        size -= written
    }
}
