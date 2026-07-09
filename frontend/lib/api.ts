import type { BedSnapshot, MeasurementPage, Patient } from './types'
import { API_BASE_URL } from './config'

const API_BASE = API_BASE_URL

export async function fetchBeds(): Promise<BedSnapshot[]> {
  const res = await fetch(`${API_BASE}/beds`)
  if (!res.ok) {
    throw new Error(`Beds request failed: ${res.status}`)
  }
  return res.json()
}

export async function fetchPatient(id: string): Promise<Patient> {
  const res = await fetch(`${API_BASE}/patients/${id}`)
  if (!res.ok) {
    throw new Error(`Patient request failed: ${res.status}`)
  }
  return res.json()
}

export async function fetchMeasurements(
  patientId: string,
  cursor?: string | null,
): Promise<MeasurementPage> {
  const url = new URL(`${API_BASE}/patients/${patientId}/measurements`)
  if (cursor) {
    url.searchParams.set('cursor', cursor)
  }
  const res = await fetch(url)
  if (!res.ok) {
    throw new Error(`Measurements request failed: ${res.status}`)
  }
  return res.json()
}
