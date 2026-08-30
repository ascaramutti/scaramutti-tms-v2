import { useQuery } from '@tanstack/react-query'
import { listCurrencies, type CurrencyResponse } from '../../../api'
import { operationsKeys } from '../queryKeys'

async function fetchAllCurrencies(): Promise<CurrencyResponse[]> {
  const { data } = await listCurrencies({ throwOnError: true })
  // Se pregunta por la FORMA y no por la ausencia, y la diferencia está medida: ante un
  // 200 sin cuerpo el cliente generado no entrega `null` sino `{}`, así que un `if
  // (!data)` no dispara y el objeto vacío sigue viaje como si fuera la lista. Rompe
  // recién donde alguien la recorra, lejos de acá y sin nombrar la causa.
  if (!Array.isArray(data)) {
    throw new Error('Respuesta vacía del backend en GET /currencies')
  }
  return data
}

/**
 * El catálogo COMPLETO de monedas, activas y dadas de baja, para editar un viaje.
 *
 * El filtro que este hook NO aplica es su única razón de existir, así que conviene
 * decirlo antes de que alguien lo unifique con `useCurrencies`, que pide solo activas:
 * al editar, el servidor exige que la moneda esté activa SOLO si se la cambia, porque
 * retirar una moneda cierra su uso hacia adelante y no congela lo ya emitido. Con el
 * catálogo filtrado, un viaje cuya moneda se dio de baja después abriría el selector
 * vacío y su dueño quedaría obligado a CAMBIARLE la moneda para poder guardar cualquier
 * otra corrección, que es justamente lo que esa regla del contrato quiso evitar.
 *
 * Vive en operaciones y no en `catalogs/` por la misma razón: es esta pantalla la que
 * necesita ver lo dado de baja. El alta sigue ofreciendo solo lo vigente, que es lo
 * correcto para un viaje que todavía no existe.
 */
export function useServiceCurrencies() {
  return useQuery({
    queryKey: operationsKeys.serviceCurrencies(),
    queryFn: fetchAllCurrencies,
    staleTime: Infinity,
  })
}
