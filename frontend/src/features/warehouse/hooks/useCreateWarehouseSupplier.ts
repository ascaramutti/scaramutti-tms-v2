import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  createWarehouseSupplier,
  type WarehouseSupplierRequest,
  type WarehouseSupplierResponse,
} from '../../../api'
import { trimToNull } from '../../../shared/utils/trimToNull'
import { warehouseKeys } from '../queryKeys'
import type { SupplierFormInput } from '../schemas/supplier.schema'

/** Form → body del POST de proveedor. */
export function toSupplierRequest(input: SupplierFormInput): WarehouseSupplierRequest {
  return {
    name: input.name.trim(),
    ruc: trimToNull(input.ruc),
    phone: trimToNull(input.phone),
    contactName: trimToNull(input.contactName),
  }
}

async function performCreateWarehouseSupplier(
  body: WarehouseSupplierRequest,
): Promise<WarehouseSupplierResponse> {
  const { data } = await createWarehouseSupplier({ body, throwOnError: true })
  if (!data) {
    throw new Error('Respuesta vacía del backend en POST /warehouse/suppliers')
  }
  return data
}

/**
 * Crea un proveedor al vuelo desde el form de la entrada. `throwOnError: true`
 * para que el 409 (nombre o RUC repetido) llegue como AxiosError y el modal
 * muestre el `detail` del backend. Invalida las búsquedas para que aparezca.
 */
export function useCreateWarehouseSupplier() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: performCreateWarehouseSupplier,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: warehouseKeys.supplierSearches() })
    },
  })
}
