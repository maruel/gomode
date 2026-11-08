// Exception thrown by the Halo BLE library for all transport-level errors.
package com.fghbuild.halo.ble

class HaloException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)
