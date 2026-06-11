import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { delay, http, HttpResponse } from 'msw'
import { configureHttpClient } from './client'
import { client } from '../../api/client.gen'
import { getCurrentUser, login, refreshToken } from '../../api'
import { server } from '../../test/mocks/server'
import { tokenStorage } from '../auth/tokenStorage'

const API = 'http://localhost:8080/api/v1'

const FAKE_USER = {
  id: 1,
  username: 'admin',
  fullName: 'Admin TMS',
  position: 'Administrador del sistema',
  role: 'admin' as const,
  isActive: true,
}

describe('http client interceptor (refresh-on-401)', () => {
  let onSessionExpired: ReturnType<typeof vi.fn>

  beforeAll(() => {
    // configureHttpClient lee VITE_API_BASE_URL al ejecutarse.
    vi.stubEnv('VITE_API_BASE_URL', API)
  })

  beforeEach(() => {
    tokenStorage.clear()
    onSessionExpired = vi.fn()
    // Limpiar interceptores antes de re-configurar (cada configure agrega uno).
    client.instance.interceptors.response.clear()
    configureHttpClient(onSessionExpired)
  })

  afterEach(() => {
    client.instance.interceptors.response.clear()
  })

  it('request 401 → refresh exitoso → reintenta el request con el nuevo token', async () => {
    tokenStorage.setTokens('old-access', 'valid-refresh')
    let meCallCount = 0
    server.use(
      http.get(`${API}/auth/me`, ({ request }) => {
        meCallCount++
        const auth = request.headers.get('Authorization') ?? ''
        // El primer call llega con el token viejo (rechazar), el segundo con el nuevo (aceptar)
        if (auth.includes('old-access')) {
          return HttpResponse.json({ status: 401 }, { status: 401 })
        }
        return HttpResponse.json(FAKE_USER)
      }),
      http.post(`${API}/auth/refresh`, () =>
        HttpResponse.json({
          token: 'new-access',
          refreshToken: 'new-refresh',
          expiresAt: '2027-01-01T00:00:00Z',
          expiresIn: 3600,
          user: FAKE_USER,
        }),
      ),
    )

    const { data } = await getCurrentUser({ throwOnError: true })

    expect(data).toEqual(FAKE_USER)
    expect(meCallCount).toBe(2) // 1 fallido + 1 retry
    expect(tokenStorage.getAccessToken()).toBe('new-access')
    expect(tokenStorage.getRefreshToken()).toBe('new-refresh')
    expect(onSessionExpired).not.toHaveBeenCalled()
  })

  it('request 401 → refresh tambien falla → onSessionExpired llamado, error rechaza', async () => {
    tokenStorage.setTokens('old-access', 'expired-refresh')
    server.use(
      http.get(`${API}/auth/me`, () => HttpResponse.json({ status: 401 }, { status: 401 })),
      http.post(`${API}/auth/refresh`, () => HttpResponse.json({ status: 401 }, { status: 401 })),
    )

    await expect(getCurrentUser({ throwOnError: true })).rejects.toThrow()

    expect(onSessionExpired).toHaveBeenCalledTimes(1)
    expect(tokenStorage.getAccessToken()).toBeNull() // refresh fallido limpia tokens
    expect(tokenStorage.getRefreshToken()).toBeNull()
  })

  it('N requests 401 paralelos disparan UN solo POST /auth/refresh (dedupe)', async () => {
    tokenStorage.setTokens('old-access', 'valid-refresh')
    let refreshCallCount = 0
    server.use(
      http.get(`${API}/auth/me`, ({ request }) => {
        const auth = request.headers.get('Authorization') ?? ''
        if (auth.includes('old-access')) {
          return HttpResponse.json({ status: 401 }, { status: 401 })
        }
        return HttpResponse.json(FAKE_USER)
      }),
      http.post(`${API}/auth/refresh`, async () => {
        refreshCallCount++
        // Pequenio delay para garantizar que los 3 requests llegan a refresh
        // antes de que el primero resuelva.
        await delay(20)
        return HttpResponse.json({
          token: 'new-access',
          refreshToken: 'new-refresh',
          expiresAt: '2027-01-01T00:00:00Z',
          expiresIn: 3600,
          user: FAKE_USER,
        })
      }),
    )

    const results = await Promise.all([
      getCurrentUser({ throwOnError: true }),
      getCurrentUser({ throwOnError: true }),
      getCurrentUser({ throwOnError: true }),
    ])

    expect(results).toHaveLength(3)
    results.forEach((r) => expect(r.data).toEqual(FAKE_USER))
    expect(refreshCallCount).toBe(1) // dedupe: solo UN refresh disparado
  })

  it('/auth/refresh que falla NO dispara otro refresh (no loop)', async () => {
    tokenStorage.setTokens('any-access', 'invalid-refresh')
    let refreshCallCount = 0
    server.use(
      http.post(`${API}/auth/refresh`, () => {
        refreshCallCount++
        return HttpResponse.json({ status: 401 }, { status: 401 })
      }),
    )

    // Llamar directo al endpoint /auth/refresh; debe rechazar sin reentrar.
    await expect(
      refreshToken({ body: { refreshToken: 'invalid-refresh' }, throwOnError: true }),
    ).rejects.toThrow()

    expect(refreshCallCount).toBe(1) // UN solo intento, no loop
  })

  it('/auth/login con 401 NO dispara refresh (refresh token huerfano en storage)', async () => {
    // Caso: usuario tiene refreshToken viejo en storage (no se limpio bien)
    // y tipea credenciales incorrectas. El 401 del login NO debe disparar
    // refresh ni sobreescribir tokens.
    tokenStorage.setTokens('huerfano-access', 'huerfano-refresh')
    let refreshCallCount = 0
    server.use(
      http.post(`${API}/auth/login`, () =>
        HttpResponse.json(
          { type: 'urn:tms:error:auth-001', code: 'AUTH-001', status: 401 },
          { status: 401 },
        ),
      ),
      http.post(`${API}/auth/refresh`, () => {
        refreshCallCount++
        return HttpResponse.json({ status: 200 }) // no deberia llegar aca
      }),
    )

    await expect(
      login({ body: { username: 'admin', password: 'wrong' }, throwOnError: true }),
    ).rejects.toThrow()

    expect(refreshCallCount).toBe(0) // /auth/login NO debe disparar refresh
    // Los tokens huerfanos quedan intactos (no se sobreescribieron)
    expect(tokenStorage.getAccessToken()).toBe('huerfano-access')
    expect(tokenStorage.getRefreshToken()).toBe('huerfano-refresh')
  })
})
