pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "gomode"
include(":gomode")
include(":voicegateway-sdk")
project(":voicegateway-sdk").projectDir = file("../sdk/voicegateway/kotlin")
include(":gomode-sdk")
project(":gomode-sdk").projectDir = file("../sdk/gomode/kotlin")
include(":mcp-sdk")
project(":mcp-sdk").projectDir = file("../sdk/mcp/kotlin")
include(":oauth-sdk")
project(":oauth-sdk").projectDir = file("../sdk/oauth/kotlin")
include(":halo-sdk")
project(":halo-sdk").projectDir = file("../sdk/halo")
