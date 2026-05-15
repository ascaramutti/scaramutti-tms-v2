import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import { useForm } from 'react-hook-form'
import { TextField } from './TextField'

interface TestFormProps {
  type?: 'text' | 'password' | 'email'
  placeholder?: string
  error?: string
  disabled?: boolean
  autoComplete?: string
}

/** Wrapper mínimo que provee un `register()` de react-hook-form válido. */
function TestForm({ type, placeholder, error, disabled, autoComplete }: TestFormProps) {
  const { register } = useForm<{ field: string }>()
  return (
    <TextField
      id="field"
      label="Campo de prueba"
      type={type}
      placeholder={placeholder}
      autoComplete={autoComplete}
      error={error}
      disabled={disabled}
      register={register('field')}
    />
  )
}

describe('TextField', () => {
  it('renderiza un label asociado al input vía htmlFor/id', () => {
    render(<TestForm />)
    const input = screen.getByLabelText(/campo de prueba/i)
    expect(input).toBeInTheDocument()
    expect(input).toHaveAttribute('id', 'field')
  })

  it('type por default es "text"', () => {
    render(<TestForm />)
    expect(screen.getByLabelText(/campo de prueba/i)).toHaveAttribute('type', 'text')
  })

  it('type="password" aplica placeholder default de bullets', () => {
    render(<TestForm type="password" />)
    const input = screen.getByLabelText(/campo de prueba/i)
    expect(input).toHaveAttribute('type', 'password')
    expect(input).toHaveAttribute('placeholder', '••••••••')
  })

  it('placeholder custom sobreescribe el default de password', () => {
    render(<TestForm type="password" placeholder="tu clave" />)
    expect(screen.getByLabelText(/campo de prueba/i)).toHaveAttribute('placeholder', 'tu clave')
  })

  it('cuando hay error: muestra mensaje con role="alert" y aplica aria-invalid', () => {
    render(<TestForm error="Campo requerido" />)
    const input = screen.getByLabelText(/campo de prueba/i)
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(input).toHaveAttribute('aria-describedby', 'field-error')
    const alert = screen.getByRole('alert')
    expect(alert).toHaveTextContent('Campo requerido')
    expect(alert).toHaveAttribute('id', 'field-error')
  })

  it('sin error: no aplica aria-invalid ni aria-describedby', () => {
    render(<TestForm />)
    const input = screen.getByLabelText(/campo de prueba/i)
    expect(input).toHaveAttribute('aria-invalid', 'false')
    expect(input).not.toHaveAttribute('aria-describedby')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('disabled=true deja el input no editable', () => {
    render(<TestForm disabled />)
    expect(screen.getByLabelText(/campo de prueba/i)).toBeDisabled()
  })

  it('autoComplete se aplica al input', () => {
    render(<TestForm autoComplete="username" />)
    expect(screen.getByLabelText(/campo de prueba/i)).toHaveAttribute('autocomplete', 'username')
  })
})
