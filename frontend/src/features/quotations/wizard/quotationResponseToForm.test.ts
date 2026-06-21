import { describe, expect, it } from 'vitest'
import { quotationResponseToForm } from './quotationResponseToForm'
import { quotationFormToRequest } from './quotationFormToRequest'
import { fakeItem, getQuotationResponse } from '../../../test/mocks/handlers/quotations'

describe('quotationResponseToForm — conditionIds', () => {
  it('precarga los ids de las condiciones linkeadas ACTIVAS, en orden', () => {
    const form = quotationResponseToForm(
      getQuotationResponse({
        conditions: [
          { id: 1, text: 'A', displayOrder: 1, isActive: true },
          { id: 3, text: 'C', displayOrder: 3, isActive: true },
        ],
      }),
    )
    expect(form.conditionIds).toEqual([1, 3])
  })

  it('EXCLUYE las condiciones linkeadas inactivas del pre-marcado (no re-enviables)', () => {
    const form = quotationResponseToForm(
      getQuotationResponse({
        conditions: [
          { id: 1, text: 'Vigente', displayOrder: 1, isActive: true },
          { id: 9, text: 'Desactivada', displayOrder: 2, isActive: false },
        ],
      }),
    )
    // La inactiva (9) NO entra: re-enviarla daría 409 QUO-007.
    expect(form.conditionIds).toEqual([1])
  })

  it('sin condiciones → conditionIds vacío', () => {
    const form = quotationResponseToForm(getQuotationResponse({ conditions: [] }))
    expect(form.conditionIds).toEqual([])
  })

  it('round-trip response→form→request preserva las condiciones activas', () => {
    const response = getQuotationResponse({
      conditions: [
        { id: 1, text: 'A', displayOrder: 1, isActive: true },
        { id: 2, text: 'B', displayOrder: 2, isActive: true },
      ],
    })
    const request = quotationFormToRequest(quotationResponseToForm(response))
    expect(request.conditionIds).toEqual([1, 2])
  })
})

describe('quotationResponseToForm', () => {
  it('mapea los campos de cabecera (summaries embebidos → IDs)', () => {
    const form = quotationResponseToForm(
      getQuotationResponse({
        quotationType: 'TRANSPORTE',
        client: { id: 5, name: 'Constructora X', ruc: '20555555555' },
        currency: { id: 2, code: 'PEN', symbol: 'S/' },
        paymentTerm: { id: 3, name: '30 días', days: 30 },
        validityDays: 20,
        origin: 'Lima',
        destination: 'Cusco',
        contactName: 'Ana',
        contactPhone: '912345678',
        tentativeServiceDate: '2026-06-10',
      }),
    )

    expect(form.quotationType).toBe('TRANSPORTE')
    expect(form.clientId).toBe(5)
    expect(form.currencyId).toBe(2)
    expect(form.paymentTermId).toBe(3)
    expect(form.validityDays).toBe(20)
    expect(form.origin).toBe('Lima')
    expect(form.destination).toBe('Cusco')
    expect(form.contactName).toBe('Ana')
    expect(form.contactPhone).toBe('912345678')
    expect(form.tentativeServiceDate).toBe('2026-06-10')
  })

  it('mapea opcionales `null` a strings vacíos / `paymentTermId` a null', () => {
    const form = quotationResponseToForm(
      getQuotationResponse({
        origin: null,
        destination: null,
        contactPhone: null,
        paymentTerm: undefined,
        tentativeServiceDate: null,
      }),
    )

    expect(form.origin).toBe('')
    expect(form.destination).toBe('')
    expect(form.contactPhone).toBe('')
    expect(form.tentativeServiceDate).toBe('')
    expect(form.paymentTermId).toBeNull()
  })

  it('mapea clientNote/internalNote con contenido a sus strings', () => {
    const form = quotationResponseToForm(
      getQuotationResponse({
        clientNote: 'Precio sujeto a variación.',
        internalNote: 'Margen ajustado por urgencia.',
      }),
    )

    expect(form.clientNote).toBe('Precio sujeto a variación.')
    expect(form.internalNote).toBe('Margen ajustado por urgencia.')
  })

  it('mapea clientNote/internalNote `null` a strings vacíos (no null/undefined)', () => {
    const form = quotationResponseToForm(
      getQuotationResponse({ clientNote: null, internalNote: null }),
    )

    expect(form.clientNote).toBe('')
    expect(form.internalNote).toBe('')
  })

  it('mapea un ítem root simple (sin componentes)', () => {
    const form = quotationResponseToForm(
      getQuotationResponse({
        items: [
          fakeItem({
            serviceType: { id: 2, code: 'CES', name: 'Escolta', kind: 'COMPLEMENTARIO' },
            quantity: 3,
            unitPrice: 500,
          }),
        ],
      }),
    )

    expect(form.items).toHaveLength(1)
    expect(form.items[0]).toMatchObject({
      serviceTypeId: 2,
      serviceKind: 'COMPLEMENTARIO',
      quantity: 3,
      unitPrice: 500,
      components: [],
    })
  })

  it('deriva el `serviceKind` del `kind` del serviceType embebido', () => {
    const form = quotationResponseToForm(
      getQuotationResponse({
        items: [fakeItem({ serviceType: { id: 3, code: 'SPL', name: 'Plataforma', kind: 'SERVICIO' } })],
      }),
    )

    expect(form.items[0].serviceKind).toBe('SERVICIO')
  })

  it('mapea la carga y las dimensiones de un ítem de transporte', () => {
    const form = quotationResponseToForm(
      getQuotationResponse({
        items: [
          fakeItem({
            serviceType: { id: 3, code: 'SPL', name: 'Plataforma', kind: 'SERVICIO' },
            cargoType: { id: 7, name: 'EXCAVADORA 326' },
            weightKg: 25900,
            lengthMeters: 5,
            widthMeters: 2,
            heightMeters: 2.5,
          }),
        ],
      }),
    )

    expect(form.items[0]).toMatchObject({
      cargoTypeId: 7,
      cargoTypeName: 'EXCAVADORA 326',
      weightKg: 25900,
      lengthMeters: 5,
      widthMeters: 2,
      heightMeters: 2.5,
    })
  })

  it('mapea un Servicio Integral con hijos a `components[]`', () => {
    // El default de getQuotationResponse() es un Integral con 2 hijos (SPL "1.a" + CES "1.b").
    const form = quotationResponseToForm(getQuotationResponse())
    const integral = form.items[0]

    expect(integral.serviceKind).toBe('INTEGRAL')
    expect(integral.unitPrice).toBe(1500) // precio del padre
    expect(integral.standby).toBeNull()
    expect(integral.cargoTypeId).toBeNull()
    expect(integral.components).toHaveLength(2)
    expect(integral.components[0]).toMatchObject({
      serviceTypeId: 3,
      serviceKind: 'SERVICIO',
      internalReferencePrice: 900,
      cargoTypeId: 7,
      weightKg: 25900,
    })
    expect(integral.components[1]).toMatchObject({
      serviceTypeId: 18,
      serviceKind: 'COMPLEMENTARIO',
      internalReferencePrice: 371.19,
    })
  })

  it('mapea `internalReferencePrice` ausente del hijo a null (no 0)', () => {
    const form = quotationResponseToForm(
      getQuotationResponse({
        items: [
          fakeItem({
            serviceType: { id: 24, code: 'INT', name: 'Integral', kind: 'INTEGRAL' },
            unitPrice: 1000,
            children: [
              fakeItem({
                serviceType: { id: 3, code: 'SPL', name: 'Plataforma', kind: 'SERVICIO' },
                cargoType: { id: 7, name: 'X' },
                weightKg: 100,
              }),
              fakeItem({ serviceType: { id: 18, code: 'CES', name: 'Escolta', kind: 'COMPLEMENTARIO' } }),
            ],
          }),
        ],
      }),
    )

    expect(form.items[0].components[0].internalReferencePrice).toBeNull()
  })

  it('mapea el stand-by de un ítem (descartando el id)', () => {
    const form = quotationResponseToForm(
      getQuotationResponse({
        items: [fakeItem({ standby: { id: 1, pricePerDay: 150, includesIgv: true } })],
      }),
    )

    expect(form.items[0].standby).toEqual({ pricePerDay: 150, includesIgv: true })
  })

  it('mapea un stand-by ausente a null (no undefined)', () => {
    const form = quotationResponseToForm(
      getQuotationResponse({ items: [fakeItem({ standby: undefined })] }),
    )

    expect(form.items[0].standby).toBeNull()
  })

  it('round-trip: form→request preserva los campos editables y la jerarquía del Integral', () => {
    const response = getQuotationResponse({
      clientNote: 'Nota para el cliente.',
      internalNote: 'Nota interna.',
    })
    const request = quotationFormToRequest(quotationResponseToForm(response))

    expect(request.quotationType).toBe('TRANSPORTE')
    expect(request.clientId).toBe(1)
    expect(request.currencyId).toBe(2)
    // Las observaciones sobreviven el round-trip response→form→request.
    expect(request.clientNote).toBe('Nota para el cliente.')
    expect(request.internalNote).toBe('Nota interna.')
    // El Integral se aplana: padre (itemNumber 1) + 2 hijos con parentItemNumber 1.
    expect(request.items).toHaveLength(3)
    expect(request.items[0]).toMatchObject({ itemNumber: 1, serviceTypeId: 24, unitPrice: 1500 })
    expect(request.items[1]).toMatchObject({
      itemNumber: 2,
      parentItemNumber: 1,
      serviceTypeId: 3,
      internalReferencePrice: 900,
    })
    expect(request.items[2]).toMatchObject({
      itemNumber: 3,
      parentItemNumber: 1,
      serviceTypeId: 18,
      internalReferencePrice: 371.19,
    })
  })
})
