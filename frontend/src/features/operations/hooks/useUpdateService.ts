import { useMutation, useQueryClient } from '@tanstack/react-query'
import { updateService, type ServiceUpdateRequest } from '../../../api'
import { operationsKeys } from '../queryKeys'
import { writeServiceDetail } from './serviceDetailCache'

interface UpdateServiceVariables {
  /**
   * ETag del header de la respuesta anterior, que se reenvía TAL CUAL. No se arma con el
   * `updatedAt` del cuerpo: el porqué está en `useService`.
   */
  ifMatch: string | null
  body: ServiceUpdateRequest
}

/**
 * Corrige los datos de un viaje.
 *
 * A diferencia de las transiciones de estado, acá el `If-Match` lo EXIGE el contrato, y
 * el tipo generado lo declara obligatorio. Sin ETag se manda vacío, con lo que el servidor
 * contesta 412: es el respaldo para quien llegue por otro camino, porque la pantalla ya no
 * ofrece guardar en ese estado (avisa que falta la versión y deshabilita el botón, en vez
 * de dejar que el usuario llene el formulario para chocar contra un 412 que le hablaría de
 * un conflicto inexistente).
 *
 * Se invalidan el listado y los indicadores, y nada más. Editar mueve datos que la tabla
 * muestra y por los que filtra (la fecha tentativa, el origen, el destino, el importe), y
 * los indicadores cuentan por fecha. El detalle no se invalida: se ESCRIBE con la
 * respuesta, que ya lo trae completo con su bitácora nueva.
 *
 * `throwOnError: true` para que el error llegue como `AxiosError` y la pantalla pueda
 * leerle el `detail` y el código, que acá importan más que en ningún otro endpoint del
 * módulo: 409 si el viaje salió del circuito, 412 si alguien lo editó en el medio, 403 si
 * quien mira no puede ver los importes.
 */
export function useUpdateService(serviceId: number) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({ ifMatch, body }: UpdateServiceVariables) => {
      const { data, headers } = await updateService({
        path: { id: serviceId },
        headers: { 'If-Match': ifMatch ?? '' },
        body,
        throwOnError: true,
      })
      // Se pregunta por el `id` y no por el objeto entero: ante un 200 con el cuerpo
      // vacío el cliente generado no entrega `null` sino `{}`, así que un `if (!data)`
      // no dispara y el objeto sin un solo campo llega igual al cache, donde revienta
      // al renderizarlo lejos de acá.
      if (!data?.id) {
        throw new Error('Respuesta vacía del backend en PUT /services/{id}')
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
