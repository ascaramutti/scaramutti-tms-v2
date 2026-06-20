import { http, HttpResponse, delay } from 'msw'
import type {
  ConditionResponse,
  CurrencyResponse,
  PaymentTermResponse,
  QuotationConfigResponse,
  QuotationServiceTypeResponse,
} from '../../../api'

const API = 'http://localhost:8080/api/v1'

export function fakeCurrency(overrides: Partial<CurrencyResponse> = {}): CurrencyResponse {
  return { id: 2, code: 'PEN', symbol: 'S/', name: 'Soles', isActive: true, ...overrides }
}

export function fakePaymentTerm(overrides: Partial<PaymentTermResponse> = {}): PaymentTermResponse {
  return { id: 1, name: 'Contado', days: 0, isActive: true, ...overrides }
}

export function fakeConfig(overrides: Partial<QuotationConfigResponse> = {}): QuotationConfigResponse {
  return { igvPercentage: 18, maxRootItems: 5, defaultValidityDays: 15, ...overrides }
}

export function fakeServiceType(
  overrides: Partial<QuotationServiceTypeResponse> = {},
): QuotationServiceTypeResponse {
  return {
    id: 1,
    code: 'SCB',
    name: 'Transporte de carga general',
    kind: 'SERVICIO',
    isActive: true,
    ...overrides,
  }
}

export function fakeCondition(overrides: Partial<ConditionResponse> = {}): ConditionResponse {
  return { id: 1, text: 'Condición general de ejemplo', displayOrder: 1, isActive: true, ...overrides }
}

/** Catálogo activo de condiciones (ordenado por displayOrder, como lo entrega el backend). */
const DEFAULT_CONDITIONS: ConditionResponse[] = [
  fakeCondition({ id: 1, text: 'Cond A', displayOrder: 1 }),
  fakeCondition({ id: 2, text: 'Cond B', displayOrder: 2 }),
]

/** Mezcla de kinds (incluye INTEGRAL, que el Step 2 debe EXCLUIR del select por ahora). */
const DEFAULT_SERVICE_TYPES: QuotationServiceTypeResponse[] = [
  fakeServiceType({ id: 1, code: 'SCB', name: 'Transporte de carga general', kind: 'SERVICIO' }),
  fakeServiceType({ id: 2, code: 'CES', name: 'Escolta armada', kind: 'COMPLEMENTARIO' }),
  fakeServiceType({ id: 3, code: 'ACB', name: 'Alquiler de camión', kind: 'ALQUILER' }),
  fakeServiceType({ id: 4, code: 'INT', name: 'Servicio Integral', kind: 'INTEGRAL' }),
]

/** Defaults happy-path. IMPORTANTE: este array va ANTES de quotationsHandlers en
 * `handlers.ts` para que `GET /quotations/config` matchee antes que `/quotations/:id`. */
export const catalogsHandlers = [
  http.get(`${API}/currencies`, () =>
    HttpResponse.json([
      fakeCurrency({ id: 1, code: 'USD', symbol: 'US$', name: 'Dólares' }),
      fakeCurrency(),
    ]),
  ),
  http.get(`${API}/payment-terms`, () =>
    HttpResponse.json([fakePaymentTerm(), fakePaymentTerm({ id: 3, name: '30 días', days: 30 })]),
  ),
  http.get(`${API}/quotations/config`, () => HttpResponse.json(fakeConfig())),
  http.get(`${API}/quotation-service-types`, () => HttpResponse.json(DEFAULT_SERVICE_TYPES)),
  http.get(`${API}/quotation-conditions`, () => HttpResponse.json(DEFAULT_CONDITIONS)),
]

// ----- Overrides -----

export function currenciesOk(items: CurrencyResponse[]) {
  return http.get(`${API}/currencies`, () => HttpResponse.json(items))
}

export function paymentTermsOk(items: PaymentTermResponse[]) {
  return http.get(`${API}/payment-terms`, () => HttpResponse.json(items))
}

export function configOk(overrides: Partial<QuotationConfigResponse>) {
  return http.get(`${API}/quotations/config`, () => HttpResponse.json(fakeConfig(overrides)))
}

export function serviceTypesOk(items: QuotationServiceTypeResponse[]) {
  return http.get(`${API}/quotation-service-types`, () => HttpResponse.json(items))
}

export function conditionsOk(items: ConditionResponse[]) {
  return http.get(`${API}/quotation-conditions`, () => HttpResponse.json(items))
}

export function conditionsError(status: number) {
  return http.get(`${API}/quotation-conditions`, () =>
    HttpResponse.json(
      { type: 'urn:tms:error:test', title: 'Error', status, detail: 'Fallo al cargar condiciones' },
      { status, headers: { 'Content-Type': 'application/problem+json' } },
    ),
  )
}

export function currenciesError(status: number) {
  return http.get(`${API}/currencies`, () =>
    HttpResponse.json(
      { type: 'urn:tms:error:test', title: 'Error', status, detail: 'Fallo al cargar monedas' },
      { status, headers: { 'Content-Type': 'application/problem+json' } },
    ),
  )
}

export function catalogsSlow(ms = 40) {
  return [
    http.get(`${API}/currencies`, async () => {
      await delay(ms)
      return HttpResponse.json([fakeCurrency()])
    }),
    http.get(`${API}/payment-terms`, async () => {
      await delay(ms)
      return HttpResponse.json([fakePaymentTerm()])
    }),
    http.get(`${API}/quotations/config`, async () => {
      await delay(ms)
      return HttpResponse.json(fakeConfig())
    }),
    http.get(`${API}/quotation-service-types`, async () => {
      await delay(ms)
      return HttpResponse.json(DEFAULT_SERVICE_TYPES)
    }),
    http.get(`${API}/quotation-conditions`, async () => {
      await delay(ms)
      return HttpResponse.json(DEFAULT_CONDITIONS)
    }),
  ]
}
