// Connection feedback that native settings show on one saved service.
package com.fghbuild.gomode.ui.settings

internal data class ServiceStatus(
    val readiness: String? = null,
    val error: String? = null,
    val retryLabel: String? = null,
    val needsAttention: Boolean = false,
)
