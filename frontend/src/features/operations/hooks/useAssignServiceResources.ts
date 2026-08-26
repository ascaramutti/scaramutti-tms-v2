import { useMutation, useQueryClient } from '@tanstack/react-query'
import { assignServiceResources, type AssignResourcesRequest } from '../../../api'
import { operationsKeys } from '../queryKeys'
import { writeServiceDetail } from './serviceDetailCache'

/**
 * Asigna los recursos principales de un viaje.
 *
 * `throwOnError: true` para que el error llegue como `AxiosError` y la pantalla pueda
 * leerle el código: distinguir el conflicto forzable del que no lo es es la decisión
 * central de este formulario, y los dos son 409.
 *
 * Invalida el listado y los indicadores, y nada más. Asignar mueve las dos cosas
 * porque el viaje abandona "pendiente de asignación": cambia la fila (conductor,
 * unidad y estado) y cambian dos contadores del tablero.
 */
export function useAssignServiceResources(serviceId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (body: AssignResourcesRequest) => {
      const { data, headers } = await assignServiceResources({
        path: { id: serviceId },
        body,
        throwOnError: true,
      })
      if (!data) {
        throw new Error('Respuesta vacía del backend en POST /services/{id}/assignment')
      }
      return { data, headers }
    },
    onSuccess: ({ data, headers }) => {
      writeServiceDetail(queryClient, serviceId, data, headers)
      void queryClient.invalidateQueries({ queryKey: operationsKeys.serviceLists() })
      void queryClient.invalidateQueries({ queryKey: operationsKeys.serviceStats() })
    },
  })
}
