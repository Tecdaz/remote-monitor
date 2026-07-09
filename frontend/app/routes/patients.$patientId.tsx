import { createFileRoute, Link } from '@tanstack/react-router'
import { ChartView } from '../../components/patient-chart'

export const Route = createFileRoute('/patients/$patientId')({
  component: PatientRouteComponent,
})

function PatientRouteComponent() {
  const { patientId } = Route.useParams()

  return (
    <div className="p-6">
      <Link
        to="/"
        className="mb-4 inline-block text-sm text-teal-400 hover:text-teal-300"
      >
        ← Back to beds
      </Link>
      <ChartView patientId={patientId} />
    </div>
  )
}
