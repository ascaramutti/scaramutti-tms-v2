import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  updateWarehousePurchaseInvoice,
  type WarehousePurchaseInvoiceUpdateRequest,
} from '../../../api'
import { readEtag } from '../../../shared/utils/etag'
import { trimToNull } from '../../../shared/utils/trimToNull'
import { warehouseKeys } from '../queryKeys'
import type { WarehousePurchaseInvoiceWithEtag } from './useWarehousePurchaseInvoice'
import type { PurchaseInvoiceEditFormInput } from '../schemas/purchase-invoice.schema'

interface UpdatePurchaseInvoiceVariables {
  id: number
  /** ETag OPACO del header del GET (no el `updatedAt` del body), para el header `If-Match`. */
  ifMatch: string
  body: WarehousePurchaseInvoiceUpdateRequest
}

/**
 * Form de edición → body del PUT.
 *
 * - NO incluye `supplierId`: el proveedor es inmutable y el contrato no lo acepta
 *   en `WarehousePurchaseInvoiceUpdateRequest` (mismo criterio que la unidad de
 *   medida en el PUT de un producto).
 * - CON `reason`: la justificación obligatoria (RN-WH4) que va a la auditoría.
 * - Los ítems son un REEMPLAZO COMPLETO: lo que quede en el form es lo que se manda.
 */
export function toPurchaseInvoiceUpdateRequest(
  input: PurchaseInvoiceEditFormInput,
): WarehousePurchaseInvoiceUpdateRequest {
  return {
    invoiceNumber: input.invoiceNumber.trim(),
    invoiceDate: input.invoiceDate,
    guideNumber: trimToNull(input.guideNumber),
    currencyId: input.currencyId,
    observations: trimToNull(input.observations),
    items: input.items.map((item) => ({
      productId: item.productId,
      quantity: item.quantity,
      unitPrice: item.unitPrice,
    })),
    reason: input.reason.trim(),
  }
}

async function performUpdatePurchaseInvoice({
  id,
  ifMatch,
  body,
}: UpdatePurchaseInvoiceVariables): Promise<WarehousePurchaseInvoiceWithEtag> {
  const { data, headers } = await updateWarehousePurchaseInvoice({
    path: { id },
    headers: { 'If-Match': ifMatch },
    body,
    throwOnError: true,
  })
  if (!data) {
    throw new Error('Respuesta vacía del backend en PUT /warehouse/purchase-invoices/{id}')
  }
  // El PUT devuelve un ETag nuevo en el header: lo adjunto para refrescar la cache
  // del detalle con la versión vigente (una segunda edición seguida no choca 412).
  return { ...data, _etag: readEtag(headers) }
}

/**
 * Edita una entrada (`PUT /warehouse/purchase-invoices/{id}`) con optimistic
 * locking vía `If-Match`. Los errores llegan como AxiosError para que el form
 * distinga el 412 (otro usuario cambió la factura primero) del 409 (WH-002
 * duplicado, WH-006 stock negativo, WH-008 anulada).
 *
 * Editar reemplaza los ítems y puede mover el stock, así que en éxito refresca el
 * detalle con el ETag nuevo e invalida lo derivado del stock: el listado, los
 * productos (Existencias, ficha, combobox), los indicadores y el kardex.
 */
export function useUpdateWarehousePurchaseInvoice() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: performUpdatePurchaseInvoice,
    onSuccess: (updated) => {
      queryClient.setQueryData(warehouseKeys.purchaseInvoiceDetail(updated.id), updated)
      queryClient.invalidateQueries({ queryKey: warehouseKeys.purchaseInvoiceLists() })
      queryClient.invalidateQueries({ queryKey: warehouseKeys.products() })
      queryClient.invalidateQueries({ queryKey: warehouseKeys.stats() })
      queryClient.invalidateQueries({ queryKey: warehouseKeys.kardexes() })
    },
  })
}
