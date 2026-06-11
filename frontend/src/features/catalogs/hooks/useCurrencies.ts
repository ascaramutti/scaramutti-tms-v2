import { useQuery } from '@tanstack/react-query'
import { listCurrencies, type CurrencyResponse } from '../../../api'
import { catalogKeys } from '../queryKeys'

async function fetchCurrencies(): Promise<CurrencyResponse[]> {
  const { data } = await listCurrencies({ query: { isActive: true }, throwOnError: true })
  if (!data) {
    throw new Error('Respuesta vacía del backend en GET /currencies')
  }
  return data
}

/** Monedas activas. Catálogo casi inmutable → `staleTime: Infinity`. */
export function useCurrencies() {
  return useQuery({
    queryKey: catalogKeys.currencies(),
    queryFn: fetchCurrencies,
    staleTime: Infinity,
  })
}
