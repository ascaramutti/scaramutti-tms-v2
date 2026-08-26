import { isAxiosError } from 'axios'
import type { ServiceResourceConflictProblem } from '../../../api'

/** Un recurso que otro viaje ya tiene tomado, tal como lo publica el `Problem`. */
export type ServiceResourceConflict = NonNullable<
  ServiceResourceConflictProblem['conflicts']
>[number]

export interface ServiceOperationError {
  /** Código del catálogo de errores (`OPS-002`, `OPS-003`, …). */
  code: string | null | undefined
  /** Mensaje del backend, que es el que se muestra. */
  detail: string | null | undefined
  /** `true` solo cuando el conflicto se puede forzar. */
  forcible: boolean
  /** Qué recurso choca y en qué viaje. Vacío si el error no los trae. */
  conflicts: ServiceResourceConflict[]
}

/**
 * Lee un error de los endpoints que operan el viaje.
 *
 * `forcible` y `conflicts` son miembros de EXTENSIÓN de RFC 7807: viajan aplanados
 * junto a `code`, no anidados bajo otra clave.
 *
 * Se exige el código Y la bandera para dar por forzable: el día que `forcible`
 * aparezca en otro código, la pantalla no va a ofrecer un botón que el servidor
 * rechaza. La precedencia entre el conflicto duro y el forzable NO se reimplementa
 * acá; la resuelve el servidor y la pantalla solo mira qué código llegó.
 */
export function getServiceOperationError(error: unknown): ServiceOperationError | null {
  if (!isAxiosError(error)) return null
  const problem = error.response?.data as ServiceResourceConflictProblem | undefined
  if (!problem) return null
  return {
    code: problem.code,
    detail: problem.detail,
    forcible: problem.code === 'OPS-002' && problem.forcible === true,
    conflicts: problem.conflicts ?? [],
  }
}
