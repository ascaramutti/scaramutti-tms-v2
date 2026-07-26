import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  createWarehousePurchaseInvoice,
  type WarehousePurchaseInvoiceRequest,
  type WarehousePurchaseInvoiceResponse,
} from '../../../api'
import { trimToNull } from '../../../shared/utils/trimToNull'
import { warehouseKeys } from '../queryKeys'
import type { PurchaseInvoiceFormInput } from '../schemas/purchase-invoice.schema'

/** Form → body del POST. */
export function toPurchaseInvoiceRequest(
  input: PurchaseInvoiceFormInput,
): WarehousePurchaseInvoiceRequest {
  return {
    supplierId: input.supplierId,
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
  }
}

async function performCreateWarehousePurchaseInvoice(
  body: WarehousePurchaseInvoiceRequest,
): Promise<WarehousePurchaseInvoiceResponse> {
  const { data } = await createWarehousePurchaseInvoice({ body, throwOnError: true })
  if (!data) {
    throw new Error('Respuesta vacía del backend en POST /warehouse/purchase-invoices')
  }
  return data
}

/**
 * Registra una entrada. El error llega como AxiosError (`throwOnError`) para que
 * el form distinga el 409 (factura repetida para ese proveedor) del 400.
 *
 * Registrar mueve el stock, así que invalida todo lo que se deriva de él: el
 * listado de entradas, las existencias, el kardex y los indicadores. El ETag de
 * la respuesta no se guarda: acá no hay edición ni anulación (van en su pantalla).
 */
export function useCreateWarehousePurchaseInvoice() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: performCreateWarehousePurchaseInvoice,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: warehouseKeys.purchaseInvoiceLists() })
      queryClient.invalidateQueries({ queryKey: warehouseKeys.products() })
      queryClient.invalidateQueries({ queryKey: warehouseKeys.kardexes() })
      queryClient.invalidateQueries({ queryKey: warehouseKeys.stats() })
    },
  })
}
