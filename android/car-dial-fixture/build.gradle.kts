// Test-only phone account and independent instrumentation for system-driven car dialing.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "com.fghbuild.gomode.cardialfixture"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.fghbuild.gomode.cardialfixture"
        minSdk = 33
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        warningsAsErrors = true
        abortOnError = true
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }
}
kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}
detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("$rootDir/detekt.yml"))
}
ktlint { version.set("1.8.0") }
dependencies {
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(libs.androidx.datastore.preferences)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

// Instrumentation runs in a separate APK and needs the app installed before it starts.
tasks.matching { it.name == "connectedDebugAndroidTest" }.configureEach {
    dependsOn(":gomode:installDebug")
}
