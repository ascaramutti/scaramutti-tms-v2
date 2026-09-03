import type { QuotationResponse } from '../../../api'
import { Badge } from '../../../shared/ui/Badge'
import { Card } from '../../../shared/ui/Card'
import { Alert } from '../../../shared/ui/Alert'

/**
 * Sección de lectura del motivo de rechazo en el Detalle. Espeja la observación interna de
 * `QuotationNotesSection`: card con borde azul marcado + badge "🔒 interno". El motivo es
 * INTERNO — se muestra acá, NUNCA en el PDF ni cara al cliente (ADR-007).
 *
 * Solo se renderiza si la cotización está RECHAZADA y trae un motivo con contenido (las
 * rechazadas viejas sin motivo, o cualquier otro estado, no muestran sección huérfana).
 * Texto plano escapado por JSX (`{value}`) + `whitespace-pre-wrap` para respetar saltos/
 * tabs. NUNCA `dangerouslySetInnerHTML`.
 */
export function QuotationRejectionReasonSection({ quotation }: { quotation: QuotationResponse }) {
  const { status, rejectionReason } = quotation

  if (status !== 'REJECTED' || !rejectionReason?.trim()) {
    return null
  }

  return (
    <section>
      <h2 className="flex flex-wrap items-center gap-2 text-base font-semibold text-fg">
        Motivo del rechazo
        <Badge variant="info" bordered>
          <span aria-hidden="true">🔒</span>
          interno
        </Badge>
      </h2>
      <Card className="mt-3">
        <Alert variant="info" role={undefined} className="rounded-lg p-4">
          <p className="whitespace-pre-wrap break-words text-sm text-accent-hover">{rejectionReason}</p>
        </Alert>
      </Card>
    </section>
  )
}
