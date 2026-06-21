import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { axe } from 'vitest-axe'
import { FormProvider, useForm } from 'react-hook-form'
import { Step4Resumen } from './Step4Resumen'
import { WIZARD_DEFAULTS, ITEM_DEFAULTS, type WizardFormInput } from './quotation-wizard.schema'
import type {
  ConditionResponse,
  CurrencyResponse,
  PaymentTermResponse,
  QuotationServiceTypeResponse,
} from '../../../api'

const CURRENCIES: CurrencyResponse[] = [
  { id: 2, code: 'PEN', symbol: 'S/', name: 'Soles', isActive: true },
]
const PAYMENT_TERMS: PaymentTermResponse[] = [{ id: 1, name: 'Contado', days: 0, isActive: true }]
const SERVICE_TYPES: QuotationServiceTypeResponse[] = [
  { id: 2, code: 'CES', name: 'Escolta armada', kind: 'COMPLEMENTARIO', isActive: true },
]
const CONDITIONS: ConditionResponse[] = [
  { id: 1, text: 'Cond A', displayOrder: 1, isActive: true },
  { id: 2, text: 'Cond B', displayOrder: 2, isActive: true },
]

/** Monta el Resumen dentro de un FormProvider con los valores del form dados. */
function renderResumen(formOverrides: Partial<WizardFormInput> = {}, conditions = CONDITIONS) {
  function Wrapper() {
    const methods = useForm<WizardFormInput>({
      defaultValues: { ...WIZARD_DEFAULTS, ...formOverrides },
    })
    return (
      <FormProvider {...methods}>
        <Step4Resumen
          selectedClient={null}
          currencies={CURRENCIES}
          paymentTerms={PAYMENT_TERMS}
          serviceTypes={SERVICE_TYPES}
          conditions={conditions}
          igvPercentage={18}
        />
      </FormProvider>
    )
  }
  return render(<Wrapper />)
}

/** Un ítem válido para que el Resumen renderice la tabla (no el estado vacío). */
const ONE_ITEM = [{ ...ITEM_DEFAULTS, serviceTypeId: 2, serviceKind: 'COMPLEMENTARIO' as const, unitPrice: 1000 }]

describe('Step4Resumen — read-only (ampliación US-007)', () => {
  it('es read-only puro: no tiene textareas ni checkboxes editables', () => {
    const { container } = renderResumen({
      items: ONE_ITEM,
      conditionIds: [1, 2],
      clientNote: 'Nota cliente',
      internalNote: 'Nota interna',
    })
    expect(container.querySelector('textarea')).toBeNull()
    expect(screen.queryAllByRole('checkbox')).toHaveLength(0)
  })

  it('lista las condiciones elegidas, ordenadas por displayOrder', () => {
    renderResumen({ items: ONE_ITEM, conditionIds: [2, 1] }) // ids en orden inverso
    const a = screen.getByText('Cond A')
    const b = screen.getByText('Cond B')
    // Cond A (displayOrder 1) aparece antes que Cond B (displayOrder 2) en el DOM.
    expect(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('omite del Resumen una condición no seleccionada (refleja el form)', () => {
    renderResumen({ items: ONE_ITEM, conditionIds: [2] }) // A no seleccionada
    expect(screen.getByText('Cond B')).toBeInTheDocument()
    expect(screen.queryByText('Cond A')).not.toBeInTheDocument()
  })

  it('sin condiciones seleccionadas → no muestra el bloque de condiciones', () => {
    renderResumen({ items: ONE_ITEM, conditionIds: [] })
    expect(screen.queryByRole('heading', { name: /condiciones generales/i })).not.toBeInTheDocument()
  })

  it('muestra las observaciones read-only (cliente + interna con su marca)', () => {
    renderResumen({
      items: ONE_ITEM,
      clientNote: 'Para el cliente',
      internalNote: 'Solo interno',
    })
    expect(screen.getByText('Para el cliente')).toBeInTheDocument()
    expect(screen.getByText('Solo interno')).toBeInTheDocument()
    expect(screen.getByText('interno')).toBeInTheDocument() // badge exacto (no el texto "Solo interno")
  })

  it('el texto de la condición se renderiza literal (sin HTML inyectado)', () => {
    const { container } = renderResumen(
      { items: ONE_ITEM, conditionIds: [1] },
      [{ id: 1, text: '<img src=x onerror=alert(1)>', displayOrder: 1, isActive: true }],
    )
    expect(screen.getByText('<img src=x onerror=alert(1)>')).toBeInTheDocument()
    expect(container.querySelector('img')).toBeNull()
  })

  it('a11y: sin violaciones (resumen completo read-only)', async () => {
    const { container } = renderResumen({
      items: ONE_ITEM,
      conditionIds: [1, 2],
      clientNote: 'X',
      internalNote: 'Y',
    })
    expect(await axe(container)).toHaveNoViolations()
  })
})
