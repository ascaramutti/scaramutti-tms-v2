import { isAxiosError } from 'axios'
import type { Problem } from '../../api'

/**
 * Extrae un mensaje legible de un error de API. Prioriza `Problem.detail`
 * (RFC 7807) del backend sobre copy genérico del frontend (regla del proyecto:
 * mostrar el detail del backend, no inventar mensajes).
 *
 * - AxiosError con body `Problem.detail` → ese detail.
 * - Cualquier otro caso (red caída, 5xx sin body, error no-axios) → `fallback`.
 *
 * Pensado para estados de error inline en listados (no para forms — esos usan
 * `handleApiFormError`, que asigna errores por campo + toast).
 */
export function getApiErrorMessage(error: unknown, fallback: string): string {
  if (isAxiosError(error)) {
    const problem = error.response?.data as Problem | undefined
    if (problem?.detail) {
      return problem.detail
    }
  }
  return fallback
}

/** `true` si el error es un 404 (recurso inexistente). Para distinguir el estado
 * "no encontrado" de un fallo genérico en pantallas de detalle. */
export function isNotFoundError(error: unknown): boolean {
  return isAxiosError(error) && error.response?.status === 404
}

/** `true` si el error es un 412 (precondition failed). Para distinguir el conflicto de
 * optimistic locking ("otro usuario editó primero") y ofrecer recargar antes de reintentar. */
export function isPreconditionFailedError(error: unknown): boolean {
  return isAxiosError(error) && error.response?.status === 412
}
