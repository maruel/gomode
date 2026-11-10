// Identifies documentation captures that require explicit screenshot-runner setup.
package com.fghbuild.gomode

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class DocumentationCapture
