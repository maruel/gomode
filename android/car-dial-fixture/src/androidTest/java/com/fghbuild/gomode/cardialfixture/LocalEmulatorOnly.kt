// Marks emulator variants and platform characterizations that only full local runs execute.
package com.fghbuild.gomode.cardialfixture

// CI runs one emulator case per mechanism; a marked case repeats a covered mechanism in
// another state, waits on a real production deadline, or measures device performance.
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class LocalEmulatorOnly
