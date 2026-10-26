import { cn } from '../utils/cn'

/** La raya larga de las fichas, para una parte vacía. */
const EMPTY_PART = '\u2014'

interface PersonNameRowProps {
  firstName: string
  /** En blanco solo si el apellido de antes lo estaba: la migración lo copió entero al paterno. */
  paternalLastName: string
  /** Nulo si no tiene materno. */
  maternalLastName: string | null
  /** Para el margen dentro de la tarjeta. */
  className?: string
}

/**
 * El nombre de una persona en sus tres partes, juntas en una fila y antes que el documento: en la
 * grilla de dos columnas de una ficha, el materno quedaría junto al tipo de documento. Lleva su propia
 * `<dl>` porque un `div` dentro de otra agrupa un solo rótulo, y repite el marcado de los campos de
 * las fichas porque una pieza compartida no importa de las features.
 */
export function PersonNameRow({ firstName, paternalLastName, maternalLastName, className }: PersonNameRowProps) {
  const parts = [
    ['Nombres', firstName],
    ['Apellido paterno', paternalLastName],
    ['Apellido materno', maternalLastName],
  ] as const
  return (
    <dl aria-label="Nombre" className={cn('grid grid-cols-1 gap-4 sm:grid-cols-3', className)}>
      {parts.map(([label, value]) => (
        <div key={label}>
          <dt className="text-xs font-medium uppercase tracking-wide text-fg-muted">{label}</dt>
          <dd className="mt-0.5 text-sm text-fg">{value?.trim() ? value : EMPTY_PART}</dd>
        </div>
      ))}
    </dl>
  )
}
