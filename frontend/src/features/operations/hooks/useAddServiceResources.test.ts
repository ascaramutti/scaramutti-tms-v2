import type { ReactNode } from 'react'
import { createElement } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useAddServiceResources } from './useAddServiceResources'
import { operationsKeys } from '../queryKeys'
import { server } from '../../../test/mocks/server'
import {
  DEFAULT_SERVICE_ETAG,
  ETAG_AFTER_WRITE,
  addResourcesCapture,
  addResourcesConflict,
  addResourcesDuplicate,
  addResourcesOk,
  fakeAdditionalResource,
  fakeServiceDetail,
  type AddResourcesCaptureSink,
} from '../../../test/mocks/handlers/operations'
import type { ServiceWithEtag } from './useService'

const SERVICE_ID = 77
const BODY = {
  driverId: 8,
  tractorId: null,
  trailerId: null,
  reason: 'Relevo por descanso reglamentario del conductor principal',
  force: false,
}

/**
 * El viaje arranca EN RUTA y con sus recursos principales puestos, distintos de los
 * del refuerzo: así se puede afirmar que sumar un refuerzo no los pisa.
 */
function inProgressDetail() {
  return fakeServiceDetail({
    status: 'IN_PROGRESS',
    driver: { id: 4, fullName: 'Juan Pérez Huamán' },
    tractor: { kind: 'TRACTOR', id: 7, plate: 'T7A-701' },
    trailer: { kind: 'TRAILER', id: 3, plate: 'R3C-303' },
  })
}

function setup() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  queryClient.setQueryData(operationsKeys.serviceDetail(SERVICE_ID), {
    ...inProgressDetail(),
    _etag: DEFAULT_SERVICE_ETAG,
  })
  const wrapper = ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: queryClient }, children)
  const { result } = renderHook(() => useAddServiceResources(SERVICE_ID), { wrapper })
  const detail = () =>
    queryClient.getQueryData<ServiceWithEtag>(operationsKeys.serviceDetail(SERVICE_ID))
  return { queryClient, result, detail }
}

describe('useAddServiceResources', () => {
  it('manda el cuerpo tal cual se lo pasan', async () => {
    const sink: AddResourcesCaptureSink = {}
    server.use(addResourcesCapture(sink))
    const { result } = setup()

    result.current.mutate(BODY)

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(sink.bodies?.[0]).toEqual(BODY)
  })

  it('deja el detalle nuevo en el cache con el ETag de la escritura', async () => {
    server.use(addResourcesOk())
    const { result, detail } = setup()

    result.current.mutate(BODY)

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(detail()?._etag).toBe(ETAG_AFTER_WRITE)
    expect(detail()?.additionalResources).toHaveLength(1)
  })

  it('no invalida nada: un refuerzo no mueve el listado ni los indicadores', async () => {
    server.use(addResourcesOk())
    const { queryClient, result } = setup()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    result.current.mutate(BODY)

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    // Es lo CONSTANTE, y por eso hay que afirmarlo: la fila del listado publica solo
    // los recursos principales, el viaje no cambia de estado y los contadores del
    // tablero cuentan conductores y tractos principales. Invalidar serían dos
    // consultas por algo que no pudo haber cambiado, y una suite de delta no lo ve.
    expect(invalidate).not.toHaveBeenCalled()
  })

  it('el refuerzo se suma sin pisar a los principales', async () => {
    server.use(
      addResourcesOk(
        fakeServiceDetail({
          ...inProgressDetail(),
          additionalResources: [fakeAdditionalResource()],
        }),
      ),
    )
    const { result, detail } = setup()

    result.current.mutate(BODY)

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    // Principal y refuerzo NUNCA comparten valor: con los mismos recursos en los dos
    // lados, pisar uno con otro sería un no-op invisible.
    expect(detail()?.driver?.fullName).toBe('Juan Pérez Huamán')
    expect(detail()?.tractor?.plate).toBe('T7A-701')
    expect(detail()?.additionalResources[0].driver?.fullName).toBe('Ana Ríos Chávez')
    expect(detail()?.status).toBe('IN_PROGRESS')
  })

  it('el conflicto duro llega PELADO, sin forzable ni conflictos', async () => {
    server.use(addResourcesDuplicate())
    const { result } = setup()

    result.current.mutate(BODY)

    await waitFor(() => expect(result.current.isError).toBe(true))
    const problem = (result.current.error as { response?: { data?: Record<string, unknown> } })
      .response?.data
    expect(problem?.code).toBe('OPS-003')
    // Los dos negativos, afirmados y no supuestos: son la única señal de que este 409
    // no se puede forzar, y llegan como AUSENCIA, no como `false`.
    expect(problem?.forcible).toBeUndefined()
    expect(problem?.conflicts).toBeUndefined()
  })

  it('el conflicto forzable llega con su bandera y su lista', async () => {
    server.use(addResourcesConflict())
    const { result } = setup()

    result.current.mutate(BODY)

    await waitFor(() => expect(result.current.isError).toBe(true))
    const problem = (result.current.error as { response?: { data?: Record<string, unknown> } })
      .response?.data
    // El par con el caso anterior es lo que da sentido a los dos: el mismo endpoint,
    // el mismo 409, y lo único que los separa es el código y la bandera.
    expect(problem?.code).toBe('OPS-002')
    expect(problem?.forcible).toBe(true)
  })
})
