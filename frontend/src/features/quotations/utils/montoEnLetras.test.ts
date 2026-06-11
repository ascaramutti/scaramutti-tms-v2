import { describe, expect, it } from 'vitest'
import { montoEnLetras } from './montoEnLetras'

describe('montoEnLetras', () => {
  it('convierte enteros básicos', () => {
    expect(montoEnLetras(0, 'PEN')).toBe('Cero con 00/100 soles')
    expect(montoEnLetras(1, 'PEN')).toBe('Uno con 00/100 soles')
    expect(montoEnLetras(15, 'PEN')).toBe('Quince con 00/100 soles')
  })

  it('maneja los acentos de 16-19 y 22-29', () => {
    expect(montoEnLetras(16, 'PEN')).toBe('Dieciséis con 00/100 soles')
    expect(montoEnLetras(22, 'PEN')).toBe('Veintidós con 00/100 soles')
    expect(montoEnLetras(26, 'PEN')).toBe('Veintiséis con 00/100 soles')
  })

  it('decenas con "y"', () => {
    expect(montoEnLetras(21, 'PEN')).toBe('Veintiuno con 00/100 soles')
    expect(montoEnLetras(31, 'PEN')).toBe('Treinta y uno con 00/100 soles')
    expect(montoEnLetras(45, 'PEN')).toBe('Cuarenta y cinco con 00/100 soles')
  })

  it('distingue cien vs ciento', () => {
    expect(montoEnLetras(100, 'PEN')).toBe('Cien con 00/100 soles')
    expect(montoEnLetras(101, 'PEN')).toBe('Ciento uno con 00/100 soles')
    expect(montoEnLetras(200, 'PEN')).toBe('Doscientos con 00/100 soles')
    expect(montoEnLetras(770, 'PEN')).toBe('Setecientos setenta con 00/100 soles')
  })

  it('miles con apócope antes de "mil"', () => {
    expect(montoEnLetras(1000, 'PEN')).toBe('Mil con 00/100 soles')
    expect(montoEnLetras(2000, 'PEN')).toBe('Dos mil con 00/100 soles')
    expect(montoEnLetras(21000, 'PEN')).toBe('Veintiún mil con 00/100 soles')
  })

  it('millones', () => {
    expect(montoEnLetras(1_000_000, 'PEN')).toBe('Un millón con 00/100 soles')
    expect(montoEnLetras(2_000_000, 'PEN')).toBe('Dos millones con 00/100 soles')
    expect(montoEnLetras(21_000_000, 'PEN')).toBe('Veintiún millones con 00/100 soles')
  })

  it('maneja miles de millones (≥ 1e9, alcanzable con los topes del schema)', () => {
    expect(montoEnLetras(1_000_000_000, 'PEN')).toBe('Mil millones con 00/100 soles')
    expect(montoEnLetras(11_800_000_000, 'USD')).toBe(
      'Once mil ochocientos millones con 00/100 dólares americanos',
    )
  })

  it('maneja billones (escala larga es-PE) sin malformar el literal', () => {
    expect(montoEnLetras(1_000_000_000_000, 'PEN')).toBe('Un billón con 00/100 soles')
    expect(montoEnLetras(1_234_000_000_000, 'PEN')).toBe(
      'Un billón doscientos treinta y cuatro mil millones con 00/100 soles',
    )
  })

  it('el ejemplo del usuario (4770 USD)', () => {
    expect(montoEnLetras(4770, 'USD')).toBe('Cuatro mil setecientos setenta con 00/100 dólares americanos')
  })

  it('formatea los centavos como fracción /100', () => {
    expect(montoEnLetras(1234.56, 'PEN')).toBe('Mil doscientos treinta y cuatro con 56/100 soles')
    expect(montoEnLetras(4770.5, 'USD')).toBe('Cuatro mil setecientos setenta con 50/100 dólares americanos')
  })

  it('redondea los centavos al límite (99.9 → siguiente entero)', () => {
    expect(montoEnLetras(4769.999, 'USD')).toBe('Cuatro mil setecientos setenta con 00/100 dólares americanos')
  })

  it('una moneda desconocida usa el código tal cual', () => {
    expect(montoEnLetras(10, 'EUR')).toBe('Diez con 00/100 EUR')
  })
})
