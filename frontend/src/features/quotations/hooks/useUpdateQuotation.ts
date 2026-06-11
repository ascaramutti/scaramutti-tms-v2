import { useMutation, useQueryClient } from '@tanstack/react-query'
import { updateQuotation, type QuotationRequest } from '../../../api'
import { quotationKeys } from '../queryKeys'
import { readEtag, type QuotationWithEtag } from './useQuotation'

interface UpdateQuotationVariables {
  id: number
  /** ETag OPACO del header del GET (no el `updatedAt` del body), para el header `If-Match`. */
  ifMatch: string
  body: QuotationRequest
}

async function performUpdateQuotation({
  id,
  ifMatch,
  body,
}: UpdateQuotationVariables): Promise<QuotationWithEtag> {
  const { data, headers } = await updateQuotation({
    path: { id },
    headers: { 'If-Match': ifMatch },
    body,
    throwOnError: true,
  })
  if (!data) {
    throw new Error('Respuesta vacía del backend en PUT /quotations/{id}')
  }
  // El PUT devuelve un ETag nuevo en el header: lo adjunto para refrescar la cache del detalle
  // con la versión vigente (así un re-edit inmediato no choca 412 con un ETag viejo).
  return { ...data, _etag: readEtag(headers) }
}

/**
 * Edita una cotización (`PUT /quotations/{id}`) con optimistic locking via `If-Match`.
 * `throwOnError: true` para que el error (412 COM-004 si otro usuario editó primero, 400
 * COM-001 validación / QUO-004 inmutable) llegue como AxiosError y la pantalla lo muestre
 * con `getApiErrorMessage`.
 *
 * En éxito refresca la cache del detalle con la cotización actualizada (incluido el ETag nuevo)
 * e invalida los listados (la validez/ruta/totales pudieron cambiar).
 */
export function useUpdateQuotation() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: performUpdateQuotation,
    onSuccess: (updated) => {
      queryClient.setQueryData(quotationKeys.detail(updated.id), updated)
      queryClient.invalidateQueries({ queryKey: quotationKeys.lists() })
    },
  })
}
