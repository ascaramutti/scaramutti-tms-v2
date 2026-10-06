import type { ClientResponse } from '../../../api'
import { Card } from '../../../shared/ui/Card'
import { formatDate } from '../../../shared/utils/formatters'

interface ClientDetailCardProps {
  client: ClientResponse
}

/**
 * Un dato del cliente, con su rótulo. Las clases son las mismas que las de las
 * otras cuatro fichas del producto: sin eso, dos pantallas del mismo sistema se
 * ven hechas por manos distintas.
 *
 * La clase del ancho la recibe ESTE elemento y no un envoltorio: una lista de
 * definiciones solo admite pares directos, y meter un contenedor de más adentro
 * de otro la vuelve inválida.
 */
function Field({
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

/**
 * Ficha de solo lectura de un cliente: los cuatro datos editables más la fecha
 * de alta. El estado no vive acá sino arriba, al lado del título, y solo cuando
 * es la excepción, que es como lo marca el resto del producto.
 *
 * El dato opcional ausente se dice con el guion que usa toda la casa. Lo que no
 * puede quedar es en blanco: un espacio vacío no distingue "no tiene" de "no
 * cargó la pantalla".
 */
export function ClientDetailCard({ client }: ClientDetailCardProps) {
  return (
    <Card as="section" padding="md" aria-labelledby="cliente-datos">
      <h2 id="cliente-datos" className="text-sm font-semibold text-fg">
        Datos del cliente
      </h2>
      <dl className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-2">
        <Field label="Razón social" value={client.name} className="sm:col-span-2" />
        <Field label="RUC" value={client.ruc} />
        <Field label="Teléfono" value={client.phone ?? '—'} />
        <Field label="Persona de contacto" value={client.contactName ?? '—'} className="sm:col-span-2" />
        <Field label="Fecha de alta" value={formatDate(client.createdAt)} />
      </dl>
    </Card>
  )
}
