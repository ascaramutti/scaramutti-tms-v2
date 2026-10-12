import { describe, expect, it } from 'vitest'
import { changePasswordSchema } from './change-password.schema'

const base = { currentPassword: 'Password123', newPassword: 'NuevoPassword456', confirmPassword: 'NuevoPassword456' }

const CAMPOS = [
  ['la actual', 'currentPassword', (clave: string) => ({ ...base, currentPassword: clave })],
  ['la nueva', 'newPassword', (clave: string) => ({ ...base, newPassword: clave, confirmPassword: clave })],
] as const

/**
 * Lo que el servidor cuenta como blanco (isBlank de Java 21), medido sobre todo Unicode: estos 25,
 * todos del plano básico. No es el blanco de JavaScript: este suma los espacios duros y el BOM, y
 * no cuenta los separadores de control.
 */
const BLANCOS_DEL_SERVIDOR = [
  0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x1c, 0x1d, 0x1e, 0x1f, 0x20, 0x1680, 0x2000, 0x2001, 0x2002, 0x2003,
  0x2004, 0x2005, 0x2006, 0x2008, 0x2009, 0x200a, 0x2028, 0x2029, 0x205f, 0x3000,
]

describe.each(CAMPOS)('changePasswordSchema: %s en blanco', (campo, clave, conClave) => {
  it(`rechaza ${campo} hecha solo de un blanco del servidor, y ningún otro carácter`, () => {
    const rechazados: number[] = []
    for (let codePoint = 0; codePoint <= 0xffff; codePoint++) {
      if (codePoint >= 0xd800 && codePoint <= 0xdfff) continue
      const resultado = changePasswordSchema.safeParse(conClave(String.fromCodePoint(codePoint).repeat(8)))
      if (resultado.success) continue
      rechazados.push(codePoint)
      expect(resultado.error.issues.map((issue) => issue.message)).toEqual(['No puede tener solo espacios'])
    }

    expect(rechazados).toEqual(BLANCOS_DEL_SERVIDOR)
  })

  it.each([
    ['vacía', ''],
    ['corta y en blanco', '   '],
  ])(`${campo} %s avisa primero el mínimo`, (_nombre, valor) => {
    const resultado = changePasswordSchema.safeParse(conClave(valor))

    const mensajes = resultado.error?.issues.filter((issue) => issue.path[0] === clave).map((issue) => issue.message)
    expect(mensajes?.[0]).toBe('Mínimo 8 caracteres')
  })
})
