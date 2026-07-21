import { describe, expect, it } from 'vitest'
import { productFormSchema, type ProductFormInput } from './product.schema'

/** El form arranca con la categoría en `null` hasta que se elige una, así que el
 * tipo de entrada es más laxo que el de salida del schema. */
type ProductFormDraft = Omit<Partial<ProductFormInput>, 'categoryId'> & {
  categoryId?: number | null
}

function input(overrides: ProductFormDraft = {}) {
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

/** Primer mensaje de error de un campo (o `undefined` si el campo validó bien). */
function errorFor(value: unknown, path: (string | number)[]): string | undefined {
  const result = productFormSchema.safeParse(value)
  if (result.success) return undefined
  return result.error.issues.find(
    (issue) => issue.path.join('.') === path.join('.'),
  )?.message
}

describe('productFormSchema', () => {
  it('acepta un producto completo', () => {
    expect(productFormSchema.safeParse(input()).success).toBe(true)
  })

  it('exige un nombre de al menos 3 caracteres', () => {
    expect(errorFor(input({ name: 'ab' }), ['name'])).toMatch(/al menos 3 caracteres/i)
  })

  it('acota el nombre a 200 caracteres, como el contrato', () => {
    expect(errorFor(input({ name: 'a'.repeat(201) }), ['name'])).toMatch(/200/)
    expect(productFormSchema.safeParse(input({ name: 'a'.repeat(200) })).success).toBe(true)
  })

  it('acota marca y número de parte a 100 caracteres', () => {
    expect(errorFor(input({ brand: 'a'.repeat(101) }), ['brand'])).toMatch(/100/)
    expect(errorFor(input({ partNumber: 'a'.repeat(101) }), ['partNumber'])).toMatch(/100/)
  })

  it('exige elegir una categoría', () => {
    expect(errorFor(input({ categoryId: null }), ['categoryId'])).toMatch(/selecciona una categoría/i)
  })

  it('rechaza un stock mínimo negativo', () => {
    expect(errorFor(input({ minStock: -1 }), ['minStock'])).toMatch(/no puede ser negativo/i)
  })

  it('acepta un stock mínimo en cero y con decimales', () => {
    expect(productFormSchema.safeParse(input({ minStock: 0 })).success).toBe(true)
    expect(productFormSchema.safeParse(input({ minStock: 2.5 })).success).toBe(true)
  })

  it('exige el stock mínimo cuando el campo queda vacío', () => {
    // Un input numérico vacío llega como NaN por `valueAsNumber`.
    expect(errorFor(input({ minStock: Number.NaN }), ['minStock'])).toMatch(
      /indica el stock mínimo/i,
    )
  })

  it('exige nombre y valor en cada característica', () => {
    expect(errorFor(input({ attributes: [{ key: '', value: '3/4' }] }), ['attributes', 0, 'key']))
      .toMatch(/indica el nombre/i)
    expect(errorFor(input({ attributes: [{ key: 'rosca', value: '' }] }), ['attributes', 0, 'value']))
      .toMatch(/indica el valor/i)
  })

  it('rechaza dos características con el mismo nombre sin importar mayúsculas', () => {
    // Al convertirlas en objeto, la segunda pisaría a la primera en silencio.
    const error = errorFor(
      input({
        attributes: [
          { key: 'rosca', value: '3/4' },
          { key: 'Rosca', value: '5/8' },
        ],
      }),
      ['attributes', 1, 'key'],
    )
    expect(error).toMatch(/ya usaste esa característica/i)
  })
})
