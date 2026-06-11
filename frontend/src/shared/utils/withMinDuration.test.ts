import { describe, expect, it } from 'vitest'
import { withMinDuration } from './withMinDuration'

describe('withMinDuration', () => {
  it('agrega delay cuando la promise resuelve antes del minimo', async () => {
    const fast = Promise.resolve('done')
    const start = Date.now()
    const result = await withMinDuration(fast, 80)
    const elapsed = Date.now() - start
    expect(result).toBe('done')
    expect(elapsed).toBeGreaterThanOrEqual(75) // tolerancia de 5ms por timer drift
  })

  it('NO agrega delay cuando la promise tarda mas que el minimo', async () => {
    const slow = new Promise<string>((resolve) => setTimeout(() => resolve('done'), 50))
    const start = Date.now()
    const result = await withMinDuration(slow, 20)
    const elapsed = Date.now() - start
    expect(result).toBe('done')
    expect(elapsed).toBeLessThan(80) // ~50ms + jitter, no llega a 80
  })

  it('aplica el minimo tambien cuando la promise rechaza', async () => {
    const failing = Promise.reject(new Error('boom'))
    const start = Date.now()
    await expect(withMinDuration(failing, 60)).rejects.toThrow('boom')
    const elapsed = Date.now() - start
    expect(elapsed).toBeGreaterThanOrEqual(55)
  })
})
