import { useQuery } from '@tanstack/react-query'
import { getQuotation, type QuotationResponse } from '../../../api'
import { isNotFoundError } from '../../../shared/utils/getApiErrorMessage'
import { readEtag, type WithEtag } from '../../../shared/utils/etag'
import { quotationKeys } from '../queryKeys'

/** `QuotationResponse` + el ETag opaco del header, para el `If-Match` del PUT (ver `WithEtag`). */
export type QuotationWithEtag = WithEtag<QuotationResponse>

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
