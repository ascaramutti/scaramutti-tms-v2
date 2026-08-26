import type { ReactNode } from 'react'
import { createElement } from 'react'
import { describe, expect, it } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useService } from './useService'
import { server } from '../../../test/mocks/server'
import {
  DEFAULT_SERVICE_ETAG,
  fakeServiceDetail,
  serviceDetailOk,
  serviceDetailWithoutEtag,
} from '../../../test/mocks/handlers/operations'

function setup(id: number) {
  // Sin `retry: false` acá: la política de reintentos es la que el hook define y
  // este archivo la mide, así que pisarla desde el cliente la volvería invisible.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retryDelay: 1 } } })
  const wrapper = ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: queryClient }, children)
  return renderHook(() => useService(id), { wrapper })
}

describe('useService', () => {
  it('guarda el ETag del HEADER, no el `updatedAt` del cuerpo', async () => {
    // El fixture los hace CASI iguales a propósito: mismo instante, y el cuerpo con
    // el cero final de los microsegundos ya recortado por Jackson. Es el error que
    // el contrato advierte, un If-Match armado desde `updatedAt` que devuelve un
    // 412 espurio, y solo se caza con dos valores que se parezcan.
    server.use(serviceDetailOk())
    const { result } = setup(77)

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?._etag).toBe(DEFAULT_SERVICE_ETAG)
    // Contra el valor RECONSTRUIDO, que es lo que alguien escribiría al armarlo del
    // cuerpo: entrecomillar el `updatedAt`. Comparar contra el crudo no mediría
    // nada, porque las comillas ya los harían distintos.
    expect(result.current.data?._etag).not.toBe(`"${result.current.data?.updatedAt}"`)
  })

  it('deja el ETag en null si la respuesta no lo trae, en vez de inventarlo', async () => {
    // Pasa con un gateway que no expone el header. Es preferible no tenerlo (la
    // escritura pedirá recargar) a mandar uno armado del cuerpo, que falla igual
    // pero sin decir por qué.
    server.use(serviceDetailWithoutEtag())
    const { result } = setup(77)

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?._etag).toBeNull()
  })

  it('devuelve el detalle completo junto con el ETag', async () => {
    server.use(serviceDetailOk(fakeServiceDetail({ code: 'SRV-0123' })))
    const { result } = setup(123)

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.code).toBe('SRV-0123')
    expect(result.current.data?.events).toEqual([])
  })

  it('no confunde dos viajes distintos en el mismo cache', async () => {
    // Si la clave de cache perdiera el id, abrir un viaje y después otro mostraría
    // el primero hasta que refresque. Los dos hooks comparten el `QueryClient` a
    // propósito: con uno fresco por caso, ese defecto no se puede ver.
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const wrapper = ({ children }: { children: ReactNode }) =>
      createElement(QueryClientProvider, { client: queryClient }, children)

    server.use(serviceDetailOk(fakeServiceDetail({ id: 77, code: 'SRV-0077' })))
    const first = renderHook(() => useService(77), { wrapper })
    await waitFor(() => expect(first.result.current.isSuccess).toBe(true))

    server.use(serviceDetailOk(fakeServiceDetail({ id: 91, code: 'SRV-0091' })))
    const second = renderHook(() => useService(91), { wrapper })
    await waitFor(() => expect(second.result.current.isSuccess).toBe(true))

    expect(second.result.current.data?.code).toBe('SRV-0091')
    expect(first.result.current.data?.code).toBe('SRV-0077')
  })

  it('reintenta una vez ante un 500, y no reintenta ante un 404', async () => {
    // Las dos mitades de la misma regla, contando requests: sin esto, `retry: false`
    // deja la suite entera verde y el comentario del hook queda afirmando un
    // comportamiento que nadie mide.
    let calls = 0
    server.use(
      http.get('http://localhost:8080/api/v1/services/:id', () => {
        calls += 1
        return HttpResponse.json({ detail: 'Falló' }, { status: 500 })
      }),
    )
    const first = setup(77)
    await waitFor(() => expect(first.result.current.isError).toBe(true), { timeout: 3000 })
    expect(calls).toBe(2)

    calls = 0
    server.use(
      http.get('http://localhost:8080/api/v1/services/:id', () => {
        calls += 1
        return HttpResponse.json({ detail: 'No existe' }, { status: 404 })
      }),
    )
    const second = setup(91)
    await waitFor(() => expect(second.result.current.isError).toBe(true))
    expect(calls).toBe(1)
  })

  it('no le pregunta al servidor por un id que no es válido', async () => {
    const { result } = setup(0)

    await waitFor(() => expect(result.current.fetchStatus).toBe('idle'))
    expect(result.current.data).toBeUndefined()
  })
})
