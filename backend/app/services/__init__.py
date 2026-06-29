"""Service-layer helpers.

PR3 (T3.1) introduces ``crypto`` for pgcrypto-backed at-rest encryption
of ``pii.patients.patient_number``. The remaining services (ingest,
audit) and any future PII-touching code live alongside it in this
package.
"""
