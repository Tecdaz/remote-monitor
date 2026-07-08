package com.remotemonitor.watch.identity

/**
 * wear-bed-picker-onboarding T3.14 / D19: shared test fixtures for the
 * bed-picker onboarding flow on the watch JVM-side.
 *
 * Watch-side counterpart to the backend pytest `BedFixture` defined
 * under `backend/tests/fixtures/`. The two fixtures are intentionally
 * NOT identical — the backend fixture encrypts/serializes through
 * real `pgp_sym_encrypt` bytes; the watch JVM-side fixture exposes
 * placeholders because the watch is a Moshi/Retrofit edge that never
 * participates in the `pgp_sym_encrypt` round-trip (the backend
 * returns the ciphertext over HTTP and the watch stores it
 * opaquely via `persistPaired(..., patientNumberCipher, ...)`).
 *
 * The previous test fixture used `P-00042` / `P_0042` / `abc` /
 * `  abc  ` — all operator-typed plaintext strings that the
 * pre-PR-2 onboarding regex
 * (`PatientNumberRegex = Regex("^[A-Za-z0-9-]{4,32}$")`) accepted.
 * Post-PR-3c, the watch's identity surface is the bed plaintext in
 * 1..5 and the bed ciphertext from `RegisterPatientResponse.patient_
 * number` (an opaque byte string); both are surfaced through the
 * constants below so the tests assert against canonical identifiers
 * instead of legacy operator prose.
 *
 * wear-bed-picker-onboarding D32: the backend's
 * `RegisterPatientResponse.patient_number` description is
 * `"Encrypted bed number (PostgreSQL pgp_sym_encrypt of the bed
 * number in 1..5). NOT an operator-typed identifier; do not display
 * as plain text."` The opaque base64 placeholder below is a
 * truncated PGP-style string of the bed number, analogous to the
 * `MIIBiQYJKoZIhvcNAQ…` placeholder used in the OpenAPI
 * description.
 */
object BedCiphertextFixture {

    /**
     * Bed plaintext values in the locked range `"1"`..`"5"`. Tests
     * use these instead of legacy operator-typed strings like
     * `"P-00042"`.
     */
    const val KNOWN_BED: String = "3"

    /** A second canonical bed for tests that exercise multi-bed flows. */
    const val OTHER_BED: String = "4"

    /**
     * Canonical opaque ciphertext placeholder. Tests that need to
     * seed `IdentityRepository.persistPaired`'s
     * `patientNumberCipher` argument read from this constant so the
     * green tests don't accidentally bake a real
     * `pgp_sym_encrypt("3")` byte sequence into the test pipeline —
     * the value of the ciphertext is owned by the backend, NOT the
     * watch. See D32 for the ciphertext description.
     */
    const val KNOWN_CIPHER: String = "MIIBiQYJKoZIhvcNAQcDoIIIQjCCCD4CAQ"

    /**
     * Canonical `patient_id` UUID returned by
     * `POST /api/v1/patients`. The watch stores this opaque value
     * under `KEY_PATIENT_ID` via `persistPaired` and never parses
     * it (the watch only ever passes it as the URL path parameter
     * on `/api/v1/patients/{patientId}/measurements`).
     */
    const val KNOWN_PATIENT_ID: String = "00000000-0000-0000-0000-000000000003"
}
