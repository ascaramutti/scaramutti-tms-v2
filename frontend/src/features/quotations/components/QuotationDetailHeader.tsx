import { Badge } from '../../../shared/ui/Badge'
import { BackLink } from '../../../shared/ui/BackLink'
import { formatDate } from '../../../shared/utils/formatters'
import { QUOTATION_TYPE_LABELS } from '../utils/quotationLabels'
import { QuotationStatusBadge } from './QuotationStatusBadge'
import type { QuotationStatus, QuotationType } from '../../../api'

interface QuotationDetailHeaderProps {
  code: string
  quotationType: QuotationType
  status: QuotationStatus
  isExpired: boolean
  createdAt: string
  expiresAt: string
}

/** Cabecera del detalle: breadcrumb navegable + código + badges (tipo/estado)
 * + fechas de emisión y validez. */
export function QuotationDetailHeader({
  code,
  quotationType,
  status,
  isExpired,
  createdAt,
  expiresAt,
}: QuotationDetailHeaderProps) {
  return (
    <header className="border-b border-border pb-5">
      <BackLink to="/cotizaciones">Cotizaciones</BackLink>

      <div className="mt-2 flex flex-wrap items-center gap-3">
        <h1 className="text-2xl font-semibold text-fg">{code}</h1>
        <Badge variant="info">{QUOTATION_TYPE_LABELS[quotationType]}</Badge>
        <QuotationStatusBadge status={status} isExpired={isExpired} />
      </div>

      <p className="mt-1 text-sm text-fg-muted">
        Emitida el {formatDate(createdAt)} · Válida hasta {formatDate(expiresAt)}
      </p>
    </header>
  )
}
