import { useQuery } from '@tanstack/react-query'
import {
  listWarehouseUnitsOfMeasure,
  type WarehouseUnitOfMeasureResponse,
} from '../../../api'
import { warehouseKeys } from '../queryKeys'

async function fetchWarehouseUnitsOfMeasure(): Promise<WarehouseUnitOfMeasureResponse[]> {
  const { data } = await listWarehouseUnitsOfMeasure({
    query: { isActive: true },
    throwOnError: true,
  })
  if (!data) {
    throw new Error('Respuesta vacía del backend en GET /warehouse/units-of-measure')
  }
  return data
}

/**
 * Catálogo de unidades de medida (opciones del alta de producto). A diferencia
 * de las categorías, es una lista CERRADA: no se crea al vuelo (el contrato ni
 * siquiera expone un POST). `staleTime: Infinity`, como el resto de catálogos.
 */
export function useWarehouseUnitsOfMeasure() {
  return useQuery({
    queryKey: warehouseKeys.unitsOfMeasure(),
    queryFn: fetchWarehouseUnitsOfMeasure,
    staleTime: Infinity,
  })
}
