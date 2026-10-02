import { useMutation, useQueryClient } from '@tanstack/react-query'
import { createWorker, type WorkerDetailResponse, type WorkerRequest } from '../../../api'
import { operationsKeys } from '../../operations/queryKeys'
import { warehouseKeys } from '../../warehouse/queryKeys'
import { workerKeys } from '../queryKeys'

/**
 * El alta de un trabajador. Al guardar, lo nuevo tiene que verse en el padrón y en los
 * dos combobox que lo ofrecen (quién recibe un retiro y los conductores de un viaje).
 * Los catálogos cuelgan de la raíz del padrón: se marcan viejos sin pedirlos otra vez.
 */
export function useCreateWorker() {
  const queryClient = useQueryClient()
  return useMutation<WorkerDetailResponse, unknown, WorkerRequest>({
    mutationKey: workerKeys.create(),
    mutationFn: async (body) => {
      const { data } = await createWorker({ body, throwOnError: true })
      if (!data) {
        throw new Error('Respuesta vacía del backend en POST /workers')
      }
      return data
    },
    onSuccess: async (created) => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: workerKeys.all, refetchType: 'none' }),
        queryClient.invalidateQueries({ queryKey: warehouseKeys.workerSearches() }),
        queryClient.invalidateQueries({ queryKey: operationsKeys.drivers() }),
      ])
      // Después de invalidar: la ficha abre con lo que devolvió el alta, sin spinner.
      queryClient.setQueryData(workerKeys.detail(created.id), created)
    },
  })
}
