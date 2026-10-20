// Controlled local WebRTC peer for gateway continuity tests using the production Android signaling transport.
package com.fghbuild.gomode.voice

import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal class ControlledRtcGateway(
    private val onMessage: (String, (String) -> Boolean) -> Unit,
) : AutoCloseable {
    private val factory = PeerConnectionFactory.builder().createPeerConnectionFactory()
    private var peer: PeerConnection? = null
    private var channel: DataChannel? = null

    @Suppress("TooManyFunctions") // PeerConnection.Observer defines the native peer's required callbacks.
    fun answer(sdp: String): String {
        check(peer == null) { "The conversation unexpectedly created a second WebRTC transport" }
        val gathered = CountDownLatch(1)
        val connection =
            requireNotNull(
                factory.createPeerConnection(
                    PeerConnection.RTCConfiguration(emptyList()),
                    object : PeerConnection.Observer {
                        override fun onIceCandidate(candidate: IceCandidate) = Unit

                        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit

                        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit

                        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

                        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                            if (state == PeerConnection.IceGatheringState.COMPLETE) gathered.countDown()
                        }

                        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit

                        override fun onAddStream(stream: MediaStream) = Unit

                        override fun onRemoveStream(stream: MediaStream) = Unit

                        override fun onRenegotiationNeeded() = Unit

                        override fun onAddTrack(
                            receiver: RtpReceiver,
                            streams: Array<out MediaStream>,
                        ) = Unit

                        override fun onDataChannel(dc: DataChannel) {
                            channel = dc
                            dc.registerObserver(
                                object : DataChannel.Observer {
                                    override fun onBufferedAmountChange(previousAmount: Long) = Unit

                                    override fun onStateChange() = Unit

                                    override fun onMessage(buffer: DataChannel.Buffer) {
                                        val bytes = ByteArray(buffer.data.remaining())
                                        buffer.data.get(bytes)
                                        onMessage(bytes.toString(Charsets.UTF_8)) { text ->
                                            dc.send(
                                                DataChannel.Buffer(
                                                    ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8)),
                                                    false,
                                                ),
                                            )
                                        }
                                    }
                                },
                            )
                        }
                    },
                ),
            )
        peer = connection
        val remote = CompletableFuture<Unit>()
        connection.setRemoteDescription(
            DescriptionObserver(onSet = { remote.complete(Unit) }, onError = remote::completeExceptionally),
            SessionDescription(SessionDescription.Type.OFFER, sdp),
        )
        remote.get(10, TimeUnit.SECONDS)
        val answer = CompletableFuture<SessionDescription>()
        connection.createAnswer(
            DescriptionObserver(onCreate = {
                answer.complete(it)
            }, onError = answer::completeExceptionally),
            MediaConstraints(),
        )
        val local = CompletableFuture<Unit>()
        connection.setLocalDescription(
            DescriptionObserver(onSet = {
                local.complete(Unit)
            }, onError = local::completeExceptionally),
            answer.get(10, TimeUnit.SECONDS),
        )
        local.get(10, TimeUnit.SECONDS)
        check(gathered.await(10, TimeUnit.SECONDS)) { "Gateway ICE gathering timed out" }
        return JSONObject()
            .put(
                "sdp",
                connection.localDescription.description,
            ).put("sessionID", "one-conversation")
            .toString()
    }

    override fun close() {
        channel?.unregisterObserver()
        channel?.close()
        channel?.dispose()
        channel = null
        peer?.close()
        peer?.dispose()
        peer = null
        factory.dispose()
    }
}

private class DescriptionObserver(
    private val onCreate: (SessionDescription) -> Unit = {},
    private val onSet: () -> Unit = {},
    private val onError: (Throwable) -> Boolean,
) : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) = onCreate(description)

    override fun onSetSuccess() = onSet()

    override fun onCreateFailure(message: String) {
        onError(IllegalStateException(message))
    }

    override fun onSetFailure(message: String) {
        onError(IllegalStateException(message))
    }
}
