import { useQuery } from '@tanstack/react-query'
import { getQuotationConfig, type QuotationConfigResponse } from '../../../api'
import { quotationKeys } from '../queryKeys'

async function fetchConfig(): Promise<QuotationConfigResponse> {
  const { data } = await getQuotationConfig({ throwOnError: true })
  if (!data) {
    throw new Error('Respuesta vacía del backend en GET /quotations/config')
  }
  return data
}

/** Config pública del módulo (IGV, máx ítems, validez por defecto). Cacheable
 * agresivamente (`staleTime: Infinity`) — el contrato lo recomienda. */
export function useQuotationConfig() {
  return useQuery({
    queryKey: quotationKeys.config(),
    queryFn: fetchConfig,
    staleTime: Infinity,
  })
}
