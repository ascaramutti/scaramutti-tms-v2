import type { ItemInput } from './quotation-wizard.schema'

type Priceable = Pick<ItemInput, 'unitPrice' | 'quantity'>

function num(value: number | null | undefined): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0
}

/** Subtotal (neto) del ítem = precio unitario × cantidad. */
export function itemSubtotal(item: Priceable): number {
  return num(item.unitPrice) * num(item.quantity)
}

/** Subtotal (neto) de todos los ítems. */
export function itemsSubtotal(items: Priceable[]): number {
  return items.reduce((acc, item) => acc + itemSubtotal(item), 0)
}

/** Total del ítem con IGV, según el porcentaje del config. */
export function itemTotal(item: Priceable, igvPercentage: number): number {
  const subtotal = itemSubtotal(item)
  return subtotal + subtotal * (num(igvPercentage) / 100)
}

/** Total de la cotización: suma de los totales con IGV de todos los ítems root. */
export function itemsGrandTotal(items: Priceable[], igvPercentage: number): number {
  return items.reduce((acc, item) => acc + itemTotal(item, igvPercentage), 0)
}

/** Subtotal de referencia de un componente del Integral = precio de referencia × cantidad.
 * Es informativo (desglose interno del paquete): NO se cobra al cliente ni suma al total. */
export function componentReferenceSubtotal(component: {
  internalReferencePrice?: number | null
  quantity?: number | null
}): number {
  return num(component.internalReferencePrice) * num(component.quantity)
}
