import { describe, expect, it } from 'vitest'
import {
  CHILD_DEFAULTS,
  ITEM_DEFAULTS,
  WIZARD_DEFAULTS,
  type WizardFormInput,
} from './quotation-wizard.schema'
import { quotationFormToRequest } from './quotationFormToRequest'

/** Form válido base (cabecera completa); se sobreescribe lo necesario por test. */
function formWith(overrides: Partial<WizardFormInput>): WizardFormInput {
  return {
    ...WIZARD_DEFAULTS,
    quotationType: 'TRANSPORTE',
    clientId: 7,
    contactName: 'Juan Pérez',
    currencyId: 2,
    validityDays: 15,
    ...overrides,
  }
}

describe('quotationFormToRequest', () => {
  it('mapea la cabecera y normaliza opcionales vacíos a null', () => {
    const request = quotationFormToRequest(
      formWith({
        contactName: '  Juan Pérez  ',
        contactPhone: '',
        origin: '',
        destination: '',
        tentativeServiceDate: '',
        paymentTermId: null,
        items: [{ ...ITEM_DEFAULTS, serviceTypeId: 3, unitPrice: 500 }],
      }),
    )
    expect(request).toMatchObject({
      quotationType: 'TRANSPORTE',
      clientId: 7,
      contactName: 'Juan Pérez',
      contactPhone: null,
      currencyId: 2,
      paymentTermId: null,
      tentativeServiceDate: null,
      validityDays: 15,
      origin: null,
      destination: null,
    })
  })

  it('mapea un ítem root facturable con itemNumber 1 y sin parentItemNumber', () => {
    const request = quotationFormToRequest(
      formWith({
        items: [
          {
            ...ITEM_DEFAULTS,
            serviceTypeId: 3,
            serviceKind: 'SERVICIO',
            cargoTypeId: 4,
            weightKg: 1200,
            quantity: 2,
            unitPrice: 500,
          },
        ],
      }),
    )
    expect(request.items).toHaveLength(1)
    expect(request.items[0]).toMatchObject({
      itemNumber: 1,
      serviceTypeId: 3,
      cargoTypeId: 4,
      weightKg: 1200,
      quantity: 2,
      unitPrice: 500,
    })
    expect(request.items[0].parentItemNumber).toBeUndefined()
  })

  it('aplana el Servicio Integral: padre itemNumber 1 + hijos contiguos con parentItemNumber 1 sin unitPrice', () => {
    const request = quotationFormToRequest(
      formWith({
        items: [
          {
            ...ITEM_DEFAULTS,
            serviceTypeId: 24,
            serviceKind: 'INTEGRAL',
            quantity: 1,
            unitPrice: 1500,
            components: [
              {
                ...CHILD_DEFAULTS,
                serviceTypeId: 3,
                serviceKind: 'SERVICIO',
                cargoTypeId: 4,
                weightKg: 1000,
                internalReferencePrice: 900,
              },
              { ...CHILD_DEFAULTS, serviceTypeId: 18, serviceKind: 'COMPLEMENTARIO', internalReferencePrice: 0 },
            ],
          },
        ],
      }),
    )
    // Padre + 2 hijos = 3 ítems en la lista plana, numerados 1, 2, 3.
    expect(request.items).toHaveLength(3)
    const [parent, child1, child2] = request.items
    expect(parent).toMatchObject({ itemNumber: 1, serviceTypeId: 24, unitPrice: 1500 })
    expect(parent.parentItemNumber).toBeUndefined()
    expect(child1).toMatchObject({
      itemNumber: 2,
      parentItemNumber: 1,
      serviceTypeId: 3,
      internalReferencePrice: 900,
    })
    expect(child1.unitPrice).toBeUndefined()
    expect(child2).toMatchObject({ itemNumber: 3, parentItemNumber: 1, serviceTypeId: 18 })
    // internalReferencePrice 0 ≡ sin precio referencial → null.
    expect(child2.internalReferencePrice).toBeNull()
  })

  it('numera TODOS los ítems contiguos (1..N) — el backend rechaza una mezcla de con/sin itemNumber', () => {
    const request = quotationFormToRequest(
      formWith({
        items: [
          {
            ...ITEM_DEFAULTS,
            serviceTypeId: 24,
            serviceKind: 'INTEGRAL',
            unitPrice: 1500,
            components: [
              { ...CHILD_DEFAULTS, serviceTypeId: 3, serviceKind: 'SERVICIO', cargoTypeId: 4, weightKg: 1000 },
              { ...CHILD_DEFAULTS, serviceTypeId: 18, serviceKind: 'COMPLEMENTARIO' },
            ],
          },
          { ...ITEM_DEFAULTS, serviceTypeId: 5, serviceKind: 'COMPLEMENTARIO', unitPrice: 300 },
        ],
      }),
    )
    // Todos presentes y contiguos 1..N (la regla del backend es "todos o ninguno").
    expect(request.items.map((item) => item.itemNumber)).toEqual([1, 2, 3, 4])
    expect(request.items.every((item) => item.itemNumber != null)).toBe(true)
  })

  it('reordena el Servicio Integral al frente (itemNumber 1) aunque no sea el primer ítem del form', () => {
    const request = quotationFormToRequest(
      formWith({
        items: [
          // Root facturable primero en el form...
          { ...ITEM_DEFAULTS, serviceTypeId: 5, serviceKind: 'COMPLEMENTARIO', unitPrice: 300 },
          // ...y el Integral después (el mapper debe ponerlo como ítem 1, con sus hijos detrás).
          {
            ...ITEM_DEFAULTS,
            serviceTypeId: 24,
            serviceKind: 'INTEGRAL',
            unitPrice: 1500,
            components: [
              { ...CHILD_DEFAULTS, serviceTypeId: 3, serviceKind: 'SERVICIO', cargoTypeId: 4, weightKg: 1000 },
              { ...CHILD_DEFAULTS, serviceTypeId: 18, serviceKind: 'COMPLEMENTARIO' },
            ],
          },
        ],
      }),
    )
    // Orden de salida: Integral (1) → sus 2 hijos (2, 3, parentItemNumber 1) → el root (4).
    expect(request.items).toHaveLength(4)
    expect(request.items[0]).toMatchObject({ itemNumber: 1, serviceTypeId: 24, unitPrice: 1500 })
    expect(request.items[1]).toMatchObject({ itemNumber: 2, parentItemNumber: 1, serviceTypeId: 3 })
    expect(request.items[2]).toMatchObject({ itemNumber: 3, parentItemNumber: 1, serviceTypeId: 18 })
    // El root facturable va al final (itemNumber 4), sin parentItemNumber.
    expect(request.items[3]).toMatchObject({ itemNumber: 4, serviceTypeId: 5, unitPrice: 300 })
    expect(request.items[3].parentItemNumber).toBeUndefined()
  })

  it('mantiene la contigüidad con más ítems: Integral (3 hijos) + 2 roots → 1..6', () => {
    const request = quotationFormToRequest(
      formWith({
        items: [
          {
            ...ITEM_DEFAULTS,
            serviceTypeId: 24,
            serviceKind: 'INTEGRAL',
            unitPrice: 1500,
            components: [
              { ...CHILD_DEFAULTS, serviceTypeId: 3, serviceKind: 'SERVICIO', cargoTypeId: 4, weightKg: 1000 },
              { ...CHILD_DEFAULTS, serviceTypeId: 18, serviceKind: 'COMPLEMENTARIO' },
              { ...CHILD_DEFAULTS, serviceTypeId: 19, serviceKind: 'COMPLEMENTARIO' },
            ],
          },
          { ...ITEM_DEFAULTS, serviceTypeId: 5, serviceKind: 'COMPLEMENTARIO', unitPrice: 300 },
          { ...ITEM_DEFAULTS, serviceTypeId: 6, serviceKind: 'COMPLEMENTARIO', unitPrice: 200 },
        ],
      }),
    )
    expect(request.items.map((item) => item.itemNumber)).toEqual([1, 2, 3, 4, 5, 6])
    // Los 3 hijos referencian al padre (parentItemNumber 1); los 2 roots adicionales no.
    expect(request.items.slice(1, 4).every((item) => item.parentItemNumber === 1)).toBe(true)
    expect(request.items[4].parentItemNumber).toBeUndefined()
    expect(request.items[5].parentItemNumber).toBeUndefined()
  })

  it('mapea clientNote/internalNote con texto (trim aplicado)', () => {
    const request = quotationFormToRequest(
      formWith({
        clientNote: '  Precio sujeto a variación.  ',
        internalNote: '  Margen ajustado.  ',
        items: [{ ...ITEM_DEFAULTS, serviceTypeId: 3, unitPrice: 500 }],
      }),
    )
    expect(request.clientNote).toBe('Precio sujeto a variación.')
    expect(request.internalNote).toBe('Margen ajustado.')
  })

  it('colapsa clientNote/internalNote vacíos o solo whitespace a null', () => {
    const request = quotationFormToRequest(
      formWith({
        clientNote: '',
        internalNote: '   ',
        items: [{ ...ITEM_DEFAULTS, serviceTypeId: 3, unitPrice: 500 }],
      }),
    )
    expect(request.clientNote).toBeNull()
    expect(request.internalNote).toBeNull()
  })

  it('omite el stand-by si el ítem no tiene y lo mapea si lo tiene', () => {
    const request = quotationFormToRequest(
      formWith({
        items: [
          { ...ITEM_DEFAULTS, serviceTypeId: 3, unitPrice: 500, standby: null },
          {
            ...ITEM_DEFAULTS,
            serviceTypeId: 5,
            unitPrice: 300,
            standby: { pricePerDay: 80, includesIgv: true },
          },
        ],
      }),
    )
    expect(request.items[0].standby).toBeUndefined()
    expect(request.items[1].standby).toEqual({ pricePerDay: 80, includesIgv: true })
  })
})
