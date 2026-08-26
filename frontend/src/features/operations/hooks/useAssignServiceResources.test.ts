import type { ReactNode } from 'react'
import { createElement } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useAssignServiceResources } from './useAssignServiceResources'
import { operationsKeys } from '../queryKeys'
import { server } from '../../../test/mocks/server'
import {
  DEFAULT_SERVICE_ETAG,
  ETAG_AFTER_WRITE,
  assignResourcesCapture,
  assignResourcesConflict,
  assignResourcesOk,
  fakeServiceDetail,
  type AssignCaptureSink,
} from '../../../test/mocks/handlers/operations'
import type { ServiceWithEtag } from './useService'

const SERVICE_ID = 77

/** El cuerpo con los tres recursos distintos entre sí. */
const BODY = { driverId: 4, tractorId: 7, trailerId: 3, note: 'Sale a las 05:00', force: false }

function setup() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  // El detalle arranca en el cache como lo dejó el GET, con SU ETag: así se puede
  // afirmar que la escritura lo reemplaza y no que lo escribe por primera vez.
  queryClient.setQueryData(operationsKeys.serviceDetail(SERVICE_ID), {
    ...fakeServiceDetail(),
    _etag: DEFAULT_SERVICE_ETAG,
  })
  const wrapper = ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: queryClient }, children)
  const { result } = renderHook(() => useAssignServiceResources(SERVICE_ID), { wrapper })
  const detail = () =>
    queryClient.getQueryData<ServiceWithEtag>(operationsKeys.serviceDetail(SERVICE_ID))
  return { queryClient, result, detail }
}

describe('useAssignServiceResources', () => {
  it('manda el cuerpo tal cual se lo pasan', async () => {
    const sink: AssignCaptureSink = {}
    server.use(assignResourcesCapture(sink))
    const { result } = setup()

    result.current.mutate(BODY)

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    // El objeto ENTERO: campo por campo no vería un campo de más viajando al backend.
    expect(sink.bodies?.[0]).toEqual(BODY)
  })

  it('pega el id del viaje en la URL y no en el cuerpo', async () => {
    let capturedId: string | undefined
    server.use(
      http.post('http://localhost:8080/api/v1/services/:id/assignment', ({ params }) => {
        capturedId = params.id as string
        return HttpResponse.json(fakeServiceDetail({ status: 'PENDING_START' }), {
          status: 200,
          headers: { ETag: ETAG_AFTER_WRITE },
        })
      }),
    )
    const { result } = setup()

    result.current.mutate(BODY)

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(capturedId).toBe('77')
    expect(BODY).not.toHaveProperty('id')
  })

  it('guarda el ETag de la respuesta de ESCRITURA, no el que ya estaba', async () => {
    server.use(assignResourcesOk())
    const { result, detail } = setup()
    expect(detail()?._etag).toBe(DEFAULT_SERVICE_ETAG)

    result.current.mutate(BODY)

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    // Los dos valores son distintos a propósito: con el mismo, quedarse con el viejo
    // sería un no-op y el próximo `If-Match` viajaría con una versión que la base ya
    // no tiene, que vuelve como un 412 que nadie sabe explicar.
    expect(detail()?._etag).toBe(ETAG_AFTER_WRITE)
    expect(detail()?._etag).not.toBe(DEFAULT_SERVICE_ETAG)
  })

  it('deja el detalle nuevo en el cache sin volver a pedirlo', async () => {
    let detailRequests = 0
    server.use(
      http.get('http://localhost:8080/api/v1/services/:id', () => {
        detailRequests += 1
        return HttpResponse.json(fakeServiceDetail(), {
          headers: { ETag: DEFAULT_SERVICE_ETAG },
        })
      }),
      assignResourcesOk(),
    )
    const { result, detail } = setup()

    result.current.mutate(BODY)

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(detail()?.status).toBe('PENDING_START')
    // El endpoint devuelve el detalle completo: refetchearlo es una consulta de más y
    // una ventana en la que la pantalla muestra lo viejo.
    expect(detailRequests).toBe(0)
  })

  it('invalida el listado y los indicadores, y nada más', async () => {
    server.use(assignResourcesOk())
    const { queryClient, result } = setup()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    result.current.mutate(BODY)

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    // Se CUENTA, no solo se afirma que están: con `toHaveBeenCalledWith` suelto, una
    // invalidación de toda la rama del módulo (que tira abajo media pantalla) pasaría
    // igual. Asignar mueve la fila del listado y dos contadores del tablero, y nada
    // más que eso.
    expect(invalidate).toHaveBeenCalledTimes(2)
    expect(invalidate).toHaveBeenCalledWith({ queryKey: operationsKeys.serviceLists() })
    expect(invalidate).toHaveBeenCalledWith({ queryKey: operationsKeys.serviceStats() })
  })

  it('un conflicto llega como error con su Problem entero', async () => {
    server.use(assignResourcesConflict())
    const { result } = setup()

    result.current.mutate(BODY)

    await waitFor(() => expect(result.current.isError).toBe(true))
    const problem = (result.current.error as { response?: { data?: Record<string, unknown> } })
      .response?.data
    // El hook NO desarma el Problem: sin el código no se puede distinguir el conflicto
    // forzable del duro, que es la decisión central de la pantalla.
    expect(problem?.code).toBe('OPS-002')
    expect(problem?.forcible).toBe(true)
    expect(problem?.conflicts).toHaveLength(1)
  })

  it('un conflicto no toca el detalle que ya estaba en el cache', async () => {
    server.use(assignResourcesConflict())
    const { result, detail } = setup()

    result.current.mutate(BODY)

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(detail()?.status).toBe('PENDING_ASSIGNMENT')
    expect(detail()?._etag).toBe(DEFAULT_SERVICE_ETAG)
  })

  it('una respuesta 200 sin cuerpo no se traga en silencio', async () => {
    server.use(
      http.post(
        'http://localhost:8080/api/v1/services/:id/assignment',
        () => new HttpResponse(null, { status: 200, headers: { ETag: ETAG_AFTER_WRITE } }),
      ),
    )
    const { result } = setup()

    result.current.mutate(BODY)

    // Sin este caso, la guarda del cuerpo vacío se puede borrar sin que nada avise, y
    // la pantalla cerraría el modal anunciando un éxito que no escribió nada.
    await waitFor(() => expect(result.current.isError).toBe(true))
  })

  it('sin el header ETag invalida el detalle en vez de degradarlo', async () => {
    server.use(
      http.post('http://localhost:8080/api/v1/services/:id/assignment', () =>
        HttpResponse.json(fakeServiceDetail({ status: 'PENDING_START' }), { status: 200 }),
      ),
    )
    const { queryClient, result, detail } = setup()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    result.current.mutate(BODY)

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    // Escribir el cuerpo sin ETag dejaría el detalle PEOR que antes: el GET sí lo
    // traía. Pasa de verdad si el gateway deja de exponer el header.
    expect(detail()?._etag).toBe(DEFAULT_SERVICE_ETAG)
    expect(invalidate).toHaveBeenCalledWith({
      queryKey: operationsKeys.serviceDetail(SERVICE_ID),
    })
  })
})
