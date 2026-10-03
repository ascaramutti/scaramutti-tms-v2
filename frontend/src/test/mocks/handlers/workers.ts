import { delay, http, HttpResponse } from 'msw'
import type {
  DocumentTypeResponse,
  RoleResponse,
  WorkerDetailResponse,
  WorkerDriverProfileResponse,
  WorkerRequest,
  WorkerUpdateRequest,
} from '../../../api'

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

// ----- Catálogos del alta -----

function role(
  name: string,
  description: string,
  level: number,
  driverProfile: RoleResponse['driverProfile'] = 'NONE',
  canLogin = level > 1 || name === 'warehouse_keeper',
): RoleResponse {
  return { name, description, level, canLogin, driverProfile }
}

/** El organigrama, en el orden del backend: nivel de mayor a menor y, dentro del nivel, por id. */
export const ROLES: RoleResponse[] = [
  role('admin', 'Administrador del Sistema', 4),
  role('general_manager', 'Gerente General', 3),
  role('operations_manager', 'Gerente de Operaciones', 3),
  role('finance_manager', 'Jefe de Finanzas', 2),
  role('sales', 'Ejecutivo de Ventas', 2),
  role('dispatcher', 'Coordinador de Operaciones', 2),
  role('warehouse_keeper', 'Encargado de Almacén', 1),
  role('driver', 'Conductor', 1, 'REQUIRED'),
  role('escort', 'Escolta', 1, 'REQUIRED'),
  role('assistant', 'Ayudante', 1, 'OPTIONAL'),
  role('operator', 'Operador', 1),
]

export const DNI: DocumentTypeResponse = { id: 1, code: 'DNI', name: 'DNI', maxLength: 8, validationPattern: '\\d{8}' }
export const CE: DocumentTypeResponse = {
  id: 3,
  code: 'CE',
  name: 'Carné de extranjería',
  maxLength: 12,
  validationPattern: null,
}

/** Default: la ficha del id pedido, los dos catálogos y un alta que devuelve el id 57. */
export const workersHandlers = [
  http.get(`${API}/workers/:id`, ({ params }) =>
    HttpResponse.json(fakeWorkerDetail({ id: Number(params.id) })),
  ),
  http.get(`${API}/roles`, () => HttpResponse.json(ROLES)),
  http.get(`${API}/document-types`, () => HttpResponse.json([DNI, CE])),
  http.post(`${API}/workers`, () => HttpResponse.json(fakeWorkerDetail({ id: 57, hasUser: false }), { status: 201 })),
]

export function listRolesOk(roles: RoleResponse[]) {
  return http.get(`${API}/roles`, () => HttpResponse.json(roles))
}

export function listRolesError(status = 500) {
  return http.get(`${API}/roles`, () => problema('COM-500', status, 'Error interno del servidor'))
}

export function listRolesSlow(ms: number) {
  return http.get(`${API}/roles`, async () => {
    await delay(ms)
    return HttpResponse.json(ROLES)
  })
}

/** Responde cada pedido con la siguiente lista de cargos (la última se repite) y los cuenta. */
export function listRolesSequence(respuestas: RoleResponse[][], sink: { calls?: number }) {
  sink.calls = 0
  return http.get(`${API}/roles`, () => {
    const i = Math.min(sink.calls ?? 0, respuestas.length - 1)
    sink.calls = (sink.calls ?? 0) + 1
    return HttpResponse.json(respuestas[i])
  })
}

/** Un error de catálogo que cuenta los pedidos: distingue "no reintentó" de "reintentó". */
export function listRolesFailing(status: number, sink: { calls?: number }) {
  sink.calls = 0
  return http.get(`${API}/roles`, () => {
    sink.calls = (sink.calls ?? 0) + 1
    return problema(status === 403 ? 'COM-003' : 'COM-500', status, 'Error del catálogo')
  })
}

/** Cuenta los pedidos a los dos catálogos sin cambiar la respuesta por defecto. */
export function catalogsCounting(sink: { roles?: number; documentTypes?: number }) {
  sink.roles = 0
  sink.documentTypes = 0
  return [
    http.get(`${API}/roles`, () => {
      sink.roles = (sink.roles ?? 0) + 1
      return HttpResponse.json(ROLES)
    }),
    http.get(`${API}/document-types`, () => {
      sink.documentTypes = (sink.documentTypes ?? 0) + 1
      return HttpResponse.json([DNI, CE])
    }),
  ]
}

/** Responde cada envío con el siguiente error de la lista; agotada, el alta. Guarda cada cuerpo. */
export function createWorkerErrorsInSequence(codes: [string, number][], sink: { bodies?: WorkerRequest[] }) {
  sink.bodies = []
  return http.post(`${API}/workers`, async ({ request }) => {
    sink.bodies = [...(sink.bodies ?? []), (await request.json()) as WorkerRequest]
    const next = codes[sink.bodies.length - 1]
    return next
      ? problema(next[0], next[1], 'detail del backend')
      : HttpResponse.json(fakeWorkerDetail({ id: 57, hasUser: false }), { status: 201 })
  })
}

/** Un error del catálogo de tipos que cuenta los pedidos. */
export function listDocumentTypesFailing(status: number, sink: { calls?: number }) {
  sink.calls = 0
  return http.get(`${API}/document-types`, () => {
    sink.calls = (sink.calls ?? 0) + 1
    return problema(status === 403 ? 'COM-003' : 'COM-500', status, 'Error del catálogo')
  })
}

/** El primer envío responde el error dado; los siguientes, el alta. Guarda cada cuerpo. */
export function createWorkerFailingOnce(code: string, status: number, sink: { bodies?: WorkerRequest[] }) {
  sink.bodies = []
  return http.post(`${API}/workers`, async ({ request }) => {
    sink.bodies = [...(sink.bodies ?? []), (await request.json()) as WorkerRequest]
    return sink.bodies.length === 1
      ? problema(code, status, 'detail del backend')
      : HttpResponse.json(fakeWorkerDetail({ id: 57, hasUser: false }), { status: 201 })
  })
}

export function listDocumentTypesOk(types: DocumentTypeResponse[]) {
  return http.get(`${API}/document-types`, () => HttpResponse.json(types))
}

export function listDocumentTypesError(status = 500) {
  return http.get(`${API}/document-types`, () => problema('COM-500', status, 'Error interno del servidor'))
}

/** Responde cada pedido con la siguiente lista (la última se repite) y los cuenta. */
export function listDocumentTypesSequence(respuestas: DocumentTypeResponse[][], sink: { calls?: number }) {
  sink.calls = 0
  return http.get(`${API}/document-types`, () => {
    const i = Math.min(sink.calls ?? 0, respuestas.length - 1)
    sink.calls = (sink.calls ?? 0) + 1
    return HttpResponse.json(respuestas[i])
  })
}

/** Guarda el cuerpo del alta y cuenta los envíos. */
export function createWorkerCapture(
  sink: { body?: WorkerRequest; calls?: number },
  response: WorkerDetailResponse = fakeWorkerDetail({ id: 57, hasUser: false }),
  ms = 0,
) {
  sink.calls = 0
  return http.post(`${API}/workers`, async ({ request }) => {
    sink.body = (await request.json()) as WorkerRequest
    sink.calls = (sink.calls ?? 0) + 1
    if (ms) await delay(ms)
    return HttpResponse.json(response, { status: 201 })
  })
}

/** Un error del alta con el detail real del backend: el test espera el texto propio. */
export function createWorkerProblem(code: string, status: number, detail: string) {
  return http.post(`${API}/workers`, () => problema(code, status, detail))
}

/** 400 de forma, con errores por campo. */
export function createWorkerValidation(errors: { field: string; message: string }[]) {
  return http.post(`${API}/workers`, () =>
    HttpResponse.json(
      { type: 'urn:tms:error:com-001', title: 'Bad Request', status: 400, code: 'COM-001', detail: 'Datos invalidos', errors },
      { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
    ),
  )
}

export function createWorkerNetworkError() {
  return http.post(`${API}/workers`, () => HttpResponse.error())
}

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

// ----- Edición (`PUT /workers/{id}`) -----

/** Guarda el id y el cuerpo de cada envío; responde la ficha dada o la del cuerpo. */
export function updateWorkerCapture(
  sink: { ids?: number[]; bodies?: WorkerUpdateRequest[] },
  response?: WorkerDetailResponse,
  ms = 0,
) {
  sink.ids = []
  sink.bodies = []
  return http.put(`${API}/workers/:id`, async ({ params, request }) => {
    const body = (await request.json()) as WorkerUpdateRequest
    sink.ids = [...(sink.ids ?? []), Number(params.id)]
    sink.bodies = [...(sink.bodies ?? []), body]
    if (ms) await delay(ms)
    return HttpResponse.json(
      response ?? fakeWorkerDetail({ id: Number(params.id), firstName: body.firstName, lastName: body.lastName }),
    )
  })
}

export function updateWorkerProblem(code: string, status: number, detail = 'detail del backend') {
  return http.put(`${API}/workers/:id`, () => problema(code, status, detail))
}

export function updateWorkerValidation(errors: { field: string; message: string }[]) {
  return http.put(`${API}/workers/:id`, () =>
    HttpResponse.json(
      { type: 'urn:tms:error:com-001', title: 'Bad Request', status: 400, code: 'COM-001', detail: 'Datos invalidos', errors },
      { status: 400, headers: { 'Content-Type': 'application/problem+json' } },
    ),
  )
}

/** Un 200 sin cuerpo al guardar: el hook no tiene qué devolver. */
export function updateWorkerEmpty() {
  return http.put(`${API}/workers/:id`, () => new HttpResponse(null, { status: 200 }))
}

export function updateWorkerNetworkError() {
  return http.put(`${API}/workers/:id`, () => HttpResponse.error())
}
