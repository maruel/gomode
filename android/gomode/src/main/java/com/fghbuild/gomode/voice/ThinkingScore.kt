// Loads the shared note score and piano timbre used by native and browser thinking audio.
package com.fghbuild.gomode.voice

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class ThinkingScore(
    val duration: Double,
    val gain: Double,
    val attack: Double,
    val release: Double,
    val partials: List<PianoPartial>,
    val echoes: List<PianoEcho>,
    val notes: List<PianoNote>,
) {
    companion object {
        fun load(context: Context): ThinkingScore =
            // This bundled, generated score is a fixed 55 KB asset, not external input.
            context.assets.open("thinking-score.json").bufferedReader().use {
                Json.decodeFromString<ThinkingScore>(it.readText())
            }
    }
}

@Serializable
internal data class PianoPartial(
    val ratio: Double,
    val level: Double,
    val decay: Double,
)

@Serializable
internal data class PianoEcho(
    val delay: Double,
    val feedback: Double,
    val level: Double,
)

@Serializable
internal data class PianoNote(
    val at: Double,
    val midi: Int,
    val duration: Double,
    val level: Double,
)
