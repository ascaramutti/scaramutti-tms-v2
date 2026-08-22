import type { BadgeVariant } from '../../../shared/ui/Badge'
import type { ServiceStatus, TripScope } from '../../../api'

interface ServiceStatusPresentation {
  /** Etiqueta es-PE del estado. */
  label: string
  /** Variante del `Badge`. El color nunca es el único portador de significado:
   * el badge siempre lleva además el `label`. */
  badgeVariant: BadgeVariant
}

/**
 * Fuente de verdad única de cómo se muestra el estado de un servicio: etiqueta y
 * color del badge.
 *
 * Deliberadamente NO incluye transiciones ni editabilidad. Las reglas de qué se
 * puede hacer desde cada estado viven en el servidor y son más finas de lo que
 * una tabla como ésta puede expresar (el despacho no cancela; volver atrás se
 * permite tanto desde un viaje cancelado como desde uno eliminado). Escribirlas
 * acá para una pantalla de solo lectura sería escribir mal una regla que nadie
 * vuelve a leer.
 *
 * Tipado como `Record<ServiceStatus, ...>` para que TS exija cubrir los 6 estados:
 * agregar uno al contrato rompe la compilación acá y en ningún lado más.
 */
export const SERVICE_STATUS_PRESENTATION: Record<ServiceStatus, ServiceStatusPresentation> = {
  PENDING_ASSIGNMENT: { label: 'Pendiente de asignación', badgeVariant: 'slate' },
  PENDING_START: { label: 'Pendiente de inicio', badgeVariant: 'info' },
  IN_PROGRESS: { label: 'En ruta', badgeVariant: 'teal' },
  COMPLETED: { label: 'Completado', badgeVariant: 'success' },
  CANCELLED: { label: 'Cancelado', badgeVariant: 'danger' },
  DELETED: { label: 'Eliminado', badgeVariant: 'warning' },
}

/**
 * Los 6 estados en el orden del ciclo de vida (no alfabético): es el orden en que
 * los ofrece el filtro, y leerlo de arriba abajo cuenta la vida de un viaje.
 */
export const SERVICE_STATUS_VALUES = [
  'PENDING_ASSIGNMENT',
  'PENDING_START',
  'IN_PROGRESS',
  'COMPLETED',
  'CANCELLED',
  'DELETED',
] as const satisfies readonly ServiceStatus[]

/** Etiquetas es-PE del ámbito del viaje. */
export const TRIP_SCOPE_LABELS: Record<TripScope, string> = {
  LOCAL: 'Local',
  PROVINCIA: 'Provincia',
}
