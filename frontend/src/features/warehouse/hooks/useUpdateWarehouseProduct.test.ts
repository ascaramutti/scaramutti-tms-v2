import { describe, expect, it } from 'vitest'
import { toProductUpdateRequest } from './useUpdateWarehouseProduct'
import type { ProductFormInput } from '../schemas/product.schema'

function formInput(overrides: Partial<ProductFormInput> = {}): ProductFormInput {
  return {
    name: 'Filtro de aceite XYZ',
    categoryId: 7,
    brand: 'Bosch',
    partNumber: 'F026407123',
    minStock: 4,
    attributes: [],
    observations: '',
    ...overrides,
  }
}

describe('toProductUpdateRequest', () => {
  it('no envía la unidad de medida (es inmutable y el contrato no la acepta)', () => {
    expect(toProductUpdateRequest(formInput(), true)).not.toHaveProperty('unitOfMeasureId')
  })

  it('conserva el estado activo del producto: editar no lo da de baja', () => {
    expect(toProductUpdateRequest(formInput(), true).isActive).toBe(true)
    // Y un producto ya inactivo no se reactiva al guardar una edición.
    expect(toProductUpdateRequest(formInput(), false).isActive).toBe(false)
  })

  it('manda null en los textos opcionales vacíos, no cadena vacía', () => {
    const body = toProductUpdateRequest(
      formInput({ brand: '', partNumber: '   ', observations: '' }),
      true,
    )
    expect(body.brand).toBeNull()
    expect(body.partNumber).toBeNull()
    expect(body.observations).toBeNull()
  })

  it('recorta los espacios de los textos', () => {
    const body = toProductUpdateRequest(
      formInput({ name: '  Filtro de aire  ', brand: '  Donaldson ' }),
      true,
    )
    expect(body.name).toBe('Filtro de aire')
    expect(body.brand).toBe('Donaldson')
  })

  it('convierte las filas de características en el objeto del contrato', () => {
    const body = toProductUpdateRequest(
      formInput({
        attributes: [
          { key: ' rosca ', value: ' 3/4-16 ' },
          { key: 'viscosidad', value: '15W-40' },
        ],
      }),
      true,
    )
    expect(body.attributes).toEqual({ rosca: '3/4-16', viscosidad: '15W-40' })
  })

  it('sin características manda un objeto vacío, nunca null', () => {
    expect(toProductUpdateRequest(formInput({ attributes: [] }), true).attributes).toEqual({})
  })
})
