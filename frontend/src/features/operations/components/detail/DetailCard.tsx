import type { ReactNode } from 'react'
import { Card } from '../../../../shared/ui/Card'

/**
 * Las piezas con las que se arman las fichas del detalle de un viaje.
 *
 * Se extraen acá porque las usan todas las fichas de la pantalla, y la alternativa
 * era la misma copia en cada archivo, que es de donde salen las fichas que se van
 * pareciendo cada vez menos.
 *
 * El mismo patrón ya vive copiado a mano en las pantallas de detalle de almacén.
 * Unificarlo es mudar esto a `shared/ui/`, y eso va en su propio cambio.
 */

interface DetailCardProps {
  /** Encabezado de la tarjeta. También es su nombre accesible. */
  title: string
  /** Id del encabezado, para el `aria-labelledby` de la sección. */
  headingId: string
  /**
   * Acción de la ficha, alineada con su encabezado.
   *
   * Va acá y no en una barra del encabezado de la pantalla porque cada acción muta
   * la ficha en la que está, así que el resultado aparece donde el usuario estaba
   * mirando. La ficha sin acción no cambia: el encabezado sigue ocupando su línea.
   */
  action?: ReactNode
  children: ReactNode
}

/** Tarjeta con su encabezado, anunciada como sección propia. */
export function DetailCard({ title, headingId, action, children }: DetailCardProps) {
  return (
    <Card as="section" padding="md" aria-labelledby={headingId}>
      <div className="flex items-start justify-between gap-3">
        <h2 id={headingId} className="text-sm font-semibold text-fg">
          {title}
        </h2>
        {action}
      </div>
      {children}
    </Card>
  )
}

/**
 * Un dato de la ficha: su rótulo y su valor. El valor ausente llega como guion.
 *
 * `className` es para el ancho dentro de la grilla (`col-span-*`). Va acá y no en
 * un `div` que lo envuelva porque un `<dl>` solo admite `dt`, `dd` y `div` como
 * hijos directos: envolverlo anidaría un `div` dentro de otro y deja de ser una
 * lista de definiciones para quien la lee con lector de pantalla.
 */
export function Field({
  label,
  value,
  className,
}: {
  label: string
  value: string
  className?: string
}) {
  return (
    <div className={className}>
      <dt className="text-xs font-medium uppercase tracking-wide text-fg-muted">{label}</dt>
      <dd className="mt-0.5 text-sm text-fg">{value}</dd>
    </div>
  )
}
