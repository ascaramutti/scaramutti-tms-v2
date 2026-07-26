import { describe, expect, it } from 'vitest'
import { trimToNull } from './trimToNull'

describe('trimToNull', () => {
  it('devuelve el texto sin espacios de los bordes', () => {
    expect(trimToNull('  Bosch  ')).toBe('Bosch')
  })

  it('convierte la cadena vacía en null', () => {
    expect(trimToNull('')).toBeNull()
  })

  it('convierte un texto de solo espacios en null', () => {
    expect(trimToNull('   ')).toBeNull()
  })

  it('convierte undefined y null en null', () => {
    expect(trimToNull(undefined)).toBeNull()
    expect(trimToNull(null)).toBeNull()
  })
})
