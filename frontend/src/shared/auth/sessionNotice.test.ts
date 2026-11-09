import { describe, expect, it } from 'vitest'
import { SESSION_WINDOW_NOTICE } from './sessionNotice'

describe('SESSION_WINDOW_NOTICE', () => {
  it('dice cuánto sigue sirviendo una sesión abierta', () => {
    expect(SESSION_WINDOW_NOTICE).toBe('Si tiene una sesión abierta, deja de funcionar en 16 minutos como máximo.')
  })
})
