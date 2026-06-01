import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { listCargoTypes, type PageOfCargoType } from '../../../api'
import { cargoTypeKeys } from '../queryKeys'

/** Mínimo de caracteres para que la búsqueda de tipos de carga golpee el backend. */
export const CARGO_TYPE_SEARCH_MIN_LENGTH = 3
/** Tamaño de página del combobox: suficiente para elegir, no es un listado. */
const CARGO_TYPE_SEARCH_PAGE_SIZE = 10

async function fetchCargoTypes(query: string): Promise<PageOfCargoType> {
  const { data } = await listCargoTypes({
    query: { q: query, size: CARGO_TYPE_SEARCH_PAGE_SIZE },
    throwOnError: true,
  })
  if (!data) {
    throw new Error('Respuesta vacía del backend en GET /cargo-types')
  }
  return data
}

/**
 * Búsqueda de tipos de carga para el combobox. Solo dispara con >= 3 chars (regla
 * del proyecto / contrato). `keepPreviousData` evita parpadeo entre tecleos.
 */
export function useCargoTypesSearch(query: string) {
  const trimmed = query.trim()
  return useQuery({
    queryKey: cargoTypeKeys.search(trimmed),
    queryFn: () => fetchCargoTypes(trimmed),
    enabled: trimmed.length >= CARGO_TYPE_SEARCH_MIN_LENGTH,
    placeholderData: keepPreviousData,
  })
}
