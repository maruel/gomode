// Marks Android tests that run against the standalone generic hosted frontend fixture.
package com.fghbuild.gomode

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class StandaloneHostedFixture
