import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { axe } from 'vitest-axe'
import { FormProvider, useForm, useWatch } from 'react-hook-form'
import { StepConditions } from './StepConditions'
import { WIZARD_DEFAULTS, type WizardFormInput } from './quotation-wizard.schema'
import type { ConditionResponse, QuotationConditionResponse } from '../../../api'

const ACTIVE_CATALOG: ConditionResponse[] = [
  { id: 1, text: 'Cond A', displayOrder: 1, isActive: true },
  { id: 2, text: 'Cond B', displayOrder: 2, isActive: true },
]

/** Espía de `conditionIds` del form: lo serializa en un <output> para asertarlo. */
function ConditionIdsProbe() {
  const conditionIds = useWatch<WizardFormInput, 'conditionIds'>({ name: 'conditionIds' })
  return <output data-testid="conditionIds">{JSON.stringify(conditionIds ?? [])}</output>
}

function renderStep(opts: {
  conditions?: ConditionResponse[]
  linkedConditions?: QuotationConditionResponse[]
  initialSelected?: number[]
} = {}) {
  const { conditions = ACTIVE_CATALOG, linkedConditions, initialSelected } = opts
  function Wrapper() {
    const methods = useForm<WizardFormInput>({
      defaultValues: {
        ...WIZARD_DEFAULTS,
        conditionIds: initialSelected ?? conditions.map((c) => c.id), // creación: pre-marca activas
      },
    })
    return (
      <FormProvider {...methods}>
        <StepConditions conditions={conditions} linkedConditions={linkedConditions} />
        <ConditionIdsProbe />
      </FormProvider>
    )
  }
  return render(<Wrapper />)
}

function selectedIds(): number[] {
  return JSON.parse(screen.getByTestId('conditionIds').textContent || '[]')
}

describe('StepConditions', () => {
  it('renderiza un checkbox por condición activa, con su texto como label', () => {
    renderStep()
    expect(screen.getByLabelText('Cond A')).toBeInTheDocument()
    expect(screen.getByLabelText('Cond B')).toBeInTheDocument()
  })

  it('en creación, las activas arrancan pre-marcadas (RN-07)', () => {
    renderStep()
    expect(screen.getByLabelText<HTMLInputElement>('Cond A').checked).toBe(true)
    expect(screen.getByLabelText<HTMLInputElement>('Cond B').checked).toBe(true)
  })

  it('desmarcar una condición la quita de conditionIds', async () => {
    renderStep()
    await userEvent.click(screen.getByLabelText('Cond A'))
    expect(screen.getByLabelText<HTMLInputElement>('Cond A').checked).toBe(false)
    expect(selectedIds()).toEqual([2])
  })

  it('volver a marcar la re-agrega', async () => {
    renderStep({ initialSelected: [2] })
    expect(screen.getByLabelText<HTMLInputElement>('Cond A').checked).toBe(false)
    await userEvent.click(screen.getByLabelText('Cond A'))
    expect(selectedIds()).toContain(1)
  })

  it('desmarcar todas → conditionIds vacío (válido)', async () => {
    renderStep()
    await userEvent.click(screen.getByLabelText('Cond A'))
    await userEvent.click(screen.getByLabelText('Cond B'))
    expect(selectedIds()).toEqual([])
  })

  it('catálogo sin activas → muestra estado vacío, sin checkboxes', () => {
    renderStep({ conditions: [], initialSelected: [] })
    expect(screen.getByText(/no hay condiciones generales/i)).toBeInTheDocument()
    expect(screen.queryAllByRole('checkbox')).toHaveLength(0)
  })

  it('edición: una condición linkeada inactiva se muestra deshabilitada como "ya no vigente"', () => {
    renderStep({
      conditions: ACTIVE_CATALOG,
      linkedConditions: [
        { id: 1, text: 'Cond A', displayOrder: 1, isActive: true },
        { id: 9, text: 'Cláusula retirada', displayOrder: 3, isActive: false },
      ],
      initialSelected: [1],
    })
    expect(screen.getByText(/ya no vigentes/i)).toBeInTheDocument()
    const inactive = screen.getByLabelText(/Cláusula retirada/i)
    expect(inactive).toBeDisabled()
    expect((inactive as HTMLInputElement).checked).toBe(false)
    // La inactiva NO se incluye en la selección (no es re-enviable → evita 409).
    expect(selectedIds()).toEqual([1])
  })

  it('los checkboxes se agrupan en un fieldset con nombre accesible', () => {
    renderStep()
    expect(screen.getByRole('group', { name: /condiciones generales/i })).toBeInTheDocument()
  })

  it('a11y: sin violaciones de accesibilidad (catálogo con activas)', async () => {
    const { container } = renderStep()
    expect(await axe(container)).toHaveNoViolations()
  })

  it('a11y: sin violaciones en estado vacío', async () => {
    const { container } = renderStep({ conditions: [], initialSelected: [] })
    expect(await axe(container)).toHaveNoViolations()
  })
})
