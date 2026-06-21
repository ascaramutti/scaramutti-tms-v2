import { useQuery } from '@tanstack/react-query'
import { listQuotationConditions, type ConditionResponse } from '../../../api'
import { catalogKeys } from '../queryKeys'

async function fetchConditions(): Promise<ConditionResponse[]> {
  // RN-07: el wizard solo ofrece las condiciones ACTIVAS (las inactivas no son elegibles).
  const { data } = await listQuotationConditions({ query: { isActive: true }, throwOnError: true })
  if (!data) {
    throw new Error('Respuesta vacía del backend en GET /quotation-conditions')
  }
  return data
}

/** Condiciones generales ACTIVAS del catálogo (casi inmutable → `staleTime: Infinity`).
 * El wizard las ofrece como checkboxes; vienen ordenadas por displayOrder (RN-04). */
export function useQuotationConditions() {
  return useQuery({
    queryKey: catalogKeys.conditions(),
    queryFn: fetchConditions,
    staleTime: Infinity,
  })
}
