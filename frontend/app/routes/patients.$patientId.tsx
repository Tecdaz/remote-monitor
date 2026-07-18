import { createFileRoute, Link } from '@tanstack/react-router'
import { ChartView } from '../../components/patient-chart'

export const Route = createFileRoute('/patients/$patientId')({
  component: PatientRouteComponent,
})

function PatientRouteComponent() {
  const { patientId } = Route.useParams()

  return (
    <div className="px-4 py-6 sm:px-6">
      <Link
        to="/"
        aria-label="Volver al listado de camas"
        className="mb-4 inline-flex items-center gap-1 rounded-md px-2 py-1 text-sm font-medium text-clinical-accentStrong transition-colors hover:bg-clinical-accentSoft focus:outline-none focus-visible:ring-2 focus-visible:ring-clinical-accent focus-visible:ring-offset-2 focus-visible:ring-offset-clinical-surface"
      >
        <span aria-hidden="true">←</span>
        Volver a camas
      </Link>
      <ChartView patientId={patientId} />
    </div>
  )
}