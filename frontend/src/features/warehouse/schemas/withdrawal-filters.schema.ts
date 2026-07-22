import { z } from 'zod'

/**
 * Filtros del listado de retiros. Como en Existencias y Entradas, no es un form
 * clásico: tipa y normaliza el estado local de la barra de filtros.
 *
 * A diferencia de las entradas, el listado de retiros NO tiene búsqueda libre `q`:
 * el contrato filtra por campos estructurados (producto, trabajador, unidad de
 * flota, estado y rango de fechas de `withdrawnAt`). La unidad de flota se guarda
 * como el trío disyunto del contrato (a lo sumo uno seteado), poblado según el
 * `kind` de la unidad elegida en el combobox.
 */
export const withdrawalFiltersSchema = z.object({
  status: z.enum(['ACTIVE', 'CANCELLED']).optional(),
  productId: z.number().int().positive().optional(),
  receivedByWorkerId: z.number().int().positive().optional(),
  tractorId: z.number().int().positive().optional(),
  trailerId: z.number().int().positive().optional(),
  escortVehicleId: z.number().int().positive().optional(),
  /** `withdrawnAt` desde/hasta, inclusive, en formato `YYYY-MM-DD`. */
  dateFrom: z.string().optional(),
  dateTo: z.string().optional(),
})

export type WithdrawalFilters = z.infer<typeof withdrawalFiltersSchema>

/** Estado inicial: sin filtros (todos los retiros, activos y anulados). */
export const EMPTY_WITHDRAWAL_FILTERS: WithdrawalFilters = {}
