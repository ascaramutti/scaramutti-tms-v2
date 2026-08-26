import type { QueryClient } from '@tanstack/react-query'
import type { ServiceDetailResponse } from '../../../api'
import { readEtag } from '../../../shared/utils/etag'
import { operationsKeys } from '../queryKeys'

/**
 * Deja en el cache el detalle que devolvió una escritura, con el ETag de SU respuesta.
 *
 * Los endpoints que operan el viaje devuelven el detalle completo, así que volver a
 * pedirlo sería una consulta de más y una ventana en la que la pantalla muestra lo
 * viejo.
 *
 * Sin el header no se escribe: `useService` guarda el detalle CON su ETag, y meter un
 * cuerpo sin él degradaría lo que ya había (el GET sí lo traía) y dejaría al próximo
 * `If-Match` mandando vacío, que vuelve como un 412 que nadie puede explicar. Pasa de
 * verdad si el gateway deja de exponer el header. Ahí se invalida y se paga la
 * consulta, que es lo barato.
 */
export function writeServiceDetail(
  queryClient: QueryClient,
  serviceId: number,
  data: ServiceDetailResponse,
  headers: unknown,
): void {
  const etag = readEtag(headers)
  if (etag === null) {
    void queryClient.invalidateQueries({ queryKey: operationsKeys.serviceDetail(serviceId) })
    return
  }
  queryClient.setQueryData(operationsKeys.serviceDetail(serviceId), { ...data, _etag: etag })
}
