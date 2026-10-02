// Marks Android tests that run against the standalone voice gateway fixture.
package com.fghbuild.gomode

/**
 * Instrumented tests that need `scripts/android_e2e.py --voice`. That mode serves
 * `internal/cmd/android-voice-fixture` on the `baseUrl` instrumentation argument
 * and selects tests carrying this annotation.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class VoiceFixture
