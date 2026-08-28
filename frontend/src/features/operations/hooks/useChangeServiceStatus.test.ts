import type { ReactNode } from 'react'
import { createElement } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useChangeServiceStatus } from './useChangeServiceStatus'
import { operationsKeys } from '../queryKeys'
import { server } from '../../../test/mocks/server'
import {
  DEFAULT_SERVICE_ETAG,
  ETAG_AFTER_WRITE,
  changeStatusCapture,
  changeStatusConflict,
  changeStatusEmptyBody,
  changeStatusError,
  changeStatusWithoutEtag,
  fakeServiceDetail,
  serviceDetailCounted,
  type ChangeStatusCaptureSink,
} from '../../../test/mocks/handlers/operations'
import type { ServiceWithEtag } from './useService'

const SERVICE_ID = 77

const BODY = {
  target: 'IN_PROGRESS',
  dateTime: '2026-08-25T02:30:00.000Z',
  note: 'Salió sin novedad',
} as const

function setup() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  // El detalle arranca como lo dejó el GET, con SU ETag: así se puede afirmar que la
  // escritura lo reemplaza y no que lo escribe por primera vez.
  queryClient.setQueryData(operationsKeys.serviceDetail(SERVICE_ID), {
    ...fakeServiceDetail(),
    _etag: DEFAULT_SERVICE_ETAG,
  })
  const wrapper = ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: queryClient }, children)
  const { result } = renderHook(() => useChangeServiceStatus(SERVICE_ID), { wrapper })
  const detail = () =>
    queryClient.getQueryData<ServiceWithEtag>(operationsKeys.serviceDetail(SERVICE_ID))
  return { queryClient, result, detail }
}

describe('useChangeServiceStatus, el pedido', () => {
  it('manda el cuerpo tal cual se lo pasan', async () => {
    const sink: ChangeStatusCaptureSink = {}
    server.use(changeStatusCapture(sink))
    const { result } = setup()

    result.current.mutate({ ifMatch: DEFAULT_SERVICE_ETAG, body: BODY })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    // El objeto ENTERO: campo por campo no vería una clave de más viajando al servidor.
    expect(sink.bodies?.[0]).toEqual(BODY)
  })

  it('manda el ETag del header y no el `updatedAt` del cuerpo', async () => {
    const sink: ChangeStatusCaptureSink = {}
    server.use(changeStatusCapture(sink))
    const { result } = setup()

    result.current.mutate({ ifMatch: DEFAULT_SERVICE_ETAG, body: BODY })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(sink.ifMatches?.[0]).toBe(DEFAULT_SERVICE_ETAG)
    // Los dos valores son el mismo instante escrito distinto: el cuerpo trae un cero
    // final de menos porque el serializador del servidor lo recorta. Reconstruir el
    // If-Match desde ahí devuelve un 412 que nadie puede explicar.
    expect(sink.ifMatches?.[0]).not.toBe(`"${fakeServiceDetail().updatedAt}"`)
  })

  it('pega el id del viaje en la URL', async () => {
    const sink: ChangeStatusCaptureSink = {}
    server.use(changeStatusCapture(sink))
    const { result } = setup()

    result.current.mutate({ ifMatch: DEFAULT_SERVICE_ETAG, body: BODY })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(sink.urls?.[0]).toContain(`/services/${SERVICE_ID}/status`)
  })

  it('no manda el header cuando no hay ETag, y el pedido sale igual', async () => {
    // Que el servidor deje de exponer el ETag es un problema de configuración, no un
    // estado del viaje: iniciar y finalizar no lo exigen, así que siguen andando.
    const sink: ChangeStatusCaptureSink = {}
    server.use(changeStatusCapture(sink))
    const { result } = setup()

    result.current.mutate({ ifMatch: null, body: BODY })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(sink.ifMatches?.[0]).toBe(null)
    expect(sink.bodies).toHaveLength(1)
  })
})

describe('useChangeServiceStatus, lo que deja en el cache', () => {
  it('guarda el detalle de la respuesta con el ETag de ESA respuesta', async () => {
    server.use(
      changeStatusCapture({}, fakeServiceDetail({ status: 'IN_PROGRESS' }), ETAG_AFTER_WRITE),
    )
    const { result, detail } = setup()

    result.current.mutate({ ifMatch: DEFAULT_SERVICE_ETAG, body: BODY })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(detail()?.status).toBe('IN_PROGRESS')
    expect(detail()?._etag).toBe(ETAG_AFTER_WRITE)
    // Con el ETag viejo, la transición siguiente pide con una versión que la base ya
    // no tiene y vuelve 412 sin que nadie haya tocado nada.
    expect(detail()?._etag).not.toBe(DEFAULT_SERVICE_ETAG)
  })

  it('invalida el detalle en vez de degradarlo cuando la respuesta no trae ETag', async () => {
    const sink: ChangeStatusCaptureSink = {}
    server.use(changeStatusWithoutEtag(sink))
    const { queryClient, result, detail } = setup()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    result.current.mutate({ ifMatch: DEFAULT_SERVICE_ETAG, body: BODY })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    // Las dos mitades: que se haya invalidado, y que el cuerpo sin versión NO haya
    // pisado al que sí la tenía.
    expect(invalidate).toHaveBeenCalledWith({
      queryKey: operationsKeys.serviceDetail(SERVICE_ID),
    })
    // Se afirma el valor que TIENE que estar, no la ausencia del valor malo: escribir
    // el cuerpo sin `_etag` deja el campo en `undefined`, y `undefined` no es `null`,
    // así que la forma negativa pasaba con la degradación puesta. Lo que se conserva
    // es el detalle anterior, entero, hasta que la invalidación traiga el nuevo.
    expect(detail()?._etag).toBe(DEFAULT_SERVICE_ETAG)
    expect(detail()?.status).toBe(fakeServiceDetail().status)
  })

  it('no vuelve a pedir el detalle: lo escribe desde su propia respuesta', async () => {
    // Es la promesa del hook: invalidar en vez de escribir cuesta una consulta y abre
    // una ventana en la que la pantalla muestra el estado viejo.
    //
    // Lo que caza el cambio es la aserción del ESTADO: invalidando, el cache se queda
    // con el detalle viejo y nunca llega el nuevo. El contador de pedidos acompaña, y
    // se deja escrito lo que puede y lo que no: acá no hay observador montado de esa
    // query, y react-query solo refetchea las activas, así que el contador no sube ni
    // con la invalidación puesta. Sirve como cota, no como verdugo.
    const detailSink: { calls?: number } = {}
    server.use(serviceDetailCounted(detailSink), changeStatusCapture({}))
    const { result, detail } = setup()

    result.current.mutate({ ifMatch: DEFAULT_SERVICE_ETAG, body: BODY })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(detail()?.status).toBe('IN_PROGRESS')
    expect(detailSink.calls).toBe(0)
  })

  it('rechaza una respuesta sin cuerpo en vez de guardarla', async () => {
    server.use(changeStatusEmptyBody())
    const { result, detail } = setup()

    result.current.mutate({ ifMatch: DEFAULT_SERVICE_ETAG, body: BODY })

    await waitFor(() => expect(result.current.isError).toBe(true))
    // Las dos mitades: que haya fallado, y que no haya escrito basura ANTES de fallar.
    // Sin la guarda, el cache se queda con un objeto que solo tiene `_etag`.
    expect(detail()?.code).toBe(fakeServiceDetail().code)
    expect(detail()?._etag).toBe(DEFAULT_SERVICE_ETAG)
  })

  it('invalida el listado y los indicadores, y nada más', async () => {
    server.use(changeStatusCapture({}))
    const { queryClient, result } = setup()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    result.current.mutate({ ifMatch: DEFAULT_SERVICE_ETAG, body: BODY })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(invalidate).toHaveBeenCalledWith({ queryKey: operationsKeys.serviceLists() })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: operationsKeys.serviceStats() })
    // Se CUENTA, no se afirma la ausencia de una key puntual: invalidar el módulo
    // entero con una sola llamada cubre a las dos por prefijo, pasa las dos
    // afirmaciones de arriba, y de paso tira abajo el detalle recién escrito y el
    // padrón de conductores.
    expect(invalidate).toHaveBeenCalledTimes(2)
  })

  it.each([
    ['412', () => changeStatusError(412, { detail: 'El viaje cambió mientras tanto.' })],
    ['409', () => changeStatusConflict('OPS-001', 'No se puede pasar de "Completado" a "En ruta"')],
    ['403', () => changeStatusError(403, { detail: 'Tu rol no puede pedir esa transición.' })],
  ])('no toca el cache cuando el pedido falla con %s', async (_label, handler) => {
    server.use(handler())
    const { queryClient, result, detail } = setup()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    result.current.mutate({ ifMatch: DEFAULT_SERVICE_ETAG, body: BODY })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(detail()?._etag).toBe(DEFAULT_SERVICE_ETAG)
    expect(detail()?.status).toBe(fakeServiceDetail().status)
    // Escrito en `onSettled` en vez de en `onSuccess`, esto se dispararía también acá.
    expect(invalidate).not.toHaveBeenCalled()
  })
})
