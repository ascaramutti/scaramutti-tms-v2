import { describe, expect, it } from 'vitest'
import {
  CANCEL_REASON_MAX_LENGTH,
  CANCEL_REASON_MIN_LENGTH,
  entryCancelSchema,
} from './entry-cancel.schema'

function errorAt(input: unknown, field: string): string | undefined {
  const result = entryCancelSchema.safeParse(input)
  if (result.success) return undefined
  return result.error.issues.find((issue) => issue.path[0] === field)?.message
}

describe('entryCancelSchema', () => {
  it('acepta un motivo dentro del rango del contrato', () => {
    expect(entryCancelSchema.safeParse({ reason: 'Factura cargada dos veces' }).success).toBe(true)
  })

  it('rechaza un motivo más corto que el mínimo del contrato', () => {
    expect(errorAt({ reason: 'corto' }, 'reason')).toBe(
      `El motivo debe tener al menos ${CANCEL_REASON_MIN_LENGTH} caracteres`,
    )
  })

  it('cuenta la longitud sobre el texto recortado (solo espacios no alcanza)', () => {
    expect(errorAt({ reason: '          ' }, 'reason')).toBe(
      `El motivo debe tener al menos ${CANCEL_REASON_MIN_LENGTH} caracteres`,
    )
  })

  it('rechaza un motivo más largo que el máximo del contrato', () => {
    expect(errorAt({ reason: 'x'.repeat(CANCEL_REASON_MAX_LENGTH + 1) }, 'reason')).toBe(
      `Máximo ${CANCEL_REASON_MAX_LENGTH} caracteres`,
    )
  })

  it('recorta los espacios de los extremos del motivo', () => {
    const result = entryCancelSchema.safeParse({ reason: '  Factura duplicada del proveedor  ' })
    expect(result.success && result.data.reason).toBe('Factura duplicada del proveedor')
  })
})
