import { useQuery } from '@tanstack/react-query'
import {
  listWarehouseProductCategories,
  type WarehouseProductCategoryResponse,
} from '../../../api'
import { warehouseKeys } from '../queryKeys'

async function fetchWarehouseProductCategories(): Promise<WarehouseProductCategoryResponse[]> {
  const { data } = await listWarehouseProductCategories({
    query: { isActive: true },
    throwOnError: true,
  })
  if (!data) {
    throw new Error('Respuesta vacía del backend en GET /warehouse/product-categories')
  }
  return data
}

/**
 * Catálogo de categorías de producto (opciones del filtro). Cambia muy poco:
 * `staleTime: Infinity`, mismo criterio que los catálogos de cotizaciones.
 */
export function useWarehouseProductCategories() {
  return useQuery({
    queryKey: warehouseKeys.productCategories(),
    queryFn: fetchWarehouseProductCategories,
    staleTime: Infinity,
  })
}
