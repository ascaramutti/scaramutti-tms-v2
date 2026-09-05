import type { BadgeVariant } from '../../../shared/ui/Badge'
import type { ServiceEventType } from '../../../api'

interface ServiceEventPresentation {
  /** Etiqueta es-PE del tipo de entrada. */
  label: string
  /** Variante del `Badge`. El color acompaña a la etiqueta, nunca la reemplaza. */
  badgeVariant: BadgeVariant
}

/**
 * Cómo se muestra cada tipo de entrada de la bitácora.
 *
 * El tipo existe para que la pantalla etiquete la entrada SIN interpretar su
 * texto: el contrato lo dice así, y es la razón por la que el badge no se deduce
 * de lo que la nota diga.
 *
 * Tipado como `Record<ServiceEventType, ...>` para que TS exija cubrir los cinco:
 * si el contrato suma un tipo, rompe la compilación acá y en ningún otro lado.
 */
export const SERVICE_EVENT_PRESENTATION: Record<ServiceEventType, ServiceEventPresentation> = {
  CREATED: { label: 'Registro', badgeVariant: 'default' },
  ASSIGNMENT: { label: 'Recursos', badgeVariant: 'info' },
  STATUS_CHANGE: { label: 'Estado', badgeVariant: 'transition' },
  FIELD_EDIT: { label: 'Edición', badgeVariant: 'warning' },
  // Es el tipo con el que llega TODO lo heredado del sistema anterior, así que
  // hoy es el más frecuente de lejos y lo seguirá siendo hasta que los viajes
  // nuevos superen a los migrados.
  NOTE: { label: 'Nota', badgeVariant: 'default' },
}
