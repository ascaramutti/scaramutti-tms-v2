import { describe, expect, it } from 'vitest'
import { todayIsoDate } from '../../../shared/utils/formatters'
import {
  INVOICE_MAX_ITEMS,
  INVOICE_NUMBER_MAX_LENGTH,
  INVOICE_OBSERVATIONS_MAX_LENGTH,
  purchaseInvoiceFormSchema,
  type PurchaseInvoiceFormInput,
} from './purchase-invoice.schema'

const VALID: PurchaseInvoiceFormInput = {
  supplierId: 4,
  invoiceNumber: 'F001-00123',
  invoiceDate: '2026-07-02',
  guideNumber: 'T001-0004567',
  currencyId: 2,
  observations: '',
  items: [{ productId: 12, quantity: 10, unitPrice: 45 }],
}

/** Primer mensaje de error del path pedido (o undefined si ese campo validó). */
function errorAt(input: unknown, path: (string | number)[]): string | undefined {
  const result = purchaseInvoiceFormSchema.safeParse(input)
  if (result.success) return undefined
  return result.error.issues.find(
    (issue) => issue.path.join('.') === path.join('.'),
  )?.message
}

describe('purchaseInvoiceFormSchema', () => {
  it('acepta una factura completa y válida', () => {
    expect(purchaseInvoiceFormSchema.safeParse(VALID).success).toBe(true)
  })

  // ----- Proveedor -----
  it('rechaza el proveedor sin elegir', () => {
    expect(errorAt({ ...VALID, supplierId: 0 }, ['supplierId'])).toBe('Selecciona el proveedor')
  })

  // ----- Número de factura -----
  it('rechaza el número de factura vacío', () => {
    expect(errorAt({ ...VALID, invoiceNumber: '' }, ['invoiceNumber'])).toBe(
      'Indica el número de factura',
    )
  })

  it('rechaza un número de factura de solo espacios', () => {
    expect(errorAt({ ...VALID, invoiceNumber: '   ' }, ['invoiceNumber'])).toBe(
      'Indica el número de factura',
    )
  })

  it('rechaza un número de factura más largo que el contrato', () => {
    const tooLong = 'F'.repeat(INVOICE_NUMBER_MAX_LENGTH + 1)
    expect(errorAt({ ...VALID, invoiceNumber: tooLong }, ['invoiceNumber'])).toBe(
      `Máximo ${INVOICE_NUMBER_MAX_LENGTH} caracteres`,
    )
  })

  it('recorta los espacios del número de factura', () => {
    const result = purchaseInvoiceFormSchema.safeParse({
      ...VALID,
      invoiceNumber: '  F001-00123  ',
    })
    expect(result.success && result.data.invoiceNumber).toBe('F001-00123')
  })

  // ----- Fecha -----
  it('rechaza la fecha vacía', () => {
    expect(errorAt({ ...VALID, invoiceDate: '' }, ['invoiceDate'])).toBe(
      'Indica la fecha de la factura',
    )
  })

  it('rechaza una fecha futura', () => {
    const [year, month, day] = todayIsoDate().split('-').map(Number)
    const tomorrow = new Date(year, month - 1, day + 1)
    const isoTomorrow = `${tomorrow.getFullYear()}-${String(tomorrow.getMonth() + 1).padStart(2, '0')}-${String(tomorrow.getDate()).padStart(2, '0')}`
    expect(errorAt({ ...VALID, invoiceDate: isoTomorrow }, ['invoiceDate'])).toBe(
      'La fecha no puede ser futura',
    )
  })

  it('acepta la fecha de hoy (borde de la regla)', () => {
    expect(
      purchaseInvoiceFormSchema.safeParse({ ...VALID, invoiceDate: todayIsoDate() }).success,
    ).toBe(true)
  })

  // ----- Guía y moneda -----
  it('acepta la guía de remisión vacía (es opcional)', () => {
    expect(purchaseInvoiceFormSchema.safeParse({ ...VALID, guideNumber: '' }).success).toBe(true)
  })

  it('rechaza la moneda sin elegir', () => {
    expect(errorAt({ ...VALID, currencyId: 0 }, ['currencyId'])).toBe('Selecciona la moneda')
  })

  it('rechaza observaciones más largas que el tope del formulario', () => {
    const tooLong = 'x'.repeat(INVOICE_OBSERVATIONS_MAX_LENGTH + 1)
    expect(errorAt({ ...VALID, observations: tooLong }, ['observations'])).toBe(
      `Máximo ${INVOICE_OBSERVATIONS_MAX_LENGTH} caracteres`,
    )
  })

  // ----- Ítems -----
  it('rechaza una factura sin ítems', () => {
    expect(errorAt({ ...VALID, items: [] }, ['items'])).toBe('Agrega al menos un ítem')
  })

  it('rechaza más ítems que el máximo del contrato', () => {
    const items = Array.from({ length: INVOICE_MAX_ITEMS + 1 }, () => ({
      productId: 1,
      quantity: 1,
      unitPrice: 1,
    }))
    expect(errorAt({ ...VALID, items }, ['items'])).toBe(
      `Máximo ${INVOICE_MAX_ITEMS} ítems por factura`,
    )
  })

  it('rechaza el ítem sin producto', () => {
    const items = [{ productId: 0, quantity: 1, unitPrice: 1 }]
    expect(errorAt({ ...VALID, items }, ['items', 0, 'productId'])).toBe(
      'Selecciona un producto',
    )
  })

  it('rechaza la cantidad en 0 (el contrato la exige mayor a 0)', () => {
    const items = [{ productId: 1, quantity: 0, unitPrice: 1 }]
    expect(errorAt({ ...VALID, items }, ['items', 0, 'quantity'])).toBe(
      'La cantidad debe ser mayor a 0',
    )
  })

  it('rechaza la cantidad negativa', () => {
    const items = [{ productId: 1, quantity: -2, unitPrice: 1 }]
    expect(errorAt({ ...VALID, items }, ['items', 0, 'quantity'])).toBe(
      'La cantidad debe ser mayor a 0',
    )
  })

  it('acepta cantidades decimales (litros, galones)', () => {
    const items = [{ productId: 1, quantity: 0.5, unitPrice: 10 }]
    expect(purchaseInvoiceFormSchema.safeParse({ ...VALID, items }).success).toBe(true)
  })

  it('acepta el precio unitario en 0 (bonificación)', () => {
    const items = [{ productId: 1, quantity: 3, unitPrice: 0 }]
    expect(purchaseInvoiceFormSchema.safeParse({ ...VALID, items }).success).toBe(true)
  })

  it('rechaza el precio unitario negativo', () => {
    const items = [{ productId: 1, quantity: 3, unitPrice: -1 }]
    expect(errorAt({ ...VALID, items }, ['items', 0, 'unitPrice'])).toBe('No puede ser negativo')
  })

  it('rechaza la cantidad vacía del input (NaN con valueAsNumber)', () => {
    const items = [{ productId: 1, quantity: Number.NaN, unitPrice: 1 }]
    expect(errorAt({ ...VALID, items }, ['items', 0, 'quantity'])).toBeDefined()
  })

  it('ubica el error en el ítem que falla, sin contaminar a los hermanos', () => {
    const items = [
      { productId: 1, quantity: 1, unitPrice: 1 },
      { productId: 2, quantity: 0, unitPrice: 1 },
    ]
    expect(errorAt({ ...VALID, items }, ['items', 1, 'quantity'])).toBeDefined()
    expect(errorAt({ ...VALID, items }, ['items', 0, 'quantity'])).toBeUndefined()
  })

  it('acepta el mismo producto en dos ítems (el aviso no bloquea)', () => {
    const items = [
      { productId: 7, quantity: 1, unitPrice: 10 },
      { productId: 7, quantity: 2, unitPrice: 12 },
    ]
    expect(purchaseInvoiceFormSchema.safeParse({ ...VALID, items }).success).toBe(true)
  })
})
