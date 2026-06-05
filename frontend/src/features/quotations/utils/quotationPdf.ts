import { isAxiosError } from 'axios'
import type { Problem } from '../../../api'
import { getApiErrorMessage } from '../../../shared/utils/getApiErrorMessage'

/** Dispara la descarga del PDF (blob) como archivo con el nombre dado. */
export function saveQuotationPdf(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  try {
    const link = document.createElement('a')
    link.href = url
    link.download = filename
    document.body.appendChild(link)
    link.click()
    link.remove()
  } finally {
    // Siempre liberar el object URL, aunque algún paso intermedio falle.
    URL.revokeObjectURL(url)
  }
}

/**
 * Abre el PDF (blob) en una pestaña nueva para previsualizar. Devuelve `false` si
 * el navegador bloqueó la pestaña (pop-up blocker) — el caller muestra el feedback.
 *
 * Sin `noopener`: se necesita el handle de la ventana para detectar el bloqueo, y
 * un `blob:` es same-origin (no expone un `opener` sensible). El object URL no se
 * revoca de inmediato (la pestaña recién abierta lo necesita para renderizar); se
 * libera tras un margen amplio. Si el pop-up fue bloqueado, se revoca al instante.
 */
export function openQuotationPdf(blob: Blob): boolean {
  const url = URL.createObjectURL(blob)
  const win = window.open(url, '_blank')
  if (!win) {
    URL.revokeObjectURL(url)
    return false
  }
  window.setTimeout(() => URL.revokeObjectURL(url), 60_000)
  return true
}

/** Extrae el `filename` de un header `Content-Disposition` (o `null` si no viene). */
export function filenameFromContentDisposition(header: string | undefined): string | null {
  if (!header) return null
  const match = /filename="?([^";]+)"?/i.exec(header)
  return match?.[1]?.trim() ?? null
}

/**
 * Mensaje de error para fallos del PDF. Como el endpoint usa `responseType: 'blob'`,
 * el body de error (Problem JSON) también llega como `Blob` — hay que leerlo y
 * parsearlo para respetar la regla del proyecto: mostrar el `Problem.detail` del
 * backend, no inventar copy. Si el blob no es JSON, cae al `fallback`.
 */
export async function getPdfErrorMessage(error: unknown, fallback: string): Promise<string> {
  if (isAxiosError(error) && error.response?.data instanceof Blob) {
    try {
      const problem = JSON.parse(await error.response.data.text()) as Problem
      if (problem?.detail) {
        return problem.detail
      }
    } catch {
      // el blob no es JSON parseable → fallback
    }
    return fallback
  }
  return getApiErrorMessage(error, fallback)
}
