import type { ReactNode } from 'react'
import { createElement } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useUpdateService } from './useUpdateService'
import { operationsKeys } from '../queryKeys'
import { server } from '../../../test/mocks/server'
import {
  DEFAULT_SERVICE_ETAG,
  ETAG_AFTER_WRITE,
  fakeServiceDetail,
  updateServiceCapture,
  updateServiceEmptyBody,
  updateServiceError,
  type ChangeStatusCaptureSink,
} from '../../../test/mocks/handlers/operations'
import type { ServiceWithEtag } from './useService'
import type { ServiceUpdateRequest } from '../../../api'

const SERVICE_ID = 77

const BODY: ServiceUpdateRequest = {
  tentativeDate: '2026-09-10',
  origin: 'Piura',
  destination: 'Trujillo',
  weightKg: 28000,
  lengthM: null,
  widthM: null,
  heightM: null,
  price: 4500,
  currencyId: 2,
  observations: null,
  justification: 'Corrijo el destino que vino mal del cliente',
}

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
  const { result } = renderHook(() => useUpdateService(SERVICE_ID), { wrapper })
  const detail = () =>
    queryClient.getQueryData<ServiceWithEtag>(operationsKeys.serviceDetail(SERVICE_ID))
  return { queryClient, result, detail }
}

describe('useUpdateService, el pedido', () => {
  it('manda el cuerpo tal cual se lo pasan, al viaje que se le pidió', async () => {
    const sink: ChangeStatusCaptureSink = {}
    server.use(updateServiceCapture(sink))
    const { result } = setup()

    result.current.mutate({ ifMatch: DEFAULT_SERVICE_ETAG, body: BODY })

    await waitFor(() => expect(sink.bodies).toHaveLength(1))
    expect(sink.bodies?.[0]).toEqual(BODY)
    // La URL y no solo el cuerpo: con el id mal armado el handler igual respondería.
    expect(sink.urls?.[0]).toContain(`/services/${SERVICE_ID}`)
  })

  it('reenvía el ETag tal cual, comillas incluidas', async () => {
    const sink: ChangeStatusCaptureSink = {}
    server.use(updateServiceCapture(sink))
    const { result } = setup()

    result.current.mutate({ ifMatch: DEFAULT_SERVICE_ETAG, body: BODY })

    await waitFor(() => expect(sink.ifMatches).toHaveLength(1))
    expect(sink.ifMatches?.[0]).toBe(DEFAULT_SERVICE_ETAG)
  })

  it('manda el header vacío cuando no hay ETag, en vez de omitirlo', async () => {
    // El contrato lo exige, así que la respuesta va a ser 412 y eso es lo buscado: que el
    // servidor deje de exponer el header es un problema de configuración, no un estado del
    // viaje, y frenar el envío escondería un camino que el servidor sí atiende.
    const sink: ChangeStatusCaptureSink = {}
    server.use(updateServiceCapture(sink))
    const { result } = setup()

    result.current.mutate({ ifMatch: null, body: BODY })

    await waitFor(() => expect(sink.ifMatches).toHaveLength(1))
    expect(sink.ifMatches?.[0]).toBe('')
  })
})

describe('useUpdateService, lo que pasa después', () => {
  it('escribe el detalle con la respuesta y su ETag nuevo', async () => {
    const editado = fakeServiceDetail({ destination: 'Trujillo' })
    server.use(updateServiceCapture({}, editado, ETAG_AFTER_WRITE))
    const { result, detail } = setup()

    result.current.mutate({ ifMatch: DEFAULT_SERVICE_ETAG, body: BODY })

    await waitFor(() => expect(detail()?.destination).toBe('Trujillo'))
    // El ETag viejo serviría para un envío más y devolvería 412 sin que nadie entienda
    // por qué, así que lo que importa es que se REEMPLAZÓ.
    expect(detail()?._etag).toBe(ETAG_AFTER_WRITE)
  })

  it('invalida el listado y los indicadores, que muestran lo que se editó', async () => {
    server.use(updateServiceCapture({}))
    const { queryClient, result } = setup()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    result.current.mutate({ ifMatch: DEFAULT_SERVICE_ETAG, body: BODY })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    const claves = invalidate.mock.calls.map(([args]) => JSON.stringify(args?.queryKey))
    expect(claves).toContain(JSON.stringify(operationsKeys.serviceLists()))
    expect(claves).toContain(JSON.stringify(operationsKeys.serviceStats()))
  })

  it('no deja el detalle a medias si el servidor contesta 200 con el cuerpo vacío', async () => {
    // El cliente generado entrega `{}` y no `null`, así que una guarda por el objeto
    // entero no dispara y el cache se queda con un viaje sin código ni estado, que
    // revienta al renderizarlo lejos de acá.
    server.use(updateServiceEmptyBody())
    const { result, detail } = setup()

    result.current.mutate({ ifMatch: DEFAULT_SERVICE_ETAG, body: BODY })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(detail()?.code).toBe('SRV-0077')
  })

  it('deja pasar el error con su código para que la pantalla lo explique', async () => {
    server.use(updateServiceError(409, { code: 'OPS-004', detail: 'El viaje está cancelado.' }))
    const { result } = setup()

    result.current.mutate({ ifMatch: DEFAULT_SERVICE_ETAG, body: BODY })

    await waitFor(() => expect(result.current.isError).toBe(true))
    // Con `throwOnError: false` el error llegaría como una respuesta más y la pantalla no
    // podría distinguir un 409 de un 412.
    const error = result.current.error as { response?: { status?: number; data?: { code?: string } } }
    expect(error.response?.status).toBe(409)
    expect(error.response?.data?.code).toBe('OPS-004')
  })

  it('no toca el detalle cuando la edición falla', async () => {
    server.use(updateServiceError(412, { code: 'COM-004' }))
    const { result, detail } = setup()

    result.current.mutate({ ifMatch: 'W/"vieja"', body: BODY })

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(detail()?._etag).toBe(DEFAULT_SERVICE_ETAG)
  })
})
