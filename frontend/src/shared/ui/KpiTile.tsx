import type { ElementType, ReactNode } from 'react'
import type { LucideIcon } from 'lucide-react'
import { Card } from './Card'
import { Spinner } from './Spinner'
import { cn } from '../utils/cn'

/**
 * `as` por el mismo motivo que en `Card`, que es quien dibuja la caja: de los diez tiles del
 * árbol hay uno que se clickea (el de stock bajo, que aplica su corte a la tabla) y tiene que
 * seguir siendo un `<button>`. Las props que sobran viajan a `Card` y de ahí al elemento, así
 * que ese tile conserva su `onClick`, su `type` y su `aria-pressed`.
 */
type KpiTileProps<E extends ElementType> = {
  as?: E
  /** El rótulo del indicador, en versalitas. */
  label: string
  /**
   * El número. Es un nodo y no un `number` porque dos tiles muestran una razón ("3 de 5 de
   * alta") con la segunda mitad en otro tamaño. `undefined` se dibuja como guion: el cero es
   * un dato y se muestra.
   */
  value?: ReactNode
  icon?: LucideIcon
  isLoading?: boolean
  /** El valor en ámbar, para el indicador que está pidiendo atención. */
  highlight?: boolean
  className?: string
} & Omit<
  React.ComponentPropsWithoutRef<E>,
  'as' | 'label' | 'value' | 'icon' | 'isLoading' | 'highlight' | 'className' | 'children'
>

/**
 * Tile de indicador. Reemplaza al cuerpo que los dos strips de KPI escribían por su cuenta,
 * con las mismas clases expresadas en tokens: los cuatro colores que usa valen lo mismo antes
 * y después, así que en modo claro no cambia un pixel.
 *
 * DEUDA D-15, que este componente NO arregla pero es el lugar donde se arregla: cada tile en
 * carga dibuja su propio `Spinner`, y el `Spinner` es un `role="status"` con `aria-live`. Seis
 * tiles cargando anuncian "Cargando indicadores" seis veces seguidas. La salida es que el
 * anuncio lo dé el grupo una sola vez y los tiles muestren la animación sin rol; se hace acá
 * porque acá está el único `Spinner` de los seis.
 */
export function KpiTile<E extends ElementType = 'div'>({
  as,
  label,
  value,
  icon: Icon,
  isLoading = false,
  highlight = false,
  className,
  ...rest
}: KpiTileProps<E>) {
  // El cast es el mismo recurso que usa `Card` con su propio elemento: TypeScript no puede
  // seguir la genérica a través de dos componentes polimórficos encadenados, y afirmarlo acá
  // es más honesto que aflojar el tipo de la prop, que es lo que protege al que lo llama.
  const Contenedor = Card as (props: Record<string, unknown>) => ReactNode
  return (
    <Contenedor
      as={as}
      padding="md"
      className={cn('text-left', className)}
      {...(rest as Record<string, unknown>)}
    >
      <span className="flex items-center gap-2 text-xs font-medium uppercase tracking-wide text-fg-muted">
        {Icon && <Icon className="h-4 w-4" aria-hidden="true" />}
        {label}
      </span>
      {isLoading ? (
        <Spinner size={18} label="Cargando indicadores" className="mt-2 text-accent" />
      ) : (
        <span
          className={cn(
            'mt-2 block text-2xl font-semibold tabular-nums',
            highlight ? 'text-warning' : 'text-fg',
          )}
        >
          {/* `0` es un dato, no un vacío: solo el undefined (stats en error) cae al guion. */}
          {value ?? '—'}
        </span>
      )}
    </Contenedor>
  )
}
