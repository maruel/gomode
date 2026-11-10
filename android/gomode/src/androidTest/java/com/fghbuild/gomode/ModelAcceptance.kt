// Excludes real model inference and large model transfers from ordinary emulator runs.
package com.fghbuild.gomode

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class ModelAcceptance
