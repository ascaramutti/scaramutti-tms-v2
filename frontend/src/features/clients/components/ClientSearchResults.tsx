import { ChevronRight } from 'lucide-react'
import { Link } from 'react-router-dom'
import type { ClientResponse } from '../../../api'
import { CLIENTS_BASE } from '../../../shared/paths'
import { Card } from '../../../shared/ui/Card'

interface ClientSearchResultsProps {
  clients: ClientResponse[]
  /** Total de coincidencias del backend, que puede ser mayor que las filas. */
  total: number
}

/**
 * Resultados de la búsqueda, una fila por cliente.
 *
 * Cada fila es un enlace de verdad y no una fila clickeable: el destino es una
 * navegación, así que tiene que soportar el clic del medio y "abrir en pestaña
 * nueva" como cualquier otro enlace de la aplicación.
 *
 * Muestra razón social y RUC, los dos campos que identifican; el teléfono y el
 * contacto describen y se ven al abrir. Es el mismo par que muestran los dos
 * combobox de alta al vuelo.
 *
 * Lleva al detalle y no al formulario: primero se mira el cliente y desde ahí se
 * decide editarlo, igual que en cotizaciones.
 */
export function ClientSearchResults({ clients, total }: ClientSearchResultsProps) {
  const hayMas = total > clients.length

  return (
    <Card padding="none">
      <ul className="divide-y divide-border">
        {clients.map((client) => (
          <li key={client.id}>
            <Link
              to={`${CLIENTS_BASE}/${client.id}`}
              aria-label={`Ver ${client.name}, RUC ${client.ruc}`}
              className="flex items-center justify-between gap-4 px-4 py-3 transition-colors hover:bg-surface-subtle focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-focus"
            >
              <span className="min-w-0">
                <span className="block truncate text-sm font-medium text-fg">
                  {client.name}
                </span>
                <span className="block text-xs text-fg-muted">RUC {client.ruc}</span>
              </span>
              <ChevronRight className="h-4 w-4 shrink-0 text-fg-subtle" aria-hidden="true" />
            </Link>
          </li>
        ))}
      </ul>
      {hayMas && (
        <p className="border-t border-border px-4 py-2 text-xs text-fg-muted">
          Se muestran los primeros {clients.length} de {total}. Escribe más letras del nombre o el
          RUC completo.
        </p>
      )}
    </Card>
  )
}
