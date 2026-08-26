import type { ListFleetUnitsData } from '../../api'

type FleetUnitsParams = NonNullable<ListFleetUnitsData['query']>

/**
 * Query keys de los catálogos COMPARTIDOS entre módulos (hoy, la flota de
 * `public.fleet_units`). Rama propia, fuera de la de cada módulo: hoy la pide
 * almacén y va a pedirla también operaciones para asignar recursos a un viaje, y
 * colgarla de uno de los dos haría que invalidar ese módulo tirara abajo el cache
 * del otro.
 *
 * La key lleva los parámetros de la consulta DENTRO, y no es un detalle: dos
 * pedidos con distinto `kind` traen listas distintas, así que compartir entrada
 * haría que el segundo consumidor leyera la lista del primero (un combobox que
 * pide solo tractos dejaría al que los pide todos viendo solo tractos, o al
 * revés). Con los params en la key, cada consulta tiene su propia entrada.
 */
export const sharedCatalogKeys = {
  all: ['shared-catalogs'] as const,
  fleetUnits: (params: FleetUnitsParams) =>
    [...sharedCatalogKeys.all, 'fleet-units', params] as const,
}
