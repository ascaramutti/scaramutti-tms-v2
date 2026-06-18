import type { QuotationResponse } from '../../../api'

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
      <h2 className="flex flex-wrap items-center gap-2 text-base font-semibold text-slate-900">
        Motivo del rechazo
        <span className="inline-flex items-center gap-1 rounded-full border border-blue-200 bg-blue-100 px-2 py-0.5 text-xs font-medium text-blue-700">
          <span aria-hidden="true">🔒</span>
          interno
        </span>
      </h2>
      <div className="mt-3 rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
        <div className="rounded-lg border border-blue-300 bg-blue-50 p-4">
          <p className="whitespace-pre-wrap break-words text-sm text-blue-900">{rejectionReason}</p>
        </div>
      </div>
    </section>
  )
}
