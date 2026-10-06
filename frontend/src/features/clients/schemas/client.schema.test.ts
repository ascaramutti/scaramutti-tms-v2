import { describe, expect, it } from 'vitest'
import { createClientSchema } from './client.schema'

/**
 * El schema es el espejo de lo que el backend exige, y hasta acá nadie lo medía:
 * aflojar el patrón del teléfono o subir cualquiera de los dos topes dejaba la
 * suite entera verde y el 400 aparecía recién en producción.
 *
 * Sirve a las dos pantallas que lo usan, el alta al vuelo y la edición.
 */
const base = { name: 'ACME S.A.C.', ruc: '20123456789', phone: '987654321', contactName: 'Ana' }

describe('createClientSchema', () => {
  it('acepta los cuatro campos bien formados', () => {
    expect(createClientSchema.safeParse(base).success).toBe(true)
  })

  it('la razón social va de 1 a 200 caracteres', () => {
    expect(createClientSchema.safeParse({ ...base, name: '' }).success).toBe(false)
    expect(createClientSchema.safeParse({ ...base, name: 'A'.repeat(200) }).success).toBe(true)
    expect(createClientSchema.safeParse({ ...base, name: 'A'.repeat(201) }).success).toBe(false)
  })

  it('el RUC son exactamente once dígitos', () => {
    expect(createClientSchema.safeParse({ ...base, ruc: '2012345678' }).success).toBe(false)
    expect(createClientSchema.safeParse({ ...base, ruc: '201234567890' }).success).toBe(false)
    expect(createClientSchema.safeParse({ ...base, ruc: '2012345678a' }).success).toBe(false)
  })

  it('el teléfono son exactamente nueve dígitos, y es opcional', () => {
    expect(createClientSchema.safeParse({ ...base, phone: '12345678' }).success).toBe(false)
    expect(createClientSchema.safeParse({ ...base, phone: '1234567890' }).success).toBe(false)
    expect(createClientSchema.safeParse({ ...base, phone: '98765432a' }).success).toBe(false)
    expect(createClientSchema.safeParse({ ...base, phone: '' }).success).toBe(true)
  })

  it('el contacto llega hasta 100 caracteres, y es opcional', () => {
    expect(createClientSchema.safeParse({ ...base, contactName: 'A'.repeat(100) }).success).toBe(true)
    expect(createClientSchema.safeParse({ ...base, contactName: 'A'.repeat(101) }).success).toBe(false)
    expect(createClientSchema.safeParse({ ...base, contactName: '' }).success).toBe(true)
  })
})
