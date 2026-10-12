import { describe, expect, it } from 'vitest'
import { changePasswordSchema } from './change-password.schema'
import { loginSchema } from './login.schema'

// Los textos van escritos acá y no importados: así el test también fija lo que ve el usuario.
const TOO_SHORT_MESSAGE = 'Mínimo 8 caracteres'
const TOO_LONG_MESSAGE = 'Es demasiado larga: máximo 72 bytes; las tildes y los emojis cuentan doble o más'
const UNUSABLE_CHARACTER_MESSAGE = 'Tiene un carácter que no se puede usar'

const ENYE = 0xf1 // 2 bytes en UTF-8, 1 unidad UTF-16
const A_WITH_ACCENT = 0xe1 // 2 bytes, 1 unidad
const KANJI = 0x4e2d // 3 bytes, 1 unidad
const EMOJI = 0x1f600 // 4 bytes, 2 unidades

// Sin escapes en el fuente: así se ve qué carácter es y cuántos bytes pesa.
function repeat(codePoint: number, times: number): string {
  return String.fromCodePoint(codePoint).repeat(times)
}

const NUL = String.fromCharCode(0)
const HIGH = String.fromCharCode(0xd800)
const LOW = String.fromCharCode(0xdc00)

type Result = { success: boolean; error?: { issues: { path: PropertyKey[]; message: string }[] } }

function messagesOf(result: Result, field: string): string[] {
  return result.error?.issues.filter((issue) => issue.path[0] === field).map((issue) => issue.message) ?? []
}

const CAMPOS = [
  ['la del login', (clave: string) => messagesOf(loginSchema.safeParse({ username: 'sales', password: clave }), 'password')],
  [
    'la actual',
    (clave: string) =>
      messagesOf(
        changePasswordSchema.safeParse({ currentPassword: clave, newPassword: 'NuevoPassword456', confirmPassword: 'NuevoPassword456' }),
        'currentPassword',
      ),
  ],
  [
    'la nueva',
    (clave: string) =>
      messagesOf(changePasswordSchema.safeParse({ currentPassword: 'Password123', newPassword: clave, confirmPassword: clave }), 'newPassword'),
  ],
] as const

describe.each(CAMPOS)('la contraseña: %s', (_campo, mensajes) => {
  it.each([
    ['72 ASCII', 'a'.repeat(72)],
    ['36 a con tilde', repeat(A_WITH_ACCENT, 36)],
    ['36 eñes', repeat(ENYE, 36)],
    ['24 kanji', repeat(KANJI, 24)],
    ['18 emojis', repeat(EMOJI, 18)],
  ])('acepta %s (72 bytes)', (_nombre, clave) => {
    expect(mensajes(clave)).toEqual([])
  })

  // El límite son 72 bytes UTF-8, no caracteres: salvo las ASCII, ninguna de estas filas llega a 73 caracteres.
  it.each([
    ['73 ASCII', 'a'.repeat(73)],
    ['37 a con tilde (74 bytes)', repeat(A_WITH_ACCENT, 37)],
    ['71 ASCII y una eñe (73 bytes, 72 caracteres)', 'a'.repeat(71) + repeat(ENYE, 1)],
    ['25 kanji (75 bytes)', repeat(KANJI, 25)],
    ['19 emojis (76 bytes)', repeat(EMOJI, 19)],
    ['101 ASCII, uno más que el máximo de antes', 'a'.repeat(101)],
  ])('rechaza %s con el texto del máximo en bytes', (_nombre, clave) => {
    expect(mensajes(clave)).toEqual([TOO_LONG_MESSAGE])
  })

  // El servidor cuenta el mínimo en unidades UTF-16: 4 de estos emojis son 8, aunque se vean 4.
  it.each([
    ['4 emojis (8 unidades)', repeat(EMOJI, 4), []],
    ['3 emojis y una letra (7 unidades)', repeat(EMOJI, 3) + 'a', [TOO_SHORT_MESSAGE]],
    ['7 letras', 'a'.repeat(7), [TOO_SHORT_MESSAGE]],
  ])('cuenta el mínimo como el servidor: %s', (_nombre, clave, esperado) => {
    expect(mensajes(clave)).toEqual(esperado)
  })

  it.each([
    ['un nulo en el medio', `Clave${NUL}123`, [UNUSABLE_CHARACTER_MESSAGE]],
    ['un nulo al final', `Clave123${NUL}`, [UNUSABLE_CHARACTER_MESSAGE]],
    ['un surrogate alto suelto', `Clave${HIGH}123`, [UNUSABLE_CHARACTER_MESSAGE]],
    ['un surrogate alto al final', `Clave123${HIGH}`, [UNUSABLE_CHARACTER_MESSAGE]],
    ['un surrogate bajo suelto', `Clave${LOW}123`, [UNUSABLE_CHARACTER_MESSAGE]],
    ['el último surrogate bajo, suelto', `Clave${String.fromCharCode(0xdfff)}123`, [UNUSABLE_CHARACTER_MESSAGE]],
    ['un par al revés', `Clave${LOW}${HIGH}123`, [UNUSABLE_CHARACTER_MESSAGE]],
    ['un par bien formado', `Clave${HIGH}${LOW}123`, []],
    ['el carácter justo antes de los surrogates', `Clave${String.fromCharCode(0xd7ff)}123`, []],
    ['el carácter justo después', `Clave${String.fromCharCode(0xe000)}123`, []],
    ['otro carácter de control, que no es nulo', `Clave${String.fromCharCode(9)}123`, []],
    // Sus 16 bits bajos caen en el rango de los surrogates: no hay que confundirlo con uno.
    ['un punto de código alto cuyo resto parece surrogate', `Clave123${repeat(0x1d800, 1)}`, []],
  ])('con %s', (_nombre, clave, esperado) => {
    expect(mensajes(clave)).toEqual(esperado)
  })

  // Con el surrogate suelto, TextEncoder cuenta 73 bytes y Java 71: el aviso que se ve es el del carácter.
  it('con 70 letras y un surrogate suelto avisa primero el carácter', () => {
    expect(mensajes(`${'a'.repeat(70)}${HIGH}`)[0]).toBe(UNUSABLE_CHARACTER_MESSAGE)
  })

  it('corta y con un nulo avisa primero el mínimo', () => {
    expect(mensajes(`abc${NUL}`)[0]).toBe(TOO_SHORT_MESSAGE)
  })
})
