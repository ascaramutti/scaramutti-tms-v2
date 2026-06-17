import type { QuotationResponse } from '../../../api'

/** ¿La nota tiene contenido visible? (null/undefined/solo-whitespace → vacía). */
function hasContent(value: string | null | undefined): boolean {
  return !!value?.trim()
}

/**
 * Sección de lectura de las observaciones en el Detalle. Renderiza la observación para el
 * cliente y la interna (esta SIEMPRE diferenciada: badge "🔒 interno" + azul más marcado — RN-03,
 * pantalla interna). Texto plano escapado por JSX (`{value}`) + `whitespace-pre-wrap` para
 * respetar saltos/tabs del texto libre. NUNCA `dangerouslySetInnerHTML`.
 *
 * - Ambas vacías → NO renderiza nada (RN-06: cotizaciones viejas no muestran sección huérfana).
 * - Una vacía → la sección se muestra; el vacío usa placeholder "—".
 */
export function QuotationNotesSection({ quotation }: { quotation: QuotationResponse }) {
  const { clientNote, internalNote } = quotation

  // RN-06 / D-7: si ninguna tiene contenido, no se muestra la sección.
  if (!hasContent(clientNote) && !hasContent(internalNote)) {
    return null
  }

  return (
    <section>
      <h2 className="text-base font-semibold text-slate-900">Observaciones</h2>
      <div className="mt-3 grid grid-cols-1 gap-4 rounded-xl border border-slate-200 bg-white p-5 shadow-sm md:grid-cols-2">
        <div className="rounded-lg border border-sky-200 bg-sky-50/60 p-4">
          <h3 className="text-sm font-medium text-slate-700">Observaciones para el cliente</h3>
          {hasContent(clientNote) ? (
            <p className="mt-1 whitespace-pre-wrap break-words text-sm text-slate-900">{clientNote}</p>
          ) : (
            <p className="mt-1 text-sm text-slate-400">—</p>
          )}
        </div>

        <div className="rounded-lg border border-blue-300 bg-blue-50 p-4">
          <h3 className="flex flex-wrap items-center gap-2 text-sm font-medium text-blue-900">
            Observaciones internas
            <span className="inline-flex items-center gap-1 rounded-full border border-blue-200 bg-blue-100 px-2 py-0.5 text-xs font-medium text-blue-700">
              <span aria-hidden="true">🔒</span>
              interno
            </span>
          </h3>
          {hasContent(internalNote) ? (
            <p className="mt-1 whitespace-pre-wrap break-words text-sm text-blue-900">{internalNote}</p>
          ) : (
            <p className="mt-1 text-sm text-blue-400">—</p>
          )}
        </div>
      </div>
    </section>
  )
}
