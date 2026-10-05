import type { QueryClient } from '@tanstack/react-query'
import type { WorkerDetailResponse } from '../../../api'
import { operationsKeys } from '../../operations/queryKeys'
import { warehouseKeys } from '../../warehouse/queryKeys'
import { workerKeys } from '../queryKeys'

/**
 * Lo guardado tiene que verse en el padrón y en los dos combobox que lo ofrecen (quién recibe un
 * retiro y los conductores de un viaje). Los catálogos cuelgan de la raíz del padrón: se marcan
 * viejos sin pedirlos otra vez. Después de invalidar, la ficha queda sembrada con lo que devolvió
 * el backend: abre sin spinner. Si ya pudo estar asignado, también los viajes: una baja los deja
 * para reasignar, cambia los conductores en ruta, y un cambio de nombre o licencia se ve en ellos.
 */
export async function refreshAfterWorkerSaved(
  queryClient: QueryClient,
  saved: WorkerDetailResponse,
  { mayBeAssigned }: { mayBeAssigned: boolean },
) {
  await Promise.all([
    queryClient.invalidateQueries({ queryKey: workerKeys.all, refetchType: 'none' }),
    queryClient.invalidateQueries({ queryKey: warehouseKeys.workerSearches() }),
    queryClient.invalidateQueries({ queryKey: operationsKeys.drivers() }),
    ...(mayBeAssigned
      ? [
          queryClient.invalidateQueries({ queryKey: operationsKeys.serviceLists() }),
          queryClient.invalidateQueries({ queryKey: operationsKeys.serviceDetails() }),
          queryClient.invalidateQueries({ queryKey: operationsKeys.serviceStats() }),
        ]
      : []),
  ])
  queryClient.setQueryData(workerKeys.detail(saved.id), saved)
}
