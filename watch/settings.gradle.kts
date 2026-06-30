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
        // T-WATCH-12 / REQ-WATCH-12: the Samsung Health Sensor SDK AAR
        // is committed at app/libs/ and consumed via a flat directory
        // repository. This gives the AAR a proper module identity
        // (group:name:version) so AGP propagates it to BOTH the main
        // and the test compile classpaths; `files(...)` deps are not
        // propagated to the test classpath in AGP 9.x. The AAR
        // contains a `aar-metadata.properties` with `aarFormatVersion=1.0`.
        flatDir {
            dirs("${rootDir}/app/libs")
        }
    }
}

rootProject.name = "watch"
include(":app")
 