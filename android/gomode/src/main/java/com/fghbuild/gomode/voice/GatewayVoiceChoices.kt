// Discovers gateway voice choices, explains gateway HTTP failures, and resolves the saved setup.
//
// Resolution follows web/src/VoiceSession.ts (setupChoices, compatibleChoices, choiceSupportsSpeech,
// transportFor, resolveSpeechModel, and _applySetup). All-in-one lists native audio choices; separate
// stages list composable choices, or text choices when the gateway offers no composable one. The
// transport is text only when the device owns both recognition and speech; otherwise it is WebRTC.
// A choice supports the stage owners its speechModes list, or, without them, client speech over
// text and gateway speech over WebRTC. A saved choice that this mode does not list falls back to
// the transport's advertised default, then the first compatible choice; a removed saved choice, a
// choice without the selected stage owners, or a removed saved speech model blocks connecting until
// the user picks another. Composable choices resolve each gateway stage's model, the first listed
// by default. SettingsRepository documents the mode that follows the gateway's choice.
//
// Connecting pins the resolved choice, stage owners, and speech models per session; recovery keeps
// them and fails when the gateway withdrew one. Speaking voices are saved per
// gateway and choice when the gateway owns speech and the choice advertises voices; the advertised
// default applies until then, and a removed saved voice blocks connecting. External gateway
// metadata requests carry no hosted-service credentials; embedded gateways use the service
// credentials.
package com.fghbuild.gomode.voice

import com.fghbuild.gomode.data.GatewayVoiceSetup
import com.fghbuild.gomode.data.SettingsLoadState
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.SettingsState
import com.fghbuild.gomode.data.SpeechStage
import com.fghbuild.gomode.data.SpeechStageLocation
import com.fghbuild.gomode.data.VoiceLanguageModel
import com.fghbuild.gomode.data.VoiceSetupMode
import com.fghbuild.gomode.sdk.v1.Settings
import com.fghbuild.gomode.service.credentialedHTTPClient
import com.fghbuild.gomode.service.isTransientHTTPStatus
import com.fghbuild.gomode.service.resolveServiceURL
import com.fghbuild.gomode.service.serviceOrigin
import com.fghbuild.voicegateway.sdk.v2.ApiClient
import com.fghbuild.voicegateway.sdk.v2.SpeechConfig
import com.fghbuild.voicegateway.sdk.v2.SpeechLocation
import com.fghbuild.voicegateway.sdk.v2.SpeechModel
import com.fghbuild.voicegateway.sdk.v2.VoiceChoice
import com.fghbuild.voicegateway.sdk.v2.VoiceChoicesResp
import com.fghbuild.voicegateway.sdk.v2.VoiceTransport
import java.io.IOException
import java.net.SocketTimeoutException

// Keep these messages aligned with gatewayHTTPError in web/src/VoiceSession.ts.
internal fun gatewayHTTPError(
    status: Int,
    path: String,
): String? =
    when (status) {
        401 -> {
            "Voice authorization failed (HTTP 401)."
        }

        403 -> {
            "Voice access denied (HTTP 403)."
        }

        404 -> {
            if (path.startsWith("/api/voicegateway/v2/voice/rtc/") && !path.endsWith("/rtc/offer")) {
                "Voice session not found (HTTP 404)."
            } else {
                "Voice gateway not found (HTTP 404)."
            }
        }

        408, 504 -> {
            "Voice connection timed out (HTTP $status)."
        }

        429 -> {
            "Voice gateway busy (HTTP 429)."
        }

        in 500..599 -> {
            "Voice gateway unavailable (HTTP $status)."
        }

        else -> {
            null
        }
    }

// Transient failures are worth retrying while recovering an established conversation.
internal class GatewayConnectionException(
    message: String,
    val transient: Boolean,
    cause: Throwable? = null,
) : IOException(message, cause)

internal fun gatewayNetworkError(error: IOException): GatewayConnectionException =
    GatewayConnectionException(
        if (error is SocketTimeoutException) {
            "Voice connection timed out."
        } else {
            "Voice gateway unreachable."
        },
        transient = true,
        error,
    )

// Apply only to gateway requests; service discovery and token failures retain
// their own context. Classify status before the generated SDK decodes the body.
internal val voiceGatewayHTTPClient =
    credentialedHTTPClient
        .newBuilder()
        .addInterceptor { chain ->
            val response =
                try {
                    chain.proceed(chain.request())
                } catch (error: IOException) {
                    throw gatewayNetworkError(error)
                }
            val message = gatewayHTTPError(response.code, response.request.url.encodedPath)
            if (message != null) {
                response.close()
                throw GatewayConnectionException(message, isTransientHTTPStatus(response.code))
            }
            response
        }.build()

/** VoiceSetupKind names the choices a resolved setup lists: native audio models or language models. */
internal enum class VoiceSetupKind {
    ALL_IN_ONE,
    SEPARATE_STAGES,
}

/** One gateway's resolved setup: the choice, the stage owners, and composable speech models. */
internal data class ResolvedVoiceSetup(
    val recognition: SpeechStageLocation,
    val speech: SpeechStageLocation,
    val choice: VoiceChoice,
    /** Set only for a composable choice with gateway recognition. */
    val recognitionModelID: String?,
    /** Set only for a composable choice with gateway speech. */
    val synthesisModelID: String?,
) {
    val transport: VoiceTransport get() = transportFor(recognition, speech)
}

internal data class GatewayVoiceChoices(
    val gatewayURL: String,
    val catalog: VoiceChoicesResp,
) {
    private val choices: List<VoiceChoice> get() = catalog.choices.orEmpty()

    /** Native audio models for all-in-one, or independently selectable language models. */
    fun setupChoices(kind: VoiceSetupKind): List<VoiceChoice> {
        val composable = choices.any { it.composable == true }
        return choices.filter {
            when (kind) {
                VoiceSetupKind.ALL_IN_ONE -> {
                    nativeVoiceChoice(it)
                }

                VoiceSetupKind.SEPARATE_STAGES -> {
                    it.composable == true || (!composable && VoiceTransport.Text in it.transports.orEmpty())
                }
            }
        }
    }

    /** The choices of [kind] that serve the given stage owners. */
    fun compatibleChoices(
        kind: VoiceSetupKind,
        recognition: SpeechStageLocation,
        speech: SpeechStageLocation,
    ): List<VoiceChoice> {
        val transport = transportFor(recognition, speech)
        val composable = choices.any { it.composable == true }
        return setupChoices(kind).filter {
            (!composable || it.composable == true || VoiceTransport.Text !in it.transports.orEmpty()) &&
                transport in it.transports.orEmpty() &&
                choiceSupportsSpeech(it, recognition, speech)
        }
    }

    fun speakingVoice(
        settings: SettingsState,
        choice: VoiceChoice,
    ): String {
        if (settings.voiceSetup.effectiveSpeech != SpeechStageLocation.GATEWAY) return ""
        val voices = speakingVoices(choice)
        if (voices.isEmpty()) return ""
        val saved = settings.speakingVoices[gatewayURL]?.get(choice.id)
        if (!saved.isNullOrEmpty() && saved !in voices) {
            throw VoiceSetupException("Saved speaking voice is unavailable. Select a speaking voice in Settings.")
        }
        return saved.orEmpty()
    }

    fun speakingVoices(choice: VoiceChoice): List<String> {
        val voices = choice.voices.orEmpty()
        if (voices.any { it.isBlank() } || voices.toSet().size != voices.size) {
            throw VoiceSetupException("Gateway returned invalid speaking voices")
        }
        return voices
    }

    /** Resolves the session selection, or checks that a pinned one is still available. */
    fun resolve(
        settings: SettingsState,
        pinned: GatewayVoiceSelection? = null,
    ): GatewayVoiceSelection {
        if (pinned == null) {
            val setup = resolveSetup(settings)
            return GatewayVoiceSelection(
                gatewayURL = gatewayURL,
                choiceID = setup.choice.id,
                voiceName = speakingVoice(settings, setup.choice),
                recognition = setup.recognition,
                speech = setup.speech,
                recognitionModelID = setup.recognitionModelID,
                synthesisModelID = setup.synthesisModelID,
            )
        }
        requireLoadedVoiceSettings(settings)
        if (gatewayURL != pinned.gatewayURL) {
            throw VoiceSetupException("Gateway voice choice changed. Start a new voice session.")
        }
        val choice =
            choices.firstOrNull {
                it.id == pinned.choiceID &&
                    pinned.transport in it.transports.orEmpty() &&
                    choiceSupportsSpeech(it, pinned.recognition, pinned.speech)
            } ?: throw VoiceSetupException("Session voice choice is unavailable. Start a new voice session.")
        val removedModel =
            listOf(
                catalog.recognitionModels to pinned.recognitionModelID,
                catalog.synthesisModels to pinned.synthesisModelID,
            ).any { (models, id) -> id != null && models.orEmpty().none { it.id == id } }
        if (removedModel) {
            throw VoiceSetupException("Session speech model is unavailable. Start a new voice session.")
        }
        val voices = if (pinned.speech == SpeechStageLocation.GATEWAY) speakingVoices(choice) else emptyList()
        if (pinned.voiceName.isNotEmpty() && pinned.voiceName !in voices) {
            throw VoiceSetupException("Session speaking voice is unavailable. Start a new voice session.")
        }
        return pinned
    }

    /** Applies the saved setup to this catalog with the same rules as the web's _applySetup. */
    fun resolveSetup(settings: SettingsState): ResolvedVoiceSetup {
        requireLoadedVoiceSettings(settings)
        val preference = settings.voiceSetup
        check(preference.effectiveLanguageModel == VoiceLanguageModel.GATEWAY) {
            "The language model runs on this device"
        }
        val saved = settings.gatewayVoiceSetups[SettingsRepository.normalizeURL(gatewayURL)] ?: GatewayVoiceSetup()
        val savedID = saved.choiceID(preference.mode)
        val kind =
            when (preference.mode) {
                VoiceSetupMode.FOLLOW_GATEWAY -> followedKind(savedID ?: defaultChoiceID(VoiceTransport.WebRTC))
                VoiceSetupMode.ALL_IN_ONE -> VoiceSetupKind.ALL_IN_ONE
                VoiceSetupMode.SEPARATE_STAGES -> VoiceSetupKind.SEPARATE_STAGES
            }
        val recognition = preference.effectiveRecognition
        val speech = preference.effectiveSpeech
        val listed = setupChoices(kind)
        val compatible = compatibleChoices(kind, recognition, speech)
        val advertised = defaultChoiceID(transportFor(recognition, speech))
        // A choice another kind lists keeps its own mode's selection; this mode uses its default.
        val id =
            savedID?.takeUnless { id -> choices.any { it.id == id } && listed.none { it.id == id } }
                ?: compatible.firstOrNull { it.id == advertised }?.id
                ?: compatible.firstOrNull()?.id
                ?: throw VoiceSetupException(
                    "The voice gateway has no model for this setup. Choose another setup in Settings.",
                )
        val choice =
            listed.firstOrNull { it.id == id }
                ?: throw VoiceSetupException("Saved voice choice is unavailable. Select a voice choice in Settings.")
        if (choice !in compatible) {
            throw VoiceSetupException(
                "This model does not support the selected recognition and speech. Choose another in Settings.",
            )
        }
        val composable = choice.composable == true
        return ResolvedVoiceSetup(
            recognition = recognition,
            speech = speech,
            choice = choice,
            recognitionModelID =
                if (composable && recognition == SpeechStageLocation.GATEWAY) {
                    resolveSpeechModel(catalog.recognitionModels.orEmpty(), saved, SpeechStage.RECOGNITION)
                } else {
                    null
                },
            synthesisModelID =
                if (composable && speech == SpeechStageLocation.GATEWAY) {
                    resolveSpeechModel(catalog.synthesisModels.orEmpty(), saved, SpeechStage.SYNTHESIS)
                } else {
                    null
                },
        )
    }

    // The followed choice selects the kind it belongs to; without language models, all-in-one applies.
    private fun followedKind(choiceID: String?): VoiceSetupKind {
        val candidate = choices.firstOrNull { it.id == choiceID }
        return if ((candidate != null && nativeVoiceChoice(candidate)) ||
            setupChoices(VoiceSetupKind.SEPARATE_STAGES).isEmpty()
        ) {
            VoiceSetupKind.ALL_IN_ONE
        } else {
            VoiceSetupKind.SEPARATE_STAGES
        }
    }

    private fun defaultChoiceID(transport: VoiceTransport): String? =
        catalog.defaults
            .orEmpty()
            .firstOrNull { it.transport == transport }
            ?.id
}

/** Native audio models are indivisible and offer no text transport. */
internal fun nativeVoiceChoice(choice: VoiceChoice): Boolean =
    choice.composable != true && VoiceTransport.Text !in choice.transports.orEmpty()

/** Text carries turns only when the device owns both recognition and speech. */
internal fun transportFor(
    recognition: SpeechStageLocation,
    speech: SpeechStageLocation,
): VoiceTransport =
    if (recognition == SpeechStageLocation.DEVICE && speech == SpeechStageLocation.DEVICE) {
        VoiceTransport.Text
    } else {
        VoiceTransport.WebRTC
    }

internal fun choiceSupportsSpeech(
    choice: VoiceChoice,
    recognition: SpeechStageLocation,
    speech: SpeechStageLocation,
): Boolean {
    val modes =
        choice.speechModes ?: choice.transports.orEmpty().mapNotNull {
            when (it) {
                VoiceTransport.Text -> SpeechConfig(SpeechLocation.Client, SpeechLocation.Client)
                VoiceTransport.WebRTC -> SpeechConfig(SpeechLocation.Gateway, SpeechLocation.Gateway)
                is VoiceTransport.Other -> null
            }
        }
    return modes.any { it.input == wireLocation(recognition) && it.output == wireLocation(speech) }
}

internal fun wireLocation(location: SpeechStageLocation): SpeechLocation =
    when (location) {
        SpeechStageLocation.DEVICE -> SpeechLocation.Client
        SpeechStageLocation.GATEWAY -> SpeechLocation.Gateway
    }

/** A saved model must still be listed; otherwise the first listed model applies. */
internal fun resolveSpeechModel(
    models: List<SpeechModel>,
    saved: GatewayVoiceSetup,
    stage: SpeechStage,
): String {
    val label = if (stage == SpeechStage.RECOGNITION) "recognition" else "speech"
    val selected = saved.modelID(stage)
    if (selected != null) {
        if (models.none { it.id == selected }) {
            throw VoiceSetupException(
                "The selected $label model is unavailable. Choose another $label model in Settings.",
            )
        }
        return selected
    }
    return models.firstOrNull()?.id
        ?: throw VoiceSetupException(
            "The gateway has no $label models. Choose device $label or an all-in-one model in Settings.",
        )
}

internal fun usesGatewayVoice(settings: SettingsState): Boolean =
    settings.voiceSetup.effectiveLanguageModel == VoiceLanguageModel.GATEWAY

internal suspend fun discoverVoiceChoices(
    serviceURL: String,
    serviceSettings: Settings,
    bearerTokenFor: (String) -> String?,
): GatewayVoiceChoices {
    val advertised = serviceSettings.webShell.voiceGateway.url
    if (advertised.isNullOrBlank()) throw VoiceSetupException("Voice is not available for this service")
    val gatewayURL = resolveServiceURL(serviceURL, advertised)
    // External gateway metadata is public: never forward service credentials to it.
    val headers =
        if (serviceOrigin(serviceURL) == serviceOrigin(gatewayURL)) {
            serviceAuthHeaders(gatewayURL, bearerTokenFor)
        } else {
            emptyMap()
        }
    val catalog = ApiClient(gatewayURL, httpClient = voiceGatewayHTTPClient).voiceChoices(headers)
    // Each family advertises one default that must name a choice offering it.
    val invalidDefaults =
        catalog.defaults.orEmpty().any { advertised ->
            advertised.id.isBlank() ||
                catalog.choices.orEmpty().none {
                    advertised.transport in it.transports.orEmpty() &&
                        it.id == advertised.id
                }
        } || catalog.defaults
            .orEmpty()
            .map { it.transport }
            .toSet()
            .size != catalog.defaults.orEmpty().size
    val invalidModels =
        listOf(catalog.recognitionModels.orEmpty(), catalog.synthesisModels.orEmpty()).any { models ->
            models.any { it.id.isBlank() || it.label.isBlank() } || models.map { it.id }.toSet().size != models.size
        }
    val invalidSpeechModes =
        catalog.choices.orEmpty().any { choice ->
            val modes = choice.speechModes ?: return@any false
            modes.isEmpty() ||
                modes.any { mode ->
                    listOf(
                        mode.input,
                        mode.output,
                    ).any { it !is SpeechLocation.Client && it !is SpeechLocation.Gateway }
                }
        }
    if (catalog.choices.orEmpty().any { it.id.isBlank() || it.label.isBlank() } ||
        catalog.choices
            .orEmpty()
            .map { it.id }
            .toSet()
            .size != catalog.choices.orEmpty().size ||
        invalidDefaults ||
        invalidModels ||
        invalidSpeechModes
    ) {
        throw VoiceSetupException("Gateway returned invalid voice choices")
    }
    return GatewayVoiceChoices(gatewayURL, catalog)
}

internal fun requireLoadedVoiceSettings(settings: SettingsState) {
    when (val state = settings.loadState) {
        SettingsLoadState.Loading -> throw VoiceSetupException("Saved settings are still loading")
        SettingsLoadState.Ready -> Unit
        is SettingsLoadState.Failed -> throw VoiceSetupException(state.message)
    }
}

/** The setup a session pins; recovery reconnects with the same choice, voice, stage owners, and models. */
internal data class GatewayVoiceSelection(
    val gatewayURL: String,
    val choiceID: String,
    /** Empty uses the provider default; only gateway speech names a voice. */
    val voiceName: String,
    val recognition: SpeechStageLocation,
    val speech: SpeechStageLocation,
    /** Set only for a composable choice with gateway recognition. */
    val recognitionModelID: String?,
    /** Set only for a composable choice with gateway speech. */
    val synthesisModelID: String?,
) {
    val transport: VoiceTransport get() = transportFor(recognition, speech)
}
