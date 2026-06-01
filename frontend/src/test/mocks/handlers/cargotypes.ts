import { http, HttpResponse } from 'msw'
import type { CargoTypeRequest, CargoTypeResponse, PageOfCargoType } from '../../../api'

const API = 'http://localhost:8080/api/v1'

export function fakeCargoType(overrides: Partial<CargoTypeResponse> = {}): CargoTypeResponse {
  return { id: 1, name: 'CARGA GENERAL', standardWeight: 1000, isActive: true, ...overrides }
}

export function pageOfCargoTypes(content: CargoTypeResponse[]): PageOfCargoType {
  return {
    content,
    page: 0,
    size: 10,
    totalElements: content.length,
    totalPages: content.length === 0 ? 0 : 1,
    numberOfElements: content.length,
    first: true,
    last: true,
    empty: content.length === 0,
  }
}

/** Defaults happy-path: búsqueda vacía + creación que ecoa el body. */
export const cargoTypesHandlers = [
  http.get(`${API}/cargo-types`, () => HttpResponse.json(pageOfCargoTypes([]))),
  http.post(`${API}/cargo-types`, async ({ request }) => {
    const body = (await request.json()) as CargoTypeRequest
    return HttpResponse.json(
      fakeCargoType({ id: 99, name: body.name, standardWeight: body.standardWeight }),
      { status: 201 },
    )
  }),
]

// ----- Overrides -----

export function cargoTypesSearch(content: CargoTypeResponse[]) {
  return http.get(`${API}/cargo-types`, () => HttpResponse.json(pageOfCargoTypes(content)))
}

export function createCargoTypeOk(cargoType: CargoTypeResponse) {
  return http.post(`${API}/cargo-types`, () => HttpResponse.json(cargoType, { status: 201 }))
}

export function createCargoTypeConflict(detail = 'El tipo de carga ya existe.') {
  return http.post(`${API}/cargo-types`, () =>
    HttpResponse.json(
      { type: 'urn:tms:error:cgt-001', title: 'Conflict', status: 409, code: 'CGT-001', detail, traceId: 'test' },
      { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
    ),
  )
}
