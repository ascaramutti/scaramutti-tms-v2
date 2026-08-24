import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { BackLink } from '../../../shared/ui/BackLink'
import { PageHeader } from '../../../shared/ui/PageHeader'
import { OPERACIONES_LANDING } from '../../../shared/auth/roleLanding'
import { ServiceForm } from '../components/ServiceForm'

/**
 * Alta de un servicio.
 *
 * Al registrar vuelve al listado con el código que el servidor asignó, que es el
 * dato con el que después se busca el viaje. El destino natural sería el detalle
 * del servicio recién creado (el alta ya devuelve su cuerpo completo), y ahí va a
 * apuntar cuando esa pantalla exista: es el único lugar que hay que cambiar.
 */
export function ServiceCreatePage() {
  const navigate = useNavigate()

  return (
    <div className="mx-auto max-w-[860px] space-y-6 px-6 py-8">
      <BackLink to={OPERACIONES_LANDING}>Volver a servicios</BackLink>

      <PageHeader
        title="Registrar servicio"
        description="Alta de un viaje · queda pendiente de asignación hasta que se le carguen recursos."
        divider
      />

      <ServiceForm
        onCreated={(service) => {
          toast.success(`Servicio ${service.code} registrado.`)
          navigate(OPERACIONES_LANDING)
        }}
        onCancel={() => navigate(OPERACIONES_LANDING)}
      />
    </div>
  )
}
