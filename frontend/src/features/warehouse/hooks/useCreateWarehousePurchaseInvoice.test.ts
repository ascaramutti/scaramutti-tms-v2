import { describe, expect, it } from 'vitest'
import { toPurchaseInvoiceRequest } from './useCreateWarehousePurchaseInvoice'
import type { PurchaseInvoiceFormInput } from '../schemas/purchase-invoice.schema'

describe('toPurchaseInvoiceRequest', () => {
  const input: PurchaseInvoiceFormInput = {
    supplierId: 4,
    invoiceNumber: '  F001-00123 ',
    invoiceDate: '2026-07-02',
    guideNumber: 'T001-0004567',
    currencyId: 2,
    observations: 'Compra de repuestos',
    items: [
      { productId: 12, quantity: 10, unitPrice: 45 },
      { productId: 31, quantity: 24, unitPrice: 18.5 },
    ],
  }

  it('mapea el form al body del contrato', () => {
    expect(toPurchaseInvoiceRequest(input)).toEqual({
      supplierId: 4,
      invoiceNumber: 'F001-00123',
      invoiceDate: '2026-07-02',
      guideNumber: 'T001-0004567',
      currencyId: 2,
      observations: 'Compra de repuestos',
      items: [
        { productId: 12, quantity: 10, unitPrice: 45 },
        { productId: 31, quantity: 24, unitPrice: 18.5 },
      ],
    })
  })

  it('envía null (no cadena vacía) en guía y observaciones sin completar', () => {
    const body = toPurchaseInvoiceRequest({ ...input, guideNumber: '', observations: '' })
    expect(body.guideNumber).toBeNull()
    expect(body.observations).toBeNull()
  })

  it('no envía el total: el contrato lo deriva de los ítems', () => {
    expect(toPurchaseInvoiceRequest(input)).not.toHaveProperty('total')
  })
})
