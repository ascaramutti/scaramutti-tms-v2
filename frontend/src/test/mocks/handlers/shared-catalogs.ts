import { http, HttpResponse } from 'msw'
import type { FleetUnitResponse, Problem, WorkerResponse } from '../../../api'
import type { ProductsCaptureSink } from './warehouse'

const API = 'http://localhost:8080/api/v1'

/** Catálogos COMPARTIDOS con operaciones (public.workers / fleet_units), que el
 * módulo almacén consume para el retiro: "quién recibe" y la unidad de flota. */

/** Fixture de trabajador (`public.workers`). */
export function fakeWorker(overrides: Partial<WorkerResponse> = {}): WorkerResponse {
  return {
    id: 8,
    fullName: 'Juan Pérez Gómez',
    position: 'Chofer',
    isActive: true,
    ...overrides,
  }
}

/** Fixture de unidad de flota (`public.fleet_units`). Default: un tracto. */
export function fakeFleetUnit(overrides: Partial<FleetUnitResponse> = {}): FleetUnitResponse {
  return {
    kind: 'TRACTOR',
    id: 5,
    plate: 'ABC-123',
    brand: 'Volvo',
    model: 'FH',
    isActive: true,
    ...overrides,
  }
}

function problemResponse(status: number, problem: Partial<Problem> = {}) {
  return HttpResponse.json(
    { type: 'urn:tms:error:test', title: 'Error', status, detail: 'Fallo de prueba', ...problem },
    { status, headers: { 'Content-Type': 'application/problem+json' } },
  )
}

/** Default happy-path: catálogos de trabajadores y unidades de flota. */
export const sharedCatalogsHandlers = [
  http.get(`${API}/workers`, () =>
    HttpResponse.json([
      fakeWorker(),
      fakeWorker({ id: 9, fullName: 'María López Díaz', position: 'Almacenera' }),
    ]),
  ),
  http.get(`${API}/fleet-units`, () =>
    HttpResponse.json([
      fakeFleetUnit(),
      fakeFleetUnit({ kind: 'TRAILER', id: 9, plate: 'XY-9876', brand: 'Randon', model: 'SR' }),
      fakeFleetUnit({ kind: 'ESCORT', id: 3, plate: 'ES-100', brand: 'Toyota', model: 'Hilux' }),
    ]),
  ),
]

// ----- Overrides para server.use(...) -----

/** Responde la búsqueda de trabajadores con la lista dada. */
export function workersSearchPage(content: WorkerResponse[]) {
  return http.get(`${API}/workers`, () => HttpResponse.json(content))
}

/** Captura los query params de la búsqueda de trabajadores. */
export function workersSearchCapture(
  sink: ProductsCaptureSink,
  content: WorkerResponse[] = [fakeWorker()],
) {
  sink.calls = []
  return http.get(`${API}/workers`, ({ request }) => {
    const params = new URL(request.url).searchParams
    sink.params = params
    sink.calls = [...(sink.calls ?? []), params]
    return HttpResponse.json(content)
  })
}

/** Responde un error en la búsqueda de trabajadores. */
export function workersSearchError(status: number, problem: Partial<Problem> = {}) {
  return http.get(`${API}/workers`, () => problemResponse(status, problem))
}

/** Responde el listado de unidades de flota con la lista dada. */
export function fleetUnitsList(content: FleetUnitResponse[]) {
  return http.get(`${API}/fleet-units`, () => HttpResponse.json(content))
}

/** Captura los query params del listado de unidades de flota. */
export function fleetUnitsCapture(
  sink: ProductsCaptureSink,
  content: FleetUnitResponse[] = [fakeFleetUnit()],
) {
  sink.calls = []
  return http.get(`${API}/fleet-units`, ({ request }) => {
    const params = new URL(request.url).searchParams
    sink.params = params
    sink.calls = [...(sink.calls ?? []), params]
    return HttpResponse.json(content)
  })
}

/** Responde un error en el listado de unidades de flota. */
export function fleetUnitsError(status: number, problem: Partial<Problem> = {}) {
  return http.get(`${API}/fleet-units`, () => problemResponse(status, problem))
}
