import { Boxes } from 'lucide-react'
import { PageHeader } from '../../../shared/ui/PageHeader'
import { EmptyState } from '../../../shared/ui/EmptyState'

/**
 * Portada del módulo mientras se construyen sus pantallas.
 *
 * Existe porque el shell ya rutea acá: los roles de almacén aterrizan en esta
 * dirección al iniciar sesión y el menú apunta a ella. La reemplaza la pantalla
 * de Existencias, que es la portada definitiva del módulo.
 */
export function WarehouseHomePage() {
  return (
    <div className="mx-auto max-w-[1024px] space-y-6 px-6 py-8">
      <PageHeader
        title="Almacén"
        description="Control de inventario · Transportes Scaramutti S.A.C."
        divider
      />
      <EmptyState
        icon={Boxes}
        title="El módulo se está construyendo"
        description="Las pantallas de existencias, entradas, retiros y reportes se habilitan en las próximas entregas."
      />
    </div>
  )
}
