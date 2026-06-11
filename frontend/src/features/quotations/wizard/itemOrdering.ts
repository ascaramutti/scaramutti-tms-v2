import type { WizardFormInput } from './quotation-wizard.schema'

/**
 * Reordena los ítems del form poniendo el Servicio Integral primero (debe ser el ítem 1 —
 * regla del backend). La UI ya lo fuerza a la posición 1, pero los mappers (request al
 * `POST /quotations` y Resumen) reordenan defensivamente para NO atar el formato/numeración a
 * esa invariante de UI: así un futuro reordenamiento o flujo de edición no produce un payload
 * mal aplanado ni `displayLabel` inconsistentes entre el Resumen (preview) y el Detalle (del
 * backend, que pone el Integral primero vía `itemNumber=1`). Solo hay un Integral por cotización.
 */
export function orderIntegralFirst(items: WizardFormInput['items']): WizardFormInput['items'] {
  const integral = items.find((item) => item.serviceKind === 'INTEGRAL')
  return integral ? [integral, ...items.filter((item) => item !== integral)] : items
}
