import type { FleetResourceStatus } from '../../../api'

/**
 * La disponibilidad de un CONDUCTOR, en es-PE.
 *
 * `MAINTENANCE` y `NOT_AVAILABLE` se leen igual a propósito: el enum sale de la
 * flota, donde una unidad sí entra en mantenimiento, y para una persona las dos
 * significan lo mismo, que hoy no está para salir. Distinguirlas con palabras
 * inventadas diría más de lo que el dato sabe.
 *
 * No hay un mapa gemelo para las unidades porque ninguna pantalla muestra hoy su
 * disponibilidad: el campo de flota rotula con marca y modelo. Cuando la muestre, va
 * a necesitar el suyo, y por eso este está acotado a conductores en el nombre.
 */
export const DRIVER_STATUS_LABELS: Record<FleetResourceStatus, string> = {
  AVAILABLE: 'Disponible',
  MAINTENANCE: 'No disponible',
  NOT_AVAILABLE: 'No disponible',
}

/** Cómo se nombra cada recurso de un viaje, para los mensajes de conflicto. */
export const SERVICE_RESOURCE_LABELS = {
  DRIVER: 'Conductor',
  TRACTOR: 'Tracto',
  TRAILER: 'Carreta',
} as const
