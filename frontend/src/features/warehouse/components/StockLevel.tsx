import { Badge } from '../../../shared/ui/Badge'
import { cn } from '../../../shared/utils/cn'
import { formatQuantity } from '../../../shared/utils/formatters'

interface StockLevelProps {
  stock: number
  /** Código de la unidad de medida del producto (UND, LT…). */
  unitCode: string
  /** Viene del backend (`lowStock`), NO se recalcula acá: la regla RN-WH11 vive en la VIEW. */
  low: boolean
}

/**
 * Stock actual de un producto con su unidad y, si está por debajo del mínimo,
 * el distintivo "Bajo". El color nunca es el único portador del significado:
 * siempre acompaña el texto del badge.
 */
export function StockLevel({ stock, unitCode, low }: StockLevelProps) {
  return (
    <span className="inline-flex items-center justify-end gap-2">
      <span className={cn('font-medium tabular-nums', low ? 'text-amber-700' : 'text-slate-900')}>
        {formatQuantity(stock)} {unitCode}
      </span>
      {low && <Badge variant="warning">Bajo</Badge>}
    </span>
  )
}
