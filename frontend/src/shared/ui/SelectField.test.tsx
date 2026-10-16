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
  helperText,
  disabled,
}: {
  options: SelectOption[]
  onValue: (value: Values['campo']) => void
  error?: string
  helperText?: string
  disabled?: boolean
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
        helperText={helperText}
        disabled={disabled}
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

  it('sin ayuda ni error no apunta a ninguna descripción', () => {
    render(<TestForm options={[]} onValue={() => {}} />)
    expect(screen.getByLabelText('Campo')).not.toHaveAttribute('aria-describedby')
  })

  it('la ayuda va en el tono apagado y antes del error, que sigue en rojo', () => {
    render(<TestForm options={[]} onValue={() => {}} helperText="Ayuda" error="Mal" />)
    const ayuda = screen.getByText('Ayuda')
    expect(ayuda).toHaveClass('text-xs', 'text-fg-muted')
    expect(ayuda).not.toHaveClass('text-danger')
    expect(screen.getByText('Mal')).toHaveClass('text-danger')
    expect(ayuda.compareDocumentPosition(screen.getByText('Mal')) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  /** Como en el campo de texto: la ayuda y el error se anuncian juntos al enfocar el select. */
  it('anuncia la ayuda, y la ayuda y el error juntos', () => {
    const { rerender } = render(<TestForm options={[]} onValue={() => {}} helperText="Ayuda" />)
    expect(screen.getByLabelText('Campo')).toHaveAccessibleDescription('Ayuda')
    // La ayuda no es un error: no se anuncia sola al abrir la pantalla.
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()

    rerender(<TestForm options={[]} onValue={() => {}} helperText="Ayuda" error="Mal" />)
    expect(screen.getByLabelText('Campo')).toHaveAccessibleDescription('Ayuda Mal')
  })

  /** Un select fijo (un cargo que no se puede cambiar) se ve apagado, igual que un campo de texto. */
  it('deshabilitado lleva el estilo apagado de los campos', () => {
    render(<TestForm options={[]} onValue={() => {}} disabled />)
    expect(screen.getByLabelText('Campo')).toBeDisabled()
    // Contra literales y no contra la constante: si la constante pierde una clase, el test lo nota.
    expect(screen.getByLabelText('Campo')).toHaveClass(
      'disabled:bg-surface-subtle',
      'disabled:text-fg-subtle',
      'disabled:opacity-60',
      'disabled:cursor-not-allowed',
    )
  })

  /** Con el ref, el formulario puede llevar el foco al select que tiene el error. */
  it('el formulario puede enfocarlo', async () => {
    const user = userEvent.setup()
    render(<TestForm options={[{ value: 1, label: 'Uno' }]} onValue={() => {}} />)

    await user.click(screen.getByRole('button', { name: 'Marcar' }))
    await waitFor(() => expect(screen.getByLabelText('Campo')).toHaveFocus())
  })
})
