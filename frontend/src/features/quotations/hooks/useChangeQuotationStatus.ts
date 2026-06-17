import { useMutation, useQueryClient } from '@tanstack/react-query'
import { updateQuotationStatus } from '../../../api'
import { quotationKeys } from '../queryKeys'
import type { QuotationStatusTarget } from '../status/quotationStatusPresentation'
import { readEtag, type QuotationWithEtag } from './useQuotation'

interface ChangeQuotationStatusVariables {
  id: number
  /** ETag OPACO del header del GET (no el `updatedAt` del body), para el `If-Match`. */
  ifMatch: string
  /** Estado destino de la transición. */
  status: QuotationStatusTarget
  /** Motivo del rechazo. Obligatorio Y exclusivo de `status=REJECTED` (lo valida el back). */
  rejectionReason?: string
}

async function performChangeQuotationStatus({
  id,
  ifMatch,
  status,
  rejectionReason,
}: ChangeQuotationStatusVariables): Promise<QuotationWithEtag> {
  const { data, headers } = await updateQuotationStatus({
    path: { id },
    headers: { 'If-Match': ifMatch },
    // El motivo viaja SOLO en el rechazo: en las demás transiciones el backend
    // rechaza un `rejectionReason` presente (COM-001).
    body: status === 'REJECTED' ? { status, rejectionReason } : { status },
    throwOnError: true,
  })
  if (!data) {
    throw new Error('Respuesta vacía del backend en PATCH /quotations/{id}/status')
  }
  // El PATCH devuelve un ETag nuevo en el header: lo adjunto para refrescar la cache del
  // detalle con la versión vigente (así una transición siguiente no choca 412 con un ETag viejo).
  return { ...data, _etag: readEtag(headers) }
}

/**
 * Cambia el estado de una cotización (`PATCH /quotations/{id}/status`) con optimistic
 * locking vía `If-Match`. Espeja a `useUpdateQuotation`: NO optimista, `throwOnError: true`
 * para que el error llegue como AxiosError y la pantalla lo muestre (409 QUO-005 transición
 * inválida, 412 COM-004 ETag stale, 400 COM-001 motivo, 403/404).
 *
 * En éxito refresca la cache del detalle con la cotización actualizada (incluido el ETag
 * nuevo) e invalida los listados (el badge de estado cambió).
 */
export function useChangeQuotationStatus() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: performChangeQuotationStatus,
    onSuccess: (updated) => {
      queryClient.setQueryData(quotationKeys.detail(updated.id), updated)
      queryClient.invalidateQueries({ queryKey: quotationKeys.lists() })
    },
  })
}
