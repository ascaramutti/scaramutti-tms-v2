import { describe, expect, it } from 'vitest'
import { toSupplierRequest } from './useCreateWarehouseSupplier'

const VALID = {
  name: 'REPUESTOS DIÉSEL S.A.C.',
  ruc: '20512345678',
  phone: '987654321',
  contactName: 'Marta Ríos',
}

describe('toSupplierRequest', () => {
  it('mapea el form al body del contrato', () => {
    expect(toSupplierRequest(VALID)).toEqual({
      name: 'REPUESTOS DIÉSEL S.A.C.',
      ruc: '20512345678',
      phone: '987654321',
      contactName: 'Marta Ríos',
    })
  })

  it('envía null (no cadena vacía) en los opcionales sin completar', () => {
    expect(toSupplierRequest({ name: 'Sellos SAC', ruc: '', phone: '', contactName: '' })).toEqual({
      name: 'Sellos SAC',
      ruc: null,
      phone: null,
      contactName: null,
    })
  })
})
