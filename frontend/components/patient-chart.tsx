import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ConnectionPill } from './charts/connection-pill'
import { ModeToggle } from './charts/mode-toggle'
import {
  PatientHeader,
  PatientHeaderSkeleton,
} from './charts/patient-header'
import { PoincareChart } from './charts/poincare-chart'
import { TachogramChart } from './charts/tachogram-chart'
import { FrequencyChart } from './charts/frequency-chart'
import { usePatientChart } from '../hooks/use-patient-chart'
import { usePatientWebSocket } from '../hooks/use-patient-websocket'
import { useBedGrid } from '../hooks/use-bed-grid'
import { fetchPatient } from '../lib/api'
import { patientKey } from '../lib/query-keys'

/**
 * Top-level clinical dashboard for a single patient. Pulls the realtime
 * WebSocket connection, the patient metadata, and the IBI measurement
 * cache, then renders the three chart cards in a calm light surface.
 */
export function ChartView({ patientId }: { patientId: string }) {
  const queryClient = useQueryClient()
  const {
    measurements,
    isLoading,
    mode,
    setMode,
    hasStatusData,
    effectiveMode,
  } = usePatientChart(patientId)

  const { connectionState, justDeactivated } = usePatientWebSocket(patientId, queryClient)

  const { data: patient, isLoading: isPatientLoading } = useQuery({
    queryKey: patientKey(patientId),
    queryFn: () => fetchPatient(patientId),
    // 60s fallback so a long-running live session does not silently
    // miss a server-side deactivation; the WebSocket hook also writes
    // `patient.deactivated` into the cache so the UI is immediate.
    staleTime: 60_000,
  })

  const { beds } = useBedGrid()
  const assignedBed = beds.find(
    (bed) => bed.current_patient_id === patientId,
  )

  return (
    <div className="mx-auto flex w-full max-w-6xl flex-col gap-5 px-4 py-6 sm:px-6 lg:px-8">
      {isPatientLoading || !patient ? (
        <PatientHeaderSkeleton />
      ) : (
        <PatientHeader
          bedNumber={assignedBed?.bed_number ?? null}
          isActive={patient.is_active}
          justDeactivated={justDeactivated}
          createdAt={patient.created_at}
          deviceModel={patient.device_model}
          osVersion={patient.os_version}
        />
      )}

      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex items-center gap-3">
          <ConnectionPill state={connectionState} />
          {!hasStatusData && measurements.length > 0 ? (
            <span className="text-xs text-clinical-inkFaint">
              Calidad de señal no disponible
            </span>
          ) : null}
        </div>
        <ModeToggle
          mode={effectiveMode}
          onChange={setMode}
          disabled={!hasStatusData}
        />
      </div>

      {isLoading && measurements.length === 0 ? (
        <div className="grid gap-5">
          <div
            role="status"
            aria-label="Cargando mediciones"
            className="clinical-card h-80 animate-pulse motion-reduce:animate-none"
          />
          <div
            role="status"
            aria-label="Cargando mediciones"
            className="clinical-card h-80 animate-pulse motion-reduce:animate-none"
          />
          <div
            role="status"
            aria-label="Cargando mediciones"
            className="clinical-card h-80 animate-pulse motion-reduce:animate-none"
          />
        </div>
      ) : (
        <div className="grid gap-5">
          <TachogramChart
            measurements={measurements}
            mode={effectiveMode}
            connectionState={connectionState}
          />
          <PoincareChart measurements={measurements} mode={effectiveMode} />
          <FrequencyChart measurements={measurements} mode={effectiveMode} />
        </div>
      )}
    </div>
  )
}