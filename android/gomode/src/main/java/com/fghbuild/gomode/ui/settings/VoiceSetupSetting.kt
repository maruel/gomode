// Native voice setup: all-in-one or separate stages in pipeline order, with per-control errors.
//
// A segmented control selects All-in-one, one gateway native audio model, or Separate stages:
// Recognition, Language model, and Speech pickers. Recognition and Speech offer Local, then the
// gateway's models for a composable language model, or the language model's own gateway speech
// otherwise. The Language model lists gateway choices, then a "Local" group with the
// local model variants and Whistle + Needle. An on-device language model requires device
// recognition and speech: those pickers show Local and disable gateway options while the saved
// gateway selections stay, and the model's download, storage, and diagnostics follow the Language
// model picker. Speech details follow the Speech picker: the speaking voice for gateway speech, the
// speaking voice and speed for device speech. All-in-one has no device stage, so the gateway is
// required. Without a service, a gateway language model cannot run, so a line under the Language
// model picker says to add a service or choose a local model.
//
// Following the gateway shows the explicit setup it resolves to with the loaded catalog. Every
// edit, the speaking voice and speed included, saves that explicit setup with the edit, so the
// first pick ends following and a later gateway default no longer changes the setup or detaches a
// speaking voice from its model. A followed choice the gateway removed shows as the explicit
// choice, unavailable under its model picker, until a pick replaces it. Until the catalog loads, a followed setup
// selects neither option and both are disabled, so no pick skips that resolution. Without a
// service, a followed setup shows as separate stages on the device, which its first edit saves.
//
// Choice discovery shows progress or its failure with a retry in place of pickers that need the
// gateway; an on-device language model keeps its pickers and shows it below the Language model.
// A failed save shows under the control that made it until the next successful save.
package com.fghbuild.gomode.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.fghbuild.gomode.data.GatewayVoiceSetup
import com.fghbuild.gomode.data.LocalLlmModel
import com.fghbuild.gomode.data.SavedVoiceSetup
import com.fghbuild.gomode.data.SettingsLoadState
import com.fghbuild.gomode.data.SettingsRepository
import com.fghbuild.gomode.data.SettingsState
import com.fghbuild.gomode.data.SpeechStage
import com.fghbuild.gomode.data.SpeechStageLocation
import com.fghbuild.gomode.data.VoiceLanguageModel
import com.fghbuild.gomode.data.VoiceSetupMode
import com.fghbuild.gomode.service.ServiceSettingsClient
import com.fghbuild.gomode.voice.GatewayVoiceChoices
import com.fghbuild.gomode.voice.ResolvedVoiceSetup
import com.fghbuild.gomode.voice.VoiceSetupException
import com.fghbuild.gomode.voice.VoiceSetupKind
import com.fghbuild.gomode.voice.discoverVoiceChoices
import com.fghbuild.voicegateway.sdk.v2.VoiceChoice
import com.fghbuild.voicegateway.sdk.v2.VoiceTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

private const val UNAVAILABLE_MODEL = "Choose an available model"

private sealed interface ChoiceDiscovery {
    /** No service is active, so no gateway offers choices. */
    data object Unavailable : ChoiceDiscovery

    data object Loading : ChoiceDiscovery

    data class Ready(
        val choices: GatewayVoiceChoices,
    ) : ChoiceDiscovery

    data class Failed(
        val message: String,
    ) : ChoiceDiscovery
}

/** The setup controls that own a save failure. */
private enum class SetupControl(
    val noun: String,
) {
    SETUP("voice setup"),
    RECOGNITION("recognition"),
    MODEL("model"),
    SPEECH("speech"),
}

private data class SaveFailure(
    val control: SetupControl,
    val message: String,
)

/**
 * The explicit setup [saved] runs: the catalog resolves a followed setup, and without a service only
 * device stages can run. A followed setup with a service stays unresolved until its catalog loads.
 */
private fun explicitSetup(
    saved: SavedVoiceSetup,
    choices: GatewayVoiceChoices?,
    noService: Boolean,
): SavedVoiceSetup {
    val preference = saved.preference
    return when {
        choices != null -> {
            choices.explicit(saved)
        }

        noService && preference.mode == VoiceSetupMode.FOLLOW_GATEWAY -> {
            saved.copy(
                preference =
                    preference.copy(
                        mode = VoiceSetupMode.SEPARATE_STAGES,
                        recognition = SpeechStageLocation.DEVICE,
                        speech = SpeechStageLocation.DEVICE,
                    ),
            )
        }

        else -> {
            saved
        }
    }
}

/** What the setup pickers show and how they save; [shown] is the explicit form of the saved setup. */
private class SetupEditor(
    val repository: SettingsRepository,
    val settings: SettingsState,
    val choices: GatewayVoiceChoices?,
    val noService: Boolean,
    val shown: SavedVoiceSetup,
    /** Set when a gateway language model's setup resolves against the loaded catalog. */
    val resolution: Result<ResolvedVoiceSetup>?,
    /** The gateway choice the setup runs, even when one of its speech models is unavailable. */
    val choice: VoiceChoice?,
    val enabled: Boolean,
    private val saveFailure: SaveFailure?,
    val save: (SetupControl, suspend () -> Unit) -> Unit,
) {
    fun failure(control: SetupControl): String? = saveFailure?.takeIf { it.control == control }?.message

    /** The resolution failure that belongs to [stage], or to the model when null. */
    fun resolutionError(stage: SpeechStage?): String? =
        (resolution?.exceptionOrNull() as? VoiceSetupException)?.takeIf { it.stage == stage }?.message

    fun explicit(saved: SavedVoiceSetup): SavedVoiceSetup = explicitSetup(saved, choices, noService)

    /** Saves [transform] applied to the explicit setup, with the loaded gateway's selections. */
    fun edit(
        control: SetupControl,
        transform: (SavedVoiceSetup) -> SavedVoiceSetup,
    ) = save(control) { saveExplicit(transform) }

    /** Saves [transform] applied to the explicit setup; the caller reports a failure. */
    suspend fun saveExplicit(transform: (SavedVoiceSetup) -> SavedVoiceSetup) =
        repository.updateSavedVoiceSetup(choices?.gatewayURL) { transform(explicit(it)) }

    /** Saves the speaking voice of [choice] with the explicit setup, which keeps running that choice. */
    suspend fun saveSpeakingVoice(
        choices: GatewayVoiceChoices,
        choice: VoiceChoice,
        voiceName: String,
    ) = repository.updateSpeakingVoice(choices.gatewayURL, choice.id, voiceName, ::explicit)
}

@Composable
internal fun VoiceSetupSetting(
    settings: SettingsState,
    repository: SettingsRepository,
    enabled: Boolean,
    bearerTokenFor: (String) -> String?,
    bearerToken: String?,
    authEpoch: Long,
) {
    val identity = listOf(settings.activeServiceId, settings.activeServiceURL, bearerToken, authEpoch)
    val noService = settings.activeServiceURL.isBlank()
    var discovery by remember(identity) {
        mutableStateOf(if (noService) ChoiceDiscovery.Unavailable else ChoiceDiscovery.Loading)
    }
    var retry by remember(identity) { mutableStateOf(0) }
    var saveFailure by remember(identity) { mutableStateOf<SaveFailure?>(null) }
    var saving by remember(identity) { mutableStateOf(false) }
    val tokenProvider by rememberUpdatedState(bearerTokenFor)
    val scope = rememberCoroutineScope()
    LaunchedEffect(identity, retry) {
        if (noService) return@LaunchedEffect
        discovery = ChoiceDiscovery.Loading
        try {
            val service = ServiceSettingsClient().fetch(settings.activeServiceURL)
            val choices = discoverVoiceChoices(settings.activeServiceURL, service, tokenProvider)
            coroutineContext.ensureActive()
            discovery = ChoiceDiscovery.Ready(choices)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // User-visible discovery boundary.
            discovery = ChoiceDiscovery.Failed(e.message ?: "Unable to load voice choices")
        }
    }
    val choices = (discovery as? ChoiceDiscovery.Ready)?.choices
    val saved =
        SavedVoiceSetup(
            settings.voiceSetup,
            choices?.savedSetup(settings) ?: GatewayVoiceSetup(),
            settings.localLlmModel,
        )
    val shown = explicitSetup(saved, choices, noService)
    val mode = shown.preference.mode.takeUnless { it == VoiceSetupMode.FOLLOW_GATEWAY }
    val resolution =
        if (choices != null && mode != null &&
            shown.preference.effectiveLanguageModel == VoiceLanguageModel.GATEWAY
        ) {
            try {
                Result.success(choices.resolveSetup(shown.preference, shown.gateway))
            } catch (e: VoiceSetupException) {
                Result.failure(e)
            }
        } else {
            null
        }
    val choice =
        resolution?.let {
            try {
                choices?.resolveChoice(shown.preference, shown.gateway)
            } catch (_: VoiceSetupException) {
                null
            }
        }
    val editor =
        SetupEditor(
            repository = repository,
            settings = settings,
            choices = choices,
            noService = noService,
            shown = shown,
            resolution = resolution,
            choice = choice,
            enabled = enabled && !saving && settings.loadState == SettingsLoadState.Ready,
            saveFailure = saveFailure,
            save = { control, write ->
                scope.launch {
                    saving = true
                    try {
                        write()
                        saveFailure = null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Exception,
                    ) {
                        // Display failed persistence under the control that made it.
                        saveFailure = SaveFailure(control, "Unable to save ${control.noun}: ${e.message}")
                    } finally {
                        saving = false
                    }
                }
            },
        )
    val status: @Composable () -> Unit = {
        DiscoveryStatus(discovery, enabled) { retry += 1 }
    }
    SetupSegments(
        mode,
        allInOneAvailable = choices?.setupChoices(VoiceSetupKind.ALL_IN_ONE).orEmpty().isNotEmpty(),
        // An unresolved followed setup has no explicit setup for a pick to save.
        enabled = editor.enabled && mode != null,
        error = editor.failure(SetupControl.SETUP),
    ) { target -> editor.edit(SetupControl.SETUP) { it.copy(preference = it.preference.copy(mode = target)) } }
    when (mode) {
        null -> {
            status()
        }

        VoiceSetupMode.ALL_IN_ONE -> {
            when {
                choices != null -> {
                    AllInOneSetting(editor, choices)
                }

                // All-in-one runs on a gateway; Separate stages stays available for local speech.
                noService -> {
                    Text(
                        "Add a service to use All-in-one.",
                        modifier = Modifier.testTag("gomode-all-in-one-no-service"),
                    )
                }

                else -> {
                    status()
                }
            }
        }

        else -> {
            val onDevice = shown.preference.languageModel != VoiceLanguageModel.GATEWAY
            if (choices == null && !onDevice && !noService) {
                status()
            } else {
                SeparateStagesSetting(editor, downloadsEnabled = enabled, status = if (onDevice) status else null)
            }
        }
    }
}

@Composable
private fun SetupSegments(
    mode: VoiceSetupMode?,
    allInOneAvailable: Boolean,
    enabled: Boolean,
    error: String?,
    onSelect: (VoiceSetupMode) -> Unit,
) {
    val options = listOf(VoiceSetupMode.ALL_IN_ONE to "All-in-one", VoiceSetupMode.SEPARATE_STAGES to "Separate stages")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // A label that wraps at large text sizes keeps both segments the same height.
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            options.forEachIndexed { index, (option, label) ->
                SegmentedButton(
                    selected = mode == option,
                    onClick = { if (mode != option) onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    // All-in-one needs a gateway native audio model; on-device stages need none.
                    enabled = enabled && (option != VoiceSetupMode.ALL_IN_ONE || allInOneAvailable || mode == option),
                    label = { Text(label) },
                    modifier =
                        Modifier.fillMaxHeight().testTag(
                            if (option == VoiceSetupMode.ALL_IN_ONE) {
                                "gomode-voice-setup-all-in-one"
                            } else {
                                "gomode-voice-setup-separate-stages"
                            },
                        ),
                )
            }
        }
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.testTag("gomode-voice-setup-error"),
            )
        }
    }
}

@Composable
private fun DiscoveryStatus(
    discovery: ChoiceDiscovery,
    enabled: Boolean,
    onRetry: () -> Unit,
) {
    when (discovery) {
        ChoiceDiscovery.Loading -> {
            Text("Loading voice choices…", modifier = Modifier.testTag("gomode-voice-choices-loading"))
        }

        is ChoiceDiscovery.Failed -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    discovery.message,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f, fill = false).testTag("gomode-voice-choice-error"),
                )
                IconButton(
                    onClick = onRetry,
                    enabled = enabled,
                    modifier = Modifier.testTag("gomode-voice-choices-retry"),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Retry loading voice choices")
                }
            }
        }

        // Without a gateway, or with its choices, nothing is pending.
        ChoiceDiscovery.Unavailable, is ChoiceDiscovery.Ready -> {}
    }
}

@Composable
private fun AllInOneSetting(
    editor: SetupEditor,
    choices: GatewayVoiceChoices,
) {
    val listed = choices.setupChoices(VoiceSetupKind.ALL_IN_ONE)
    val resolved = editor.resolution?.getOrNull()
    val selectedID = editor.choice?.id
    SettingsPicker(
        label = "Model",
        value = listed.firstOrNull { it.id == selectedID }?.label ?: UNAVAILABLE_MODEL,
        enabled = editor.enabled,
        tag = "gomode-voice-model",
        menuTag = "gomode-voice-models",
        error = editor.failure(SetupControl.MODEL) ?: editor.resolution?.exceptionOrNull()?.message,
        errorTag = "gomode-voice-model-error",
    ) {
        listed.forEach { choice ->
            Item(choice.label, selected = choice.id == selectedID, tag = "gomode-voice-choice-${choice.id}") {
                editor.save(SetupControl.MODEL) {
                    editor.repository.updateVoiceChoice(choices.gatewayURL, choice.id) { saved ->
                        editor.explicit(saved).let {
                            it.copy(preference = it.preference.copy(mode = VoiceSetupMode.ALL_IN_ONE))
                        }
                    }
                }
            }
        }
    }
    resolved?.choice?.let { choice ->
        SpeakingVoiceSetting(choices, choice, editor.settings, editor.enabled) {
            editor.saveSpeakingVoice(choices, choice, it)
        }
    }
}

@Composable
private fun SeparateStagesSetting(
    editor: SetupEditor,
    downloadsEnabled: Boolean,
    status: (@Composable () -> Unit)?,
) {
    val shown = editor.shown
    StagePicker(editor, SpeechStage.RECOGNITION)
    LanguageModelPicker(editor)
    status?.invoke()
    if (shown.preference.languageModel == VoiceLanguageModel.LOCAL_LLM) {
        ModelDownloadSetting(shown.localLlmModel, enabled = downloadsEnabled)
    }
    StagePicker(editor, SpeechStage.SYNTHESIS)
    if (shown.preference.effectiveSpeech == SpeechStageLocation.GATEWAY) {
        val choices = editor.choices
        val choice = editor.resolution?.getOrNull()?.choice
        if (choices != null && choice != null) {
            SpeakingVoiceSetting(choices, choice, editor.settings, editor.enabled) {
                editor.saveSpeakingVoice(choices, choice, it)
            }
        }
    } else {
        DeviceSpeakingVoiceSetting(
            shown.preference.deviceSpeechVoice,
            editor.settings.voiceLanguageTag,
            editor.enabled,
        ) { name ->
            editor.saveExplicit { it.copy(preference = it.preference.copy(deviceSpeechVoice = name)) }
        }
        DeviceSpeechRateSetting(shown.preference.deviceSpeechRate, editor.enabled) { rate ->
            editor.saveExplicit { it.copy(preference = it.preference.copy(deviceSpeechRate = rate)) }
        }
    }
}

@Composable
private fun LanguageModelPicker(editor: SetupEditor) {
    val shown = editor.shown
    val languageModel = shown.preference.languageModel
    val choices = editor.choices
    val listed = choices?.setupChoices(VoiceSetupKind.SEPARATE_STAGES).orEmpty()
    val gatewayID = editor.choice?.id ?: shown.gateway.separateStagesChoiceID
    SettingsPicker(
        label = "Language model",
        value =
            when (languageModel) {
                VoiceLanguageModel.GATEWAY -> listed.firstOrNull { it.id == gatewayID }?.label ?: UNAVAILABLE_MODEL
                VoiceLanguageModel.LOCAL_LLM -> shown.localLlmModel.label
                VoiceLanguageModel.NEEDLE_WHISTLE -> "Whistle + Needle"
            },
        enabled = editor.enabled,
        tag = "gomode-language-model",
        menuTag = "gomode-language-models",
        error = editor.failure(SetupControl.MODEL) ?: editor.resolutionError(null),
        errorTag = "gomode-language-model-error",
        hint =
            "Add a service, or choose a Local language model.".takeIf {
                editor.noService && languageModel == VoiceLanguageModel.GATEWAY
            },
    ) {
        if (choices != null) {
            listed.forEach { choice ->
                Item(
                    choice.label,
                    selected = languageModel == VoiceLanguageModel.GATEWAY && choice.id == gatewayID,
                    tag = "gomode-voice-choice-${choice.id}",
                ) {
                    editor.save(SetupControl.MODEL) {
                        editor.repository.updateVoiceChoice(choices.gatewayURL, choice.id) { saved ->
                            editor.explicit(saved).let {
                                it.copy(preference = it.preference.withLanguageModel(VoiceLanguageModel.GATEWAY))
                            }
                        }
                    }
                }
            }
        }
        Header("Local")
        LocalLlmModel.entries.forEach { model ->
            Item(
                model.label,
                selected = languageModel == VoiceLanguageModel.LOCAL_LLM && shown.localLlmModel == model,
                tag = "gomode-model-${model.name}",
            ) {
                editor.edit(SetupControl.MODEL) {
                    it.copy(
                        preference = it.preference.withLanguageModel(VoiceLanguageModel.LOCAL_LLM),
                        localLlmModel = model,
                    )
                }
            }
        }
        Item(
            "Whistle + Needle",
            selected = languageModel == VoiceLanguageModel.NEEDLE_WHISTLE,
            tag = "gomode-language-model-needle-whistle",
        ) {
            editor.edit(SetupControl.MODEL) {
                it.copy(preference = it.preference.withLanguageModel(VoiceLanguageModel.NEEDLE_WHISTLE))
            }
        }
    }
}

@Composable
private fun StagePicker(
    editor: SetupEditor,
    stage: SpeechStage,
) {
    val preference = editor.shown.preference
    val choices = editor.choices
    val onDevice = preference.languageModel != VoiceLanguageModel.GATEWAY
    val location = preference.effectiveLocation(stage)
    val resolved = editor.resolution?.getOrNull()
    val languageModel =
        editor.choice ?: choices?.setupChoices(VoiceSetupKind.SEPARATE_STAGES)?.firstOrNull {
            it.id == editor.shown.gateway.separateStagesChoiceID
        }
    val catalogModels =
        when (stage) {
            SpeechStage.RECOGNITION -> choices?.catalog?.recognitionModels
            SpeechStage.SYNTHESIS -> choices?.catalog?.synthesisModels
        }.orEmpty()
    // Only a composable language model takes gateway speech models; another runs its own gateway speech.
    val fixed = languageModel?.takeIf { it.composable != true }
    val models = if (fixed == null) catalogModels else emptyList()
    val ownSpeech = fixed?.takeIf { VoiceTransport.WebRTC in it.transports.orEmpty() }
    val modelID =
        when (stage) {
            SpeechStage.RECOGNITION -> resolved?.recognitionModelID
            SpeechStage.SYNTHESIS -> resolved?.synthesisModelID
        } ?: editor.shown.gateway.modelID(stage) ?: models.firstOrNull()?.id
    val (label, key, control) =
        when (stage) {
            SpeechStage.RECOGNITION -> Triple("Recognition", "recognition", SetupControl.RECOGNITION)
            SpeechStage.SYNTHESIS -> Triple("Speech", "speech", SetupControl.SPEECH)
        }
    val gateway = location == SpeechStageLocation.GATEWAY
    SettingsPicker(
        label = label,
        value =
            when {
                !gateway -> "Local"
                fixed != null -> ownSpeech?.label ?: UNAVAILABLE_MODEL
                else -> models.firstOrNull { it.id == modelID }?.label ?: UNAVAILABLE_MODEL
            },
        enabled = editor.enabled,
        tag = "gomode-$key",
        menuTag = "gomode-$key-options",
        error = editor.failure(control) ?: editor.resolutionError(stage),
        errorTag = "gomode-$key-error",
    ) {
        Item("Local", selected = !gateway, tag = "gomode-$key-device") {
            editor.edit(control) { it.copy(preference = it.preference.withStage(stage, SpeechStageLocation.DEVICE)) }
        }
        ownSpeech?.let { choice ->
            Item(choice.label, selected = gateway, tag = "gomode-$key-gateway", enabled = !onDevice) {
                editor.edit(control) {
                    it.copy(preference = it.preference.withStage(stage, SpeechStageLocation.GATEWAY))
                }
            }
        }
        if (choices != null) {
            models.forEach { model ->
                Item(
                    model.label,
                    selected = gateway && model.id == modelID,
                    tag = "gomode-$key-model-${model.id}",
                    enabled = !onDevice,
                ) {
                    editor.save(control) {
                        editor.repository.updateSpeechModel(choices.gatewayURL, stage, model.id) { saved ->
                            editor.explicit(saved).let {
                                it.copy(preference = it.preference.withStage(stage, SpeechStageLocation.GATEWAY))
                            }
                        }
                    }
                }
            }
        }
    }
}
