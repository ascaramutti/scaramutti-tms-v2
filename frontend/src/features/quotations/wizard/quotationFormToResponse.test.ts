import { describe, expect, it } from 'vitest'
import { CHILD_DEFAULTS, ITEM_DEFAULTS, type WizardFormInput } from './quotation-wizard.schema'
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

  it('asigna displayLabel jerárquico: root por posición, hijos del Integral con letra', () => {
    const items: WizardFormInput['items'] = [
      {
        ...ITEM_DEFAULTS,
        serviceTypeId: 1,
        serviceKind: 'INTEGRAL',
        unitPrice: 1500,
        components: [
          { ...CHILD_DEFAULTS, serviceTypeId: 1, serviceKind: 'SERVICIO', cargoTypeId: 4, weightKg: 1000 },
          { ...CHILD_DEFAULTS, serviceTypeId: 1, serviceKind: 'COMPLEMENTARIO' },
        ],
      },
      rootItem({ unitPrice: 300 }),
    ]
    const mapped = quotationFormItemsToResponse(items, SERVICE_TYPES, 18)

    expect(mapped[0].displayLabel).toBe('1') // Integral
    expect(mapped[0].children?.[0].displayLabel).toBe('1.a')
    expect(mapped[0].children?.[1].displayLabel).toBe('1.b')
    expect(mapped[1].displayLabel).toBe('2') // root posterior (su itemNumber plano es 4, su label "2")
  })

  it('reordena el Integral al frente para el displayLabel aunque no sea el primero en el form', () => {
    const items: WizardFormInput['items'] = [
      rootItem({ unitPrice: 300 }), // root no-Integral primero en el form
      {
        ...ITEM_DEFAULTS,
        serviceTypeId: 1,
        serviceKind: 'INTEGRAL',
        unitPrice: 1500,
        components: [
          { ...CHILD_DEFAULTS, serviceTypeId: 1, serviceKind: 'SERVICIO', cargoTypeId: 4, weightKg: 1000 },
          { ...CHILD_DEFAULTS, serviceTypeId: 1, serviceKind: 'COMPLEMENTARIO' },
        ],
      },
    ]
    const mapped = quotationFormItemsToResponse(items, SERVICE_TYPES, 18)

    // El Integral (con hijos) se reordena al frente: "1" + hijos "1.a","1.b"; el root no-Integral
    // pasa a "2". Coincide con el backend, que pone el Integral primero (itemNumber=1).
    expect(mapped[0].children).toHaveLength(2)
    expect(mapped[0].displayLabel).toBe('1')
    expect(mapped[0].children?.[0].displayLabel).toBe('1.a')
    expect(mapped[0].children?.[1].displayLabel).toBe('1.b')
    expect(mapped[1].displayLabel).toBe('2')
  })
})
