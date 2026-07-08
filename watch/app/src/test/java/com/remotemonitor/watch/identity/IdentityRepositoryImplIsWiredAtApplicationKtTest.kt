package com.remotemonitor.watch.identity

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * wear-bed-picker-onboarding D28 caller-rule enforcement.
 *
 * Konsist is absent from this project (verified: `rg -i konsist
 * *.gradle.kts` returns zero hits), so this is the documented
 * Robolectric grep fallback. Asserts:
 *
 *   1. `WatchApplication.kt` (the DI / ServiceLocator wiring point)
 *      references `IdentityRepositoryImpl`.
 *   2. No other `.kt` file under `src/main` references it.
 *
 * Callers (UI screens, ViewModels, sync workers) MUST depend on the
 * [IdentityRepository] interface — referencing the concrete impl would
 * leak the DataStore-bound implementation into layers that should be
 * swappable in tests. Test sources (`src/test/`, `src/androidTest/`)
 * are out of scope; they may legitimately mock either side.
 *
 * If `WatchApplication.kt` is renamed in the future, update
 * [allowedReferencer] below (per R4 in `sdd/wear-bed-picker-onboarding/tasks`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.remotemonitor.watch.WatchApplication::class)
class IdentityRepositoryImplIsWiredAtApplicationKtTest {

    @Test
    fun `IdentityRepositoryImpl is only referenced from WatchApplication`() {
        val appContext = ApplicationProvider.getApplicationContext<android.app.Application>()
        val mainSrcRoot = File("src/main/java").resolve(
            appContext.packageName.replace('.', File.separatorChar),
        )
        assertTrue(
            "expected $mainSrcRoot to exist (production source root)",
            mainSrcRoot.isDirectory,
        )

        val allowedReferencer = File(mainSrcRoot, "WatchApplication.kt")
        assertTrue(
            "expected $allowedReferencer to exist (DI wiring point)",
            allowedReferencer.isFile,
        )

        // The impl file itself defines the class — excluded from the scan.
        val implFile = File(mainSrcRoot, "identity/IdentityRepositoryImpl.kt")
        val implRefRegex = Regex("""\bIdentityRepositoryImpl\b""")

        val kotlinFiles = mainSrcRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

        val offenders = mutableListOf<String>()
        var sawWiring = false
        for (file in kotlinFiles) {
            if (file.absolutePath == implFile.absolutePath) continue
            if (!implRefRegex.containsMatchIn(file.readText())) continue
            if (file.absolutePath == allowedReferencer.absolutePath) {
                sawWiring = true
            } else {
                offenders += file.absolutePath
            }
        }

        assertTrue(
            "expected WatchApplication.kt to reference IdentityRepositoryImpl " +
                "(the DI wiring point) but no match was found",
            sawWiring,
        )
        assertEquals(
            "IdentityRepositoryImpl must only be referenced from " +
                "${allowedReferencer.name}. Offenders:\n  - " +
                offenders.joinToString("\n  - "),
            emptyList<String>(),
            offenders,
        )
    }
}