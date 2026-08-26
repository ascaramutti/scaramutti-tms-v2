import type { ReactNode } from 'react'
import { createElement } from 'react'
import { describe, expect, it } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useFleetUnits } from './useFleetUnits'
import { server } from '../../test/mocks/server'
import type { ProductsCaptureSink } from '../../test/mocks/handlers/warehouse'
import { fakeFleetUnit, fleetUnitsByKind } from '../../test/mocks/handlers/shared-catalogs'

/**
 * Los tres subtipos, con placas distintas entre sí: si el filtro por subtipo no
 * se aplicara, la lista de tractos y la completa serían distinguibles a simple
 * vista. Con un fixture de un solo tipo, "trajo solo tractos" y "trajo todo"
 * serían la misma lista y ningún caso de acá mediría nada.
 */
const TRACTOR = fakeFleetUnit({ kind: 'TRACTOR', id: 5, plate: 'ABC-123' })
const TRAILER = fakeFleetUnit({ kind: 'TRAILER', id: 9, plate: 'XY-9876' })
const ESCORT = fakeFleetUnit({ kind: 'ESCORT', id: 3, plate: 'ES-100' })
const FLEET = [TRACTOR, TRAILER, ESCORT]

/** Un cliente por caso: el cache no se comparte entre pruebas. */
function wrapper() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: queryClient }, children)
}

describe('useFleetUnits', () => {
  it('sin subtipo pide solo las unidades vigentes y trae los tres subtipos', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(fleetUnitsByKind(FLEET, sink))

    const { result } = renderHook(() => useFleetUnits(), { wrapper: wrapper() })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toEqual(FLEET)
    expect(sink.params?.get('isActive')).toBe('true')
    // Ausente, no vacío: `?kind=` sin valor le llegaría al backend como un subtipo
    // que no existe, no como "todos".
    expect(sink.params?.has('kind')).toBe(false)
  })

  it('con subtipo lo manda en la consulta y trae solo ese', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(fleetUnitsByKind(FLEET, sink))

    const { result } = renderHook(() => useFleetUnits({ kind: 'TRACTOR' }), {
      wrapper: wrapper(),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(sink.params?.get('kind')).toBe('TRACTOR')
    expect(sink.params?.get('isActive')).toBe('true')
    expect(result.current.data).toEqual([TRACTOR])
  })

  it('dos subtipos distintos no comparten entrada de cache', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(fleetUnitsByKind(FLEET, sink))
    const shared = wrapper()

    const tractors = renderHook(() => useFleetUnits({ kind: 'TRACTOR' }), { wrapper: shared })
    await waitFor(() => expect(tractors.result.current.isSuccess).toBe(true))

    const trailers = renderHook(() => useFleetUnits({ kind: 'TRAILER' }), { wrapper: shared })
    await waitFor(() => expect(trailers.result.current.isSuccess).toBe(true))

    // Cada uno con SU lista, no con la del que llegó primero.
    expect(tractors.result.current.data).toEqual([TRACTOR])
    expect(trailers.result.current.data).toEqual([TRAILER])
    // Y dos consultas de verdad: con una sola key, la segunda leería del cache
    // sin salir a la red y las dos listas serían la misma.
    expect(sink.calls).toHaveLength(2)
    expect(sink.calls?.map((call) => call.get('kind'))).toEqual(['TRACTOR', 'TRAILER'])
  })

  it('quien no filtra por subtipo sigue viendo la flota entera aunque otro haya cargado un subtipo antes', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(fleetUnitsByKind(FLEET, sink))
    const shared = wrapper()

    const tractors = renderHook(() => useFleetUnits({ kind: 'TRACTOR' }), { wrapper: shared })
    await waitFor(() => expect(tractors.result.current.isSuccess).toBe(true))

    const all = renderHook(() => useFleetUnits(), { wrapper: shared })
    await waitFor(() => expect(all.result.current.isSuccess).toBe(true))

    // La contracara del caso anterior, y el bug que esta key previene: el combobox
    // del retiro de almacén ofrece las tres clases de unidad, y si compartiera
    // entrada con un campo que pide solo tractos se quedaría sin carretas ni
    // escoltas por el solo hecho de abrirse segundo.
    expect(all.result.current.data).toEqual(FLEET)
    expect(sink.calls).toHaveLength(2)
  })

  it('dos consumidores del mismo subtipo sí comparten una sola consulta', async () => {
    const sink: ProductsCaptureSink = {}
    server.use(fleetUnitsByKind(FLEET, sink))
    const shared = wrapper()

    const first = renderHook(() => useFleetUnits({ kind: 'TRACTOR' }), { wrapper: shared })
    await waitFor(() => expect(first.result.current.isSuccess).toBe(true))
    const second = renderHook(() => useFleetUnits({ kind: 'TRACTOR' }), { wrapper: shared })
    await waitFor(() => expect(second.result.current.isSuccess).toBe(true))

    // La otra dirección de la guarda: parametrizar la key no puede degenerar en
    // una entrada por montaje, o la flota se pediría de nuevo en cada apertura.
    expect(sink.calls).toHaveLength(1)
    expect(second.result.current.data).toEqual([TRACTOR])
  })
})
