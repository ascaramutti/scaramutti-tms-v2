import { useQuery } from '@tanstack/react-query'
import { getQuotation, type QuotationResponse } from '../../../api'
import { isNotFoundError } from '../../../shared/utils/getApiErrorMessage'
import { quotationKeys } from '../queryKeys'

/**
 * `QuotationResponse` + el ETag OPACO del header de la respuesta, para el `If-Match` del PUT.
 *
 * El `_etag` NO sale del body: el campo `updatedAt` del JSON NO sirve como If-Match porque su
 * serialización difiere del ETag que el backend compara (p.ej. Jackson recorta un cero final de
 * los microsegundos → `.39289Z` en el body vs `.392890Z` en el ETag → 412 espurio). Según HTTP el
 * ETag es opaco: hay que reenviar el valor del header tal cual, sin reconstruirlo.
 */
export interface QuotationWithEtag extends QuotationResponse {
  _etag: string | null
}

/** Lee el header `ETag` de la respuesta, o `null` si no vino. Prueba las dos casing que se dan en
 * la práctica: el adapter del browser lo baja a `etag`; en Node/tests (MSW) queda `ETag`. */
export function readEtag(headers: unknown): string | null {
  if (!headers || typeof headers !== 'object') return null
  const record = headers as Record<string, unknown>
  const value = record.etag ?? record.ETag
  return typeof value === 'string' ? value : null
}

async function fetchQuotation(id: number): Promise<QuotationWithEtag> {
  const { data, headers } = await getQuotation({ path: { id }, throwOnError: true })
  if (!data) {
    throw new Error('Respuesta vacía del backend en GET /quotations/{id}')
  }
  return { ...data, _etag: readEtag(headers) }
}

/**
 * Trae el detalle completo de una cotización (+ su ETag para optimistic locking). Hereda el
 * `staleTime` global (30 s): no se sube para que `isExpired` (recomputado server-side en cada
 * GET) quede al día razonablemente pronto al revisitar.
 *
 * No reintenta ante 404 (recurso inexistente) para que el estado "no encontrada" aparezca de
 * inmediato; el resto de errores siguen un reintento.
 */
export function useQuotation(id: number) {
  return useQuery({
    queryKey: quotationKeys.detail(id),
    queryFn: () => fetchQuotation(id),
    enabled: Number.isInteger(id) && id > 0,
    retry: (failureCount, error) => !isNotFoundError(error) && failureCount < 1,
  })
}
