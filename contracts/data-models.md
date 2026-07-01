# Data Models

Cross-language reference for the 5 core models. The OpenAPI schema in `contracts/openapi.yaml` is the source of truth (D2). Each model below is shown in JSON Schema (YAML), Python (Pydantic v2), TypeScript, and Kotlin (`kotlinx.serialization`) for copy-paste.

## Patient
```yaml
# openapi.yaml#/components/schemas/Patient; patient_number is the only PII field
type: object
required: [patient_id, patient_number, device_model, os_version, created_at, is_active]
properties: { patient_id: {type: string, format: uuid}, patient_number: {type: string}, device_model: {type: string}, os_version: {type: string}, created_at: {type: string, format: date-time}, is_active: {type: boolean} }
```
```python
class Patient(BaseModel):
    patient_id: UUID
    patient_number: str = Field(..., description="PII")
    device_model: str; os_version: str
    created_at: datetime; is_active: bool = True
```
```typescript
export interface Patient { patient_id: string; patient_number: string; device_model: string; os_version: string; created_at: string; is_active: boolean; }
```
```kotlin
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
