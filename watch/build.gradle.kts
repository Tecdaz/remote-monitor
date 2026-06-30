// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

// Per design (obs #300, D5): enforce dependency locking across all subprojects.
// `lockAllConfigurations()` makes `./gradlew :app:dependencies` read from
// `gradle/dependency-locks/*.lockfile` and fail if a transitive dep would
// resolve to a different version. The lockfile is generated via
// `./gradlew :app:dependencies --write-locks` and committed to git.
subprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}
