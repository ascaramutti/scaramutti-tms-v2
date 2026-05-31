import type { UseFormRegisterReturn } from 'react-hook-form'
import { cn } from '../utils/cn'

interface TextFieldProps {
  /** Id del input. Se usa también como base del id del mensaje de error. */
  id: string
  /** Label visible y asociado al input vía htmlFor. */
  label: string
  /** Tipo HTML del input. Default `'text'`. */
  type?: 'text' | 'password' | 'email' | 'tel' | 'number'
  /** Atributo `autocomplete` HTML (sugerencias del browser). */
  autoComplete?: string
  /** Placeholder. Si type='password' y no se pasa, usa '••••••••'. */
  placeholder?: string
  /** Mensaje de error inline (suele venir de `formState.errors[field].message`). */
  error?: string
  disabled?: boolean
  /** Para `type='number'`: límites y paso del input nativo (acotan el spinner + validación HTML). */
  min?: number
  max?: number
  step?: number
  /** Clases extra para el `<label>` (ej. reservar altura en grids multi-columna para alinear). */
  labelClassName?: string
  /** Registro de react-hook-form: `register('fieldName')`. */
  register: UseFormRegisterReturn
}

const PASSWORD_PLACEHOLDER = '••••••••'

/**
 * Input de texto reusable. Encapsula label + input + mensaje de error + a11y
 * (aria-invalid, aria-describedby, role="alert").
 *
 * Diseño consistente con el sistema (focus ring azul, estado de error con
 * borde y ring rojos, estado disabled). Para forms con varios campos del
 * mismo tipo (login, change-password, registros futuros), reusable sin
 * duplicar clases.
 */
export function TextField({
  id,
  label,
  type = 'text',
  autoComplete,
  placeholder,
  error,
  disabled,
  min,
  max,
  step,
  labelClassName,
  register,
}: TextFieldProps) {
  const finalPlaceholder =
    placeholder ?? (type === 'password' ? PASSWORD_PLACEHOLDER : undefined)

  return (
    <div>
      <label htmlFor={id} className={cn('mb-1.5 block text-sm font-medium text-slate-700', labelClassName)}>
        {label}
      </label>
      <input
        id={id}
        type={type}
        min={min}
        max={max}
        step={step}
        autoComplete={autoComplete}
        aria-invalid={!!error}
        aria-describedby={error ? `${id}-error` : undefined}
        disabled={disabled}
        placeholder={finalPlaceholder}
        onKeyDown={
          // En inputs numéricos, bloquear signo/notación científica (ej. evita teclear "-8").
          // El rango efectivo lo dan min/max + la validación zod.
          type === 'number'
            ? (event) => {
                if (['e', 'E', '+', '-'].includes(event.key)) event.preventDefault()
              }
            : undefined
        }
        className={cn(
          'w-full rounded-lg border bg-white px-3.5 py-2.5 text-sm text-slate-900 placeholder:text-slate-400',
          'focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500',
          'disabled:bg-slate-50 disabled:text-slate-400 disabled:cursor-not-allowed',
          error
            ? 'border-red-300 focus:ring-red-500 focus:border-red-500'
            : 'border-slate-300',
        )}
        {...register}
      />
      {error && (
        <p id={`${id}-error`} role="alert" className="mt-1.5 text-sm text-red-600">
          {error}
        </p>
      )}
    </div>
  )
}
