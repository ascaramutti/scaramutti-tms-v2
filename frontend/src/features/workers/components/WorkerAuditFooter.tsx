import type { UserRef } from '../../../api'
import { formatDate } from '../../../shared/utils/formatters'

interface WorkerAuditFooterProps {
  createdAt: string
  createdBy?: UserRef | null
  updatedAt: string
  updatedBy?: UserRef | null
}

/**
 * Quién dio de alta al trabajador y quién lo tocó por última vez, en día de Lima.
 * Sin autor no se inventa uno: el alta dice solo la fecha. La modificación sale solo
 * si hubo una: el alta también firma al modificador, con el mismo instante.
 */
export function WorkerAuditFooter({ createdAt, createdBy, updatedAt, updatedBy }: WorkerAuditFooterProps) {
  return (
    <footer className="text-xs text-fg-muted">
      <p>
        {createdBy
          ? `Creado por ${createdBy.fullName} el ${formatDate(createdAt)}`
          : `Creado el ${formatDate(createdAt)}`}
      </p>
      {updatedBy && Date.parse(updatedAt) !== Date.parse(createdAt) && (
        <p>{`Modificado por ${updatedBy.fullName} el ${formatDate(updatedAt)}`}</p>
      )}
    </footer>
  )
}
