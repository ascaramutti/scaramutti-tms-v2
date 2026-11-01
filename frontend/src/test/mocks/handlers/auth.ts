import { http, HttpResponse } from 'msw'
import type { LoginResponse, Problem, SessionUser, UserResponse } from '../../../api'

const API = 'http://localhost:8080/api/v1'

// El trabajador no es el 1: con el mismo número que la cuenta, un error de cuenta por trabajador
// pasaría verde, y cada ficha de los fixtures de trabajadores (id 1) sería la propia.
export const fakeUser: SessionUser = {
  id: 1,
  username: 'admin',
  fullName: 'Admin TMS',
  position: 'Administrador del sistema',
  role: 'admin',
  isActive: true,
  workerId: 29119,
}

export const fakeLoginResponse: LoginResponse = {
  token: 'fake-access-token',
  refreshToken: 'fake-refresh-token',
  expiresAt: '2026-12-31T23:59:59Z',
  expiresIn: 3600,
  user: fakeUser,
}

// La sesión nueva que responde el cambio de contraseña: otros tokens, con la versión nueva del refresh.
export const fakeChangedPasswordResponse: LoginResponse = {
  ...fakeLoginResponse,
  token: 'fake-access-token-tras-el-cambio',
  refreshToken: 'fake-refresh-token-tras-el-cambio',
}

export const authHandlers = [
  http.post(`${API}/auth/login`, () => HttpResponse.json(fakeLoginResponse)),
  http.get(`${API}/auth/me`, () => HttpResponse.json(fakeUser)),
  http.post(`${API}/auth/change-password`, () => HttpResponse.json(fakeChangedPasswordResponse)),
]

// Login exitoso con un rol específico (para tests del landing por rol).
export function loginAsRoleResponse(role: UserResponse['role']) {
  return http.post(`${API}/auth/login`, () =>
    HttpResponse.json({
      ...fakeLoginResponse,
      user: { ...fakeUser, username: `user-${role}`, role },
    } satisfies LoginResponse),
  )
}

// Helpers para tests que quieren respuestas de error puntuales.
export function loginErrorResponse(status: number, problem: Problem) {
  return http.post(`${API}/auth/login`, () =>
    HttpResponse.json(problem, { status, headers: { 'Content-Type': 'application/problem+json' } }),
  )
}

export function getCurrentUserErrorResponse(status: number, problem: Problem) {
  return http.get(`${API}/auth/me`, () =>
    HttpResponse.json(problem, { status, headers: { 'Content-Type': 'application/problem+json' } }),
  )
}

export function changePasswordErrorResponse(status: number, problem: Problem) {
  return http.post(`${API}/auth/change-password`, () =>
    HttpResponse.json(problem, { status, headers: { 'Content-Type': 'application/problem+json' } }),
  )
}
