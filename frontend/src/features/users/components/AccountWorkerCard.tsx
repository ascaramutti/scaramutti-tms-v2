import { Link } from 'react-router-dom'
import type { UserAccountWorkerDetailRef } from '../../../api'
import { DetailCard, Field } from '../../operations/components/detail/DetailCard'
import { workerDetailPath } from '../../../shared/paths'
import { Badge } from '../../../shared/ui/Badge'
import { PersonNameRow } from '../../../shared/ui/PersonNameRow'

export function AccountWorkerCard({ worker }: { worker: UserAccountWorkerDetailRef }) {
  return (
    <DetailCard title="Trabajador" headingId="cuenta-trabajador">
      <PersonNameRow
        firstName={worker.firstName}
        paternalLastName={worker.paternalLastName}
        maternalLastName={worker.maternalLastName}
        className="mt-4"
      />
      <dl className="mt-4">
        <Field
          label="Ficha"
          value={
            <span className="inline-flex flex-wrap items-center gap-2">
              <Link
                to={workerDetailPath(worker.id)}
                className="rounded font-medium text-accent hover:text-accent-hover hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-focus"
              >
                Ver la ficha
              </Link>
              {!worker.isActive && <Badge>Dado de baja</Badge>}
            </span>
          }
        />
      </dl>
    </DetailCard>
  )
}
