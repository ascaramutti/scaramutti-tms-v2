import { Badge } from '../../../shared/ui/Badge'
import type { QuotationStatus } from '../../../api'

interface QuotationStatusBadgeProps {
  status: QuotationStatus
  isExpired: boolean
}

/**
 * Badge de estado de una cotización. La expiración (runtime) tiene prioridad
 * visual sobre el status: una cotización vencida se muestra "Vencida" aunque
 * su status sea DRAFT o SENT.
 */
export function QuotationStatusBadge({ status, isExpired }: QuotationStatusBadgeProps) {
  if (isExpired) {
    return <Badge variant="danger">Vencida</Badge>
  }
  if (status === 'SENT') {
    return <Badge variant="success">Enviada</Badge>
  }
  return <Badge variant="default">Borrador</Badge>
}
