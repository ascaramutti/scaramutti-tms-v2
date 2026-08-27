import { Link, useParams } from 'react-router-dom'
import { BackLink } from '../../../shared/ui/BackLink'
import { EmptyState } from '../../../shared/ui/EmptyState'
import { PageHeader } from '../../../shared/ui/PageHeader'
import { Spinner } from '../../../shared/ui/Spinner'
import { useAuth } from '../../../shared/auth/AuthContext'
import { OPERACIONES_LANDING } from '../../../shared/auth/roleLanding'
import { formatDate, formatDateTime } from '../../../shared/utils/formatters'
import { getApiErrorMessage, isNotFoundError } from '../../../shared/utils/getApiErrorMessage'
import { ServiceStatusBadge } from '../components/ServiceStatusBadge'
import { ServiceStatusActions } from '../components/status/ServiceStatusActions'
import { DetailCard } from '../components/detail/DetailCard'
import { ServiceInfoCards } from '../components/detail/ServiceInfoCards'
import { ServiceResources } from '../components/detail/ServiceResources'
import { ServiceTimeline } from '../components/detail/ServiceTimeline'
import { useService } from '../hooks/useService'
import { canOperateService, canSeeServicePrices } from '../status/operationsPermissions'

const SECONDARY_LINK =
  'inline-flex items-center rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500'

/**
 * Detalle de un viaje: lo que se sabe de él, y las acciones que lo mueven.
 *
 * Entran los cinco roles del módulo. El despacho lo ve sin los importes: el
 * servidor se los OMITE (RN-OP8), y la pantalla no arma la tarjeta del precio para
 * no dejar una ficha con guiones donde los demás ven un número.
 *
 * Desde acá se asignan los recursos del viaje y se lo mueve de estado. Las cinco
 * transiciones son un mismo endpoint; de ellas, iniciar, finalizar y cancelar ya se
 * ofrecen junto al badge del encabezado, y eliminar y reabrir llegan en su propio
 * cambio. Ventas entra a la pantalla pero no opera el viaje, así que ve las fichas
 * sin las acciones.
 */
export function ServiceDetailPage() {
  const { id } = useParams()
  const { user } = useAuth()
  const serviceId = Number(id)
  // Un id no numérico entra igual a la ruta (el patrón no lo distingue): se
  // resuelve acá como "no encontrado" en vez de dispararle una request al backend.
  const invalidId = !Number.isInteger(serviceId) || serviceId <= 0

  const service = useService(invalidId ? 0 : serviceId)

  if (invalidId || isNotFoundError(service.error)) {
    return (
      <div className="mx-auto max-w-[1024px] space-y-6 px-6 py-8">
        <BackLink to={OPERACIONES_LANDING}>Volver a servicios</BackLink>
        <EmptyState
          title="No se encontró el servicio"
          description="Puede que el enlace esté mal o que el viaje ya no exista."
          action={
            <Link to={OPERACIONES_LANDING} className={SECONDARY_LINK}>
              Ir a servicios
            </Link>
          }
        />
      </div>
    )
  }

  if (service.isLoading) {
    return (
      <div className="flex justify-center py-16">
        <Spinner size={28} label="Cargando servicio" className="text-blue-600" />
      </div>
    )
  }

  if (service.isError || !service.data) {
    return (
      <div className="mx-auto max-w-[1024px] space-y-6 px-6 py-8">
        <BackLink to={OPERACIONES_LANDING}>Volver a servicios</BackLink>
        <div role="alert" className="flex flex-col items-center justify-center py-16 text-center">
          <p className="text-sm font-medium text-slate-700">
            {getApiErrorMessage(service.error, 'No se pudo cargar el servicio.')}
          </p>
          <button
            type="button"
            onClick={() => void service.refetch()}
            className={`mt-4 ${SECONDARY_LINK}`}
          >
            Reintentar
          </button>
        </div>
      </div>
    )
  }

  const data = service.data

  return (
    <div className="mx-auto max-w-[1024px] space-y-6 px-6 py-8">
      <BackLink to={OPERACIONES_LANDING}>Volver a servicios</BackLink>

      {/* El estado y sus acciones van en su PROPIA fila, fuera del slot de acción del
          encabezado. Ahí adentro el bloque se acomoda al lado del título mientras entra
          y baja de línea cuando no, así que la pantalla se veía distinta según el ancho
          de la ventana. Acá abajo la fila es siempre la misma: el estado a la izquierda,
          las acciones a la derecha, y en anchos chicos los botones bajan sin pisar al
          badge. */}
      <div className="space-y-4 border-b border-slate-200 pb-5">
        <PageHeader
          title={data.code}
          description={`${data.client.name} · RUC ${data.client.ruc} · registrado el ${formatDate(data.createdAt)} por ${data.createdBy.fullName}`}
        />
        <div className="flex flex-wrap items-center justify-between gap-3">
          <ServiceStatusBadge status={data.status} />
          <ServiceStatusActions service={data} role={user?.role} />
        </div>
      </div>

      <ServiceInfoCards service={data} showPrice={canSeeServicePrices(user?.role)} />

      <ServiceResources service={data} canOperate={canOperateService(user?.role)} />

      <DetailCard title="Bitácora" headingId="service-timeline-heading">
        {/* La última actualización va acá y no en una ficha de datos: es un hecho
            sobre el rastro, no sobre el viaje, y la bitácora es donde ese rastro
            vive. Quién registró el viaje va en el encabezado, junto a la fecha del
            alta: son la misma frase partida, y separarlas obligaba a leer dos
            lugares para saber quién cargó el viaje y cuándo. */}
        <p className="mt-1 text-xs text-slate-500">
          Última actualización: {formatDateTime(data.updatedAt)}
        </p>
        <div className="mt-3">
          <ServiceTimeline events={data.events} />
        </div>
      </DetailCard>
    </div>
  )
}
