import { Link } from 'react-router-dom'
import type { WorkerDetailResponse } from '../../../api'
import { DetailCard, Field } from '../../operations/components/detail/DetailCard'
import { userAccountDetailPath } from '../../../shared/paths'
import { AccountStatusBadge } from './AccountStatusBadge'

export function WorkerAccountCard({ worker }: { worker: WorkerDetailResponse }) {
  const { account } = worker
  return (
    <DetailCard title="Usuario del sistema" headingId="trabajador-cuenta">
      {account ? (
        <dl className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-2">
          <Field
            label="Usuario"
            value={
              <Link
                to={userAccountDetailPath(account.id)}
                className="rounded font-medium text-accent hover:text-accent-hover hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-focus font-mono"
              >
                {account.username}
              </Link>
            }
          />
          <Field label="Estado" value={<AccountStatusBadge isActive={account.isActive} />} />
        </dl>
      ) : (
        <p className="mt-4 text-sm text-fg-body">
          No tiene usuario.
          {!worker.role.canLogin && ' Su cargo no inicia sesión.'}
          {worker.role.canLogin && !worker.isActive && ' Está dado de baja.'}
        </p>
      )}
    </DetailCard>
  )
}
