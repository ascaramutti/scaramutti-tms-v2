import { useMutation, useQueryClient } from '@tanstack/react-query'
import { changeServiceStatus, type ChangeStatusRequest } from '../../../api'
import { operationsKeys } from '../queryKeys'
import { writeServiceDetail } from './serviceDetailCache'

interface ChangeServiceStatusVariables {
  /** ETag OPACO del header de la respuesta anterior (no el `updatedAt` del cuerpo). */
  ifMatch: string | null
  body: ChangeStatusRequest
}

/**
 * Mueve el viaje de estado.
 *
 * Un solo hook para todas las transiciones porque son un solo endpoint, con un solo
 * cuerpo, y lo que hay que refrescar después es idéntico: lo que cambia entre ellas es
 * el `target` y lo que el formulario recoge, no lo que pasa acá.
 *
 * El `If-Match` viaja siempre que haya uno, incluso donde el servidor no lo exige.
 * Protege de operar sobre una pantalla que quedó vieja mientras el modal estaba abierto,
 * y el costo aceptado es que aparezca algún 412 en transiciones que hoy pasarían de
 * largo. Cuando no hay ETag el header no se manda y la pantalla sigue ofreciendo las
 * acciones: que el servidor deje de exponer el header es un problema de configuración,
 * no un estado del viaje, y esconder los botones lo haría ver como si el sistema no
 * dejara operar.
 *
 * `throwOnError: true` para que el error llegue como `AxiosError` y el modal pueda
 * leerle el `detail` y el código.
 *
 * Se invalidan el listado y los indicadores, y nada más. Cambiar de estado mueve la fila
 * de la tabla y los contadores del tablero, que se agrupan justamente por estado. El
 * detalle no se invalida: se ESCRIBE con la respuesta, que ya lo trae completo.
 */
export function useChangeServiceStatus(serviceId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({ ifMatch, body }: ChangeServiceStatusVariables) => {
      const { data, headers } = await changeServiceStatus({
        path: { id: serviceId },
        headers: ifMatch === null ? undefined : { 'If-Match': ifMatch },
        body,
        throwOnError: true,
      })
      if (!data) {
        throw new Error('Respuesta vacía del backend en POST /services/{id}/status')
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
