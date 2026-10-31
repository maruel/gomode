// Pins public local-model artifacts independently of download scheduling and HTTP transport.
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

internal fun LocalLlmModel.artifact(): ModelArtifact? {
    val repository: String
    val revision: String
    val size: Long
    val sha256: String
    when (this) {
        LocalLlmModel.GEMMA_4_E2B -> {
            repository = "litert-community/gemma-4-E2B-it-litert-lm"
            revision = "1caa527c1e88b55f046fcd8d2b4265606680663d"
            size = 2_588_147_712
            sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
        }

        LocalLlmModel.GEMMA_4_E4B -> {
            repository = "litert-community/gemma-4-E4B-it-litert-lm"
            revision = "e6a3892a8c22cf87ff99583996149cf3cb32452b"
            size = 3_659_530_240
            sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0"
        }

        LocalLlmModel.LFM_3_1_7B -> {
            // The custom 64K INT8 export needs a published artifact URL before downloads can be enabled.
            return null
        }
    }
    return ModelArtifact(
        fileName,
        "https://huggingface.co/$repository/resolve/$revision/$fileName".toHttpUrl(),
        size,
        sha256,
    )
}
