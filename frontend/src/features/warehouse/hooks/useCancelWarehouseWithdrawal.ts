import { useMutation, useQueryClient } from '@tanstack/react-query'
import { cancelWarehouseWithdrawal, type WarehouseCancelRequest } from '../../../api'
import { readEtag } from '../../../shared/utils/etag'
import { warehouseKeys } from '../queryKeys'
import type { WarehouseWithdrawalWithEtag } from './useWarehouseWithdrawal'

interface CancelWithdrawalVariables {
  id: number
  /** ETag OPACO del header del GET (no el `updatedAt` del body), para el header `If-Match`. */
  ifMatch: string
  body: WarehouseCancelRequest
}

async function performCancelWithdrawal({
  id,
  ifMatch,
  body,
}: CancelWithdrawalVariables): Promise<WarehouseWithdrawalWithEtag> {
  const { data, headers } = await cancelWarehouseWithdrawal({
    path: { id },
    headers: { 'If-Match': ifMatch },
    body,
    throwOnError: true,
  })
  if (!data) {
    throw new Error('Respuesta vacía del backend en POST /warehouse/withdrawals/{id}/cancel')
  }
  return { ...data, _etag: readEtag(headers) }
}

/**
 * Anula un retiro (`POST /warehouse/withdrawals/{id}/cancel`) con optimistic
 * locking vía `If-Match`. Los errores llegan como AxiosError para que el modal
 * distinga el 412 (otro usuario cambió el retiro primero) del 409 (WH-008: ya
 * está anulado). A diferencia de las entradas, anular un retiro nunca puede
 * dejar stock negativo: devuelve al almacén lo que había sacado, así que no hay
 * un conflicto de stock que manejar.
 *
 * En éxito refresca el detalle con la versión anulada e invalida:
 * - el listado de retiros (la fila pasa a "Anulado"),
 * - los productos (Existencias, la ficha y las búsquedas del combobox): el stock
 *   que el retiro había descontado vuelve,
 * - los indicadores del dashboard (el stock cambió),
 * - el kardex (anular borra la salida que el retiro había generado).
 */
export function useCancelWarehouseWithdrawal() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: performCancelWithdrawal,
    onSuccess: (cancelled) => {
      queryClient.setQueryData(warehouseKeys.withdrawalDetail(cancelled.id), cancelled)
      queryClient.invalidateQueries({ queryKey: warehouseKeys.withdrawalLists() })
      queryClient.invalidateQueries({ queryKey: warehouseKeys.products() })
      queryClient.invalidateQueries({ queryKey: warehouseKeys.stats() })
      queryClient.invalidateQueries({ queryKey: warehouseKeys.kardexes() })
    },
  })
}
