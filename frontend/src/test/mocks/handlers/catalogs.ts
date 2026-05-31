import { http, HttpResponse, delay } from 'msw'
import type { CurrencyResponse, PaymentTermResponse, QuotationConfigResponse } from '../../../api'

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
  ]
}
