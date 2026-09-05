import type { UseFormRegisterReturn } from 'react-hook-form'
import { cn } from '../utils/cn'
import {
  FIELD_DISABLED,
  FIELD_ERROR,
  FIELD_FOCUS_INVALID,
  FIELD_HELPER,
  FIELD_LABEL,
  FIELD_PLACEHOLDER,
  fieldClasses,
} from './fieldClasses'

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
  /** Ayuda bajo el campo, anunciada por lector de pantalla (como en `Textarea`). */
  helperText?: string
  disabled?: boolean
  /** Para `type='number'`: límites y paso del input nativo (acotan el spinner + validación HTML).
   * `step='any'` acepta decimales sin atarlos a una escala: un `step` numérico chico (0.01) hace
   * que el browser marque "step mismatch" por redondeo de coma flotante en valores válidos. */
  min?: number
  max?: number
  step?: number | 'any'
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
  helperText,
  disabled,
  min,
  max,
  step,
  labelClassName,
  register,
}: TextFieldProps) {
  const finalPlaceholder =
    placeholder ?? (type === 'password' ? PASSWORD_PLACEHOLDER : undefined)

  // Encadenado como en `Textarea`: la ayuda y el error se anuncian juntos al
  // enfocar el campo, no solo el error.
  const helperId = helperText ? `${id}-helper` : undefined
  const errorId = error ? `${id}-error` : undefined
  const describedBy = [helperId, errorId].filter(Boolean).join(' ') || undefined

  return (
    <div>
      <label htmlFor={id} className={cn(FIELD_LABEL, labelClassName)}>
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
        aria-describedby={describedBy}
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
          'w-full',
          fieldClasses({ invalid: !!error }),
          FIELD_PLACEHOLDER,
          FIELD_DISABLED,
          error && FIELD_FOCUS_INVALID,
        )}
        {...register}
      />
      {helperText && (
        <p id={helperId} className={FIELD_HELPER}>
          {helperText}
        </p>
      )}
      {error && (
        <p id={errorId} role="alert" className={FIELD_ERROR}>
          {error}
        </p>
      )}
    </div>
  )
}
