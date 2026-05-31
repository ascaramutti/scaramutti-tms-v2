import { AlertCircle, Check } from 'lucide-react'
import { cn } from '../utils/cn'

export interface StepperStep {
  label: string
}

interface StepperProps {
  steps: StepperStep[]
  currentStep: number
  /** Máximo paso visitado (para habilitar navegación hacia atrás por el stepper). */
  maxVisitedStep?: number
  /** Índices de steps con error de validación. */
  stepErrors?: number[]
  onStepClick?: (index: number) => void
}

/**
 * Indicador de pasos del wizard. Estados por paso: actual, completado, con error
 * y pendiente. Los pasos ya visitados son navegables (botón) si se pasa `onStepClick`.
 */
export function Stepper({
  steps,
  currentStep,
  maxVisitedStep = 0,
  stepErrors = [],
  onStepClick,
}: StepperProps) {
  const lastIndex = steps.length - 1

  return (
    <ol className="flex items-center justify-center overflow-x-auto pb-1">
      {steps.map((step, index) => {
        const isActive = index === currentStep
        const hasError = stepErrors.includes(index) && !isActive
        const isCompleted = index < currentStep && !hasError
        const isClickable = !!onStepClick && index !== currentStep && index <= maxVisitedStep

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
          <li key={step.label} className="flex items-center">
            <button
              type="button"
              disabled={!isClickable}
              onClick={isClickable ? () => onStepClick(index) : undefined}
              aria-current={isActive ? 'step' : undefined}
              className={cn('flex flex-col items-center', isClickable ? 'cursor-pointer' : 'cursor-default')}
            >
              <span
                className={cn(
                  'flex h-8 w-8 items-center justify-center rounded-full border-2 text-sm font-medium transition-colors',
                  circleClass,
                )}
              >
                {content}
              </span>
              <span
                className={cn(
                  'mt-1 whitespace-nowrap text-xs',
                  index <= maxVisitedStep ? 'font-medium text-blue-700' : 'text-slate-400',
                )}
              >
                {step.label}
              </span>
            </button>
            {index < lastIndex && (
              <span
                aria-hidden="true"
                className={cn(
                  'mx-2 mt-[-16px] h-0.5 w-8 sm:w-12',
                  index < maxVisitedStep ? 'bg-blue-600' : 'bg-slate-300',
                )}
              />
            )}
          </li>
        )
      })}
    </ol>
  )
}
