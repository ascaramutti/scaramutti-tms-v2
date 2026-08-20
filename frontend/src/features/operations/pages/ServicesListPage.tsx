import { Route } from 'lucide-react'
import { EmptyState } from '../../../shared/ui/EmptyState'
import { PageHeader } from '../../../shared/ui/PageHeader'

/**
 * Pantalla inicial del módulo Operaciones.
 *
 * Placeholder: existe para que la ruta, el item de menú y el aterrizaje del
 * despachador tengan a dónde llegar desde el primer día. El listado real (con
 * indicadores, filtros y tabla paginada) la reemplaza en su propia entrega.
 */
export function ServicesListPage() {
  return (
    <div className="mx-auto max-w-[1024px] space-y-6 px-6 py-8">
      <PageHeader
        title="Servicios"
        description="Control de viajes · estado y recursos asignados de cada servicio."
        divider
      />
      <EmptyState
        icon={Route}
        title="El listado de servicios todavía no está disponible"
        description="El listado va a mostrar los viajes registrados, con su estado y los recursos asignados. La pantalla está en preparación."
      />
    </div>
  )
}
