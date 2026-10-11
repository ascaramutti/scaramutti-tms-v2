import { CHANGE_PASSWORD_PATH, LOGIN_PATH, OPERATIONS_BASE } from '../paths'
import type { ReactNode } from 'react'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query'
import { RouterProvider, createMemoryRouter } from 'react-router-dom'
import { http, HttpResponse } from 'msw'
import { toast } from 'sonner'
import { routes } from '../../router'
import { client } from '../../api/client.gen'
import { listServices, type LoginRequest, type LoginResponse, type UserResponse } from '../../api'
import { configureSessionHttpClient } from '../http/client'
import { queryClient as appQueryClient } from '../query/queryClient'
import { ThemeProvider } from '../ui/theme/ThemeContext'
import { ThemedToaster } from '../ui/theme/ThemedToaster'
import { useChangeWorkerStatus } from '../../features/workers/hooks/useChangeWorkerStatus'
import { AuthProvider } from './AuthContext'
import { currentUserQueryKey } from './queryKeys'
import { landingPathFor } from './roleLanding'
import { tokenStorage } from './tokenStorage'
import { server } from '../../test/mocks/server'
import {
  fakeDispatcherServiceSummary,
  fakeServiceStats,
  fakeServiceSummary,
  pageOfServices,
} from '../../test/mocks/handlers/operations'
import { fakeWorkerDetail } from '../../test/mocks/handlers/workers'

const API = 'http://localhost:8080/api/v1'

interface Persona {
  user: UserResponse
  password: string
  token: string
  refresh: string
}

const ANA: Persona = {
  user: {
    id: 11,
    username: 'ana',
    fullName: 'Ana Gerente',
    position: 'Jefa de Operaciones',
    role: 'operations_manager',
    isActive: true,
  },
  password: 'Clave-de-Ana-1',
  token: 'tok-ana',
  refresh: 'ref-ana',
}

const BETO: Persona = {
  user: {
    id: 12,
    username: 'beto',
    fullName: 'Beto Despacho',
    position: 'Despachador',
    role: 'dispatcher',
    isActive: true,
  },
  password: 'Clave-de-Beto-1',
  token: 'tok-beto',
  refresh: 'ref-beto',
}

/** Un token que el backend todavía reconoce como de Ana en /auth/me, pero que ya venció. */
const TOKEN_VENCIDO_DE_ANA = 'tok-ana-vencido'

const SERVICIO_DE_ANA = fakeServiceSummary({ id: 501, code: 'SRV-ANA', price: 5800 })
const SERVICIO_DE_BETO = fakeDispatcherServiceSummary({ id: 502, code: 'SRV-BETO' })

function bearer(request: Request): string | null {
  return request.headers.get('Authorization')?.replace('Bearer ', '') ?? null
}

function sesionDe(user: UserResponse, token: string, refresh: string): LoginResponse {
  return { token, refreshToken: refresh, expiresAt: '2027-01-01T00:00:00Z', expiresIn: 3600, user }
}

/** El backend de las dos personas: cada token ve lo suyo, y sin token no hay nada. */
function backendDeDosPersonas() {
  return [
    http.post(`${API}/auth/login`, async ({ request }) => {
      const body = (await request.json()) as LoginRequest
      const persona = [ANA, BETO].find(
        (candidata) => candidata.user.username === body.username && candidata.password === body.password,
      )
      if (!persona) {
        return HttpResponse.json(
          { status: 401, code: 'AUTH-001', detail: 'Usuario o contraseña incorrectos.' },
          { status: 401, headers: { 'Content-Type': 'application/problem+json' } },
        )
      }
      return HttpResponse.json(sesionDe(persona.user, persona.token, persona.refresh))
    }),
    http.get(`${API}/auth/me`, ({ request }) => {
      const token = bearer(request)
      if (token === ANA.token || token === TOKEN_VENCIDO_DE_ANA) return HttpResponse.json(ANA.user)
      if (token === BETO.token) return HttpResponse.json(BETO.user)
      return new HttpResponse(null, { status: 401 })
    }),
    http.get(`${API}/services`, ({ request }) => {
      const token = bearer(request)
      if (token === ANA.token) return HttpResponse.json(pageOfServices([SERVICIO_DE_ANA]))
      if (token === BETO.token) return HttpResponse.json(pageOfServices([SERVICIO_DE_BETO]))
      return new HttpResponse(null, { status: 401 })
    }),
    http.post(`${API}/auth/refresh`, () => new HttpResponse(null, { status: 401 })),
    http.post(`${API}/workers/:id/deactivate`, ({ params }) =>
      HttpResponse.json(fakeWorkerDetail({ id: Number(params.id), isActive: false })),
    ),
  ]
}

/** Una respuesta que el test suelta cuando quiere: para que vuelva después de salir. */
function compuerta() {
  let abrir!: () => void
  const abierta = new Promise<void>((resolve) => {
    abrir = resolve
  })
  return { abrir, abierta }
}

/** Una mutación real de la sesión, montada fuera de las rutas: sigue montada después de salir. */
function BajaDeUnTrabajador() {
  const cambio = useChangeWorkerStatus(7)
  return (
    <button type="button" onClick={() => cambio.mutate('deactivate')}>
      Desactivar al trabajador 7
    </button>
  )
}

/** Una query de la sesión que sigue montada después de salir: no la corta desmontarse. */
function ListadoFueraDeLasRutas() {
  useQuery({
    queryKey: ['fuera-de-las-rutas', 'servicios'],
    queryFn: async () => (await listServices({ throwOnError: true })).data,
  })
  return null
}

let queryClient: QueryClient
let pedidos: string[]
let entregadas: string[]

/** La aplicación como la arranca main.tsx: rutas reales, cliente HTTP configurado y avisos. */
function montar(path: string, { extra, antes }: { extra?: ReactNode; antes?: () => void } = {}) {
  queryClient = new QueryClient({ defaultOptions: appQueryClient.getDefaultOptions() })
  antes?.()
  configureSessionHttpClient(queryClient)
  const router = createMemoryRouter(routes, { initialEntries: [path] })
  render(
    <QueryClientProvider client={queryClient}>
      <ThemeProvider>
        <AuthProvider>
          <RouterProvider router={router} />
          <ThemedToaster />
          {extra}
        </AuthProvider>
      </ThemeProvider>
    </QueryClientProvider>,
  )
  return router
}

async function entrar(user: ReturnType<typeof userEvent.setup>, persona: Persona) {
  await user.type(await screen.findByLabelText('Usuario'), persona.user.username)
  await user.type(screen.getByLabelText('Contraseña'), persona.password)
  await user.click(screen.getByRole('button', { name: 'Iniciar sesión' }))
}

async function salir(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole('button', { name: 'Cerrar sesión' }))
}

async function cambiarLaClave(user: ReturnType<typeof userEvent.setup>, nueva: string) {
  await user.type(await screen.findByLabelText('Contraseña actual'), ANA.password)
  await user.type(screen.getByLabelText('Nueva contraseña'), nueva)
  await user.type(screen.getByLabelText('Confirmar nueva contraseña'), nueva)
  await user.click(screen.getByRole('button', { name: 'Cambiar contraseña' }))
}

/** Todo lo que la aplicación guarda en memoria: datos de las queries y variables de las mutaciones. */
function memoria(): string {
  return JSON.stringify({
    queries: queryClient.getQueryCache().getAll().map((query) => [query.queryKey, query.state.data]),
    mutations: queryClient.getMutationCache().getAll().map((mutation) => mutation.state.variables),
  })
}

/** Espera a que MSW entregue la respuesta y a que el cliente termine de procesarla. */
async function cuandoSeEntregue(pedido: string) {
  await waitFor(() => expect(entregadas).toContain(pedido))
  await act(() => new Promise((resolve) => setTimeout(resolve, 50)))
}

describe('fin de sesión', () => {
  beforeAll(() => {
    vi.stubEnv('VITE_API_BASE_URL', API)
  })

  beforeEach(() => {
    client.instance.interceptors.request.clear()
    client.instance.interceptors.response.clear()
    pedidos = []
    entregadas = []
    server.events.on('request:start', ({ request }) => {
      const auth = request.headers.get('Authorization') ?? 'sin token'
      pedidos.push(`${request.method} ${new URL(request.url).pathname} ${auth}`)
    })
    server.events.on('response:mocked', ({ request }) => {
      entregadas.push(`${request.method} ${new URL(request.url).pathname}`)
    })
    server.use(...backendDeDosPersonas())
  })

  afterEach(() => {
    client.instance.interceptors.request.clear()
    client.instance.interceptors.response.clear()
    server.events.removeAllListeners()
    window.history.replaceState(null, '', '/')
    vi.restoreAllMocks()
    // sonner guarda los avisos en un store del módulo: sin esto, el de un test aparece en el siguiente.
    toast.dismiss()
  })

  it('quien entra después en la misma pestaña no ve nada del anterior: todo se pide de nuevo', async () => {
    const user = userEvent.setup()
    const router = montar(LOGIN_PATH)
    await entrar(user, ANA)
    await act(() => router.navigate(OPERATIONS_BASE))
    expect(await screen.findByText('SRV-ANA')).toBeInTheDocument()

    await salir(user)
    await entrar(user, BETO)

    expect(await screen.findByText('SRV-BETO')).toBeInTheDocument()
    expect(screen.queryByText('SRV-ANA')).not.toBeInTheDocument()
    expect(pedidos).toContain(`GET /api/v1/services Bearer ${BETO.token}`)
    expect(memoria()).not.toContain('SRV-ANA')
  })

  it('al salir solo queda el usuario, en null: ninguna otra query ni mutación, y nada sale a la red', async () => {
    const user = userEvent.setup()
    const router = montar(LOGIN_PATH, { extra: <BajaDeUnTrabajador /> })
    await entrar(user, ANA)
    await act(() => router.navigate(OPERATIONS_BASE))
    await screen.findByText('SRV-ANA')
    await user.click(screen.getByRole('button', { name: 'Desactivar al trabajador 7' }))
    await waitFor(() =>
      expect(queryClient.getMutationCache().getAll().map((mutation) => mutation.state.status)).toContain('success'),
    )
    const pedidosAntesDeSalir = pedidos.length

    await salir(user)
    await screen.findByRole('heading', { name: 'Iniciar sesión' })

    expect(queryClient.getQueryCache().getAll().map((query) => [query.queryKey, query.state.data])).toEqual([
      [currentUserQueryKey, null],
    ])
    expect(queryClient.getMutationCache().getAll()).toEqual([])
    expect(pedidos.slice(pedidosAntesDeSalir)).toEqual([])
  })

  it('salir lleva al ingreso y saca al usuario de la pantalla', async () => {
    const user = userEvent.setup()
    const router = montar(LOGIN_PATH)
    await entrar(user, ANA)
    await act(() => router.navigate(OPERATIONS_BASE))
    await screen.findByText('SRV-ANA')
    expect(screen.getByText(ANA.user.fullName)).toBeInTheDocument()

    await salir(user)

    expect(await screen.findByRole('heading', { name: 'Iniciar sesión' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe(LOGIN_PATH)
    expect(screen.queryByText(ANA.user.fullName)).not.toBeInTheDocument()
  })

  describe('una renovación que vuelve después de salir', () => {
    function renovacionDemorada(
      respuesta = () => HttpResponse.json(sesionDe(ANA.user, 'tok-ana-renovado', 'ref-ana-renovado')),
    ) {
      const renovacion = compuerta()
      server.use(
        http.post(`${API}/auth/refresh`, async () => {
          await renovacion.abierta
          return respuesta()
        }),
      )
      return renovacion
    }

    it('no revive los tokens del que salió ni reintenta su pedido', async () => {
      const user = userEvent.setup()
      const renovacion = renovacionDemorada()
      tokenStorage.setTokens(TOKEN_VENCIDO_DE_ANA, ANA.refresh)
      const router = montar(OPERATIONS_BASE)
      await waitFor(() => expect(pedidos.some((pedido) => pedido.startsWith('POST /api/v1/auth/refresh'))).toBe(true))
      const pedidosAntesDeSalir = pedidos.length

      await salir(user)
      renovacion.abrir()
      await cuandoSeEntregue('POST /api/v1/auth/refresh')

      expect(tokenStorage.getAccessToken()).toBeNull()
      expect(tokenStorage.getRefreshToken()).toBeNull()
      expect(pedidos.slice(pedidosAntesDeSalir)).toEqual([])
      expect(router.state.location.pathname).toBe(LOGIN_PATH)
    })

    it('ni pisa los tokens del que entró mientras volvía', async () => {
      const user = userEvent.setup()
      const renovacion = renovacionDemorada()
      tokenStorage.setTokens(TOKEN_VENCIDO_DE_ANA, ANA.refresh)
      montar(OPERATIONS_BASE)
      await waitFor(() => expect(pedidos.some((pedido) => pedido.startsWith('POST /api/v1/auth/refresh'))).toBe(true))

      await salir(user)
      await entrar(user, BETO)
      await screen.findByText('SRV-BETO')
      renovacion.abrir()
      await cuandoSeEntregue('POST /api/v1/auth/refresh')

      expect(tokenStorage.getAccessToken()).toBe(BETO.token)
      expect(tokenStorage.getRefreshToken()).toBe(BETO.refresh)
      expect(screen.getByText(BETO.user.fullName)).toBeInTheDocument()
      expect(pedidos.filter((pedido) => pedido.includes('tok-ana-renovado'))).toEqual([])
    })

    it('ni borra los tokens del que entró mientras volvía cuando la renovación falla', async () => {
      const user = userEvent.setup()
      const renovacion = renovacionDemorada(() => new HttpResponse(null, { status: 401 }))
      tokenStorage.setTokens(TOKEN_VENCIDO_DE_ANA, ANA.refresh)
      const router = montar(OPERATIONS_BASE)
      await waitFor(() => expect(pedidos.some((pedido) => pedido.startsWith('POST /api/v1/auth/refresh'))).toBe(true))

      await salir(user)
      await entrar(user, BETO)
      await screen.findByText('SRV-BETO')
      renovacion.abrir()
      await cuandoSeEntregue('POST /api/v1/auth/refresh')

      expect(tokenStorage.getAccessToken()).toBe(BETO.token)
      expect(tokenStorage.getRefreshToken()).toBe(BETO.refresh)
      expect(router.state.location.pathname).toBe(OPERATIONS_BASE)
      expect(screen.getByText(BETO.user.fullName)).toBeInTheDocument()
    })

    it('y quien entró puede renovar la suya después: la renovación descartada no queda trabada', async () => {
      const user = userEvent.setup()
      const renovacion = renovacionDemorada()
      tokenStorage.setTokens(TOKEN_VENCIDO_DE_ANA, ANA.refresh)
      montar(OPERATIONS_BASE)
      await waitFor(() => expect(pedidos.some((pedido) => pedido.startsWith('POST /api/v1/auth/refresh'))).toBe(true))
      await salir(user)
      await entrar(user, BETO)
      await screen.findByText('SRV-BETO')
      renovacion.abrir()
      await cuandoSeEntregue('POST /api/v1/auth/refresh')

      server.use(
        http.get(`${API}/services`, ({ request }) =>
          bearer(request) === 'tok-beto-renovado'
            ? HttpResponse.json(pageOfServices([SERVICIO_DE_BETO]))
            : new HttpResponse(null, { status: 401 }),
        ),
        http.post(`${API}/auth/refresh`, () =>
          HttpResponse.json(sesionDe(BETO.user, 'tok-beto-renovado', 'ref-beto-renovado')),
        ),
      )
      await act(() => queryClient.invalidateQueries())

      await waitFor(() => expect(tokenStorage.getAccessToken()).toBe('tok-beto-renovado'))
      expect(pedidos).toContain('GET /api/v1/services Bearer tok-beto-renovado')
    })
  })

  describe('una renovación de antes del ingreso que vuelve después de que otro entró', () => {
    /**
     * La pestaña quedó en el ingreso con los tokens viejos de Ana: `/auth/me` da 401 y su renovación
     * queda en vuelo mientras Beto entra. La respuesta de la renovación la suelta el test.
     */
    function renovacionDeAnaEnVuelo(respuesta: () => Response) {
      const renovacion = compuerta()
      let respondida = false
      server.use(
        http.post(`${API}/auth/refresh`, async () => {
          await renovacion.abierta
          respondida = true
          return respuesta()
        }),
        http.get(`${API}/auth/me`, ({ request }) => {
          const token = bearer(request)
          if (token === 'tok-ana-renovado') return HttpResponse.json(ANA.user)
          if (token === BETO.token) return HttpResponse.json(BETO.user)
          return new HttpResponse(null, { status: 401 })
        }),
      )
      return { abrir: renovacion.abrir, respondida: () => respondida }
    }

    async function betoEntraMientrasVuelve(respuesta: () => Response) {
      const user = userEvent.setup()
      window.history.replaceState(null, '', LOGIN_PATH)
      const recarga = vi.spyOn(window.location, 'assign').mockImplementation(() => {})
      const renovacion = renovacionDeAnaEnVuelo(respuesta)
      tokenStorage.setTokens('tok-desconocido', ANA.refresh)
      const router = montar(LOGIN_PATH)
      await waitFor(() => expect(pedidos.some((pedido) => pedido.startsWith('POST /api/v1/auth/refresh'))).toBe(true))
      await entrar(user, BETO)
      await screen.findByText('SRV-BETO')

      renovacion.abrir()
      await waitFor(() => expect(renovacion.respondida()).toBe(true))
      await act(() => new Promise((resolve) => setTimeout(resolve, 50)))
      return { router, recarga }
    }

    it('si falla, no echa al que entró', async () => {
      const { router, recarga } = await betoEntraMientrasVuelve(() => new HttpResponse(null, { status: 401 }))

      expect(tokenStorage.getAccessToken()).toBe(BETO.token)
      expect(tokenStorage.getRefreshToken()).toBe(BETO.refresh)
      expect(router.state.location.pathname).toBe(landingPathFor(BETO.user.role))
      expect(screen.getByText(BETO.user.fullName)).toBeInTheDocument()
      expect(recarga).not.toHaveBeenCalled()
      expect(queryClient.isFetching()).toBe(0)
    })

    it('si sale bien, no le pisa los tokens ni lo deja en la sesión del otro', async () => {
      const { router } = await betoEntraMientrasVuelve(() =>
        HttpResponse.json(sesionDe(ANA.user, 'tok-ana-renovado', 'ref-ana-renovado')),
      )

      expect(tokenStorage.getAccessToken()).toBe(BETO.token)
      expect(tokenStorage.getRefreshToken()).toBe(BETO.refresh)
      expect(router.state.location.pathname).toBe(landingPathFor(BETO.user.role))
      expect(screen.getByText(BETO.user.fullName)).toBeInTheDocument()
      expect(screen.queryByText(ANA.user.fullName)).not.toBeInTheDocument()
      expect(pedidos.filter((pedido) => pedido.includes('tok-ana-renovado'))).toEqual([])
      expect(queryClient.isFetching()).toBe(0)
    })
  })

  it('el 401 del que entró no espera la renovación de la sesión anterior, y los siguientes comparten la suya', async () => {
    const user = userEvent.setup()
    window.history.replaceState(null, '', OPERATIONS_BASE)
    const recarga = vi.spyOn(window.location, 'assign').mockImplementation(() => {})
    const deAna = compuerta()
    const deBeto = compuerta()
    let anaRespondida = false
    let renovacionesDeBeto = 0
    let statsDeBetoRespondidos = false
    server.use(
      http.post(`${API}/auth/refresh`, async ({ request }) => {
        const body = (await request.json()) as { refreshToken: string }
        if (body.refreshToken === ANA.refresh) {
          await deAna.abierta
          anaRespondida = true
          return HttpResponse.json(sesionDe(ANA.user, 'tok-ana-renovado', 'ref-ana-renovado'))
        }
        renovacionesDeBeto++
        await deBeto.abierta
        return HttpResponse.json(sesionDe(BETO.user, 'tok-beto-renovado', 'ref-beto-renovado'))
      }),
      http.get(`${API}/services`, ({ request }) =>
        bearer(request) === 'tok-beto-renovado'
          ? HttpResponse.json(pageOfServices([SERVICIO_DE_BETO]))
          : new HttpResponse(null, { status: 401 }),
      ),
      // Los indicadores responden la primera vez con el token de Beto: al recargarlos, su 401 es el
      // siguiente, el que tiene que compartir la renovación de Beto en vez de pedir otra.
      http.get(`${API}/services/stats`, ({ request }) => {
        const token = bearer(request)
        if (token === 'tok-beto-renovado') return HttpResponse.json(fakeServiceStats())
        if (token === BETO.token && !statsDeBetoRespondidos) {
          statsDeBetoRespondidos = true
          return HttpResponse.json(fakeServiceStats())
        }
        return new HttpResponse(null, { status: 401 })
      }),
    )
    tokenStorage.setTokens(TOKEN_VENCIDO_DE_ANA, ANA.refresh)
    montar(OPERATIONS_BASE)
    await waitFor(() => expect(pedidos.some((pedido) => pedido.startsWith('POST /api/v1/auth/refresh'))).toBe(true))
    await salir(user)
    await entrar(user, BETO)
    await waitFor(() => expect(renovacionesDeBeto).toBe(1))
    await waitFor(() => expect(statsDeBetoRespondidos).toBe(true))

    deAna.abrir()
    await waitFor(() => expect(anaRespondida).toBe(true))
    // Sin await: la nueva carga espera la renovación de Beto, que el test suelta después.
    act(() => {
      void queryClient.invalidateQueries()
    })
    await act(() => new Promise((resolve) => setTimeout(resolve, 100)))
    deBeto.abrir()

    expect(await screen.findByText('SRV-BETO')).toBeInTheDocument()
    expect(renovacionesDeBeto).toBe(1)
    expect(tokenStorage.getAccessToken()).toBe('tok-beto-renovado')
    expect(recarga).not.toHaveBeenCalled()
  })

  it('un reintento que estaba pendiente al salir ya no sale, aunque su query siga montada', async () => {
    const user = userEvent.setup()
    server.use(http.get(`${API}/services`, () => new HttpResponse(null, { status: 500 })))
    tokenStorage.setTokens(ANA.token, ANA.refresh)
    montar(CHANGE_PASSWORD_PATH, { extra: <ListadoFueraDeLasRutas /> })
    await waitFor(() => expect(entregadas).toContain('GET /api/v1/services'))
    const pedidosAntesDeSalir = pedidos.length

    await salir(user)
    // El reintento de la aplicación espera un segundo después del primer fallo.
    await act(() => new Promise((resolve) => setTimeout(resolve, 1300)))

    expect(pedidos.slice(pedidosAntesDeSalir)).toEqual([])
  })

  it('una query en vuelo al salir no deja nada en la caché ni vuelve a pedir', async () => {
    const user = userEvent.setup()
    const listado = compuerta()
    server.use(
      http.get(`${API}/services`, async ({ request }) => {
        if (bearer(request) !== ANA.token) return new HttpResponse(null, { status: 401 })
        await listado.abierta
        return HttpResponse.json(pageOfServices([SERVICIO_DE_ANA]))
      }),
    )
    tokenStorage.setTokens(ANA.token, ANA.refresh)
    montar(OPERATIONS_BASE)
    await waitFor(() => expect(pedidos).toContain(`GET /api/v1/services Bearer ${ANA.token}`))
    const pedidosAntesDeSalir = pedidos.length

    await salir(user)
    listado.abrir()
    await cuandoSeEntregue('GET /api/v1/services')

    expect(memoria()).not.toContain('SRV-ANA')
    expect(pedidos.slice(pedidosAntesDeSalir)).toEqual([])
    expect(queryClient.isFetching()).toBe(0)
  })

  it('el onSuccess de una mutación que vuelve después de salir no escribe en la caché', async () => {
    const user = userEvent.setup()
    const baja = compuerta()
    server.use(
      http.post(`${API}/workers/:id/deactivate`, async ({ params }) => {
        await baja.abierta
        return HttpResponse.json(
          fakeWorkerDetail({ id: Number(params.id), firstName: 'Ficha', lastName: 'de-Ana', isActive: false }),
        )
      }),
    )
    tokenStorage.setTokens(ANA.token, ANA.refresh)
    montar(OPERATIONS_BASE, { extra: <BajaDeUnTrabajador /> })
    await screen.findByText('SRV-ANA')
    await user.click(screen.getByRole('button', { name: 'Desactivar al trabajador 7' }))
    await waitFor(() => expect(pedidos).toContain(`POST /api/v1/workers/7/deactivate Bearer ${ANA.token}`))
    const pedidosAntesDeSalir = pedidos.length

    await salir(user)
    baja.abrir()
    await cuandoSeEntregue('POST /api/v1/workers/7/deactivate')

    expect(memoria()).not.toContain('de-Ana')
    expect(pedidos.slice(pedidosAntesDeSalir)).toEqual([])
    // La respuesta no se entregó, pero la mutación ya no está en la caché: un useIsMutating de la
    // sesión siguiente no la cuenta como guardando para siempre.
    expect(queryClient.isMutating()).toBe(0)
  })

  it('el cambio de clave que vuelve después de salir no avisa ni navega al que entró', async () => {
    const user = userEvent.setup()
    const cambio = compuerta()
    server.use(
      http.post(`${API}/auth/change-password`, async () => {
        await cambio.abierta
        return new HttpResponse(null, { status: 204 })
      }),
    )
    tokenStorage.setTokens(ANA.token, ANA.refresh)
    const router = montar(CHANGE_PASSWORD_PATH)
    await cambiarLaClave(user, 'Clave-nueva-de-Ana-2')
    await waitFor(() => expect(pedidos.some((pedido) => pedido.startsWith('POST /api/v1/auth/change-password'))).toBe(true))

    await salir(user)
    await entrar(user, BETO)
    await screen.findByText('SRV-BETO')
    cambio.abrir()
    await cuandoSeEntregue('POST /api/v1/auth/change-password')

    expect(screen.queryByText('Contraseña actualizada')).not.toBeInTheDocument()
    expect(router.state.location.pathname).toBe(landingPathFor(BETO.user.role))
  })

  it('la contraseña del ingreso no queda en la caché de mutaciones', async () => {
    const user = userEvent.setup()
    const router = montar(LOGIN_PATH)

    await entrar(user, ANA)

    await waitFor(() => expect(router.state.location.pathname).toBe(landingPathFor(ANA.user.role)))
    await waitFor(() => expect(memoria()).not.toContain(ANA.password))
  })

  it('las contraseñas del cambio de clave tampoco quedan en la caché de mutaciones', async () => {
    const user = userEvent.setup()
    tokenStorage.setTokens(ANA.token, ANA.refresh)
    const router = montar(CHANGE_PASSWORD_PATH)

    await cambiarLaClave(user, 'Clave-nueva-de-Ana-2')

    await waitFor(() => expect(router.state.location.pathname).toBe(landingPathFor(ANA.user.role)))
    await waitFor(() => expect(memoria()).not.toContain('Clave-nueva-de-Ana-2'))
    expect(memoria()).not.toContain(ANA.password)
  })

  it('la sesión que expira estando ya en el ingreso también borra todo, sin recargar', async () => {
    window.history.replaceState(null, '', LOGIN_PATH)
    const recarga = vi.spyOn(window.location, 'assign').mockImplementation(() => {})
    tokenStorage.setTokens('tok-desconocido', 'ref-desconocido')
    const router = montar(LOGIN_PATH, {
      antes: () => queryClient.setQueryData(['lo', 'que', 'dejo', 'ana'], pageOfServices([SERVICIO_DE_ANA])),
    })
    expect(memoria()).toContain('SRV-ANA')

    await waitFor(() => expect(entregadas).toContain('POST /api/v1/auth/refresh'))

    await waitFor(() => expect(memoria()).not.toContain('SRV-ANA'))
    expect(tokenStorage.getAccessToken()).toBeNull()
    expect(tokenStorage.getRefreshToken()).toBeNull()
    expect(recarga).not.toHaveBeenCalled()
    expect(router.state.location.pathname).toBe(LOGIN_PATH)
    expect(screen.getByRole('heading', { name: 'Iniciar sesión' })).toBeInTheDocument()
  })

  it('un ingreso en vuelo cuando expira la sesión vieja se completa: abre una sesión, no es de la que terminó', async () => {
    const user = userEvent.setup()
    window.history.replaceState(null, '', LOGIN_PATH)
    vi.spyOn(window.location, 'assign').mockImplementation(() => {})
    const renovacion = compuerta()
    const ingreso = compuerta()
    server.use(
      http.post(`${API}/auth/refresh`, async () => {
        await renovacion.abierta
        return new HttpResponse(null, { status: 401 })
      }),
      http.post(`${API}/auth/login`, async () => {
        await ingreso.abierta
        return HttpResponse.json(sesionDe(BETO.user, BETO.token, BETO.refresh))
      }),
    )
    tokenStorage.setTokens('tok-desconocido', 'ref-desconocido')
    const router = montar(LOGIN_PATH)
    await waitFor(() => expect(pedidos.some((pedido) => pedido.startsWith('POST /api/v1/auth/refresh'))).toBe(true))
    await entrar(user, BETO)
    await waitFor(() => expect(pedidos.some((pedido) => pedido.startsWith('POST /api/v1/auth/login'))).toBe(true))

    renovacion.abrir()
    await cuandoSeEntregue('POST /api/v1/auth/refresh')
    ingreso.abrir()

    await waitFor(() => expect(router.state.location.pathname).toBe(landingPathFor(BETO.user.role)))
    expect(tokenStorage.getAccessToken()).toBe(BETO.token)
  })

  it('la sesión que expira en el ingreso no cierra el aviso de quien está entrando', async () => {
    const user = userEvent.setup()
    window.history.replaceState(null, '', LOGIN_PATH)
    vi.spyOn(window.location, 'assign').mockImplementation(() => {})
    const renovacion = compuerta()
    server.use(
      http.post(`${API}/auth/refresh`, async () => {
        await renovacion.abierta
        return new HttpResponse(null, { status: 401 })
      }),
    )
    tokenStorage.setTokens('tok-desconocido', 'ref-desconocido')
    montar(LOGIN_PATH)
    await waitFor(() => expect(pedidos.some((pedido) => pedido.startsWith('POST /api/v1/auth/refresh'))).toBe(true))
    await entrar(user, { ...BETO, password: 'Clave-de-Beto-2' })
    expect(await screen.findByText('Usuario o contraseña incorrectos.')).toBeInTheDocument()

    renovacion.abrir()
    await cuandoSeEntregue('POST /api/v1/auth/refresh')
    await act(() => new Promise((resolve) => setTimeout(resolve, 400)))

    expect(tokenStorage.getAccessToken()).toBeNull()
    expect(screen.getByText('Usuario o contraseña incorrectos.')).toBeInTheDocument()
  })

  it('la sesión que expira en otra pantalla recarga al ingreso, como antes', async () => {
    window.history.replaceState(null, '', OPERATIONS_BASE)
    const recarga = vi.spyOn(window.location, 'assign').mockImplementation(() => {})
    tokenStorage.setTokens('tok-desconocido', 'ref-desconocido')
    montar(OPERATIONS_BASE)

    await waitFor(() => expect(recarga).toHaveBeenCalledWith(LOGIN_PATH))
    expect(recarga).toHaveBeenCalledTimes(1)
    expect(tokenStorage.getAccessToken()).toBeNull()
  })

  it('quien entra después de una salida aterriza en su inicio, no donde estaba el anterior', async () => {
    const user = userEvent.setup()
    // Sale desde una pantalla que no es el inicio de nadie: el test no depende de los inicios de cada rol.
    expect([landingPathFor(ANA.user.role), landingPathFor(BETO.user.role)]).not.toContain(CHANGE_PASSWORD_PATH)
    const router = montar(LOGIN_PATH)
    await entrar(user, BETO)
    await screen.findByText('SRV-BETO')
    await act(() => router.navigate(CHANGE_PASSWORD_PATH))
    await screen.findByLabelText('Contraseña actual')

    await salir(user)
    await entrar(user, ANA)

    await waitFor(() => expect(router.state.location.pathname).toBe(landingPathFor(ANA.user.role)))
  })

  it('la segunda salida de la pestaña también descarta lo que la sesión dejó en vuelo', async () => {
    const user = userEvent.setup()
    const baja = compuerta()
    server.use(
      http.post(`${API}/workers/:id/deactivate`, async ({ params }) => {
        await baja.abierta
        return HttpResponse.json(
          fakeWorkerDetail({ id: Number(params.id), firstName: 'Ficha', lastName: 'de-Beto', isActive: false }),
        )
      }),
    )
    const router = montar(LOGIN_PATH, { extra: <BajaDeUnTrabajador /> })
    await entrar(user, ANA)
    await waitFor(() => expect(router.state.location.pathname).toBe(landingPathFor(ANA.user.role)))
    await salir(user)
    await entrar(user, BETO)
    await screen.findByText('SRV-BETO')
    await user.click(screen.getByRole('button', { name: 'Desactivar al trabajador 7' }))
    await waitFor(() => expect(pedidos).toContain(`POST /api/v1/workers/7/deactivate Bearer ${BETO.token}`))

    await salir(user)
    await screen.findByRole('heading', { name: 'Iniciar sesión' })
    // Se suelta antes de que entre nadie: así lo descarta la salida, no el ingreso siguiente.
    baja.abrir()
    await cuandoSeEntregue('POST /api/v1/workers/7/deactivate')

    expect(memoria()).not.toContain('de-Beto')
  })

  it('salir cierra los avisos abiertos', async () => {
    const user = userEvent.setup()
    tokenStorage.setTokens(ANA.token, ANA.refresh)
    montar(CHANGE_PASSWORD_PATH)
    await cambiarLaClave(user, 'Clave-nueva-de-Ana-2')
    expect(await screen.findByText('Contraseña actualizada')).toBeInTheDocument()

    await salir(user)

    await waitFor(() => expect(screen.queryByText('Contraseña actualizada')).not.toBeInTheDocument())
  })

  /**
   * Estructural, como las del tema: afirma que el arranque use el mismo cableado que montan estos
   * tests. Medido en la revisión: con main.tsx de vuelta al callback viejo, la suite entera seguía
   * en verde y la sesión expirada en el ingreso no borraba nada.
   */
  it('el arranque conecta el cliente HTTP con la rutina de fin de sesión', () => {
    const main = readFileSync(join(process.cwd(), 'src', 'main.tsx'), 'utf8')
    expect(main).toMatch(/^configureSessionHttpClient\(queryClient\)$/m)
    expect(main).not.toMatch(/\bconfigureHttpClient\(/)
  })

  it('el destino se guarda hasta que termina la primera sesión de la pestaña (entrar no cuenta), y desde ahí ya no', async () => {
    vi.resetModules()
    const session = await import('./session')
    expect(session.hasSessionEndedHere()).toBe(false)
    session.beginSession()
    expect(session.hasSessionEndedHere()).toBe(false)

    session.endSession(new QueryClient())

    expect(session.hasSessionEndedHere()).toBe(true)
  })
})
