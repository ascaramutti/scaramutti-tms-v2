import { Badge } from '../../../shared/ui/Badge'
import { QUOTATION_STATUS_PRESENTATION } from '../status/quotationStatusPresentation'
import type { QuotationStatus } from '../../../api'

interface QuotationStatusBadgeProps {
  status: QuotationStatus
  /**
   * @deprecated Redundante: el color y el label se derivan del `status` (la expiración ya
   * vive en el estado `EXPIRED`, que pone el job). Se mantiene en la firma para no romper
   * los call-sites existentes; no participa en el render.
   */
  isExpired?: boolean
}

/**
 * Badge del estado de una cotización, derivado de la fuente de verdad única
 * (`QUOTATION_STATUS_PRESENTATION`). Cubre los 5 estados (Borrador/Enviada/Aceptada/
 * Rechazada/Vencida); el color nunca es el único portador de significado: siempre lleva
 * el label textual.
 */
export function QuotationStatusBadge({ status }: QuotationStatusBadgeProps) {
  const { label, badgeVariant } = QUOTATION_STATUS_PRESENTATION[status]
  return <Badge variant={badgeVariant}>{label}</Badge>
}
