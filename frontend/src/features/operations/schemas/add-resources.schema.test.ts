import { describe, expect, it } from 'vitest'
import {
  REINFORCEMENT_REASON_MAX_LENGTH,
  REINFORCEMENT_REASON_MIN_LENGTH,
  addResourcesFormSchema,
  toAddResourcesRequest,
  type AddResourcesFormInput,
} from './add-resources.schema'

const VALID_REASON = 'Relevo por descanso reglamentario del conductor principal'

function values(overrides: Partial<AddResourcesFormInput> = {}): AddResourcesFormInput {
  return { driverId: 8, tractorId: null, trailerId: null, reason: VALID_REASON, ...overrides }
}

function issueFor(input: AddResourcesFormInput, path: string) {
  const result = addResourcesFormSchema.safeParse(input)
  return result.success
    ? undefined
    : result.error.issues.find((issue) => issue.path[0] === path)?.message
}

describe('addResourcesFormSchema · qué recursos', () => {
  it('exige al menos un recurso', () => {
    expect(
      issueFor(values({ driverId: null, tractorId: null, trailerId: null }), 'driverId'),
    ).toBe('Elige al menos un conductor, tracto o carreta')
  })

  // Los tres por separado: con un solo caso "alcanza con alguno", borrar dos ramas del
  // refinamiento no rompería nada.
  it('alcanza con el conductor', () => {
    expect(addResourcesFormSchema.safeParse(values({ driverId: 8 })).success).toBe(true)
  })

  it('alcanza con el tracto', () => {
    expect(
      addResourcesFormSchema.safeParse(values({ driverId: null, tractorId: 11 })).success,
    ).toBe(true)
  })

  it('alcanza con la carreta', () => {
    expect(
      addResourcesFormSchema.safeParse(values({ driverId: null, trailerId: 9 })).success,
    ).toBe(true)
  })

  it('acepta los tres a la vez', () => {
    // El contrato dice que un pedido con los tres deja UNA fila de refuerzo, no tres.
    expect(
      addResourcesFormSchema.safeParse(values({ driverId: 8, tractorId: 11, trailerId: 9 }))
        .success,
    ).toBe(true)
  })

  it('rechaza un id que no es positivo', () => {
    expect(addResourcesFormSchema.safeParse(values({ driverId: 0 })).success).toBe(false)
  })
})

describe('addResourcesFormSchema · el motivo', () => {
  it('lo exige', () => {
    expect(issueFor(values({ reason: '' }), 'reason')).toBe(
      `El motivo debe tener al menos ${REINFORCEMENT_REASON_MIN_LENGTH} caracteres`,
    )
  })

  it('diez espacios no son un motivo', () => {
    // El caso que mide el "después de recortar": con la longitud medida sobre el
    // texto crudo, diez espacios pasarían.
    expect(issueFor(values({ reason: ' '.repeat(10) }), 'reason')).toBe(
      `El motivo debe tener al menos ${REINFORCEMENT_REASON_MIN_LENGTH} caracteres`,
    )
  })

  it('nueve caracteres útiles rodeados de espacios tampoco', () => {
    // 'Relevo AB' son NUEVE: cierra la otra dirección del borde, con el largo crudo
    // por encima del mínimo y el útil justo por debajo.
    expect(issueFor(values({ reason: '   Relevo AB   ' }), 'reason')).toBe(
      `El motivo debe tener al menos ${REINFORCEMENT_REASON_MIN_LENGTH} caracteres`,
    )
  })

  it('exactamente diez caracteres útiles alcanzan', () => {
    // El borde exacto por arriba: sin él, un `> 10` en vez de `>= 10` sobrevive.
    // 'Relevo ABC' son exactamente DIEZ.
    const result = addResourcesFormSchema.safeParse(values({ reason: '  Relevo ABC  ' }))
    expect(result.success).toBe(true)
    expect(result.success && result.data.reason).toBe('Relevo ABC')
  })

  it('admite exactamente el tope', () => {
    expect(
      addResourcesFormSchema.safeParse(values({ reason: 'a'.repeat(REINFORCEMENT_REASON_MAX_LENGTH) }))
        .success,
    ).toBe(true)
  })

  it('rechaza un carácter más que el tope', () => {
    expect(
      issueFor(values({ reason: 'a'.repeat(REINFORCEMENT_REASON_MAX_LENGTH + 1) }), 'reason'),
    ).toBe(`Máximo ${REINFORCEMENT_REASON_MAX_LENGTH} caracteres`)
  })

  it('rechaza el byte NUL', () => {
    // Escapado y nunca pegado: un NUL literal en el fuente lo trunca cualquier
    // herramienta del camino y el caso quedaría midiendo un texto cortado.
    expect(issueFor(values({ reason: `${VALID_REASON}\u0000` }), 'reason')).toBe(
      'No se permiten caracteres de control',
    )
  })

  it('conserva los saltos de línea internos', () => {
    const reason = 'Varado en el km 214\nSale unidad de apoyo desde Piura'
    const result = addResourcesFormSchema.safeParse(values({ reason }))
    expect(result.success && result.data.reason).toBe(reason)
  })
})

describe('toAddResourcesRequest', () => {
  it('manda cada recurso en su propio campo, y los ausentes en null', () => {
    expect(toAddResourcesRequest(values({ driverId: 8, trailerId: 9 }), false)).toEqual({
      driverId: 8,
      tractorId: null,
      trailerId: 9,
      reason: VALID_REASON,
      force: false,
    })
  })

  it('el motivo viaja recortado, que es como se mide', () => {
    expect(toAddResourcesRequest(values({ reason: `  ${VALID_REASON}  ` }), false).reason).toBe(
      VALID_REASON,
    )
  })

  it('lleva el forzado que se le pide', () => {
    expect(toAddResourcesRequest(values(), true).force).toBe(true)
    expect(toAddResourcesRequest(values(), false).force).toBe(false)
  })
})
