import { describe, expect, it } from 'vitest'
import {
  WITHDRAWAL_MAX_QUANTITY,
  WITHDRAWAL_OBSERVATIONS_MAX_LENGTH,
  withdrawalFormSchema,
  type WithdrawalFormInput,
} from './withdrawal.schema'

const VALID: WithdrawalFormInput = {
  productId: 1,
  quantity: 4,
  receivedByWorkerId: 8,
  observations: '',
}

/** Primer mensaje de error del path pedido (o undefined si ese campo validó). */
function errorAt(input: unknown, path: (string | number)[]): string | undefined {
  const result = withdrawalFormSchema.safeParse(input)
  if (result.success) return undefined
  return result.error.issues.find((issue) => issue.path.join('.') === path.join('.'))?.message
}

describe('withdrawalFormSchema', () => {
  it('acepta un retiro completo y válido', () => {
    expect(withdrawalFormSchema.safeParse(VALID).success).toBe(true)
  })

  // ----- Producto -----
  it('rechaza el producto sin elegir', () => {
    expect(errorAt({ ...VALID, productId: 0 }, ['productId'])).toBe('Selecciona el producto')
  })

  // ----- Cantidad -----
  it('rechaza la cantidad vacía del input (NaN con valueAsNumber)', () => {
    expect(errorAt({ ...VALID, quantity: Number.NaN }, ['quantity'])).toBe('Indica la cantidad')
  })

  it('rechaza la cantidad en 0 (el contrato la exige mayor a 0)', () => {
    expect(errorAt({ ...VALID, quantity: 0 }, ['quantity'])).toBe('La cantidad debe ser mayor a 0')
  })

  it('rechaza la cantidad negativa', () => {
    expect(errorAt({ ...VALID, quantity: -2 }, ['quantity'])).toBe('La cantidad debe ser mayor a 0')
  })

  it('acepta cantidades decimales (litros, galones)', () => {
    expect(withdrawalFormSchema.safeParse({ ...VALID, quantity: 0.5 }).success).toBe(true)
  })

  it('rechaza una cantidad mayor que el tope del formulario', () => {
    expect(errorAt({ ...VALID, quantity: WITHDRAWAL_MAX_QUANTITY + 1 }, ['quantity'])).toBe(
      `Máximo ${WITHDRAWAL_MAX_QUANTITY}`,
    )
  })

  // ----- Trabajador receptor -----
  it('rechaza el trabajador sin elegir (es obligatorio, RN-WH2)', () => {
    expect(errorAt({ ...VALID, receivedByWorkerId: 0 }, ['receivedByWorkerId'])).toBe(
      'Indica quién recibe',
    )
  })

  // ----- Observaciones -----
  it('acepta las observaciones vacías (son opcionales)', () => {
    expect(withdrawalFormSchema.safeParse({ ...VALID, observations: '' }).success).toBe(true)
  })

  it('rechaza observaciones más largas que el tope del formulario', () => {
    const tooLong = 'x'.repeat(WITHDRAWAL_OBSERVATIONS_MAX_LENGTH + 1)
    expect(errorAt({ ...VALID, observations: tooLong }, ['observations'])).toBe(
      `Máximo ${WITHDRAWAL_OBSERVATIONS_MAX_LENGTH} caracteres`,
    )
  })

  it('no valida la unidad de flota: es opcional y no está en el schema', () => {
    // La disyunción de la unidad se garantiza en el mapper, no en zod.
    expect(withdrawalFormSchema.safeParse({ ...VALID, tractorId: 5 }).success).toBe(true)
  })
})
