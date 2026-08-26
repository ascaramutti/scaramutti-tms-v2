import { useMutation, useQueryClient } from '@tanstack/react-query'
import { addServiceResources, type AddResourcesRequest } from '../../../api'
import { writeServiceDetail } from './serviceDetailCache'

/**
 * Suma recursos de refuerzo a un viaje que ya está en ruta.
 *
 * `throwOnError: true` para que el error llegue como `AxiosError`: acá conviven dos
 * conflictos que son el mismo 409 y se tratan distinto, y lo único que los separa es
 * el código.
 *
 * NO invalida el listado ni los indicadores, y es una decisión, no un olvido: un
 * refuerzo no cambia ninguna columna de la fila del listado (que publica solo los
 * recursos principales), no mueve el estado del viaje y no entra en ningún contador
 * del tablero, que cuenta conductores y tractos PRINCIPALES en ruta. Invalidar ahí
 * serían dos consultas por algo que no pudo haber cambiado.
 */
export function useAddServiceResources(serviceId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async (body: AddResourcesRequest) => {
      const { data, headers } = await addServiceResources({
        path: { id: serviceId },
        body,
        throwOnError: true,
      })
      if (!data) {
        throw new Error('Respuesta vacía del backend en POST /services/{id}/resources')
      }
      return { data, headers }
    },
    onSuccess: ({ data, headers }) => {
      writeServiceDetail(queryClient, serviceId, data, headers)
    },
  })
}
