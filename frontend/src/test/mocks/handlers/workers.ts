import { delay, http, HttpResponse } from 'msw'
import type { WorkerDetailResponse, WorkerDriverProfileResponse } from '../../../api'

const API = 'http://localhost:8080/api/v1'

/**
 * Ficha de trabajador (`GET /workers/{id}`). Todos los valores son distintos entre
 * sí, para que un dato bajo el rótulo de otro se note. Las fechas de auditoría caen
 * de noche en Lima: formateadas fuera de esa zona, dan el día siguiente.
 */
export function fakeWorkerDetail(overrides: Partial<WorkerDetailResponse> = {}): WorkerDetailResponse {
  return {
    id: 1,
    firstName: 'Ana',
    lastName: 'Torres Ruiz',
    documentType: { id: 3, code: 'CE', name: 'Carné de extranjería', maxLength: 12, validationPattern: null },
    documentNumber: '001234567',
    phone: '987654321',
    role: {
      name: 'operations_manager',
      description: 'Jefe de Operaciones',
      level: 3,
      canLogin: true,
      driverProfile: 'NONE',
    },
    hireDate: '2026-03-01',
    isActive: true,
    createdAt: '2026-05-21T02:00:00Z',
    createdBy: { id: 2, username: 'mlopez', fullName: 'María López Díaz', position: 'Administrador' },
    updatedAt: '2026-09-01T04:30:00Z',
    updatedBy: { id: 3, username: 'psalas', fullName: 'Pedro Salas Vega', position: 'Gerente General' },
    driver: null,
    hasUser: true,
    ...overrides,
  }
}

/** Ficha de conductor del trabajador. */
export function fakeDriverProfile(
  overrides: Partial<WorkerDriverProfileResponse> = {},
): WorkerDriverProfileResponse {
  return {
    id: 5,
    licenseNumber: 'Q12345678',
    licenseCategory: 'A-IIIb',
    status: 'AVAILABLE',
    isActive: true,
    ...overrides,
  }
}

function problema(code: string, status: number, detail: string) {
  return HttpResponse.json(
    { type: `urn:tms:error:${code.toLowerCase()}`, title: 'Error', status, code, detail, traceId: 'test' },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )
}

/** Default: la ficha del id pedido. */
export const workersHandlers = [
  http.get(`${API}/workers/:id`, ({ params }) =>
    HttpResponse.json(fakeWorkerDetail({ id: Number(params.id) })),
  ),
]

// ----- Overrides para server.use(...) -----

export function getWorkerOk(worker: WorkerDetailResponse) {
  return http.get(`${API}/workers/:id`, () => HttpResponse.json(worker))
}

/** Guarda cada id pedido: distingue "pidió 42" de "pidió dos veces" y de "no pidió". */
export function getWorkerCapture(sink: { ids?: number[] }, worker?: WorkerDetailResponse) {
  sink.ids = []
  return http.get(`${API}/workers/:id`, ({ params }) => {
    sink.ids = [...(sink.ids ?? []), Number(params.id)]
    return HttpResponse.json(worker ?? fakeWorkerDetail({ id: Number(params.id) }))
  })
}

export function getWorkerSlow(worker: WorkerDetailResponse, ms = 40) {
  return http.get(`${API}/workers/:id`, async () => {
    await delay(ms)
    return HttpResponse.json(worker)
  })
}

/** Anota cada pedido en el sink, si lo hay: distingue "no reintentó" de "reintentó". */
function contando(sink: { ids?: number[] } | undefined, respuesta: () => Response) {
  if (sink) sink.ids = []
  return http.get(`${API}/workers/:id`, ({ params }) => {
    if (sink) sink.ids = [...(sink.ids ?? []), Number(params.id)]
    return respuesta()
  })
}

export function getWorkerNotFound(sink?: { ids?: number[] }) {
  return contando(sink, () => problema('WRK-001', 404, 'Trabajador no encontrado'))
}

/** El otro 404 del contrato: sin cuerpo, para un id que no entra en un entero. */
export function getWorkerNotFoundWithoutBody() {
  return http.get(`${API}/workers/:id`, () => new HttpResponse(null, { status: 404 }))
}

export function getWorkerForbidden(sink?: { ids?: number[] }) {
  return contando(sink, () => problema('COM-003', 403, 'No tiene permisos para acceder a este recurso'))
}

export function getWorkerError(status = 500, sink?: { ids?: number[] }) {
  return contando(sink, () => problema('COM-500', status, 'Error interno del servidor'))
}

/** Un 200 sin cuerpo: el hook no tiene qué mostrar. */
export function getWorkerEmpty() {
  return http.get(`${API}/workers/:id`, () => new HttpResponse(null, { status: 200 }))
}
