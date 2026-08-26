import { useQuery } from '@tanstack/react-query'
import { listDrivers, type DriverResponse } from '../../../api'
import { operationsKeys } from '../queryKeys'

/** El padrón de conductores cambia poco: una carga sirve para varias aperturas. */
const DRIVERS_STALE_TIME_MS = 5 * 60_000

/**
 * Conductores activos, para el selector de la asignación y el de los refuerzos.
 *
 * El contrato de `/drivers` no acepta `q` ni pagina, así que se trae el padrón entero
 * y el combobox filtra en cliente, igual que la flota.
 *
 * `isActive: true` no se parametriza: un conductor dado de baja no se asigna a un
 * viaje. Es la misma decisión que toma el hook de la flota, por el mismo motivo.
 */
export function useDrivers() {
  return useQuery({
    queryKey: operationsKeys.drivers(),
    queryFn: async (): Promise<DriverResponse[]> => {
      const { data } = await listDrivers({ query: { isActive: true }, throwOnError: true })
      return data ?? []
    },
    staleTime: DRIVERS_STALE_TIME_MS,
  })
}
