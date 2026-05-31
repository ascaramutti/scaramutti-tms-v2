import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { listClients, type PageOfClient } from '../../../api'
import { clientKeys } from '../queryKeys'

/** Mínimo de caracteres para que la búsqueda de clientes golpee el backend. */
export const CLIENT_SEARCH_MIN_LENGTH = 3
/** Tamaño de página del combobox: suficiente para elegir, no es un listado. */
const CLIENT_SEARCH_PAGE_SIZE = 10

async function fetchClients(query: string): Promise<PageOfClient> {
  const { data } = await listClients({
    query: { q: query, size: CLIENT_SEARCH_PAGE_SIZE },
    throwOnError: true,
  })
  if (!data) {
    throw new Error('Respuesta vacía del backend en GET /clients')
  }
  return data
}

/**
 * Búsqueda de clientes para el combobox. Solo dispara con >= 3 chars (regla del
 * proyecto / contrato). `keepPreviousData` evita parpadeo entre tecleos.
 */
export function useClientsSearch(query: string) {
  const trimmed = query.trim()
  return useQuery({
    queryKey: clientKeys.search(trimmed),
    queryFn: () => fetchClients(trimmed),
    enabled: trimmed.length >= CLIENT_SEARCH_MIN_LENGTH,
    placeholderData: keepPreviousData,
  })
}
