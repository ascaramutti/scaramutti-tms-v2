import { describe, expect, it } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { FormProvider, useForm } from 'react-hook-form'
import { QuotationNotesFields } from './QuotationNotesFields'
import { WIZARD_DEFAULTS, type WizardFormInput } from './quotation-wizard.schema'

/** Monta el componente dentro de un FormProvider (lee `register`/`watch` del contexto). */
function renderFields(defaults: Partial<WizardFormInput> = {}) {
  function Wrapper() {
    const methods = useForm<WizardFormInput>({
      defaultValues: { ...WIZARD_DEFAULTS, ...defaults },
    })
    return (
      <FormProvider {...methods}>
        <QuotationNotesFields />
      </FormProvider>
    )
  }
  return render(<Wrapper />)
}

describe('QuotationNotesFields', () => {
  it('renderiza los dos textarea con sus labels asociados', () => {
    renderFields()
    expect(screen.getByLabelText('Observaciones para el cliente')).toBeInTheDocument()
    expect(screen.getByLabelText('Observaciones internas')).toBeInTheDocument()
  })

  it('agrupa los campos en un fieldset "Observaciones"', () => {
    renderFields()
    expect(screen.getByRole('group', { name: /observaciones/i })).toBeInTheDocument()
  })

  it('marca la interna como "🔒 interno — no se muestra al cliente" (texto visible)', () => {
    renderFields()
    expect(screen.getByText(/interno — no se muestra al cliente/i)).toBeInTheDocument()
  })

  it('ambos textarea tienen maxLength=500 (L1)', () => {
    renderFields()
    expect(screen.getByLabelText('Observaciones para el cliente')).toHaveAttribute('maxlength', '500')
    expect(screen.getByLabelText('Observaciones internas')).toHaveAttribute('maxlength', '500')
  })

  it('tipear en clientNote actualiza el contador "{n}/500"', async () => {
    renderFields()
    const textarea = screen.getByLabelText('Observaciones para el cliente')
    await userEvent.type(textarea, 'Hola')
    expect(screen.getByText('4/500')).toBeInTheDocument()
  })

  it('L2 sanitize — pegar "a\\x00b\\x07c" deja "abc" (control-chars eliminados)', () => {
    renderFields()
    const textarea = screen.getByLabelText<HTMLTextAreaElement>('Observaciones para el cliente')
    // fireEvent.change simula un paste/entrada programática (un solo onChange con el valor crudo).
    fireEvent.change(textarea, { target: { value: 'a\x00b\x07c' } })
    expect(textarea.value).toBe('abc')
    expect(screen.getByText('3/500')).toBeInTheDocument()
  })

  it('L2 sanitize — conserva tab y salto de línea al pegar', () => {
    renderFields()
    const textarea = screen.getByLabelText<HTMLTextAreaElement>('Observaciones internas')
    fireEvent.change(textarea, { target: { value: 'l1\tx\nl2\x00' } })
    expect(textarea.value).toBe('l1\tx\nl2')
  })

  it('a11y: cada textarea encadena helper + contador en aria-describedby', () => {
    renderFields()
    const client = screen.getByLabelText('Observaciones para el cliente')
    expect(client).toHaveAttribute('aria-invalid', 'false')
    expect(client.getAttribute('aria-describedby')).toContain('quotation-client-note-helper')
    expect(client.getAttribute('aria-describedby')).toContain('quotation-client-note-counter')
  })

  it('edición: precargar el wizard muestra los textos en los textarea', () => {
    renderFields({ clientNote: 'Texto cliente precargado', internalNote: 'Texto interno precargado' })
    expect(screen.getByLabelText<HTMLTextAreaElement>('Observaciones para el cliente').value).toBe(
      'Texto cliente precargado',
    )
    expect(screen.getByLabelText<HTMLTextAreaElement>('Observaciones internas').value).toBe(
      'Texto interno precargado',
    )
  })
})
