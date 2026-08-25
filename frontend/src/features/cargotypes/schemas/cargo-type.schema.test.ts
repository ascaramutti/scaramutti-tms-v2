import { describe, expect, it } from 'vitest'
import { createCargoTypeSchema, type CreateCargoTypeInput } from './cargo-type.schema'

/**
 * Las reglas se ejercitan contra el schema y no por la pantalla a propósito.
 *
 * El campo de texto compartido bloquea por teclado el signo y el exponente, y el
 * propio campo numérico normaliza lo que se pega, así que varias de estas formas no
 * llegan al schema desde el formulario. La autoridad sobre qué es un valor válido es
 * el schema, no la interfaz que hoy lo protege: medirlo acá lo deja fijado aunque esa
 * protección cambie.
 */
function validForm(overrides: Partial<CreateCargoTypeInput> = {}): CreateCargoTypeInput {
  return {
    name: 'CARGA GENERAL',
    description: '',
    standardWeight: '1000',
    standardLength: '',
    standardWidth: '',
    standardHeight: '',
    ...overrides,
  }
}

function errorFor(form: CreateCargoTypeInput, field: string): string | undefined {
  const result = createCargoTypeSchema.safeParse(form)
  if (result.success) return undefined
  return result.error.issues.find((issue) => issue.path[0] === field)?.message
}

describe('createCargoTypeSchema', () => {
  it('acepta un alta mínima: nombre y peso', () => {
    expect(createCargoTypeSchema.safeParse(validForm()).success).toBe(true)
  })

  // ----- El peso -----
  it('exige el peso', () => {
    expect(errorFor(validForm({ standardWeight: '' }), 'standardWeight')).toBe(
      'Ingresa el peso estándar (kg).',
    )
  })

  it('rechaza el peso en cero: una carga que pesa cero no existe', () => {
    expect(errorFor(validForm({ standardWeight: '0' }), 'standardWeight')).toBe(
      'El peso estándar debe ser mayor a 0.',
    )
  })

  it('rechaza el peso negativo', () => {
    expect(errorFor(validForm({ standardWeight: '-5' }), 'standardWeight')).toBe(
      'Escribe un número con hasta 2 decimales.',
    )
  })

  it('rechaza un peso más grande que su columna', () => {
    // Literal del contrato: NUMERIC(10,2), o sea 8 cifras enteras.
    expect(errorFor(validForm({ standardWeight: '100000000' }), 'standardWeight')).toBe(
      'Valor demasiado grande.',
    )
  })

  it('acepta el peso más grande que sí entra', () => {
    expect(createCargoTypeSchema.safeParse(validForm({ standardWeight: '99999999.99' })).success).toBe(
      true,
    )
  })

  it('rechaza un peso que no es un número', () => {
    expect(errorFor(validForm({ standardWeight: 'mucho' }), 'standardWeight')).toBe(
      'Escribe un número con hasta 2 decimales.',
    )
  })

  it('rechaza más de dos decimales, que la columna no guarda', () => {
    expect(errorFor(validForm({ standardWeight: '10.555' }), 'standardWeight')).toBe(
      'Escribe un número con hasta 2 decimales.',
    )
  })

  it('rechaza la notación científica, que colaba un peso cero por la ventana', () => {
    // Un campo numérico admite el exponente, y `1e-5` vale 0.00001: más decimales de
    // los que la columna guarda. El servidor ya lo rechazaba; acá se ataja antes.
    expect(errorFor(validForm({ standardWeight: '1e-5' }), 'standardWeight')).toBe(
      'Escribe un número con hasta 2 decimales.',
    )
    expect(errorFor(validForm({ standardWeight: '1E-5' }), 'standardWeight')).toBe(
      'Escribe un número con hasta 2 decimales.',
    )
    // Y por el otro lado: `1e2` es 100, un valor legítimo escrito de una forma que la
    // columna no interpreta. Se rechaza igual, y el usuario escribe 100.
    expect(errorFor(validForm({ standardWeight: '1e2' }), 'standardWeight')).toBe(
      'Escribe un número con hasta 2 decimales.',
    )
  })

  it('rechaza la notación científica también en una dimensión', () => {
    expect(errorFor(validForm({ standardLength: '1e-5' }), 'standardLength')).toBe(
      'Escribe un número con hasta 2 decimales.',
    )
  })

  it('sigue aceptando lo que la columna sí guarda', () => {
    // El patrón nuevo no puede volverse tan estricto que rechace lo válido.
    for (const value of ['1000', '0.01', '0.5', '12.75', '99999999.99', '7']) {
      expect(createCargoTypeSchema.safeParse(validForm({ standardWeight: value })).success).toBe(true)
    }
    // El vacío entra (es "no la sé"); el 0 ya no, y tiene su propio caso.
    for (const value of ['', '12.5', '2.06', '0.01']) {
      expect(createCargoTypeSchema.safeParse(validForm({ standardLength: value })).success).toBe(true)
    }
  })

  // ----- Las dimensiones: la distinción sobre la que gira todo el cambio -----
  it('una dimensión vacía viaja como null: es el cero que nadie escribió', () => {
    const parsed = createCargoTypeSchema.parse(validForm())
    expect(parsed.standardLength).toBeNull()
    expect(parsed.standardWidth).toBeNull()
    expect(parsed.standardHeight).toBeNull()
  })

  it('rechaza un cero escrito a mano en una dimensión', () => {
    // Antes se admitía, porque el contrato del catálogo lo declaraba válido. Esa
    // decisión se revirtió: una medida en cero no existe, y el campo vacío ya dice
    // "no la sé". El servidor aplica la misma regla.
    expect(errorFor(validForm({ standardLength: '0' }), 'standardLength')).toBe(
      'La medida debe ser mayor a 0.',
    )
  })

  it('el campo vacío sigue siendo la forma de decir "no la sé"', () => {
    const parsed = createCargoTypeSchema.parse(validForm({ standardLength: '' }))
    expect(parsed.standardLength).toBeNull()
  })

  it('rechaza una dimensión negativa', () => {
    // El signo lo bloquea el patrón, junto con el resto de las formas que la columna
    // no puede guardar.
    expect(errorFor(validForm({ standardLength: '-5' }), 'standardLength')).toBe(
      'Escribe un número con hasta 2 decimales.',
    )
  })

  it('rechaza una dimensión más grande que su columna', () => {
    expect(errorFor(validForm({ standardWidth: '100000000' }), 'standardWidth')).toBe(
      'Valor demasiado grande.',
    )
  })

  it('rechaza más de dos decimales en una dimensión', () => {
    expect(errorFor(validForm({ standardHeight: '2.999' }), 'standardHeight')).toBe(
      'Escribe un número con hasta 2 decimales.',
    )
  })

  it('convierte a número la dimensión cargada', () => {
    const parsed = createCargoTypeSchema.parse(validForm({ standardLength: '12.5' }))
    expect(parsed.standardLength).toBe(12.5)
  })

  // ----- El nombre y la descripción -----
  it('exige el nombre', () => {
    expect(errorFor(validForm({ name: '   ' }), 'name')).toBe('El nombre es obligatorio.')
  })

  it('corta el nombre en el tope de la columna', () => {
    expect(createCargoTypeSchema.safeParse(validForm({ name: 'x'.repeat(100) })).success).toBe(true)
    expect(errorFor(validForm({ name: 'x'.repeat(101) }), 'name')).toBe('Máximo 100 caracteres.')
  })

  it('recorta los espacios del nombre y de la descripción', () => {
    const parsed = createCargoTypeSchema.parse(
      validForm({ name: '  GRANEL  ', description: '  suelto  ' }),
    )
    expect(parsed.name).toBe('GRANEL')
    expect(parsed.description).toBe('suelto')
  })
})
