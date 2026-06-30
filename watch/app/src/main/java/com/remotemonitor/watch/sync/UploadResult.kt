package com.remotemonitor.watch.sync

/**
 * Result of one upload pass (T-WATCH-20).
 *
 * @property acceptedCount how many rows were echoed in the 2xx `accepted_ids`
 *           and deleted from Room
 * @property rejectedCount how many rows were returned in the 2xx `rejected`
 *           list (kept in Room for inspection per REQ-WATCH-05 S05.2)
 * @property keptCount how many rows remain in Room after this pass
 *           (either rejected or untouched due to non-2xx / IOException)
 */
data class UploadResult(
    val acceptedCount: Int,
    val rejectedCount: Int,
    val keptCount: Int,
)
