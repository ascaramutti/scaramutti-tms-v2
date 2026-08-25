import { useNavigate } from 'react-router-dom'
import { toast } from 'sonner'
import { BackLink } from '../../../shared/ui/BackLink'
import { PageHeader } from '../../../shared/ui/PageHeader'
import { OPERACIONES_LANDING } from '../../../shared/auth/roleLanding'
import { ServiceForm } from '../components/ServiceForm'

/**
 * Alta de un servicio.
 *
 * Al registrar abre el detalle del viaje recién creado: es donde se le asignan
 * recursos, que es lo próximo que alguien va a querer hacer con él, y evita
 * buscarlo en un listado del que acaba de salir. El aviso sigue diciendo el código
 * que asignó el servidor, que es el dato con el que después se lo busca.
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
          navigate(`${OPERACIONES_LANDING}/servicios/${service.id}`)
        }}
        onCancel={() => navigate(OPERACIONES_LANDING)}
      />
    </div>
  )
}
