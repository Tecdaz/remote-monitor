package com.remotemonitor.watch.identity

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Behavioural tests for [IdentityRepositoryImpl] (wear-ui-guidelines D10).
 *
 * Robolectric-backed so the real AndroidX DataStore Preferences store is
 * exercised end-to-end (design D9 layering: JVM/Robolectric for the
 * DataStore-bound repository).
 *
 * The D24 atomic [IdentityRepository.persistPaired] write path is NOT
 * modified by this change; [observeBedNumber] is strictly additive and
 * only observes `KEY_BED_NUMBER`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = com.remotemonitor.watch.WatchApplication::class)
class IdentityRepositoryImplTest {

    /**
     * D10: `observeBedNumber()` reflects the latest value persisted to
     * DataStore, re-reading on every fresh collection (a subsequent
     * write is visible to a new collector — the reactive contract the
     * home vitals Flow relies on).
     */
    @Test
    fun S_observeBedNumber_reemits_on_DataStore_change() = runBlocking {
        val repo = IdentityRepositoryImpl(ApplicationProvider.getApplicationContext())
        repo.clear()

        assertNull("expected no bed number after clear", repo.observeBedNumber().first())

        repo.setBedNumber("3")
        assertEquals("3", repo.observeBedNumber().first())

        // A later write must be observed by a fresh collection — the
        // DataStore-backed flow re-emits the current value.
        repo.setBedNumber("4")
        assertEquals("4", repo.observeBedNumber().first())
    }

    /**
     * D24 invariant guard: the atomic `persistPaired(...)` batch write
     * is the pairing path and `observeBedNumber` sees the bed number it
     * writes — proving the additive observer does not bypass or replace
     * the atomic write.
     */
    @Test
    fun S_observeBedNumber_sees_persistPaired_bed_number() = runBlocking {
        val repo = IdentityRepositoryImpl(ApplicationProvider.getApplicationContext())
        repo.clear()

        repo.persistPaired(
            bedNumber = "2",
            patientNumberCipher = "cipher-xyz",
            patientId = "patient-uuid",
        )

        assertEquals("2", repo.observeBedNumber().first())
    }
}
