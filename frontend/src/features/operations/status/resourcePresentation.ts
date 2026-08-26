import type { FleetResourceStatus, ServiceAdditionalResourceResponse } from '../../../api'

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

/**
 * Resumen de una fila de refuerzo: los recursos que trae, en una línea.
 *
 * Las dos PLACAS van rotuladas porque seguidas y sin rótulo no dicen cuál es el
 * tracto y cuál la carreta, y una sola se lee como tracto aunque sea la carreta. El
 * conductor no lo necesita: un nombre no se confunde con una placa.
 */
export function describeAdditionalResource(
  resource: ServiceAdditionalResourceResponse,
): string {
  return (
    [
      resource.driver?.fullName,
      resource.tractor && `Tracto ${resource.tractor.plate}`,
      resource.trailer && `Carreta ${resource.trailer.plate}`,
    ]
      .filter((value): value is string => !!value)
      .join(' · ') || '—'
  )
}

/** Cómo se nombra cada recurso de un viaje, para los mensajes de conflicto. */
export const SERVICE_RESOURCE_LABELS = {
  DRIVER: 'Conductor',
  TRACTOR: 'Tracto',
  TRAILER: 'Carreta',
} as const
