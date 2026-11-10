// Runs permission-denial scenarios before microphone-granting tests in a separate instrumentation process.
package com.fghbuild.gomode

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class DeniedMicrophonePermission
