import type { BadgeVariant } from '../../../shared/ui/Badge'
import type { QuotationStatus, UserRole } from '../../../api'

/**
 * Destino de una transición que el usuario puede disparar. Espeja los destinos del
 * contrato (`PATCH /quotations/{id}/status` → `SENT | ACCEPTED | REJECTED`): nunca
 * `DRAFT` (inicial) ni `EXPIRED` (lo pone solo el job).
 */
export type QuotationStatusTarget = Extract<QuotationStatus, 'SENT' | 'ACCEPTED' | 'REJECTED'>

/** Variante visual del botón de una acción de transición. */
export type StatusActionVariant = 'primary' | 'success' | 'danger'

export interface QuotationStatusAction {
  /** Estado al que transiciona la cotización. */
  target: QuotationStatusTarget
  /** Etiqueta es-PE del botón. */
  label: string
  /** Variante visual (color) del botón. */
  variant: StatusActionVariant
  /** `true` si la transición exige un motivo (solo el rechazo). */
  requiresReason: boolean
}

interface QuotationStatusPresentation {
  /** Etiqueta es-PE del estado (única fuente; `QUOTATION_STATUS_LABELS` la deriva). */
  label: string
  /** Variante del `Badge` para este estado. El color nunca es el único portador de
   * significado: el badge siempre lleva además el `label`. */
  badgeVariant: BadgeVariant
  /** Transiciones que el usuario puede disparar desde este estado. Vacío en los
   * terminales (ACCEPTED/REJECTED/EXPIRED → inmutables, sin botones). */
  actions: readonly QuotationStatusAction[]
}

/**
 * Fuente de verdad única del estado de una cotización: etiqueta, color del badge y
 * acciones de transición disponibles. Eje único `status`:
 * `DRAFT → SENT → {ACCEPTED | REJECTED | EXPIRED}`.
 *
 * - Solo `SENT` ofrece dos caminos (Aceptar / Rechazar); el rechazo exige motivo.
 * - `EXPIRED` lo pone únicamente el job, nunca el usuario → sin acción que lo apunte.
 * - Terminales (ACCEPTED/REJECTED/EXPIRED) → `actions: []` (inmutables).
 *
 * Tipado como `Record<QuotationStatus, ...>` para que TS exija cubrir los 5 estados
 * (chequeo de exhaustividad: agregar un estado al contrato rompe la compilación acá).
 * La paleta de badges son tokens de partida (afinables): el significado va en el label.
 */
export const QUOTATION_STATUS_PRESENTATION: Record<QuotationStatus, QuotationStatusPresentation> = {
  DRAFT: {
    label: 'Borrador',
    badgeVariant: 'slate',
    actions: [{ target: 'SENT', label: 'Enviada', variant: 'primary', requiresReason: false }],
  },
  SENT: {
    label: 'Enviada',
    badgeVariant: 'info',
    actions: [
      { target: 'ACCEPTED', label: 'Aceptada', variant: 'success', requiresReason: false },
      { target: 'REJECTED', label: 'Rechazada', variant: 'danger', requiresReason: true },
    ],
  },
  ACCEPTED: {
    label: 'Aceptada',
    badgeVariant: 'teal',
    actions: [],
  },
  REJECTED: {
    label: 'Rechazada',
    badgeVariant: 'danger',
    actions: [],
  },
  EXPIRED: {
    label: 'Vencida',
    badgeVariant: 'warning',
    actions: [],
  },
}

/**
 * Roles autorizados a cambiar el estado de una cotización (espeja el contrato del
 * `PATCH /quotations/{id}/status`): admin, ventas y las dos gerencias. `dispatcher`
 * queda fuera (puede ver, no transicionar). Se define como `Set` para chequeo O(1).
 */
const STATUS_CHANGE_ROLES: ReadonlySet<UserRole> = new Set<UserRole>([
  'admin',
  'sales',
  'general_manager',
  'operations_manager',
])

/**
 * `true` si el rol puede cambiar el estado de una cotización. Se usa para ocultar los
 * botones de transición (defensa de UX; el backend es la autoridad real con 403). Un
 * rol ausente/desconocido (`undefined`) no puede.
 */
export function canChangeQuotationStatus(role: UserRole | undefined): boolean {
  return role != null && STATUS_CHANGE_ROLES.has(role)
}
