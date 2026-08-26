import type { ReactNode } from 'react'
import { createElement } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useRemoveServiceResource } from './useRemoveServiceResource'
import { operationsKeys } from '../queryKeys'
import { server } from '../../../test/mocks/server'
import {
  DEFAULT_SERVICE_ETAG,
  ETAG_AFTER_WRITE,
  fakeAdditionalResource,
  fakeServiceDetail,
  removeResourceCapture,
  removeResourceConflict,
  removeResourceNotFound,
  removeResourceOk,
  type RemoveResourceCaptureSink,
} from '../../../test/mocks/handlers/operations'
import type { ServiceWithEtag } from './useService'

/** El id del viaje y el del refuerzo son DISTINTOS: invertidos, la URL cambia. */
const SERVICE_ID = 77
const ASSIGNMENT_ID = 51
const OTHER_ASSIGNMENT_ID = 52

const TWO_REINFORCEMENTS = [
  fakeAdditionalResource(),
  fakeAdditionalResource({
    id: OTHER_ASSIGNMENT_ID,
    driver: { id: 15, fullName: 'Luis Quispe Mamani' },
    tractor: null,
    trailer: { kind: 'TRAILER', id: 9, plate: 'Z9D-909' },
  }),
]

function setup() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  queryClient.setQueryData(operationsKeys.serviceDetail(SERVICE_ID), {
    ...fakeServiceDetail({ status: 'IN_PROGRESS', additionalResources: TWO_REINFORCEMENTS }),
    _etag: DEFAULT_SERVICE_ETAG,
  })
  const wrapper = ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: queryClient }, children)
  const { result } = renderHook(() => useRemoveServiceResource(SERVICE_ID), { wrapper })
  const detail = () =>
    queryClient.getQueryData<ServiceWithEtag>(operationsKeys.serviceDetail(SERVICE_ID))
  return { queryClient, result, detail }
}

describe('useRemoveServiceResource', () => {
  it('pega los DOS ids en la URL, cada uno en su lugar', async () => {
    const sink: RemoveResourceCaptureSink = {}
    server.use(removeResourceCapture(sink))
    const { result } = setup()

    result.current.mutate(ASSIGNMENT_ID)

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    // Los dos ids son distintos a propósito: con el mismo valor en los dos, invertir
    // el orden sería un no-op invisible.
    expect(sink.calls?.[0]).toEqual({ id: '77', assignmentId: '51' })
  })

  it('consume el 200 con su detalle, y queda el refuerzo que corresponde', async () => {
    server.use(
      removeResourceOk(
        fakeServiceDetail({
          status: 'IN_PROGRESS',
          additionalResources: [TWO_REINFORCEMENTS[1]],
        }),
      ),
    )
    const { result, detail } = setup()

    result.current.mutate(ASSIGNMENT_ID)

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    // Se afirma QUIÉN quedó, no cuántos: con dos refuerzos indistinguibles, borrar el
    // equivocado no movería el número.
    expect(detail()?.additionalResources).toHaveLength(1)
    expect(detail()?.additionalResources[0].id).toBe(OTHER_ASSIGNMENT_ID)
    expect(detail()?.additionalResources[0].driver?.fullName).toBe('Luis Quispe Mamani')
  })

  it('guarda el ETag nuevo, que es para lo que el endpoint devuelve el detalle', async () => {
    server.use(removeResourceOk())
    const { result, detail } = setup()

    result.current.mutate(ASSIGNMENT_ID)

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    // El borrado mueve la versión del viaje: con un 204 el cliente se quedaría con una
    // que la base ya no tiene, y su próximo `If-Match` comería un 412 espurio.
    expect(detail()?._etag).toBe(ETAG_AFTER_WRITE)
    expect(detail()?._etag).not.toBe(DEFAULT_SERVICE_ETAG)
  })

  it('no invalida el listado ni los indicadores', async () => {
    server.use(removeResourceOk())
    const { queryClient, result } = setup()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    result.current.mutate(ASSIGNMENT_ID)

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    // Quitar un refuerzo no toca ninguna columna de la fila del listado ni ningún
    // contador del tablero, igual que sumarlo. Es lo constante, y por eso se afirma.
    expect(invalidate).not.toHaveBeenCalled()
  })

  it('un refuerzo que ya no está invalida el detalle, porque el cache miente', async () => {
    server.use(removeResourceNotFound())
    const { queryClient, result } = setup()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    result.current.mutate(ASSIGNMENT_ID)

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(invalidate).toHaveBeenCalledWith({
      queryKey: operationsKeys.serviceDetail(SERVICE_ID),
    })
  })

  it('un 409 no borra nada del cache ni invalida', async () => {
    server.use(
      removeResourceConflict('OPS-006', 'El estado del servicio no admite esta acción'),
    )
    const { queryClient, result, detail } = setup()
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')

    result.current.mutate(ASSIGNMENT_ID)

    await waitFor(() => expect(result.current.isError).toBe(true))
    // La otra dirección de la guarda del 404: invalidar ante CUALQUIER error tiraría
    // una consulta por algo que no cambió.
    expect(detail()?.additionalResources).toHaveLength(2)
    expect(invalidate).not.toHaveBeenCalled()
  })
})
