import { describe, expect, it } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useForm } from 'react-hook-form'
import { DateField } from './DateField'

function TestForm() {
  const { control, setError } = useForm<{ fecha: string }>({ defaultValues: { fecha: '' } })
  return (
    <form>
      <DateField<{ fecha: string }> id="fecha" label="Fecha" name="fecha" control={control} max="2026-08-24" />
      <button type="button" onClick={() => setError('fecha', { message: 'mal' }, { shouldFocus: true })}>
        Marcar
      </button>
    </form>
  )
}

describe('DateField', () => {
  it('lleva el tope al input nativo', () => {
    render(<TestForm />)
    expect(screen.getByLabelText('Fecha')).toHaveAttribute('max', '2026-08-24')
  })

  /** Con el ref, el formulario puede llevar el foco a la fecha que tiene el error. */
  it('el formulario puede enfocarlo', async () => {
    const user = userEvent.setup()
    render(<TestForm />)

    await user.click(screen.getByRole('button', { name: 'Marcar' }))
    await waitFor(() => expect(screen.getByLabelText('Fecha')).toHaveFocus())
  })
})
