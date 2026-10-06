import { useQuery } from '@tanstack/react-query'
import { getClient, type ClientResponse } from '../../../api'
import { clientKeys } from '../queryKeys'
import { isNotFoundError } from '../../../shared/utils/getApiErrorMessage'

/**
 * Un cliente por id, para el formulario de edición.
 *
 * No sube el tiempo de frescura heredado: dos gerentes pueden corregir el mismo
 * cliente el mismo día y no hay control de edición simultánea, así que gana la
 * última escritura. Lo único que protege al segundo es abrir sobre datos
 * frescos y no sobre una foto vieja de la caché.
 *
 * Y no reintenta el "no existe": un 404 es definitivo, y reintentarlo solo
 * retrasa el aviso de que el cliente ya no está.
 */
export function useClient(id: number) {
  return useQuery<ClientResponse>({
    queryKey: clientKeys.detail(id),
    queryFn: async () => {
      const { data } = await getClient({ path: { id }, throwOnError: true })
      if (!data) {
        throw new Error('Respuesta vacía del backend en GET /clients/{id}')
      }
      return data
    },
    enabled: Number.isInteger(id) && id > 0,
    retry: (intentos, error) => !isNotFoundError(error) && intentos < 1,
  })
}
