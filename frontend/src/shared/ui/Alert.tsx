import type { ElementType, ReactNode } from 'react'
import { cn } from '../utils/cn'
import { alertClasses } from './alertClasses'
import type { AlertVariant } from './alertClasses'

type AlertProps<E extends ElementType> = {
  /**
   * El elemento. Por omisión `div`, pero ocho de los avisos del árbol son `<p>` (las
   * franjas de error bajo un formulario) y convertirlos en `div` cambiaría el marcado sin
   * necesidad. La prop existe para no tener que elegir entre convertir y no tocar.
   */
  as?: E
  variant?: AlertVariant
  /** `false` para las ocho franjas rojas de error de formulario, que no llevan borde. */
  bordered?: boolean
  /**
   * ⚠️ OBLIGATORIO, y el componente NO lo elige. `alert` interrumpe al lector de pantalla,
   * `status` no, y no son intercambiables: el error que frena una operación es `alert`, el
   * aviso que acompaña es `status`. Cada sitio conserva el que ya tenía, copiado uno por
   * uno; poner un valor por omisión acá haría que la mudanza cambie en silencio cómo se
   * anuncian los avisos.
   *
   * Incluye `undefined` a propósito, y sigue siendo obligatoria: cuatro de los avisos del
   * árbol no tienen rol (son cajas informativas que acompañan al contenido, no anuncios), y
   * ponerles uno les daría una voz que hoy no tienen. Escribir `role={undefined}` obliga a
   * que esa también sea una decisión escrita y no un olvido.
   */
  role: 'alert' | 'status' | undefined
  className?: string
  children?: ReactNode
} & Omit<React.ComponentPropsWithoutRef<E>, 'as' | 'variant' | 'bordered' | 'role' | 'className' | 'children'>

/**
 * Aviso de estado. Reemplaza a las 35 cajas de fondo suave que el árbol escribía a mano,
 * con los mismos valores expresados en tokens del tema.
 *
 * Se queda con el color y no con la forma: ver `alertClasses`. Lo que el sitio traiga por
 * `className` (su espaciado, su `text-sm`, su `flex`) pasa tal cual y gana, porque `cn` es
 * `twMerge`.
 */
export function Alert<E extends ElementType = 'div'>({
  as,
  variant = 'danger',
  bordered = true,
  className,
  children,
  ...rest
}: AlertProps<E>) {
  const Elemento = (as ?? 'div') as ElementType
  return (
    <Elemento className={cn(alertClasses({ variant, bordered }), className)} {...rest}>
      {children}
    </Elemento>
  )
}
