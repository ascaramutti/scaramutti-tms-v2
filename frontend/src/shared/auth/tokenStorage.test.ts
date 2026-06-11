import { afterEach, describe, expect, it } from 'vitest'
import { tokenStorage } from './tokenStorage'

describe('tokenStorage', () => {
  afterEach(() => {
    tokenStorage.clear()
  })

  it('devuelve null cuando no hay tokens almacenados', () => {
    expect(tokenStorage.getAccessToken()).toBeNull()
    expect(tokenStorage.getRefreshToken()).toBeNull()
  })

  it('almacena y recupera ambos tokens', () => {
    tokenStorage.setTokens('access-abc', 'refresh-xyz')
    expect(tokenStorage.getAccessToken()).toBe('access-abc')
    expect(tokenStorage.getRefreshToken()).toBe('refresh-xyz')
  })

  it('limpia el refreshToken cuando se pasa null', () => {
    tokenStorage.setTokens('access-1', 'refresh-1')
    tokenStorage.setTokens('access-2', null)
    expect(tokenStorage.getAccessToken()).toBe('access-2')
    expect(tokenStorage.getRefreshToken()).toBeNull()
  })

  it('clear() elimina ambos tokens', () => {
    tokenStorage.setTokens('a', 'r')
    tokenStorage.clear()
    expect(tokenStorage.getAccessToken()).toBeNull()
    expect(tokenStorage.getRefreshToken()).toBeNull()
  })
})
