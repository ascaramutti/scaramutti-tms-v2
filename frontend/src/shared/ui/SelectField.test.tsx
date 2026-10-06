import { describe, expect, it } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useForm, useWatch } from 'react-hook-form'
import { SelectField, type SelectOption } from './SelectField'

interface Values {
  campo: number | string | null
}

function TestForm({
  options,
  onValue,
  error,
}: {
  options: SelectOption[]
  onValue: (value: Values['campo']) => void
  error?: string
}) {
  const { control, handleSubmit, setError } = useForm<Values>({ defaultValues: { campo: null } })
  onValue(useWatch({ control, name: 'campo' }))
  return (
    <form onSubmit={handleSubmit(() => {}, () => {})}>
      <SelectField<Values>
        id="campo"
        label="Campo"
        name="campo"
        control={control}
        options={options}
        placeholder="Elige"
        error={error}
      />
      <button type="button" onClick={() => setError('campo', { message: 'mal' }, { shouldFocus: true })}>
        Marcar
      </button>
    </form>
  )
}

describe('SelectField', () => {
  /** Los usuarios de siempre (ids) siguen recibiendo números. */
  it('una opción numérica devuelve un número', async () => {
    const user = userEvent.setup()
    let valor: Values['campo'] = null
    render(<TestForm options={[{ value: 1, label: 'Uno' }, { value: 3, label: 'Tres' }]} onValue={(v) => (valor = v)} />)

    await user.selectOptions(screen.getByLabelText('Campo'), 'Tres')
    expect(valor).toBe(3)
  })

  it('una opción de texto devuelve el texto', async () => {
    const user = userEvent.setup()
    let valor: Values['campo'] = null
    render(<TestForm options={[{ value: 'driver', label: 'Conductor' }]} onValue={(v) => (valor = v)} />)

    await user.selectOptions(screen.getByLabelText('Campo'), 'Conductor')
    expect(valor).toBe('driver')
  })

  it('la opción vacía devuelve null', async () => {
    const user = userEvent.setup()
    let valor: Values['campo'] = 'x'
    render(<TestForm options={[{ value: 1, label: 'Uno' }]} onValue={(v) => (valor = v)} />)

    await user.selectOptions(screen.getByLabelText('Campo'), 'Uno')
    await user.selectOptions(screen.getByLabelText('Campo'), 'Elige')
    expect(valor).toBeNull()
  })

  /** Con el ref, el formulario puede llevar el foco al select que tiene el error. */
  it('el formulario puede enfocarlo', async () => {
    const user = userEvent.setup()
    render(<TestForm options={[{ value: 1, label: 'Uno' }]} onValue={() => {}} />)

    await user.click(screen.getByRole('button', { name: 'Marcar' }))
    await waitFor(() => expect(screen.getByLabelText('Campo')).toHaveFocus())
  })
})
