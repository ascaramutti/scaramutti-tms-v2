import type { ReactNode } from 'react'
import { createElement } from 'react'
import { describe, expect, it } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { useServiceCurrencies } from './useServiceCurrencies'
import { useCurrencies } from '../../catalogs/hooks/useCurrencies'
import { server } from '../../../test/mocks/server'
import { fakeCurrency } from '../../../test/mocks/handlers/catalogs'

const API = '*/api/v1'

function setup() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: queryClient }, children)
  return renderHook(() => useServiceCurrencies(), { wrapper })
}

/** El catálogo como lo sirve el backend: filtrado por activas o completo. */
function currenciesByFilter() {
  return http.get(`${API}/currencies`, ({ request }) => {
    const activas = new URL(request.url).searchParams.get('isActive') === 'true'
    const todas = [
      fakeCurrency({ id: 2, code: 'PEN' }),
      fakeCurrency({ id: 9, code: 'ARS', isActive: false }),
    ]
    return HttpResponse.json(activas ? todas.filter((c) => c.isActive) : todas)
  })
}

describe('useServiceCurrencies', () => {
  it('pide el catálogo SIN filtrar por activas', async () => {
    /*
     * Este es el único caso que distingue este hook del compartido, así que es el que
     * tiene que morir si alguien los unifica agregando `isActive: true`.
     *
     * Se afirma sobre la URL pedida y no sobre lo que devuelve el servidor falso, porque
     * el handler contesta lo mismo con filtro y sin él: mirar la respuesta mediría al
     * doble, no al hook.
     */
    const urls: string[] = []
    server.use(
      http.get(`${API}/currencies`, ({ request }) => {
        urls.push(request.url)
        return HttpResponse.json([fakeCurrency()])
      }),
    )
    const { result } = setup()

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(urls).toHaveLength(1)
    expect(urls[0]).not.toContain('isActive')
  })

  it('entrega también las monedas dadas de baja', async () => {
    // Es el viaje viejo que el contrato protege: se sigue editando mientras no se le
    // cambie la moneda, y para eso su moneda tiene que estar en la lista.
    server.use(
      http.get(`${API}/currencies`, () =>
        HttpResponse.json([
          fakeCurrency({ id: 2, code: 'PEN' }),
          fakeCurrency({ id: 9, code: 'ARS', isActive: false }),
        ]),
      ),
    )
    const { result } = setup()

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.map((currency) => currency.code)).toEqual(['PEN', 'ARS'])
  })

  it('no comparte el cache con el catálogo del alta', async () => {
    /*
     * La key propia es lo único que separa las dos listas, y el riesgo es asimétrico: si
     * el alta monta primero, siembra las ACTIVAS bajo esa key, y la edición de un viaje
     * con moneda dada de baja recibe una lista que no la tiene. Ahí la resolución falla y
     * la pantalla no abre, que es exactamente lo que este hook existe para evitar.
     *
     * Los dos hooks sobre el MISMO cliente y con respuestas DISTINTAS según el filtro:
     * con un doble que contesta lo mismo en los dos casos, apuntar la key a la del alta
     * sobreviviría igual.
     */
    server.use(currenciesByFilter())
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const wrapper = ({ children }: { children: ReactNode }) =>
      createElement(QueryClientProvider, { client: queryClient }, children)

    const alta = renderHook(() => useCurrencies(), { wrapper })
    await waitFor(() => expect(alta.result.current.isSuccess).toBe(true))
    expect(alta.result.current.data?.map((c) => c.code)).toEqual(['PEN'])

    const edicion = renderHook(() => useServiceCurrencies(), { wrapper })
    await waitFor(() => expect(edicion.result.current.isSuccess).toBe(true))
    expect(edicion.result.current.data?.map((c) => c.code)).toEqual(['PEN', 'ARS'])
  })

  it('revienta si el servidor contesta sin cuerpo', async () => {
    // Sin esta guarda el formulario abriría con el catálogo vacío y la resolución de la
    // moneda del viaje fallaría más lejos, con un mensaje que no nombra la causa.
    server.use(http.get(`${API}/currencies`, () => HttpResponse.json(null)))
    const { result } = setup()

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect((result.current.error as Error).message).toContain('GET /currencies')
  })
})
