import { useQuery } from '@tanstack/react-query'
import { getWarehouseProductStock, type WarehouseProductStockResponse } from '../../../api'
import { warehouseKeys } from '../queryKeys'

async function fetchWarehouseProductStock(id: number): Promise<WarehouseProductStockResponse> {
  const { data } = await getWarehouseProductStock({ path: { id }, throwOnError: true })
  if (!data) {
    throw new Error('Respuesta vacía del backend en GET /warehouse/products/{id}/stock')
  }
  return data
}

/**
 * Stock disponible en vivo de un producto, para orientar la cantidad del retiro.
 * Solo consulta cuando hay un producto elegido (`enabled`). Es un aviso, no un
 * gate: la autoridad es el 409 WH-001 del backend (validado bajo lock), porque el
 * stock puede cambiar entre esta lectura y el envío.
 */
export function useWarehouseProductStock(productId: number | null) {
  return useQuery({
    queryKey: warehouseKeys.productStock(productId ?? 0),
    queryFn: () => fetchWarehouseProductStock(productId as number),
    enabled: productId != null,
  })
}
