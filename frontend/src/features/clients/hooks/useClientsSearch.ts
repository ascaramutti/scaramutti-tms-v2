import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { listClients, type PageOfClient } from '../../../api'
import { clientKeys, type ClientSearchParams } from '../queryKeys'

/** Mínimo de caracteres para que la búsqueda de clientes golpee el backend. */
export const CLIENT_SEARCH_MIN_LENGTH = 3
/**
 * Tope del término, espejo del máximo que el backend exige. Sin él, un término
 * más largo sale con un 400 que la pantalla muestra como un error pasajero, con
 * un botón de reintentar que no puede funcionar nunca.
 */
export const CLIENT_SEARCH_MAX_LENGTH = 200
/**
 * Filas que pide cada consumidor. Son TRES con intenciones distintas: los dos
 * combobox de alta al vuelo, donde diez alcanza para elegir, y el maestro de
 * clientes, donde son las filas visibles de un listado. Cambiarlo acá los mueve
 * a los tres, así que los tres lo fijan en su test.
 */
const CLIENT_SEARCH_PAGE_SIZE = 10

async function fetchClients(params: ClientSearchParams): Promise<PageOfClient> {
  const { data } = await listClients({
    query: {
      q: params.q,
      size: CLIENT_SEARCH_PAGE_SIZE,
      ...(params.isActive !== undefined && { isActive: params.isActive }),
    },
    throwOnError: true,
  })
  if (!data) {
    throw new Error('Respuesta vacía del backend en GET /clients')
  }
  return data
}

/**
 * Búsqueda de clientes. Solo dispara con >= 3 chars (regla del proyecto /
 * contrato). `keepPreviousData` evita parpadeo entre tecleos.
 *
 * `isActive` es opcional y NO tiene valor por omisión a propósito. Este hook lo
 * comparten tres pantallas: los dos combobox de alta al vuelo (asistente de
 * cotizaciones y alta de servicios) y el buscador del maestro de clientes. Solo
 * ese último filtra por activos; ponerlo acá adentro les cambiaría en silencio lo
 * que ven los otros dos, que hoy encuentran también a los desactivados.
 */
export function useClientsSearch(query: string, options?: { isActive?: boolean }) {
  const trimmed = query.trim()
  const params: ClientSearchParams = {
    q: trimmed,
    ...(options?.isActive !== undefined && { isActive: options.isActive }),
  }
  return useQuery({
    queryKey: clientKeys.search(params),
    queryFn: () => fetchClients(params),
    enabled: trimmed.length >= CLIENT_SEARCH_MIN_LENGTH,
    placeholderData: keepPreviousData,
  })
}
