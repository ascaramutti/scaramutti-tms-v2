import { describe, expect, it } from 'vitest'
import { matchesPathPrefix } from './pathMatching'

describe('matchesPathPrefix', () => {
  it('matchea el prefijo exacto y sus rutas hijas', () => {
    expect(matchesPathPrefix('/clientes', '/clientes')).toBe(true)
    expect(matchesPathPrefix('/clientes/123', '/clientes')).toBe(true)
    expect(matchesPathPrefix('/clientes/123/editar', '/clientes')).toBe(true)
  })

  it('respeta el borde de segmento', () => {
    expect(matchesPathPrefix('/clientesX', '/clientes')).toBe(false)
    expect(matchesPathPrefix('/cotizacionesX', '/cotizaciones')).toBe(false)
  })

  it('la raíz matchea solo exacta (si no, marcaría toda la app)', () => {
    expect(matchesPathPrefix('/', '/')).toBe(true)
    expect(matchesPathPrefix('/cotizaciones', '/')).toBe(false)
  })
})
