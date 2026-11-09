import { delay, http, HttpResponse } from 'msw'
import type { UserAccountDetailResponse, UserAccountSummaryResponse } from '../../../api'

const API = 'http://localhost:8080/api/v1'

export function fakeUserAccount(overrides: Partial<UserAccountSummaryResponse> = {}): UserAccountSummaryResponse {
  return {
    id: 12,
    username: 'jperez',
    worker: { id: 31, fullName: 'Juan Pérez Quispe', isActive: true },
    role: {
      name: 'operations_manager',
      description: 'Gerente de Operaciones',
      level: 3,
      canLogin: true,
      driverProfile: 'NONE',
    },
    isActive: true,
    ...overrides,
  }
}

/** Los dos instantes caen de noche en Lima: en UTC ya es el día siguiente. */
export function fakeUserAccountDetail(overrides: Partial<UserAccountDetailResponse> = {}): UserAccountDetailResponse {
  return {
    id: 12,
    username: 'jperez',
    worker: {
      id: 31,
      fullName: 'Juan Pérez Quispe',
      isActive: true,
      firstName: 'Juan',
      paternalLastName: 'Pérez',
      maternalLastName: 'Quispe',
    },
    role: {
      name: 'operations_manager',
      description: 'Gerente de Operaciones',
      level: 3,
      canLogin: true,
      driverProfile: 'NONE',
    },
    isActive: true,
    createdAt: '2026-11-04T03:20:00Z',
    createdBy: { id: 2, username: 'aquispe', fullName: 'Ana Quispe Rojas', position: 'Gerente General' },
    updatedAt: '2026-11-06T02:10:00Z',
    updatedBy: { id: 3, username: 'lrojas', fullName: 'Luis Rojas Vega', position: 'Gerente de Finanzas' },
    ...overrides,
  }
}

export const usersHandlers = [
  http.get(`${API}/users`, ({ request }) => {
    const isActive = new URL(request.url).searchParams.get('isActive')
    const accounts = [
      fakeUserAccount(),
      fakeUserAccount({ id: 13, username: 'mrios', isActive: false }),
    ]
    if (isActive === null) return HttpResponse.json(accounts)
    return HttpResponse.json(accounts.filter((account) => String(account.isActive) === isActive))
  }),
  http.get(`${API}/users/:id`, ({ params }) => HttpResponse.json(fakeUserAccountDetail({ id: Number(params.id) }))),
]

function problema(code: string, status: number, detail: string) {
  return HttpResponse.json(
    { type: `urn:tms:error:${code.toLowerCase()}`, title: 'Error', status, code, detail, traceId: 'test' },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )
}

/** Anota cada id pedido: distingue "pidió 42" de "pidió dos veces" y de "no pidió". */
function contando(sink: { ids?: number[] } | undefined, respuesta: (id: number) => Response | Promise<Response>) {
  if (sink) sink.ids = []
  return http.get(`${API}/users/:id`, ({ params }) => {
    if (sink) sink.ids = [...(sink.ids ?? []), Number(params.id)]
    return respuesta(Number(params.id))
  })
}

export function getUserAccountOk(account: UserAccountDetailResponse, sink?: { ids?: number[] }) {
  return contando(sink, () => HttpResponse.json(account))
}

export function getUserAccountCapture(sink: { ids?: number[] }) {
  return contando(sink, (id) => HttpResponse.json(fakeUserAccountDetail({ id })))
}

export function getUserAccountSlow(account: UserAccountDetailResponse, ms = 40) {
  return contando(undefined, async () => {
    await delay(ms)
    return HttpResponse.json(account)
  })
}

export function getUserAccountNotFound(sink?: { ids?: number[] }) {
  return contando(sink, () => problema('USR-001', 404, 'Cuenta no encontrada'))
}

export function getUserAccountNotFoundWithoutBody() {
  return contando(undefined, () => new HttpResponse(null, { status: 404 }))
}

export function getUserAccountForbidden(sink?: { ids?: number[] }, detail = 'No tiene permisos para acceder a este recurso') {
  if (detail) return contando(sink, () => problema('COM-003', 403, detail))
  return contando(sink, () =>
    HttpResponse.json({ status: 403, code: 'COM-003' }, { status: 403, headers: { 'Content-Type': 'application/problem+json' } }),
  )
}

export function getUserAccountError(status = 500, sink?: { ids?: number[] }, detail = 'Error interno del servidor') {
  if (detail) return contando(sink, () => problema('COM-500', status, detail))
  return contando(sink, () => HttpResponse.json({ status }, { status }))
}

export function getUserAccountEmpty() {
  return contando(undefined, () => new HttpResponse(null, { status: 200 }))
}

type AccountStatusAction = 'deactivate' | 'reactivate'

export function changeAccountStatusCapture(
  action: AccountStatusAction,
  sink: { ids?: number[] },
  response?: UserAccountDetailResponse,
  ms = 0,
) {
  sink.ids = []
  return http.post(`${API}/users/:id/${action}`, async ({ params }) => {
    sink.ids = [...(sink.ids ?? []), Number(params.id)]
    if (ms) await delay(ms)
    return HttpResponse.json(response ?? fakeUserAccountDetail({ id: Number(params.id), isActive: action === 'reactivate' }))
  })
}

export function changeAccountStatusProblem(
  action: AccountStatusAction,
  code: string,
  status: number,
  detail = 'detail del backend',
) {
  return http.post(`${API}/users/:id/${action}`, () => problema(code, status, detail))
}

export function changeAccountStatusNetworkError(action: AccountStatusAction) {
  return http.post(`${API}/users/:id/${action}`, () => HttpResponse.error())
}

export function changeAccountStatusEmpty(action: AccountStatusAction) {
  return http.post(`${API}/users/:id/${action}`, () => new HttpResponse(null, { status: 200 }))
}
