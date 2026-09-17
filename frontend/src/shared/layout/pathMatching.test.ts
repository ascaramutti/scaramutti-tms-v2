import { CLIENTS_BASE, QUOTATIONS_BASE } from '../../shared/paths'
import { describe, expect, it } from 'vitest'
import { matchesPathPrefix } from './pathMatching'

describe('matchesPathPrefix', () => {
  it('matchea el prefijo exacto y sus rutas hijas', () => {
    expect(matchesPathPrefix(CLIENTS_BASE, CLIENTS_BASE)).toBe(true)
    expect(matchesPathPrefix(`${CLIENTS_BASE}/123`, CLIENTS_BASE)).toBe(true)
    expect(matchesPathPrefix(`${CLIENTS_BASE}/123/editar`, CLIENTS_BASE)).toBe(true)
  })

  it('respeta el borde de segmento', () => {
    expect(matchesPathPrefix(`${CLIENTS_BASE}X`, CLIENTS_BASE)).toBe(false)
    expect(matchesPathPrefix(`${QUOTATIONS_BASE}X`, QUOTATIONS_BASE)).toBe(false)
  })

  it('la raíz matchea solo exacta (si no, marcaría toda la app)', () => {
    expect(matchesPathPrefix('/', '/')).toBe(true)
    expect(matchesPathPrefix(QUOTATIONS_BASE, '/')).toBe(false)
  })
})
