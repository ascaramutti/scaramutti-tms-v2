import type { QueryClient } from '@tanstack/react-query'
import type { WorkerDetailResponse } from '../../../api'
import { operationsKeys } from '../../operations/queryKeys'
import { warehouseKeys } from '../../warehouse/queryKeys'
import { workerKeys } from '../queryKeys'

/**
 * Lo guardado tiene que verse en el padrón y en los dos combobox que lo ofrecen (quién recibe un
 * retiro y los conductores de un viaje). Los catálogos cuelgan de la raíz del padrón: se marcan
 * viejos sin pedirlos otra vez. Después de invalidar, la ficha queda sembrada con lo que devolvió
 * el backend: abre sin spinner.
 */
export async function refreshAfterWorkerSaved(queryClient: QueryClient, saved: WorkerDetailResponse) {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: workerKeys.all, refetchType: 'none' }),
    queryClient.invalidateQueries({ queryKey: warehouseKeys.workerSearches() }),
    queryClient.invalidateQueries({ queryKey: operationsKeys.drivers() }),
  ])
  queryClient.setQueryData(workerKeys.detail(saved.id), saved)
}
