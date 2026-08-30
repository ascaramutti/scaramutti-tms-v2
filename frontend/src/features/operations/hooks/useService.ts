import { useQuery } from '@tanstack/react-query'
import { getService, type ServiceDetailResponse } from '../../../api'
import { readEtag, type WithEtag } from '../../../shared/utils/etag'
import { isNotFoundError } from '../../../shared/utils/getApiErrorMessage'
import { operationsKeys } from '../queryKeys'

/**
 * Detalle del viaje más el ETag del header, que hay que reenviar TAL CUAL.
 *
 * Se guarda desde ya, aunque esta pantalla todavía no escriba nada: es lo que van
 * a exigir en el `If-Match` la edición, la cancelación, la eliminación y la
 * reapertura.
 *
 * No es opaco (el servidor lo arma con la fecha de actualización, así que se puede
 * reconstruir), pero reconstruirlo termina en un 412 espurio: Jackson recorta el
 * cero final de los microsegundos, así que el `updatedAt` del cuerpo y el ETag del
 * header son el mismo instante escrito distinto.
 */
export type ServiceWithEtag = WithEtag<ServiceDetailResponse>

async function fetchService(id: number): Promise<ServiceWithEtag> {
  const { data, headers } = await getService({ path: { id }, throwOnError: true })
  if (!data) {
    throw new Error('Respuesta vacía del backend en GET /services/{id}')
  }
  return { ...data, _etag: readEtag(headers) }
}

/**
 * Detalle de un servicio por id.
 *
 * Hereda el `staleTime` global (30 s), que no se sube: el estado de un viaje
 * cambia durante el día y esta pantalla no puede servir una foto vieja al volver
 * a ella.
 *
 * No reintenta ante 404 para que "no encontrado" aparezca de inmediato en vez de
 * después de un reintento que va a fallar igual.
 */
export function useService(id: number) {
  return useQuery({
    queryKey: operationsKeys.serviceDetail(id),
    queryFn: () => fetchService(id),
    enabled: Number.isInteger(id) && id > 0,
    retry: (failureCount, error) => !isNotFoundError(error) && failureCount < 1,
  })
}
