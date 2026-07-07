# Data Models

Cross-language reference for the 5 core models. The OpenAPI schema in `contracts/openapi.yaml` is the source of truth (D2). Each model below is shown in JSON Schema (YAML), Python (Pydantic v2), TypeScript, and Kotlin (`kotlinx.serialization`) for copy-paste.

## Patient
```yaml
# openapi.yaml#/components/schemas/Patient; patient_number is the only PII field.
# D32: patient_number is encrypted bed number (pgp_sym_encrypt of the bed number in 1..5),
# NOT an operator-typed identifier. Do not display as plain text.
type: object
required: [patient_id, patient_number, device_model, os_version, created_at, is_active]
properties: { patient_id: {type: string, format: uuid}, patient_number: {type: string}, device_model: {type: string}, os_version: {type: string}, created_at: {type: string, format: date-time}, is_active: {type: boolean} }
```
```python
class Patient(BaseModel):
    patient_id: UUID
    patient_number: str = Field(
        ...,
        description=(
            "Encrypted bed number (PostgreSQL pgp_sym_encrypt of the bed number in 1..5). "
            "NOT an operator-typed identifier; do not display as plain text."
        ),
        examples=["MIIBiQYJKoZIhvcNAQDo+0D+lpNbQEDdHJlYXRlZEBrE1lMrQECREMwBQYDK2VwBCMQOlhLVDc4WnB1djVxTEhNSjFQcmZQTlpMNlVrQVhxY3NQNFhmN2tVS2tGM1VxSlhVSmkrRmhKN0FLb0o3SFd6RHhLdlJZRHJLZkx4c1Q3Z2VBQ0FvM2JrR0tuZUFIZlpPMU9wNkhRK09wcG9WM2d2bXF1TGIya3RCVVZtVGh1YVk4PQECMB2jTFPleV9bscAtxA99JeUQiEYD6v6+arT3qUUEAQH/"],
    )
    device_model: str; os_version: str
    created_at: datetime; is_active: bool = True
```
```typescript
// patient_number is encrypted bed number (pgp_sym_encrypt of bed 1..5);
// NOT an operator-typed identifier; do not display as plain text.
export interface Patient { patient_id: string; patient_number: string; device_model: string; os_version: string; created_at: string; is_active: boolean; }
```
```kotlin
// patient_number is encrypted bed number (pgp_sym_encrypt of bed 1..5);
// NOT an operator-typed identifier; do not display as plain text.
@Serializable data class Patient(val patientId: UUID, val patientNumber: String, val deviceModel: String, val osVersion: String, val createdAt: Instant, val isActive: Boolean = true)
```

## Measurement
```yaml
# openapi.yaml#/components/schemas/Measurement (allOf: MeasurementBatch + server-assigned fields)
type: object
required: [id, patient_id, local_id, timestamp, received_at]
properties: { id: {type: string, format: uuid}, patient_id: {type: string, format: uuid}, local_id: {type: string, format: uuid}, timestamp: {type: string, format: date-time}, received_at: {type: string, format: date-time}, heart_rate_bpm: {type: [integer, "null"], minimum: 1, maximum: 299}, spo2_percent: {type: [number, "null"], exclusiveMinimum: 0, maximum: 100}, ibis_ms: {type: array, nullable: true, items: {type: integer, minimum: 1, maximum: 5000}} }
```
```python
class Measurement(BaseModel):
    id: UUID; patient_id: UUID; local_id: UUID
    timestamp: datetime; received_at: datetime
    heart_rate_bpm: int | None = Field(None, ge=1, le=299)
    spo2_percent: float | None = Field(None, gt=0, le=100)
    ibis_ms: list[int] | None = Field(None, description="Inter-beat intervals in ms; per-item [1, 5000].")
```
```typescript
export interface Measurement { id: string; patient_id: string; local_id: string; timestamp: string; received_at: string; heart_rate_bpm: number | null; spo2_percent: number | null; ibis_ms: number[] | null; }
```
```kotlin
@Serializable data class Measurement(val id: UUID, val patientId: UUID, val localId: UUID, val timestamp: Instant, val receivedAt: Instant, val heartRateBpm: Int? = null, val spo2Percent: Double? = null, val ibisMs: List<Long>? = null)
```

## MeasurementBatch
```yaml
# openapi.yaml#/components/schemas/MeasurementBatch
type: object
required: [local_id, timestamp]
properties: { local_id: {type: string, format: uuid}, timestamp: {type: string, format: date-time}, heart_rate_bpm: {type: [integer, "null"], minimum: 1, maximum: 299}, spo2_percent: {type: [number, "null"], exclusiveMinimum: 0, maximum: 100}, ibis_ms: {type: array, nullable: true, items: {type: integer, minimum: 1, maximum: 5000}} }
```
```python
class MeasurementBatch(BaseModel):
    local_id: UUID; timestamp: datetime
    heart_rate_bpm: int | None = Field(None, ge=1, le=299)
    spo2_percent: float | None = Field(None, gt=0, le=100)
    ibis_ms: list[int] | None = Field(None, description="Inter-beat intervals in ms; per-item [1, 5000].")
```
```typescript
export interface MeasurementBatch { local_id: string; timestamp: string; heart_rate_bpm: number | null; spo2_percent: number | null; ibis_ms: number[] | null; }
```
```kotlin
@Serializable data class MeasurementBatch(val localId: UUID, val timestamp: Instant, val heartRateBpm: Int? = null, val spo2Percent: Double? = null, val ibisMs: List<Long>? = null)
```

## BatchResponse
```yaml
# openapi.yaml#/components/schemas/BatchResponse (delete-after-echo wire encoding)
type: object
required: [accepted_ids, rejected]
properties: { accepted_ids: {type: array, items: {type: string, format: uuid}}, rejected: {type: array, items: {$ref: "#/components/schemas/RejectedMeasurement"}} }
```
```python
class BatchResponse(BaseModel):
    accepted_ids: list[UUID]
    rejected: list[RejectedMeasurement]
```
```typescript
export interface BatchResponse { accepted_ids: string[]; rejected: RejectedMeasurement[]; }
```
```kotlin
@Serializable data class BatchResponse(val acceptedIds: List<UUID>, val rejected: List<RejectedMeasurement>)
```

## RejectedMeasurement
```yaml
# openapi.yaml#/components/schemas/RejectedMeasurement
type: object
required: [local_id, reason]
properties: { local_id: {type: string, format: uuid}, reason: {type: string} }
```
```python
class RejectedMeasurement(BaseModel):
    local_id: UUID
    reason: str
```
```typescript
export interface RejectedMeasurement { local_id: string; reason: string; }
```
```kotlin
@Serializable data class RejectedMeasurement(val localId: UUID, val reason: String)
```

## BedSnapshot
```yaml
# openapi.yaml#/components/schemas/BedSnapshot
# Per-bed occupancy row in the GET /api/v1/beds response (BedSnapshotList).
type: object
required: [bed_number, is_occupied]
properties:
  bed_number: { type: integer, minimum: 1, maximum: 5 }
  is_occupied: { type: boolean }
  current_patient_id: { type: [string, "null"], format: uuid }
```
```python
class BedSnapshot(BaseModel):
    bed_number: int = Field(..., ge=1, le=5)
    is_occupied: bool
    current_patient_id: UUID | None = None
```
```typescript
export interface BedSnapshot { bed_number: number; is_occupied: boolean; current_patient_id: string | null; }
```
```kotlin
@Serializable
data class BedSnapshot(
    val bedNumber: Int,
    val isOccupied: Boolean,
    val currentPatientId: String? = null,
)
```

## BedSnapshotList
```yaml
# openapi.yaml#/components/schemas/BedSnapshotList
# Always length 5 (PoC hardcoded bed universe 1..5).
type: array
items: { $ref: "#/components/schemas/BedSnapshot" }
minItems: 5
maxItems: 5
```
```python
class BedSnapshotList(RootModel[list[BedSnapshot]]):
    pass
```
```typescript
export type BedSnapshotList = BedSnapshot[]; // length 5
```
```kotlin
@Serializable
data class BedSnapshotList(val items: List<BedSnapshot>) // assert size == 5 at the call site
```

## RegisterPatientRequest
```yaml
# openapi.yaml#/components/schemas/RegisterPatientRequest
# Replaces the free-form PatientRegistration schema (now removed).
type: object
required: [bed_number, device_model, os_version]
properties:
  bed_number: { type: integer, minimum: 1, maximum: 5 }
  device_model: { type: string }
  os_version: { type: string }
  replace_active_session: { type: boolean, default: false }
```
```python
class RegisterPatientRequest(BaseModel):
    bed_number: int = Field(..., ge=1, le=5, examples=[3])
    device_model: str
    os_version: str
    replace_active_session: bool = False
```
```typescript
export interface RegisterPatientRequest {
  bed_number: number; // 1..5
  device_model: string;
  os_version: string;
  replace_active_session?: boolean; // defaults to false
}
```
```kotlin
@Serializable
data class RegisterPatientRequest(
    val bedNumber: Int,                   // 1..5
    val deviceModel: String,
    val osVersion: String,
    val replaceActiveSession: Boolean = false,
)
```
```
