// Owns local model preparation and drained replacement across activity and voice lifetimes.
package com.fghbuild.gomode.voice

import android.content.Context
import android.util.Log
import com.fghbuild.gomode.data.LocalLlmModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class LocalModelSelection(
    val languageTag: String,
    val prepareSetup: suspend () -> LocalConversationPreparation,
    val isCurrent: () -> Boolean,
    val model: LocalLlmModel = LocalLlmModel.GEMMA_4_E2B,
)

internal data class PreparedLocalModel(
    val generation: Long,
    val engine: LocalLlmEngine,
    val setup: VoiceSetup,
    val lifetime: VoiceConversationLifetime,
)

internal sealed interface LocalModelState {
    data object Idle : LocalModelState

    data object Preparing : LocalModelState

    data object Ready : LocalModelState

    data class Failed(
        val message: String,
    ) : LocalModelState
}

internal class LocalModelOwner(
    context: Context,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val engineFactory: (LocalLlmModel) -> LocalLlmEngine = { model ->
        LocalLlmEngine(context, ioDispatcher = ioDispatcher, model = model)
    },
) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val operations = Mutex()
    private val mutableState = MutableStateFlow<LocalModelState>(LocalModelState.Idle)
    val state = mutableState.asStateFlow()

    // Selection and generation are changed synchronously at the UI/auth boundary, not by late workers.
    private var selection: LocalModelSelection? = null
    private var generation = 0L
    private var closed = false
    private var preparation: Deferred<PreparedLocalModel?>? = null

    // Workers mutate the handle under operations. Selection changes read it only to invalidate turns.
    // Disposal failure retains the handle and prevents replacement.
    @Volatile
    private var allocated: LocalLlmEngine? = null

    @Synchronized
    fun select(next: LocalModelSelection?) {
        if (closed || selection === next) return
        selection = next
        schedule()
    }

    @Synchronized
    fun clearSelection(expected: LocalModelSelection?) {
        // An older activity's disposal/auth callback cannot invalidate a newer activity's selection.
        if (selection === expected) select(null)
    }

    @Synchronized
    fun retry() {
        if (!closed && selection != null && mutableState.value is LocalModelState.Failed) {
            schedule(reuseAllocated = true)
        }
    }

    suspend fun acquire(): PreparedLocalModel {
        while (true) {
            val pending =
                synchronized(this) {
                    check(!closed && selection?.isCurrent() == true) { "Local model service credentials are not ready" }
                    if (mutableState.value is LocalModelState.Failed) schedule(reuseAllocated = true)
                    checkNotNull(preparation)
                }
            val prepared = checkNotNull(pending.await()) { "Local model selection changed" }
            synchronized(this) {
                requireCurrent(prepared.generation)
                if (prepared.lifetime.isCurrent) return prepared
                // Acquisition may have been canceled before a controller received the prepared handle.
                // A finished authorization cannot reuse that handle's native conversation or prefill.
                schedule(reuseAllocated = true)
            }
        }
    }

    @Synchronized
    fun restartConversation() {
        if (!closed && selection != null) schedule(reuseAllocated = true)
    }

    @Synchronized
    fun release(prepared: PreparedLocalModel) {
        // Drain and reset the conversation, retaining selected weights across ordinary voice disconnects.
        if (!closed && prepared.generation == generation) {
            schedule(reuseAllocated = true)
        }
    }

    private fun schedule(reuseAllocated: Boolean = false) {
        // Invalidate before the disposal worker can run, including selection-before-disconnect.
        allocated?.invalidateTurns()
        val request = ++generation
        val selected = selection
        mutableState.value = if (selected == null) LocalModelState.Idle else LocalModelState.Preparing
        preparation =
            scope.async {
                operations.withLock {
                    if (!owns(request)) return@withLock null
                    try {
                        val reusable = allocated?.takeIf { reuseAllocated && it.isModelLoaded }
                        if (reusable == null) {
                            allocated?.closeAndJoin()
                            allocated = null
                        }
                        if (selected == null || !selected.isCurrent() || !owns(request)) return@withLock null
                        val authorized = selected.prepareSetup()
                        val setup = authorized.setup
                        if (!selected.isCurrent() || !owns(request)) return@withLock null
                        val engine =
                            try {
                                val context =
                                    EngineSessionContext(
                                        selected.languageTag,
                                        setup.systemInstruction,
                                        setup.serviceContextText,
                                        setup.tools,
                                        setup.mcpClient,
                                    )
                                if (reusable != null) {
                                    reusable.resetConversation(context)
                                    reusable
                                } else {
                                    engineFactory(selected.model).also { engine ->
                                        allocated = engine
                                        engine.initialize(context)
                                    }
                                }
                            } catch (e: LinkageError) {
                                throw IllegalStateException(
                                    "Local model native runtime is unavailable. Reinstall Go Mode with " +
                                        "compatible LiteRT-LM libraries.",
                                    e,
                                )
                            }
                        if (!selected.isCurrent() || !owns(request)) {
                            engine.closeAndJoin()
                            allocated = null
                            return@withLock null
                        }
                        publish(request, LocalModelState.Ready)
                        PreparedLocalModel(request, engine, setup, authorized.lifetime)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (
                        // Preparation is an actionable UI boundary.
                        @Suppress("TooGenericExceptionCaught") e: Exception,
                    ) {
                        try {
                            allocated?.closeAndJoin()
                            allocated = null
                        } catch (
                            // Retain a failed disposal handle rather than allocating a replacement.
                            @Suppress("TooGenericExceptionCaught") cleanupFailure: Exception,
                        ) {
                            if (cleanupFailure !== e) e.addSuppressed(cleanupFailure)
                        }
                        publish(request, LocalModelState.Failed("Local model preparation failed: ${e.message}"))
                        throw e
                    }
                }
            }
    }

    @Synchronized
    private fun requireCurrent(request: Long) {
        if (closed || request != generation || selection?.isCurrent() != true) {
            throw VoiceMcpAuthChangedException()
        }
    }

    @Synchronized
    private fun owns(request: Long): Boolean = request == generation

    @Synchronized
    private fun publish(
        request: Long,
        next: LocalModelState,
    ) {
        if (request == generation) mutableState.value = next
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        selection = null
        schedule()
        // Never cancel a LiteRT collector: native inference outlives Flow cancellation.
        // Cancel the owned worker scope only after its serialized disposal completes.
        preparation?.invokeOnCompletion { failure ->
            if (failure != null) Log.e("LocalModelOwner", "Local model disposal failed", failure)
            scope.cancel()
        }
    }
}
