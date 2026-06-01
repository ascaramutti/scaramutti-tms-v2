import { useQuery } from '@tanstack/react-query'
import { listQuotationServiceTypes, type QuotationServiceTypeResponse } from '../../../api'
import { catalogKeys } from '../queryKeys'

async function fetchServiceTypes(): Promise<QuotationServiceTypeResponse[]> {
  const { data } = await listQuotationServiceTypes({ query: { isActive: true }, throwOnError: true })
  if (!data) {
    throw new Error('Respuesta vacía del backend en GET /quotation-service-types')
  }
  return data
}

/** Tipos de servicio de cotización (catálogo casi inmutable → `staleTime: Infinity`).
 * El consumidor filtra por `kind` según el tipo de cotización. */
export function useQuotationServiceTypes() {
  return useQuery({
    queryKey: catalogKeys.serviceTypes(),
    queryFn: fetchServiceTypes,
    staleTime: Infinity,
  })
}
