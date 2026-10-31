// Defines verified model artifacts and their public Hugging Face release URLs.
package com.fghbuild.gomode.voice

import com.fghbuild.gomode.data.LocalLlmModel
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

internal data class ModelArtifact(
    val fileName: String,
    val url: HttpUrl,
    val size: Long,
    val sha256: String,
) {
    init {
        require(fileName.isNotBlank() && '/' !in fileName && '\\' !in fileName)
        require(size > 0 && sha256.matches(Regex("[a-f0-9]{64}")))
    }
}

internal fun LocalLlmModel.releaseUrl(): HttpUrl {
    val repository =
        when (this) {
            LocalLlmModel.GEMMA_4_E2B -> "litert-community/gemma-4-E2B-it-litert-lm"
            LocalLlmModel.GEMMA_4_E4B -> "litert-community/gemma-4-E4B-it-litert-lm"
            LocalLlmModel.LFM_3_1_7B_32K -> "maruel/LFM3-1.7B-LiteRT-INT8-32K"
            LocalLlmModel.LFM_3_1_7B_64K -> "maruel/LFM3-1.7B-LiteRT-INT8-64K"
        }
    return "https://huggingface.co/$repository/resolve/main/$fileName".toHttpUrl()
}
