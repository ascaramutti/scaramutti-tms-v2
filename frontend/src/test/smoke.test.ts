import { describe, expect, it } from 'vitest'

// Smoke test del setup: Vitest + happy-dom + polyfill de Storage funcionando.
// Sirve como sanity check del bootstrap; los tests reales van por feature.
describe('test environment', () => {
  it('expone globals del DOM (happy-dom)', () => {
    expect(typeof window).toBe('object')
    expect(typeof document).toBe('object')
  })

  it('expone localStorage con metodos funcionales', () => {
    expect(typeof window.localStorage.setItem).toBe('function')
    window.localStorage.setItem('k', 'v')
    expect(window.localStorage.getItem('k')).toBe('v')
  })
})
