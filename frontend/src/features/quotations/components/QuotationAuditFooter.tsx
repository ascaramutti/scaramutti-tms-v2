import { formatDate } from '../../../shared/utils/formatters'
import type { UserResponse } from '../../../api'

interface QuotationAuditFooterProps {
  createdBy: UserResponse
  updatedBy: UserResponse
  createdAt: string
  updatedAt: string
}

/** Nombre + cargo de un usuario para la traza de auditoría. */
function userLabel(user: UserResponse): string {
  return user.position ? `${user.fullName} (${user.position})` : user.fullName
}

/** Traza de auditoría completa (vista interna), alineada a la izquierda:
 * quién elaboró la cotización y quién la editó por última vez, con fechas. */
export function QuotationAuditFooter({
  createdBy,
  updatedBy,
  createdAt,
  updatedAt,
}: QuotationAuditFooterProps) {
  return (
    <footer className="text-xs text-slate-500">
      <p className="font-semibold uppercase tracking-wide text-slate-400">Auditoría</p>
      <p className="mt-1">
        Elaborada por {userLabel(createdBy)} · {formatDate(createdAt)}
      </p>
      <p>
        Última edición por {userLabel(updatedBy)} · {formatDate(updatedAt)}
      </p>
    </footer>
  )
}
