import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  createWarehouseProduct,
  type WarehouseProductRequest,
  type WarehouseProductResponse,
} from '../../../api'
import { trimToNull } from '../../../shared/utils/trimToNull'
import { warehouseKeys } from '../queryKeys'
import type { ProductCreateInput } from '../schemas/product.schema'

/**
 * Form → body del POST.
 *
 * - `code` no viaja: lo asigna el backend (PRO-NNNN).
 * - `isActive` tampoco: el contrato dice que en el POST se ignora (nace activo).
 * - Las características se editan como filas para poder agregarlas y quitarlas,
 *   pero el contrato las quiere como objeto.
 */
export function toProductCreateRequest(input: ProductCreateInput): WarehouseProductRequest {
  return {
    name: input.name.trim(),
    categoryId: input.categoryId,
    unitOfMeasureId: input.unitOfMeasureId,
    brand: trimToNull(input.brand),
    partNumber: trimToNull(input.partNumber),
    attributes: Object.fromEntries(
      input.attributes.map((attribute) => [attribute.key.trim(), attribute.value.trim()]),
    ),
    minStock: input.minStock,
    observations: trimToNull(input.observations),
  }
}

async function performCreateWarehouseProduct(
  body: WarehouseProductRequest,
): Promise<WarehouseProductResponse> {
  const { data } = await createWarehouseProduct({ body, throwOnError: true })
  if (!data) {
    throw new Error('Respuesta vacía del backend en POST /warehouse/products')
  }
  return data
}

/**
 * Da de alta un producto al vuelo desde el form de la entrada. `throwOnError:
 * true` para que el 409 (identidad nombre+marca+número de parte repetida) llegue
 * como AxiosError y el modal muestre el `detail` del backend.
 *
 * Invalida el listado de existencias, las búsquedas del combobox y los
 * indicadores: el producto nuevo suma al contador de activos.
 */
export function useCreateWarehouseProduct() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: performCreateWarehouseProduct,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: warehouseKeys.productLists() })
      queryClient.invalidateQueries({ queryKey: warehouseKeys.productSearches() })
      queryClient.invalidateQueries({ queryKey: warehouseKeys.stats() })
    },
  })
}
