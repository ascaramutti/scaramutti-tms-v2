import { describe, expect, it } from 'vitest'
import { NO_CONTROL, stripControlChars } from './sanitizeText'

describe('stripControlChars (L2)', () => {
  it('elimina caracteres de control intercalados (ej. \\x00 \\x07)', () => {
    expect(stripControlChars('a\x00b\x07c')).toBe('abc')
  })

  it('conserva tab, salto de línea y retorno de carro', () => {
    expect(stripControlChars('a\tb\nc\rd')).toBe('a\tb\nc\rd')
  })

  it('deja intacto el texto imprimible (incluye `< >`, acentos, símbolos)', () => {
    const text = 'peso < 25t > 0 — atención ñ ¿?'
    expect(stripControlChars(text)).toBe(text)
  })

  it('elimina DEL (\\x7F)', () => {
    expect(stripControlChars('ab\x7Fc')).toBe('abc')
  })
})

describe('NO_CONTROL (L3)', () => {
  it('matchea texto sin control-chars', () => {
    expect(NO_CONTROL.test('texto normal <ok>')).toBe(true)
    expect(NO_CONTROL.test('con\ttab\nynl')).toBe(true)
  })

  it('rechaza texto con control-chars', () => {
    expect(NO_CONTROL.test('malo\x00')).toBe(false)
    expect(NO_CONTROL.test('malo\x7F')).toBe(false)
  })
})
