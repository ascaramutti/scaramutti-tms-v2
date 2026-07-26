import { describe, expect, it } from 'vitest'
import { toProductCreateRequest } from './useCreateWarehouseProduct'

describe('toProductCreateRequest', () => {
  const createInput = {
    name: '  Filtro de aceite XYZ ',
    categoryId: 7,
    unitOfMeasureId: 1,
    brand: 'Bosch',
    partNumber: '',
    minStock: 4,
    attributes: [{ key: 'rosca', value: '3/4-16' }],
    observations: '',
  }

  it('mapea el form al body del contrato', () => {
    expect(toProductCreateRequest(createInput)).toEqual({
      name: 'Filtro de aceite XYZ',
      categoryId: 7,
      unitOfMeasureId: 1,
      brand: 'Bosch',
      partNumber: null,
      attributes: { rosca: '3/4-16' },
      minStock: 4,
      observations: null,
    })
  })

  it('no envía el código ni el estado: los define el backend', () => {
    const body = toProductCreateRequest(createInput) as Record<string, unknown>
    expect(body).not.toHaveProperty('code')
    expect(body).not.toHaveProperty('isActive')
  })
})
