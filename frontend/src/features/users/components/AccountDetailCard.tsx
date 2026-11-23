import type { UserAccountDetailResponse } from '../../../api'
import { DetailCard, Field } from '../../operations/components/detail/DetailCard'
import { AccountStatusBadge } from './AccountStatusBadge'

export function AccountDetailCard({ account }: { account: UserAccountDetailResponse }) {
  return (
    <DetailCard title="Datos de la cuenta" headingId="cuenta-datos">
      <dl className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-2">
        <Field label="Usuario" value={<span className="font-mono">{account.username}</span>} />
        <Field label="Cargo" value={account.role.description} />
        <Field label="Estado" value={<AccountStatusBadge isActive={account.isActive} />} />
      </dl>
    </DetailCard>
  )
}
