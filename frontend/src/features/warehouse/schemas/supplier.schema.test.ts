import { describe, expect, it } from 'vitest'
import {
  SUPPLIER_CONTACT_MAX_LENGTH,
  SUPPLIER_NAME_MAX_LENGTH,
  SUPPLIER_NAME_MIN_LENGTH,
  supplierFormSchema,
} from './supplier.schema'

const VALID = {
  name: 'REPUESTOS DIÉSEL S.A.C.',
  ruc: '20512345678',
  phone: '987654321',
  contactName: 'Marta Ríos',
}

function errorAt(input: unknown, field: string): string | undefined {
  const result = supplierFormSchema.safeParse(input)
  if (result.success) return undefined
  return result.error.issues.find((issue) => issue.path[0] === field)?.message
}

describe('supplierFormSchema', () => {
  it('acepta un proveedor completo', () => {
    expect(supplierFormSchema.safeParse(VALID).success).toBe(true)
  })

  it('rechaza un nombre más corto que el mínimo del contrato', () => {
    expect(errorAt({ ...VALID, name: 'AB' }, 'name')).toBe(
      `El nombre debe tener al menos ${SUPPLIER_NAME_MIN_LENGTH} caracteres`,
    )
  })

  it('rechaza un nombre más largo que el máximo del contrato', () => {
    expect(errorAt({ ...VALID, name: 'A'.repeat(SUPPLIER_NAME_MAX_LENGTH + 1) }, 'name')).toBe(
      `Máximo ${SUPPLIER_NAME_MAX_LENGTH} caracteres`,
    )
  })

  it('recorta los espacios del nombre', () => {
    const result = supplierFormSchema.safeParse({ ...VALID, name: '  Sellos SAC  ' })
    expect(result.success && result.data.name).toBe('Sellos SAC')
  })

  it('acepta el proveedor sin RUC (acá es opcional, a diferencia del cliente)', () => {
    expect(supplierFormSchema.safeParse({ ...VALID, ruc: '' }).success).toBe(true)
  })

  it('rechaza un RUC de menos de 11 dígitos', () => {
    expect(errorAt({ ...VALID, ruc: '2051234567' }, 'ruc')).toBe('El RUC debe tener 11 dígitos')
  })

  it('rechaza un RUC con letras', () => {
    expect(errorAt({ ...VALID, ruc: '2051234567A' }, 'ruc')).toBe('El RUC debe tener 11 dígitos')
  })

  it('acepta el proveedor sin teléfono', () => {
    expect(supplierFormSchema.safeParse({ ...VALID, phone: '' }).success).toBe(true)
  })

  it('rechaza un teléfono que no tenga 9 dígitos', () => {
    expect(errorAt({ ...VALID, phone: '12345' }, 'phone')).toBe(
      'El teléfono debe tener 9 dígitos',
    )
  })

  it('rechaza una persona de contacto más larga que el máximo', () => {
    expect(
      errorAt({ ...VALID, contactName: 'x'.repeat(SUPPLIER_CONTACT_MAX_LENGTH + 1) }, 'contactName'),
    ).toBe(`Máximo ${SUPPLIER_CONTACT_MAX_LENGTH} caracteres`)
  })
})
