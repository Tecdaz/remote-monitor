import { createFileRoute } from '@tanstack/react-router'
import { BedGridView } from '../../components/bed-grid'

export const Route = createFileRoute('/')({
  component: IndexComponent,
})

function IndexComponent() {
  return <BedGridView />
}
