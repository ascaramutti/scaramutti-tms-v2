import type { ReactNode } from 'react'
import { createElement } from 'react'
import { describe, expect, it } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useChangeQuotationStatus } from './useChangeQuotationStatus'
import { quotationKeys } from '../queryKeys'
import { server } from '../../../test/mocks/server'
import {
  changeQuotationStatusError,
  changeQuotationStatusSuccess,
  getQuotationResponse,
} from '../../../test/mocks/handlers/quotations'
import type { ChangeStatusBody } from '../../../test/mocks/handlers/quotations'

function setup() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const wrapper = ({ children }: { children: ReactNode }) =>
    createElement(QueryClientProvider, { client: queryClient }, children)
  const { result } = renderHook(() => useChangeQuotationStatus(), { wrapper })
  return { result, queryClient }
}

describe('useChangeQuotationStatus', () => {
  it('en un destino no-rechazo envía solo { status } + el If-Match', async () => {
    const sink: { body?: ChangeStatusBody; ifMatch?: string | null } = {}
    server.use(changeQuotationStatusSuccess(sink))
    const { result } = setup()

    result.current.mutate({ id: 1, ifMatch: '"v3"', status: 'SENT' })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(sink.body).toEqual({ status: 'SENT' })
    expect(sink.ifMatch).toBe('"v3"')
  })

  it('en el rechazo envía { status, rejectionReason }', async () => {
    const sink: { body?: ChangeStatusBody } = {}
    server.use(changeQuotationStatusSuccess(sink))
    const { result } = setup()

    result.current.mutate({ id: 1, ifMatch: '"v1"', status: 'REJECTED', rejectionReason: 'Fuera de presupuesto' })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(sink.body).toEqual({ status: 'REJECTED', rejectionReason: 'Fuera de presupuesto' })
  })

  it('adjunta el _etag opaco del header de la respuesta', async () => {
    const updated = getQuotationResponse({ id: 1, status: 'SENT', updatedAt: '2026-06-17T12:00:00.123456Z' })
    server.use(changeQuotationStatusSuccess({}, updated))
    const { result } = setup()

    result.current.mutate({ id: 1, ifMatch: '"v1"', status: 'SENT' })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?._etag).toBe(`"${updated.updatedAt}"`)
  })

  it('en éxito actualiza la cache del detalle e invalida los listados', async () => {
    const updated = getQuotationResponse({ id: 7, status: 'ACCEPTED' })
    server.use(changeQuotationStatusSuccess({}, updated))
    const { result, queryClient } = setup()

    result.current.mutate({ id: 7, ifMatch: '"v1"', status: 'ACCEPTED' })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    const cached = queryClient.getQueryData(quotationKeys.detail(7)) as { status: string } | undefined
    expect(cached?.status).toBe('ACCEPTED')
  })

  it('propaga el error del backend (409) como rechazo de la mutación', async () => {
    server.use(changeQuotationStatusError(409, { code: 'QUO-005', detail: 'Transición inválida.' }))
    const { result } = setup()

    result.current.mutate({ id: 1, ifMatch: '"v1"', status: 'ACCEPTED' })

    await waitFor(() => expect(result.current.isError).toBe(true))
  })
})
