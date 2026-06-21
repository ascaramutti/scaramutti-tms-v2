import { AlertCircle, Check } from 'lucide-react'
import { cn } from '../utils/cn'

export interface StepperStep {
  label: string
}

/** Estado de un paso ya "visitado/evaluado": completo (check) o con faltantes (alerta). */
export type StepStatus = 'completed' | 'error'

interface StepperProps {
  steps: StepperStep[]
  currentStep: number
  /**
   * Estado por paso evaluado. `'completed'` → check verde; `'error'` → alerta. Los
   * pasos sin entrada quedan pendientes. La navegación es LIBRE (no bloqueante): se
   * puede ir a cualquier paso; el gate real es el submit final.
   */
  stepStatus?: Record<number, StepStatus>
  onStepClick?: (index: number) => void
}

/** Diámetro del círculo (h-8 = 32px); su mitad ubica la línea conectora. */
const CIRCLE_SIZE = 'h-8 w-8'
const CONNECTOR_TOP = 'top-4' // 16px = mitad del círculo

/**
 * Indicador de pasos del wizard. Estados: actual, completado (check), con error
 * (alerta) y pendiente. Cualquier paso distinto del actual es navegable si se pasa
 * `onStepClick` — los pasos NO bloquean el avance, solo señalan lo que falta.
 *
 * Layout: cada paso es una columna de ANCHO IGUAL (`flex-1`), con el círculo centrado
 * arriba y el label centrado debajo. Así los círculos quedan equiespaciados y alineados
 * a la misma altura aunque los labels tengan distinto largo (los de 2 líneas cuelgan más
 * abajo sin desplazar el círculo). El conector es una línea de fondo (absolute) que une el
 * centro de cada círculo con el del siguiente; el círculo va con `z-10` para taparla.
 */
export function Stepper({ steps, currentStep, stepStatus = {}, onStepClick }: StepperProps) {
  const lastIndex = steps.length - 1

  return (
    <ol className="flex items-start">
      {steps.map((step, index) => {
        const isActive = index === currentStep
        const status = stepStatus[index]
        const hasError = status === 'error' && !isActive
        const isCompleted = status === 'completed' && !isActive
        const isVisited = isActive || status !== undefined
        const isClickable = !!onStepClick && !isActive
        const stateLabel = isCompleted ? ' (completado)' : hasError ? ' (con alerta)' : ''
        // El label puede traer `\n` (nombres en 2 líneas). En el nombre accesible lo colapsamos a
        // espacio: el lector de pantalla lo lee corrido y los selectores por texto matchean igual.
        const accessibleLabel = step.label.replace(/\n/g, ' ')

        let circleClass: string
        let content: React.ReactNode
        if (hasError) {
          circleClass = 'border-red-500 bg-white text-red-500'
          content = <AlertCircle className="h-4 w-4" aria-hidden="true" />
        } else if (isActive) {
          circleClass = 'border-blue-600 bg-blue-600 text-white'
          content = index + 1
        } else if (isCompleted) {
          circleClass = 'border-emerald-500 bg-emerald-500 text-white'
          content = <Check className="h-4 w-4" aria-hidden="true" />
        } else {
          circleClass = 'border-slate-300 bg-white text-slate-400'
          content = index + 1
        }

        return (
          <li key={step.label} className="relative flex flex-1 flex-col items-center">
            {/* Conector hacia el siguiente paso: línea de fondo desde el centro de este círculo
                hasta el centro del próximo (left-1/2 + w-full cruza media columna a cada lado). */}
            {index < lastIndex && (
              <span
                aria-hidden="true"
                className={cn(
                  'absolute left-1/2 h-0.5 w-full',
                  CONNECTOR_TOP,
                  status !== undefined ? 'bg-blue-600' : 'bg-slate-300',
                )}
              />
            )}
            <button
              type="button"
              disabled={!isClickable}
              onClick={isClickable ? () => onStepClick(index) : undefined}
              aria-current={isActive ? 'step' : undefined}
              aria-label={stateLabel ? `${accessibleLabel}${stateLabel}` : undefined}
              className={cn(
                'relative z-10 flex w-full flex-col items-center bg-transparent',
                isClickable ? 'cursor-pointer' : 'cursor-default',
              )}
            >
              <span
                className={cn(
                  'flex items-center justify-center rounded-full border-2 bg-white text-sm font-medium transition-colors',
                  CIRCLE_SIZE,
                  circleClass,
                )}
              >
                {content}
              </span>
              <span
                className={cn(
                  // whitespace-pre-line respeta los `\n` del label para partir los nombres largos
                  // en 2 líneas; text-center los alinea bajo el círculo. px-1 evita que rocen al vecino.
                  'mt-1 whitespace-pre-line px-1 text-center text-xs',
                  isVisited ? 'font-medium text-blue-700' : 'text-slate-400',
                )}
              >
                {step.label}
              </span>
            </button>
          </li>
        )
      })}
    </ol>
  )
}
