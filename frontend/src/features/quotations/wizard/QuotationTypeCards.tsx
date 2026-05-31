import { Truck, Wrench } from 'lucide-react'
import { Controller, type Control } from 'react-hook-form'
import { cn } from '../../../shared/utils/cn'
import type { WizardFormInput } from './quotation-wizard.schema'

const TYPE_OPTIONS = [
  {
    value: 'TRANSPORTE',
    icon: Truck,
    label: 'Transporte',
    description: 'Traslado de carga con origen y destino definidos',
  },
  {
    value: 'ALQUILER',
    icon: Wrench,
    label: 'Alquiler',
    description: 'Alquiler de unidades y equipos por días',
  },
] as const

interface QuotationTypeCardsProps {
  control: Control<WizardFormInput>
}

/** Selector visual del tipo de cotización (cards TRANSPORTE / ALQUILER). */
export function QuotationTypeCards({ control }: QuotationTypeCardsProps) {
  return (
    <Controller
      name="quotationType"
      control={control}
      render={({ field }) => (
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          {TYPE_OPTIONS.map(({ value, icon: Icon, label, description }) => {
            const selected = field.value === value
            return (
              <button
                key={value}
                type="button"
                onClick={() => field.onChange(value)}
                aria-pressed={selected}
                className={cn(
                  'flex flex-col items-center gap-2 rounded-xl border-2 p-5 text-center transition-colors focus:outline-none focus:ring-2 focus:ring-blue-500',
                  selected ? 'border-blue-600 bg-blue-50' : 'border-slate-200 hover:border-slate-300',
                )}
              >
                <Icon
                  className={cn('h-7 w-7', selected ? 'text-blue-600' : 'text-slate-400')}
                  aria-hidden="true"
                />
                <span className="text-sm font-semibold text-slate-900">{label}</span>
                <span className="text-xs text-slate-500">{description}</span>
              </button>
            )
          })}
        </div>
      )}
    />
  )
}
