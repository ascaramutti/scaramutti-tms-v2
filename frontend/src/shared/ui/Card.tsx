import type { ElementType, ReactNode } from 'react'
import { cn } from '../utils/cn'
import { cardClasses } from './cardClasses'
import type { CardPadding } from './cardClasses'

/**
 * `as` porque no todas las tarjetas son un `<div>`: hay `<section>` cuando la tarjeta es una
 * región con encabezado, `<ul>` cuando es una lista, `<label>` cuando es una opción que se
 * elige con el teclado, y **dos `<button>`**, que son los tiles de KPI de almacén y de
 * operaciones. Ese último caso es el que obliga a la prop: un tile que se clickea tiene que
 * seguir siendo un botón, no un `<div>` con `onClick`, o pierde el foco y el teclado.
 *
 * Las props que sobran viajan al elemento por `...rest`, así que un tile conserva su
 * `onClick`, su `type` y su `aria-pressed`, y una sección su `aria-labelledby`.
 */
type CardProps<E extends ElementType> = {
  as?: E
  padding?: CardPadding
  /** `false` solo para los paneles internos del asistente, que hoy no llevan sombra. */
  elevated?: boolean
  className?: string
  children?: ReactNode
} & Omit<React.ComponentPropsWithoutRef<E>, 'as' | 'padding' | 'className' | 'children'>

/**
 * Tarjeta compartida. Reemplaza a la forma que 57 sitios escribían a mano, con los mismos
 * valores expresados en tokens del tema: `bg-white` pasa a `bg-surface` y `border-slate-200`
 * a `border-border`, los dos con el mismo valor, así que en modo claro no cambia un pixel.
 *
 * El `rounded-xl` y el `shadow-sm` no se tokenizan: no son color.
 */
export function Card<E extends ElementType = 'div'>({
  as,
  padding = 'lg',
  elevated = true,
  className,
  children,
  ...rest
}: CardProps<E>) {
  const Elemento = (as ?? 'div') as ElementType
  return (
    <Elemento className={cn(cardClasses({ padding, elevated }), className)} {...rest}>
      {children}
    </Elemento>
  )
}
