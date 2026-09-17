import { delay, http, HttpResponse } from 'msw'
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

/**
 * Defaults happy-path: búsqueda vacía, un cliente por id y creación que ecoa el
 * cuerpo. El del detalle existe para que montar el formulario sin un override
 * explícito no falle por una petición sin handler, que es un error confuso.
 */
export const clientsHandlers = [
  http.get(`${API}/clients`, () => HttpResponse.json(pageOfClients([]))),
  http.get(`${API}/clients/:id`, ({ params }) =>
    HttpResponse.json(fakeClient({ id: Number(params.id) })),
  ),
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

export function clientsCapture(
  sink: { params?: URLSearchParams; llamadas?: number },
  content: ClientResponse[] = [],
) {
  return http.get(`${API}/clients`, ({ request }) => {
    sink.params = new URL(request.url).searchParams
    // Se cuentan además de guardarse los últimos: sin el contador, un buscador
    // que dispara una consulta por tecla se ve igual que uno con rebote.
    sink.llamadas = (sink.llamadas ?? 0) + 1
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

/** Búsqueda que tarda: para ver el estado de carga antes de la respuesta. */
export function clientsSearchSlow(content: ClientResponse[], ms = 40) {
  return http.get(`${API}/clients`, async () => {
    await delay(ms)
    return HttpResponse.json(pageOfClients(content))
  })
}

/** La búsqueda se cae. Distinto de "no hay resultados", y la pantalla lo distingue. */
export function clientsSearchError(status = 500) {
  return http.get(`${API}/clients`, () =>
    HttpResponse.json(
      {
        type: 'about:blank',
        title: 'Internal Server Error',
        status,
        code: 'COM-500',
        detail: 'Error interno del servidor',
        traceId: 'test',
      },
      { status, headers: { 'Content-Type': 'application/problem+json' } },
    ),
  )
}

/** Una página con MÁS coincidencias que filas: el caso del aviso de "hay más". */
export function clientsSearchPartial(content: ClientResponse[], totalElements: number) {
  return http.get(`${API}/clients`, () =>
    HttpResponse.json({ ...pageOfClients(content), totalElements, totalPages: 3, last: false }),
  )
}

// ----- Un cliente por id -----

export function getClientOk(client: ClientResponse) {
  return http.get(`${API}/clients/:id`, () => HttpResponse.json(client))
}

/** Captura a QUIÉN se pidió: distingue "lo trajo del servidor" de "lo sacó de la caché". */
export function getClientCapture(sink: { id?: number }, client: ClientResponse) {
  return http.get(`${API}/clients/:id`, ({ params }) => {
    sink.id = Number(params.id)
    return HttpResponse.json(client)
  })
}

export function getClientSlow(client: ClientResponse, ms = 40) {
  return http.get(`${API}/clients/:id`, async () => {
    await delay(ms)
    return HttpResponse.json(client)
  })
}

function problema(code: string, status: number, detail: string, extra: object = {}) {
  return HttpResponse.json(
    { type: `urn:tms:error:${code.toLowerCase()}`, title: 'Error', status, code, detail, traceId: 'test', ...extra },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )
}

export function getClientNotFound() {
  return http.get(`${API}/clients/:id`, () => problema('CLI-003', 404, 'Cliente no encontrado'))
}

export function getClientError(status = 500) {
  return http.get(`${API}/clients/:id`, () =>
    problema('COM-500', status, 'Error interno del servidor'),
  )
}

// ----- Guardar la edición -----

export function updateClientOk(client: ClientResponse) {
  return http.put(`${API}/clients/:id`, () => HttpResponse.json(client))
}

/** Captura el cuerpo y el id: lo que importa es qué se manda y a quién. */
export function updateClientCapture(
  sink: { body?: ClientRequest; id?: number },
  response?: ClientResponse,
) {
  return http.put(`${API}/clients/:id`, async ({ request, params }) => {
    sink.body = (await request.json()) as ClientRequest
    sink.id = Number(params.id)
    return HttpResponse.json(response ?? fakeClient({ id: sink.id }))
  })
}

/** 409 con el código y el detalle reales del backend. */
export function updateClientConflict(code: 'CLI-001' | 'CLI-002') {
  const detail =
    code === 'CLI-001'
      ? 'Ya existe un cliente con el RUC indicado'
      : 'Ya existe un cliente con el nombre indicado'
  return http.put(`${API}/clients/:id`, () => problema(code, 409, detail))
}

export function updateClientForbidden() {
  return http.put(`${API}/clients/:id`, () =>
    problema('COM-003', 403, 'No tiene permisos para acceder a este recurso'),
  )
}

export function updateClientNotFound() {
  return http.put(`${API}/clients/:id`, () => problema('CLI-003', 404, 'Cliente no encontrado'))
}

export function updateClientValidation(errors: Array<{ field: string; message: string }>) {
  return http.put(`${API}/clients/:id`, () =>
    problema('COM-001', 400, 'La solicitud contiene errores de validación', { errors }),
  )
}
