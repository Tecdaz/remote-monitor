package com.remotemonitor.watch.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1 polar-ecg-integration: [PolarEcgChunker] splits 130 Hz ECG
 * into 10 s snippets (1300 samples). Run from `watch/`:
 *   ./gradlew :app:testDebugUnitTest --tests "com.remotemonitor.watch.sensor.PolarEcgChunkerTest"
 */
class PolarEcgChunkerTest {

    @Test
    fun `empty input yields no snippets`() {
        val (snippets, rest) = PolarEcgChunker.chunk(1_000L, emptyList())
        assertTrue(snippets.isEmpty())
        assertTrue(rest.isEmpty())
    }

    @Test
    fun `exactly 1300 samples yields one snippet`() {
        val voltages = List(1300) { it }
        val (snippets, rest) = PolarEcgChunker.chunk(1_000L, voltages)
        assertEquals(1, snippets.size)
        assertEquals(1_000L, snippets[0].timestampMillis)
        assertEquals(130, snippets[0].sampleRateHz)
        assertEquals(1300, snippets[0].voltagesUv.size)
        assertTrue(rest.isEmpty())
    }

    @Test
    fun `partial remainder is kept not persisted`() {
        val voltages = List(1500) { it }
        val (snippets, rest) = PolarEcgChunker.chunk(2_000L, voltages)
        assertEquals(1, snippets.size)
        assertEquals(200, rest.size)
    }

    @Test
    fun `2600 samples yields two snippets`() {
        val voltages = List(2600) { it }
        val (snippets, rest) = PolarEcgChunker.chunk(3_000L, voltages)
        assertEquals(2, snippets.size)
        assertTrue(rest.isEmpty())
    }

    @Test
    fun `sliding emits nothing before the window is primed`() {
        val (snippets, rest) = PolarEcgChunker.chunkSliding(1_000L, List(1299) { it })
        assertTrue(snippets.isEmpty())
        assertEquals(1299, rest.size)
    }

    @Test
    fun `sliding emits one 10 s strip per 1 s stride`() {
        // 1300 primes the window (1 strip, 1170 kept); 130 more
        // completes the next stride (2nd strip).
        val (first, rest1) = PolarEcgChunker.chunkSliding(1_000L, List(1300) { it })
        assertEquals(1, first.size)
        assertEquals(1300, first[0].voltagesUv.size)
        assertEquals(1170, rest1.size)

        val (second, rest2) = PolarEcgChunker.chunkSliding(2_000L, rest1 + List(130) { it })
        assertEquals(1, second.size)
        assertEquals(1300, second[0].voltagesUv.size)
        assertEquals(1170, rest2.size)
    }

    @Test
    fun `sliding windows overlap by 9 seconds`() {
        val voltages = List(1430) { it }
        val (snippets, rest) = PolarEcgChunker.chunkSliding(1_000L, voltages)
        assertEquals(2, snippets.size)
        // Second window starts 130 samples into the first.
        assertEquals(snippets[0].voltagesUv.drop(130), snippets[1].voltagesUv.take(1170))
        assertEquals(1170, rest.size)
    }
}
