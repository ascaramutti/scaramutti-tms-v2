import { useMutation, useQueryClient } from '@tanstack/react-query'
import { removeServiceResource } from '../../../api'
import { operationsKeys } from '../queryKeys'
import { writeServiceDetail } from './serviceDetailCache'

/**
 * Da de baja un refuerzo cargado por error.
 *
 * La baja es FÍSICA: la fila desaparece. El rastro no se va con ella, porque la
 * bitácora y la auditoría cuelgan del viaje y no del refuerzo.
 *
 * El endpoint devuelve el detalle completo y no un 204, y el motivo es el mismo por el
 * que acá se guarda el ETag de la respuesta: el borrado mueve la versión del viaje, y
 * un 204 dejaría al cliente con una versión que la base ya no tiene.
 *
 * Ante un 404 se invalida el detalle: el refuerzo ya no está y el cache miente.
 * Tampoco invalida el listado ni los indicadores, por lo mismo que el alta.
 */
export function useRemoveServiceResource(serviceId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (assignmentId: number) => {
      const { data, headers } = await removeServiceResource({
        path: { id: serviceId, assignmentId },
        throwOnError: true,
      })
      // Por el `id` y no por el objeto: el cliente generado hace `data ?? {}`, así
      // que ante un 200 con cuerpo vacío `!data` no dispara y el objeto sin un solo
      // campo llega al cache, donde revienta al renderizarlo lejos de acá.
      if (!data?.id) {
        throw new Error(
          'Respuesta vacía del backend en DELETE /services/{id}/resources/{assignmentId}',
        )
      }
      return { data, headers }
    },
    onSuccess: ({ data, headers }) => {
      writeServiceDetail(queryClient, serviceId, data, headers)
    },
    onError: (error) => {
      const status = (error as { response?: { status?: number } }).response?.status
      if (status === 404) {
        void queryClient.invalidateQueries({ queryKey: operationsKeys.serviceDetail(serviceId) })
      }
    },
  })
}
