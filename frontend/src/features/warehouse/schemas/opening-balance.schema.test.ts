import { describe, expect, it } from 'vitest'
import * as openingBalanceSchemaModule from './opening-balance.schema'
import {
  OPENING_BALANCE_MAX_QUANTITY,
  OPENING_BALANCE_OBSERVATIONS_MAX_LENGTH,
  openingBalanceFormSchema,
  type OpeningBalanceFormInput,
} from './opening-balance.schema'

const VALID: OpeningBalanceFormInput = {
  productId: 1,
  quantity: 6,
  observations: 'Conteo físico del arranque',
}

/** Primer mensaje de error del campo pedido, o undefined si el campo validó. */
function errorAt(input: unknown, path: string): string | undefined {
  const result = openingBalanceFormSchema.safeParse(input)
  if (result.success) return undefined
  return result.error.issues.find((issue) => issue.path[0] === path)?.message
}

describe('openingBalanceFormSchema', () => {
  it('acepta un corte inicial completo y válido', () => {
    expect(openingBalanceFormSchema.safeParse(VALID).success).toBe(true)
  })

  it('rechaza el producto sin elegir', () => {
    expect(errorAt({ ...VALID, productId: 0 }, 'productId')).toBe('Selecciona el producto')
  })

  it('rechaza la cantidad vacía del input (NaN con valueAsNumber)', () => {
    expect(errorAt({ ...VALID, quantity: Number.NaN }, 'quantity')).toBe('Indica la cantidad')
  })

  // El candado contra endurecer la regla a `.positive()`: el contrato dice
  // `minimum: 0` a propósito y un corte en 0 deja constancia de que se contó el
  // producto y no había existencias, que no es lo mismo que no haberlo cargado.
  it('acepta la cantidad en 0 (el contrato la permite: minimum 0)', () => {
    expect(openingBalanceFormSchema.safeParse({ ...VALID, quantity: 0 }).success).toBe(true)
  })

  it('rechaza la cantidad negativa', () => {
    expect(errorAt({ ...VALID, quantity: -1 }, 'quantity')).toBe(
      'La cantidad no puede ser negativa',
    )
  })

  it('acepta cantidades decimales (litros, galones)', () => {
    expect(openingBalanceFormSchema.safeParse({ ...VALID, quantity: 0.5 }).success).toBe(true)
  })

  it('acepta el tope de cantidad y rechaza lo que lo supera', () => {
    expect(
      openingBalanceFormSchema.safeParse({ ...VALID, quantity: OPENING_BALANCE_MAX_QUANTITY })
        .success,
    ).toBe(true)
    expect(errorAt({ ...VALID, quantity: OPENING_BALANCE_MAX_QUANTITY + 1 }, 'quantity')).toBe(
      `Máximo ${OPENING_BALANCE_MAX_QUANTITY}`,
    )
  })

  it('acepta las observaciones vacías (son opcionales)', () => {
    expect(openingBalanceFormSchema.safeParse({ ...VALID, observations: '' }).success).toBe(true)
  })

  it('rechaza observaciones más largas que el tope', () => {
    const observations = 'x'.repeat(OPENING_BALANCE_OBSERVATIONS_MAX_LENGTH + 1)
    expect(errorAt({ ...VALID, observations }, 'observations')).toBe(
      `Máximo ${OPENING_BALANCE_OBSERVATIONS_MAX_LENGTH} caracteres`,
    )
  })

  // La apertura es inmutable (el contrato no tiene PUT ni DELETE): no debe existir
  // un schema de edición, ni siquiera "por simetría" con entradas y retiros.
  it('no define reglas de edición: la apertura es inmutable', () => {
    expect(openingBalanceSchemaModule).not.toHaveProperty('openingBalanceEditFormSchema')
  })
})
