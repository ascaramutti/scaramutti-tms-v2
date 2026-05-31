import { http, HttpResponse } from 'msw'
import type { ClientRequest, ClientResponse, PageOfClient } from '../../../api'

const API = 'http://localhost:8080/api/v1'

export function fakeClient(overrides: Partial<ClientResponse> = {}): ClientResponse {
  return {
    id: 1,
    name: 'ACME S.A.C.',
    ruc: '20123456789',
    phone: '987654321',
    contactName: 'Juan Pérez',
    isActive: true,
    createdAt: '2026-05-20T10:00:00Z',
    ...overrides,
  }
}

export function pageOfClients(content: ClientResponse[]): PageOfClient {
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
export const clientsHandlers = [
  http.get(`${API}/clients`, () => HttpResponse.json(pageOfClients([]))),
  http.post(`${API}/clients`, async ({ request }) => {
    const body = (await request.json()) as ClientRequest
    return HttpResponse.json(
      fakeClient({
        id: 99,
        name: body.name,
        ruc: body.ruc,
        phone: body.phone ?? null,
        contactName: body.contactName ?? null,
      }),
      { status: 201 },
    )
  }),
]

// ----- Overrides -----

export function clientsSearch(content: ClientResponse[]) {
  return http.get(`${API}/clients`, () => HttpResponse.json(pageOfClients(content)))
}

export function clientsCapture(sink: { params?: URLSearchParams }, content: ClientResponse[] = []) {
  return http.get(`${API}/clients`, ({ request }) => {
    sink.params = new URL(request.url).searchParams
    return HttpResponse.json(pageOfClients(content))
  })
}

export function createClientOk(client: ClientResponse) {
  return http.post(`${API}/clients`, () => HttpResponse.json(client, { status: 201 }))
}

export function createClientConflict(detail = 'El RUC ya existe en otro cliente.') {
  return http.post(`${API}/clients`, () =>
    HttpResponse.json(
      { type: 'urn:tms:error:cli-001', title: 'Conflict', status: 409, code: 'CLI-001', detail, traceId: 'test' },
      { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
    ),
  )
}
