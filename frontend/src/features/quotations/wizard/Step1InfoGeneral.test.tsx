import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { FormProvider, useForm } from 'react-hook-form'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { Step1InfoGeneral } from './Step1InfoGeneral'
import { WIZARD_DEFAULTS, type WizardFormInput } from './quotation-wizard.schema'
import type { CurrencyResponse, PaymentTermResponse } from '../../../api'

const CURRENCIES: CurrencyResponse[] = [
  { id: 1, code: 'USD', symbol: 'US$', name: 'Dólar', isActive: true },
  { id: 2, code: 'PEN', symbol: 'S/', name: 'Sol', isActive: true },
]

const PAYMENT_TERMS: PaymentTermResponse[] = [
  { id: 1, name: 'Contado', days: 0, isActive: true },
]

function renderStep() {
  // El buscador de clientes del paso consulta al backend, asi que el paso necesita su cliente
  // de consultas aunque este caso no mire ninguna busqueda.
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  function Wrapper() {
    const methods = useForm<WizardFormInput>({ defaultValues: { ...WIZARD_DEFAULTS } })
    return (
      <QueryClientProvider client={queryClient}>
        <FormProvider {...methods}>
          <Step1InfoGeneral
            currencies={CURRENCIES}
            paymentTerms={PAYMENT_TERMS}
            selectedClient={null}
            onClientChange={() => {}}
          />
        </FormProvider>
      </QueryClientProvider>
    )
  }
  return render(<Wrapper />)
}

/**
 * El piso del selector de fecha tentativa es hoy EN LIMA, no el día del navegador de quien
 * cotiza. Es el espejo del `max` de la factura de entrada y existe por el mismo motivo: sin
 * este caso, el único consumidor del helper que quedaba sin mirar era este.
 *
 * Reloj fijo en el borde (02:30 UTC del 25 de agosto: en Lima es el 24 y en Tokio, donde corre
 * esta suite, ya el 25) y esperado literal, para que mida la zona y no que los dos lados llamen
 * a la misma función.
 */
describe('Step1InfoGeneral — el piso de la fecha tentativa', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-08-25T02:30:00Z'))
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('no deja elegir antes de hoy en Lima, aunque el navegador ya esté en el día siguiente', () => {
    renderStep()
    expect(screen.getByLabelText(/fecha tentativa/i)).toHaveAttribute('min', '2026-08-24')
  })
})
