import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  cancelWarehousePurchaseInvoice,
  type WarehouseCancelRequest,
} from '../../../api'
import { readEtag } from '../../../shared/utils/etag'
import { warehouseKeys } from '../queryKeys'
import type { WarehousePurchaseInvoiceWithEtag } from './useWarehousePurchaseInvoice'

interface CancelPurchaseInvoiceVariables {
  id: number
  /** ETag OPACO del header del GET (no el `updatedAt` del body), para el header `If-Match`. */
  ifMatch: string
  body: WarehouseCancelRequest
}

async function performCancelPurchaseInvoice({
  id,
  ifMatch,
  body,
}: CancelPurchaseInvoiceVariables): Promise<WarehousePurchaseInvoiceWithEtag> {
  const { data, headers } = await cancelWarehousePurchaseInvoice({
    path: { id },
    headers: { 'If-Match': ifMatch },
    body,
    throwOnError: true,
  })
  if (!data) {
    throw new Error('Respuesta vacía del backend en POST /warehouse/purchase-invoices/{id}/cancel')
  }
  return { ...data, _etag: readEtag(headers) }
}

/**
 * Anula una entrada (`POST /warehouse/purchase-invoices/{id}/cancel`) con
 * optimistic locking vía `If-Match`. Los errores llegan como AxiosError para que
 * el modal distinga el 412 (otro usuario cambió la factura primero) del 409
 * (WH-006/WH-008: la anulación dejaría stock negativo o la factura ya está anulada).
 *
 * En éxito refresca el detalle con la versión anulada e invalida:
 * - el listado de entradas (la fila pasa a "Anulada"),
 * - los productos (Existencias, la ficha y las búsquedas del combobox): anular
 *   descuenta el stock que la entrada había sumado,
 * - los indicadores del dashboard (el stock cambió),
 * - el kardex (anular borra los movimientos que la entrada había generado).
 */
export function useCancelWarehousePurchaseInvoice() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: performCancelPurchaseInvoice,
    onSuccess: (cancelled) => {
      queryClient.setQueryData(warehouseKeys.purchaseInvoiceDetail(cancelled.id), cancelled)
      queryClient.invalidateQueries({ queryKey: warehouseKeys.purchaseInvoiceLists() })
      queryClient.invalidateQueries({ queryKey: warehouseKeys.products() })
      queryClient.invalidateQueries({ queryKey: warehouseKeys.stats() })
      queryClient.invalidateQueries({ queryKey: warehouseKeys.kardexes() })
    },
  })
}
