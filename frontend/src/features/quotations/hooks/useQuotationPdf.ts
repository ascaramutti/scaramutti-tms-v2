import { useMutation } from '@tanstack/react-query'
import { downloadQuotationPdf } from '../../../api'
import { filenameFromContentDisposition } from '../utils/quotationPdf'

interface QuotationPdfVars {
  id: number
  /** `true` → inline (previsualizar); `false` → attachment (descargar). */
  preview: boolean
}

interface QuotationPdfResult {
  blob: Blob
  /** Nombre de archivo del `Content-Disposition` del backend, o `null` si no vino
   * (p.ej. si el header no está expuesto por CORS) — el caller usa un fallback. */
  filename: string | null
}

async function fetchQuotationPdf({ id, preview }: QuotationPdfVars): Promise<QuotationPdfResult> {
  const { data, headers } = await downloadQuotationPdf({ path: { id }, query: { preview }, throwOnError: true })
  if (!data) {
    throw new Error('Respuesta vacía del backend en GET /quotations/{id}/pdf')
  }
  const disposition = headers?.['content-disposition'] as string | undefined
  return { blob: data, filename: filenameFromContentDisposition(disposition) }
}

/**
 * Genera el PDF de una cotización (`GET /quotations/{id}/pdf`). `preview` decide
 * inline vs attachment (el backend setea el `Content-Disposition`, del que también
 * tomamos el nombre del archivo para la descarga). Devuelve el `Blob`; el componente
 * lo abre (previsualizar) o lo descarga. `throwOnError: true` para que el error
 * (404 QUO-003, 500 COM-500) llegue como `AxiosError` y se muestre su `Problem.detail`.
 * No invalida queries: el PDF no muta estado del servidor.
 */
export function useQuotationPdf() {
  return useMutation({ mutationFn: fetchQuotationPdf })
}
