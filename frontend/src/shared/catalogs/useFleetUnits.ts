import { useQuery } from '@tanstack/react-query'
import { listFleetUnits, type FleetUnitKind, type FleetUnitResponse } from '../../api'
import { sharedCatalogKeys } from './queryKeys'

/** La flota cambia poco: una carga sirve para varias aperturas del combobox. */
const FLEET_UNITS_STALE_TIME_MS = 5 * 60_000

interface UseFleetUnitsOptions {
  /**
   * Subtipo a traer. Omitido, vienen los tres (tractos, carretas y escoltas),
   * que es lo que pide quien carga un retiro a cualquier unidad. Existe para el
   * caso contrario: un formulario que tiene un campo para el tracto y otro para la
   * carreta pide uno por vez, y ofrecerle los tres lo dejaría elegir algo que el
   * backend rechaza.
   */
  kind?: FleetUnitKind
}

/**
 * Carga las unidades de flota activas. El contrato de `/fleet-units` NO acepta
 * `q`: la flota es chica y sin paginar, así que se trae entera y el combobox
 * filtra en cliente.
 *
 * `isActive: true` no se parametriza porque una unidad dada de baja no se puede
 * ASIGNAR: ni a un retiro de almacén ni a un viaje de operaciones. Queda un caso
 * conocido en
 * contra: el filtro del listado de retiros usa el mismo campo para BUSCAR, y ahí
 * una unidad de baja es legítima (un retiro viejo cargado a un tracto que después
 * se dio de baja hoy no se puede volver a filtrar). Se deja así por ahora; el día
 * que se decida, el filtro entra como una opción más de este hook y la key ya lo
 * distingue sola, aunque haya que propagarlo desde el campo.
 *
 * La misma consulta arma la key, así que las dos no pueden quedar desalineadas:
 * pedir un `kind` distinto es, por construcción, otra entrada de cache.
 */
export function useFleetUnits({ kind }: UseFleetUnitsOptions = {}) {
  const query = { kind, isActive: true }
  return useQuery({
    queryKey: sharedCatalogKeys.fleetUnits(query),
    queryFn: async (): Promise<FleetUnitResponse[]> => {
      const { data } = await listFleetUnits({ query, throwOnError: true })
      return data ?? []
    },
    staleTime: FLEET_UNITS_STALE_TIME_MS,
  })
}
