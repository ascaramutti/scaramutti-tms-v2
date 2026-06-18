import { useState } from 'react'
import { Link } from 'react-router-dom'
import { toast } from 'sonner'
import { Download, Eye, Loader2, Pencil, RefreshCw } from 'lucide-react'
import { PRIMARY_BUTTON, SECONDARY_BUTTON } from '../../../shared/ui/buttonStyles'
import { getApiErrorMessage, isPreconditionFailedError } from '../../../shared/utils/getApiErrorMessage'
import { useQuotationPdf } from '../hooks/useQuotationPdf'
import { getPdfErrorMessage, openQuotationPdf, saveQuotationPdf } from '../utils/quotationPdf'
import { QuotationStatusActions } from '../status/QuotationStatusActions'
import { RejectQuotationModal } from '../status/RejectQuotationModal'
import { isQuotationEditable } from '../status/quotationStatusPresentation'
import { QUOTATION_STATUS_LABELS } from '../utils/quotationLabels'
import type { QuotationStatus } from '../../../api'

type PdfMode = 'preview' | 'download'

interface QuotationDetailActionsProps {
  quotationId: number
  quotationCode: string
  /** Estado actual: decide qué botones de transición se muestran. */
  status: QuotationStatus
  /** ETag opaco del GET, para el `If-Match` de la transición de estado. */
  etag: string | null
  /** Recarga el detalle (resincroniza estado + ETag tras un 409/412). */
  onRefetch: () => void
}

const DISABLED = 'disabled:cursor-not-allowed disabled:opacity-60'

/**
 * Barra de acciones del Detalle: **transiciones de estado** (Enviar/Aceptar/Rechazar, según
 * el estado), **Editar** (wizard pre-cargado), **Previsualizar** y **Descargar** PDF.
 *
 * Rechazar abre `RejectQuotationModal` (motivo obligatorio); Enviar/Aceptar van directo. Los
 * errores de transición se centralizan acá:
 * - 412 (COM-004): banner persistente + Recargar (NO toast) y se cierra el modal.
 * - 409 (QUO-005): toast con el `detail` + refetch (resincroniza los botones).
 * - 403/404 y demás: toast con el `detail`.
 * Los del PDF se muestran inline con su `Problem.detail`.
 */
export function QuotationDetailActions({
  quotationId,
  quotationCode,
  status,
  etag,
  onRefetch,
}: QuotationDetailActionsProps) {
  const { mutateAsync, isPending, variables } = useQuotationPdf()
  const [pdfError, setPdfError] = useState<string | null>(null)
  const [isRejectOpen, setRejectOpen] = useState(false)
  const [staleConflict, setStaleConflict] = useState(false)

  async function handlePdf(mode: PdfMode) {
    setPdfError(null)
    try {
      const { blob, filename } = await mutateAsync({ id: quotationId, preview: mode === 'preview' })
      if (mode === 'preview') {
        const opened = openQuotationPdf(blob)
        if (!opened) {
          setPdfError('No se pudo abrir la previsualización (¿bloqueador de pop-ups?). Probá con "Descargar PDF".')
        }
      } else {
        saveQuotationPdf(blob, filename ?? `cotizacion-${quotationCode}.pdf`)
      }
    } catch (error) {
      setPdfError(await getPdfErrorMessage(error, 'No se pudo generar el PDF de la cotización.'))
    }
  }

  // Política única de errores de transición (Enviar/Aceptar directos + Rechazar del modal).
  function handleStatusError(error: unknown) {
    if (isPreconditionFailedError(error)) {
      // 412: alguien la modificó entremedio → banner + Recargar, sin toast. Cierro el modal.
      setRejectOpen(false)
      setStaleConflict(true)
      return
    }
    // 409 (transición inválida) / 403 / 404 → toast con el detail del backend + refetch para
    // resincronizar los botones con el estado real.
    toast.error(
      getApiErrorMessage(error, 'No se puede aplicar esa acción al estado actual de la cotización.'),
    )
    onRefetch()
  }

  function handleReload() {
    setStaleConflict(false)
    onRefetch()
  }

  // Qué botón de PDF muestra el spinner.
  const pendingMode: PdfMode | null = isPending ? (variables?.preview ? 'preview' : 'download') : null

  // Editar solo en estados no terminales (el backend rechaza editar un terminal con 409
  // QUO-006). En terminales el botón no se oculta —es un affordance estable del detalle—
  // sino que queda deshabilitado, con el motivo en un tooltip propio (inmediato, a diferencia
  // del `title` nativo que tarda ~1s) y en el nombre accesible (aria-label).
  const editable = isQuotationEditable(status)
  const notEditableReason = `No se puede editar una cotización ${QUOTATION_STATUS_LABELS[status].toLowerCase()}`

  return (
    <div className="flex flex-col items-end gap-2">
      {staleConflict && (
        <div
          role="alert"
          className="flex w-full flex-wrap items-center justify-between gap-3 rounded-lg border border-amber-300 bg-amber-50 px-4 py-3 text-sm text-amber-800"
        >
          <span>La cotización fue modificada por otra persona. Recargá para ver la versión actual.</span>
          <button type="button" onClick={handleReload} className={SECONDARY_BUTTON}>
            <RefreshCw className="mr-2 h-4 w-4" aria-hidden="true" />
            Recargar
          </button>
        </div>
      )}

      <div className="flex flex-wrap items-center justify-end gap-2">
        <QuotationStatusActions
          quotationId={quotationId}
          status={status}
          etag={etag}
          onRejectClick={() => setRejectOpen(true)}
          onStatusError={handleStatusError}
        />
        {editable ? (
          <Link to={`/cotizaciones/${quotationId}/editar`} className={SECONDARY_BUTTON}>
            <Pencil className="mr-2 h-4 w-4" aria-hidden="true" />
            Editar
          </Link>
        ) : (
          <span className="group relative inline-flex">
            <button
              type="button"
              disabled
              aria-disabled
              aria-label={`Editar — ${notEditableReason}`}
              className={`${SECONDARY_BUTTON} ${DISABLED}`}
            >
              <Pencil className="mr-2 h-4 w-4" aria-hidden="true" />
              Editar
            </button>
            {/* Tooltip propio: aparece al instante con el hover (el `title` nativo tarda ~1s).
                Decorativo (aria-hidden): el motivo ya viaja en el aria-label del botón. */}
            <span
              aria-hidden="true"
              className="pointer-events-none absolute bottom-full left-1/2 z-10 mb-2 -translate-x-1/2 whitespace-nowrap rounded-md bg-blue-700 px-2 py-1 text-xs font-medium text-white opacity-0 shadow-lg transition-opacity duration-100 group-hover:opacity-100"
            >
              {notEditableReason}
            </span>
          </span>
        )}
        <button
          type="button"
          onClick={() => handlePdf('preview')}
          disabled={isPending}
          className={`${SECONDARY_BUTTON} ${DISABLED}`}
        >
          {pendingMode === 'preview' ? (
            <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden="true" />
          ) : (
            <Eye className="mr-2 h-4 w-4" aria-hidden="true" />
          )}
          Previsualizar PDF
        </button>
        <button
          type="button"
          onClick={() => handlePdf('download')}
          disabled={isPending}
          className={`${PRIMARY_BUTTON} ${DISABLED}`}
        >
          {pendingMode === 'download' ? (
            <Loader2 className="mr-2 h-4 w-4 animate-spin" aria-hidden="true" />
          ) : (
            <Download className="mr-2 h-4 w-4" aria-hidden="true" />
          )}
          Descargar PDF
        </button>
      </div>
      {pdfError && (
        <p role="alert" className="text-sm font-medium text-red-600">
          {pdfError}
        </p>
      )}

      <RejectQuotationModal
        isOpen={isRejectOpen}
        quotationId={quotationId}
        etag={etag}
        onClose={() => setRejectOpen(false)}
        onStatusError={handleStatusError}
      />
    </div>
  )
}
