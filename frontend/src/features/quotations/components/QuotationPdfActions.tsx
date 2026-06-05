import { useState } from 'react'
import { Download, Eye, Loader2 } from 'lucide-react'
import { PRIMARY_BUTTON, SECONDARY_BUTTON } from '../../../shared/ui/buttonStyles'
import { useQuotationPdf } from '../hooks/useQuotationPdf'
import { getPdfErrorMessage, openQuotationPdf, saveQuotationPdf } from '../utils/quotationPdf'

type PdfMode = 'preview' | 'download'

interface QuotationPdfActionsProps {
  quotationId: number
  quotationCode: string
}

const DISABLED = 'disabled:cursor-not-allowed disabled:opacity-60'

/**
 * Acciones de PDF del Detalle: **Previsualizar** (abre el PDF inline en una pestaña
 * nueva) y **Descargar** (baja el archivo). Ambas pegan a `GET /quotations/{id}/pdf`
 * con el `preview` correspondiente. Una sola mutación a la vez (los botones se
 * deshabilitan mientras genera). Errores del backend se muestran inline con su
 * `Problem.detail`.
 */
export function QuotationPdfActions({ quotationId, quotationCode }: QuotationPdfActionsProps) {
  const { mutateAsync, isPending, variables } = useQuotationPdf()
  const [errorMessage, setErrorMessage] = useState<string | null>(null)

  async function handlePdf(mode: PdfMode) {
    setErrorMessage(null)
    try {
      const { blob, filename } = await mutateAsync({ id: quotationId, preview: mode === 'preview' })
      if (mode === 'preview') {
        const opened = openQuotationPdf(blob)
        if (!opened) {
          setErrorMessage('No se pudo abrir la previsualización (¿bloqueador de pop-ups?). Probá con "Descargar PDF".')
        }
      } else {
        saveQuotationPdf(blob, filename ?? `cotizacion-${quotationCode}.pdf`)
      }
    } catch (error) {
      setErrorMessage(await getPdfErrorMessage(error, 'No se pudo generar el PDF de la cotización.'))
    }
  }

  // Qué botón muestra el spinner: el `preview` de la mutación en curso (react-query
  // expone `variables` mientras `isPending`).
  const pendingMode: PdfMode | null = isPending ? (variables?.preview ? 'preview' : 'download') : null

  return (
    <div className="flex flex-col items-end gap-2">
      <div className="flex gap-2">
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
      {errorMessage && (
        <p role="alert" className="text-sm font-medium text-red-600">
          {errorMessage}
        </p>
      )}
    </div>
  )
}
