package com.remotemonitor.watch.ui

/**
 * Shared validation rules for the onboarding flow (T-WATCH-34, T-WATCH-35,
 * REQ-WATCH-18).
 *
 * The patient number is operator-typed on first launch (and on re-pair).
 * Per the openapi contract (`contracts/openapi.yaml` lines 181, 191), it
 * must be 4-32 alphanumeric characters with optional hyphens (the
 * canonical example is `P-00042`). The same regex is enforced:
 *  - in the ViewModel at submit time (so a programmatic caller can't
 *    bypass the screen check);
 *  - in the Composable to decide whether the primary action is enabled.
 *
 * Single source of truth: changing the rule here updates both call sites.
 */
internal val PatientNumberRegex: Regex = Regex("^[A-Za-z0-9-]{4,32}$")

/** Human-readable error message for the regex check. */
internal const val PatientNumberErrorMessage: String =
    "Patient number must be 4-32 letters, digits, or hyphens"
