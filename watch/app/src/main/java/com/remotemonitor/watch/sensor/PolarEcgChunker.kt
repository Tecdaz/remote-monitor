package com.remotemonitor.watch.sensor

/**
 * T1 polar-ecg-integration: pure chunker for Polar H10 ECG snippets.
 *
 * The H10 streams ECG at 130 Hz in µV (PolarH10.md). For validation
 * against Samsung we persist 10 s snippets (1300 samples) anchored to
 * the watch clock — small enough to survive Room/POST/WS, large enough
 * for HRV ground truth. Pure Kotlin (no Android/SDK APIs) so unit
 * tests run on the JVM.
 */
object PolarEcgChunker {

    const val SAMPLE_RATE_HZ = 130
    const val SNIPPET_SECONDS = 10
    const val SNIPPET_SIZE = SAMPLE_RATE_HZ * SNIPPET_SECONDS

    data class Snippet(
        val timestampMillis: Long,
        val sampleRateHz: Int = SAMPLE_RATE_HZ,
        val voltagesUv: List<Int>,
    )

    /**
     * Split a flat voltage list into full 10 s snippets. A trailing
     * partial remainder is kept by the caller (returned second) —
     * never persisted as a snippet.
     */
    fun chunk(
        timestampMillis: Long,
        voltagesUv: List<Int>,
        sampleRateHz: Int = SAMPLE_RATE_HZ,
    ): Pair<List<Snippet>, List<Int>> {
        if (voltagesUv.isEmpty()) return emptyList<Snippet>() to emptyList()
        val size = SAMPLE_RATE_HZ * SNIPPET_SECONDS
        val snippets = mutableListOf<Snippet>()
        var offset = 0
        while (offset + size <= voltagesUv.size) {
            snippets += Snippet(
                timestampMillis = timestampMillis,
                sampleRateHz = sampleRateHz,
                voltagesUv = voltagesUv.subList(offset, offset + size).toList(),
            )
            offset += size
        }
        return snippets to voltagesUv.drop(offset)
    }

    /**
     * Sliding 10 s window advanced by [STRIDE_SAMPLES] (1 s @ 130 Hz).
     * Emits one 1300-sample strip per second of new data once the
     * buffer is primed — the live-trace cadence of a clinical
     * monitor. Costs ~10× the rows of [chunk]; acceptable for PoC
     * validation sessions, revisit for 24 h wear.
     */
    const val STRIDE_SAMPLES = SAMPLE_RATE_HZ

    fun chunkSliding(
        timestampMillis: Long,
        voltagesUv: List<Int>,
        sampleRateHz: Int = SAMPLE_RATE_HZ,
    ): Pair<List<Snippet>, List<Int>> {
        if (voltagesUv.size < SNIPPET_SIZE) return emptyList<Snippet>() to voltagesUv
        val snippets = mutableListOf<Snippet>()
        var buffer = voltagesUv
        while (buffer.size >= SNIPPET_SIZE) {
            snippets += Snippet(
                timestampMillis = timestampMillis,
                sampleRateHz = sampleRateHz,
                voltagesUv = buffer.subList(0, SNIPPET_SIZE).toList(),
            )
            buffer = buffer.drop(STRIDE_SAMPLES)
        }
        return snippets to buffer
    }
}
