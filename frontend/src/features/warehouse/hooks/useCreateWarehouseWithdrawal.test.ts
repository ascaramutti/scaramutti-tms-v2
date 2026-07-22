import type { ReactNode } from 'react'
import { createElement } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  fleetUnitToWithdrawalFields,
  toWithdrawalRequest,
  useCreateWarehouseWithdrawal,
} from './useCreateWarehouseWithdrawal'
import { warehouseKeys } from '../queryKeys'
import type { WithdrawalFormInput } from '../schemas/withdrawal.schema'
import { server } from '../../../test/mocks/server'
import { createWithdrawalCapture } from '../../../test/mocks/handlers/warehouse'

const input: WithdrawalFormInput = {
  productId: 1,
  quantity: 4,
  receivedByWorkerId: 8,
  observations: 'Cambio de aceite programado',
}

describe('fleetUnitToWithdrawalFields', () => {
  it('rutea la unidad al campo del contrato según su kind', () => {
    expect(fleetUnitToWithdrawalFields({ kind: 'TRACTOR', id: 5 })).toEqual({
      tractorId: 5,
      trailerId: null,
      escortVehicleId: null,
    })
    expect(fleetUnitToWithdrawalFields({ kind: 'TRAILER', id: 9 })).toEqual({
      tractorId: null,
      trailerId: 9,
      escortVehicleId: null,
    })
    expect(fleetUnitToWithdrawalFields({ kind: 'ESCORT', id: 3 })).toEqual({
      tractorId: null,
      trailerId: null,
      escortVehicleId: 3,
    })
  })

  it('sin unidad deja los tres campos en null', () => {
    expect(fleetUnitToWithdrawalFields(null)).toEqual({
      tractorId: null,
      trailerId: null,
      escortVehicleId: null,
    })
  })
})

describe('toWithdrawalRequest', () => {
  it('mapea el form al body del contrato con la unidad elegida', () => {
    expect(toWithdrawalRequest(input, { kind: 'TRACTOR', id: 5 })).toEqual({
      productId: 1,
      quantity: 4,
      receivedByWorkerId: 8,
      tractorId: 5,
      trailerId: null,
      escortVehicleId: null,
      observations: 'Cambio de aceite programado',
    })
  })

  it('sin unidad de flota manda los tres ids en null', () => {
    const body = toWithdrawalRequest(input, null)
    expect(body.tractorId).toBeNull()
    expect(body.trailerId).toBeNull()
    expect(body.escortVehicleId).toBeNull()
  })

  it('envía null (no cadena vacía) en observaciones sin completar', () => {
    expect(toWithdrawalRequest({ ...input, observations: '' }, null).observations).toBeNull()
    expect(toWithdrawalRequest({ ...input, observations: '   ' }, null).observations).toBeNull()
  })
})

describe('useCreateWarehouseWithdrawal', () => {
  it('en éxito invalida el listado de retiros, existencias, kardex e indicadores', async () => {
    const queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    const invalidate = vi.spyOn(queryClient, 'invalidateQueries')
    const wrapper = ({ children }: { children: ReactNode }) =>
      createElement(QueryClientProvider, { client: queryClient }, children)
    server.use(createWithdrawalCapture({}))
    const { result } = renderHook(() => useCreateWarehouseWithdrawal(), { wrapper })

    result.current.mutate(toWithdrawalRequest(input, null))

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    const invalidatedKeys = invalidate.mock.calls.map(([arg]) => arg?.queryKey)
    expect(invalidatedKeys).toContainEqual(warehouseKeys.withdrawalLists())
    expect(invalidatedKeys).toContainEqual(warehouseKeys.products())
    expect(invalidatedKeys).toContainEqual(warehouseKeys.kardexes())
    expect(invalidatedKeys).toContainEqual(warehouseKeys.stats())
  })
})
