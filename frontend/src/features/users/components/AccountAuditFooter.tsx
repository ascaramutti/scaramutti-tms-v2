import type { UserAccountDetailResponse } from '../../../api'
import { formatDateTime } from '../../../shared/utils/formatters'

export function AccountAuditFooter({ account }: { account: UserAccountDetailResponse }) {
  const { createdAt, createdBy, updatedAt, updatedBy } = account
  return (
    <footer className="text-xs text-fg-muted">
      <p>
        {createdBy
          ? `Creada por ${createdBy.fullName} el ${formatDateTime(createdAt)}`
          : `Creada el ${formatDateTime(createdAt)} · sin registro de quién la creó`}
      </p>
      {Date.parse(updatedAt) !== Date.parse(createdAt) && (
        <p>
          {updatedBy
            ? `Modificada por ${updatedBy.fullName} el ${formatDateTime(updatedAt)}`
            : `Modificada el ${formatDateTime(updatedAt)} · sin registro de quién la modificó`}
        </p>
      )}
    </footer>
  )
}
