import { describe, expect, it } from 'vitest'
import { ITEM_DEFAULTS, type WizardFormInput } from './quotation-wizard.schema'
import { quotationFormItemsToResponse, quotationTotals } from './quotationFormToResponse'
import type { QuotationServiceTypeResponse } from '../../../api'

const SERVICE_TYPES: QuotationServiceTypeResponse[] = [
  { id: 1, code: 'FLU', name: 'Flete urbano', kind: 'SERVICIO', isActive: true },
]

/** Ítem root del form a partir de overrides sobre el default. */
function rootItem(overrides: Partial<WizardFormInput['items'][number]>): WizardFormInput['items'][number] {
  return { ...ITEM_DEFAULTS, serviceTypeId: 1, ...overrides }
}

describe('quotationFormItemsToResponse + quotationTotals', () => {
  it('calcula subtotal, IGV y total de un ítem root', () => {
    const mapped = quotationFormItemsToResponse([rootItem({ unitPrice: 1000, quantity: 2 })], SERVICE_TYPES, 18)
    expect(mapped[0].subtotal).toBe(2000)
    expect(quotationTotals(mapped, 18)).toEqual({ subtotal: 2000, igv: 360, total: 2360 })
  })

  it('degrada el subtotal a 0 (nunca NaN) si un ítem root quedó sin cantidad', () => {
    // El wizard es no-bloqueante: la cantidad puede vaciarse en el Step 2 (queda `undefined`
    // en runtime, aunque el tipo diga `number`) y saltar al Resumen. El subtotal debe degradar
    // a 0 vía `itemSubtotal`/`num()`, no propagar NaN al total ni a la fila.
    const itemSinCantidad = rootItem({ unitPrice: 1000, quantity: undefined as unknown as number })
    const mapped = quotationFormItemsToResponse([itemSinCantidad], SERVICE_TYPES, 18)
    const totals = quotationTotals(mapped, 18)

    expect(mapped[0].subtotal).toBe(0)
    expect(Number.isNaN(totals.subtotal)).toBe(false)
    expect(Number.isNaN(totals.total)).toBe(false)
    expect(totals).toEqual({ subtotal: 0, igv: 0, total: 0 })
  })
})
