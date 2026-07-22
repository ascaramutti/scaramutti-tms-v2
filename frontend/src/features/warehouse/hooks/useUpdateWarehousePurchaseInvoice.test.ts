import { describe, expect, it } from 'vitest'
import { toPurchaseInvoiceUpdateRequest } from './useUpdateWarehousePurchaseInvoice'
import type { PurchaseInvoiceEditFormInput } from '../schemas/purchase-invoice.schema'

function editInput(overrides: Partial<PurchaseInvoiceEditFormInput> = {}): PurchaseInvoiceEditFormInput {
  return {
    supplierId: 4,
    invoiceNumber: 'F001-00123',
    invoiceDate: '2026-07-02',
    guideNumber: 'T001-0004567',
    currencyId: 2,
    observations: '',
    reason: 'Corregí el precio unitario del filtro',
    items: [{ productId: 1, quantity: 10, unitPrice: 45 }],
    ...overrides,
  }
}

describe('toPurchaseInvoiceUpdateRequest', () => {
  it('no envía el proveedor (es inmutable y el contrato no lo acepta)', () => {
    expect(toPurchaseInvoiceUpdateRequest(editInput())).not.toHaveProperty('supplierId')
  })

  it('incluye el motivo recortado', () => {
    const body = toPurchaseInvoiceUpdateRequest(editInput({ reason: '  Corregí la fecha  ' }))
    expect(body.reason).toBe('Corregí la fecha')
  })

  it('manda null en la guía vacía, no cadena vacía', () => {
    expect(toPurchaseInvoiceUpdateRequest(editInput({ guideNumber: '   ' })).guideNumber).toBeNull()
    expect(toPurchaseInvoiceUpdateRequest(editInput({ observations: '' })).observations).toBeNull()
  })

  it('recorta el número de factura', () => {
    expect(toPurchaseInvoiceUpdateRequest(editInput({ invoiceNumber: '  F001-1  ' })).invoiceNumber).toBe(
      'F001-1',
    )
  })

  it('reemplaza los ítems con lo que quedó en el form (solo productId, quantity, unitPrice)', () => {
    const body = toPurchaseInvoiceUpdateRequest(
      editInput({
        items: [
          { productId: 1, quantity: 10, unitPrice: 45 },
          { productId: 2, quantity: 2, unitPrice: 50 },
        ],
      }),
    )
    expect(body.items).toEqual([
      { productId: 1, quantity: 10, unitPrice: 45 },
      { productId: 2, quantity: 2, unitPrice: 50 },
    ])
  })
})
