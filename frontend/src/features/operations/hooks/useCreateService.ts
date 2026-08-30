import { useMutation, useQueryClient } from '@tanstack/react-query'
import { createService, type ServiceCreateRequest, type ServiceDetailResponse } from '../../../api'
import { operationsKeys } from '../queryKeys'

async function performCreateService(body: ServiceCreateRequest): Promise<ServiceDetailResponse> {
  const { data } = await createService({ body, throwOnError: true })
  if (!data) {
    throw new Error('Respuesta vacía del backend en POST /services')
  }
  return data
}

/**
 * Registra un servicio. `throwOnError: true` para que el error llegue como
 * AxiosError y el formulario pueda leer su código: el 409 `OPS-007` (el mismo viaje
 * cargado hace segundos) necesita un mensaje propio, distinto del de un conflicto
 * real.
 *
 * Invalida el listado y los indicadores por separado: el servicio nace en
 * `PENDING_ASSIGNMENT`, así que el alta mueve tanto las filas como el contador de
 * pendientes de asignación, y son dos ramas distintas del cache.
 */
export function useCreateService() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: performCreateService,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: operationsKeys.serviceLists() })
      void queryClient.invalidateQueries({ queryKey: operationsKeys.serviceStats() })
    },
  })
}
