import { describe, expect, it } from 'vitest'
import {
  ASSIGNMENT_NOTE_MAX_LENGTH,
  assignResourcesFormSchema,
  toAssignResourcesRequest,
  type AssignResourcesFormInput,
} from './assign-resources.schema'

/** Una selección válida, sobre la que cada caso cambia UN campo. */
function validValues(overrides: Partial<AssignResourcesFormInput> = {}) {
  return { driverId: 4, tractorId: 7, trailerId: 3, note: '', ...overrides }
}

function firstIssue(values: unknown, path: string) {
  const result = assignResourcesFormSchema.safeParse(values)
  return result.success
    ? undefined
    : result.error.issues.find((issue) => issue.path[0] === path)?.message
}

describe('assignResourcesFormSchema', () => {
  it('exige el conductor', () => {
    expect(firstIssue(validValues({ driverId: null as unknown as number }), 'driverId')).toBe(
      'Selecciona el conductor',
    )
  })

  it('exige el tracto', () => {
    expect(firstIssue(validValues({ tractorId: null as unknown as number }), 'tractorId')).toBe(
      'Selecciona el tracto',
    )
  })

  it('acepta el viaje sin carreta', () => {
    const result = assignResourcesFormSchema.safeParse(validValues({ trailerId: null }))
    expect(result.success).toBe(true)
    expect(result.success && result.data.trailerId).toBeNull()
  })

  it('rechaza un id que no es positivo', () => {
    // El 0 es el valor al que llega un campo numérico vacío cuando alguien lo
    // registra como número: tiene que morir acá y con el mensaje del campo, no
    // viajar al backend como un id inexistente.
    expect(firstIssue(validValues({ driverId: 0 }), 'driverId')).toBe('Selecciona el conductor')
    expect(firstIssue(validValues({ tractorId: -3 }), 'tractorId')).toBe('Selecciona el tracto')
  })

  it('rechaza una carreta con id no positivo', () => {
    // La carreta usa OTRO esquema que el conductor y el tracto (es opcional), así que
    // sus bordes no los cubre ningún caso de los obligatorios.
    expect(assignResourcesFormSchema.safeParse(validValues({ trailerId: 0 })).success).toBe(false)
    expect(assignResourcesFormSchema.safeParse(validValues({ trailerId: -3 })).success).toBe(false)
  })

  it('rechaza un id con decimales', () => {
    expect(firstIssue(validValues({ driverId: 4.5 }), 'driverId')).toBe('Selecciona el conductor')
  })

  it('admite exactamente el tope de la nota', () => {
    const result = assignResourcesFormSchema.safeParse(
      validValues({ note: 'a'.repeat(ASSIGNMENT_NOTE_MAX_LENGTH) }),
    )
    expect(result.success).toBe(true)
  })

  it('rechaza un carácter más que el tope de la nota', () => {
    expect(firstIssue(validValues({ note: 'a'.repeat(ASSIGNMENT_NOTE_MAX_LENGTH + 1) }), 'note')).toBe(
      `Máximo ${ASSIGNMENT_NOTE_MAX_LENGTH} caracteres`,
    )
  })

  it('rechaza el byte NUL en la nota', () => {
    // Se escribe escapado y NUNCA pegado: un NUL literal en el fuente lo trunca
    // cualquier herramienta del camino y el caso quedaría midiendo un texto cortado.
    expect(firstIssue(validValues({ note: 'Sale del patio\u0000' }), 'note')).toBe(
      'No se permiten caracteres de control',
    )
  })

  it('conserva los saltos de línea de la nota', () => {
    // El servidor los APLASTA al escribir la bitácora, no los rechaza: el formulario
    // no tiene por qué prohibir lo que el backend acepta.
    const result = assignResourcesFormSchema.safeParse(validValues({ note: 'Línea 1\nLínea 2' }))
    expect(result.success).toBe(true)
    expect(result.success && result.data.note).toBe('Línea 1\nLínea 2')
  })

  it('recorta los espacios de los bordes de la nota', () => {
    const result = assignResourcesFormSchema.safeParse(validValues({ note: '  Sale del patio  ' }))
    expect(result.success && result.data.note).toBe('Sale del patio')
  })
})

describe('toAssignResourcesRequest', () => {
  it('manda cada recurso en su propio campo', () => {
    // Los tres ids son distintos entre sí: cruzar dos campos cambia el objeto.
    expect(
      toAssignResourcesRequest(
        { driverId: 4, tractorId: 7, trailerId: 3, note: 'Sale a las 05:00' },
        false,
      ),
    ).toEqual({ driverId: 4, tractorId: 7, trailerId: 3, note: 'Sale a las 05:00', force: false })
  })

  it('la nota en blanco viaja como ausente, no como cadena vacía', () => {
    // El contrato dice que una nota en blanco se trata como ausente, y mandar el
    // `null` explícito deja el cuerpo completo.
    expect(
      toAssignResourcesRequest({ driverId: 4, tractorId: 7, trailerId: null, note: '   ' }, false)
        .note,
    ).toBeNull()
  })

  it('lleva el forzado que se le pide', () => {
    const values = { driverId: 4, tractorId: 7, trailerId: null, note: '' }
    expect(toAssignResourcesRequest(values, true).force).toBe(true)
    expect(toAssignResourcesRequest(values, false).force).toBe(false)
  })
})
