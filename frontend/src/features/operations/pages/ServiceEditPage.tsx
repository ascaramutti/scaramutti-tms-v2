import { useState, type ReactNode } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { toast } from 'sonner'
import { BackLink } from '../../../shared/ui/BackLink'
import { EmptyState } from '../../../shared/ui/EmptyState'
import { PageHeader } from '../../../shared/ui/PageHeader'
import { Spinner } from '../../../shared/ui/Spinner'
import { OPERACIONES_LANDING } from '../../../shared/auth/roleLanding'
import { getApiErrorMessage, isNotFoundError } from '../../../shared/utils/getApiErrorMessage'
import { ServiceEditForm } from '../components/ServiceEditForm'
import { useService } from '../hooks/useService'
import { isServiceEditable } from '../status/serviceStatusTransitions'
import { SERVICE_STATUS_PRESENTATION } from '../status/serviceStatusPresentation'

const SECONDARY_LINK =
  'inline-flex items-center rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500'

/**
 * Edición de un viaje, con justificación obligatoria.
 *
 * Pantalla propia y no un diálogo, a diferencia de las acciones que mueven el viaje: el
 * formulario lleva todos los datos del viaje, y el usuario necesita verlos juntos
 * mientras corrige.
 *
 * Al guardar vuelve al detalle, que es donde queda la bitácora con lo que cambió y el
 * motivo. El despacho no llega acá: el cuerpo obliga a mandar el precio, que es
 * justamente lo que a ese rol se le oculta, así que la ruta lo filtra antes.
 */
export function ServiceEditPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const [reloadCount, setReloadCount] = useState(0)
  const serviceId = Number(id)
  // Un id no numérico entra igual a la ruta (el patrón no lo distingue): se resuelve acá
  // como "no encontrado" en vez de dispararle una request al backend.
  const invalidId = !Number.isInteger(serviceId) || serviceId <= 0

  const service = useService(invalidId ? 0 : serviceId)
  const detailPath = `${OPERACIONES_LANDING}/servicios/${serviceId}`

  if (invalidId || isNotFoundError(service.error)) {
    return (
      <Shell>
        <EmptyState
          title="No se encontró el servicio"
          description="Puede que el enlace esté mal o que el viaje ya no exista."
          action={
            <Link to={OPERACIONES_LANDING} className={SECONDARY_LINK}>
              Ir a servicios
            </Link>
          }
        />
      </Shell>
    )
  }

  if (service.isLoading) {
    return (
      <div className="flex justify-center py-24">
        <Spinner size={28} label="Cargando servicio" className="text-blue-600" />
      </div>
    )
  }

  if (service.isError || !service.data) {
    return (
      <Shell>
        <div className="text-center" role="alert">
          <p className="text-sm font-medium text-slate-700">
            {getApiErrorMessage(service.error, 'No se pudo cargar el servicio.')}
          </p>
          <button type="button" onClick={() => void service.refetch()} className={`mt-4 ${SECONDARY_LINK}`}>
            Reintentar
          </button>
        </div>
      </Shell>
    )
  }

  const data = service.data

  /*
   * Un viaje fuera del circuito es inmutable, y el servidor lo contesta con 409. La
   * pantalla lo explica en vez de dejar llenar el formulario entero para perderlo al
   * guardar.
   *
   * No se dice quién puede reabrirlo ni se ofrece el botón: esa decisión (y el permiso
   * que la gobierna) vive en el detalle, que es a donde lleva el enlace. Duplicarla acá
   * sería escribir la misma regla en dos lugares, y el que sobra envejece.
   */
  if (!isServiceEditable(data.status)) {
    return (
      // Sin el enlace del marco: el del aviso lleva al mismo lugar, y dos veces el mismo
      // destino con el mismo rótulo es una elección falsa. El del aviso es el que queda,
      // porque está donde el usuario está mirando.
      <Shell withBackLink={false}>
        <EmptyState
          title="Este viaje no se puede editar"
          description={`Este viaje está ${SERVICE_STATUS_PRESENTATION[data.status].label.toLowerCase()}, así que ya no admite correcciones. Vuelve al detalle para ver qué se puede hacer con él.`}
          action={
            <Link to={detailPath} className={SECONDARY_LINK}>
              Volver al detalle
            </Link>
          }
        />
      </Shell>
    )
  }

  return (
    <div className="mx-auto max-w-[860px] space-y-6 px-6 py-8">
      <BackLink to={detailPath}>Volver al detalle</BackLink>

      <PageHeader
        title={`Editar ${data.code}`}
        description={`${data.client.name} · cada cambio queda en la bitácora del viaje con su motivo.`}
        divider
      />

      <ServiceEditForm
        /*
         * El REMONTE es lo que descarta lo escrito y repone los datos del servidor: sin
         * él, invalidar refresca la versión pero el formulario conserva sus valores
         * congelados, y el próximo guardado mandaría el cuerpo viejo con el ETag nuevo,
         * pisando en silencio el cambio de la otra persona. O sea justo lo contrario de
         * lo que el botón promete. Mismo mecanismo que la edición de un retiro en
         * almacén, de donde sale el patrón: si se copia el texto hay que copiar esto.
         *
         * El contador acompaña a la versión porque recargar sobre un viaje que NO cambió
         * (el 412 vino por otra razón) deja la misma clave, y ahí el remonte igual tiene
         * que ocurrir para limpiar el aviso.
         */
        key={`${data._etag ?? data.updatedAt}-${reloadCount}`}
        service={data}
        onReload={() => {
          void service.refetch()
          setReloadCount((count) => count + 1)
        }}
        onSaved={(saved, changed) => {
          // Dos mensajes y no uno: el servidor descarta un cuerpo sin cambios reales sin
          // escribir nada, y decir "actualizado" ahí sería mentirle al usuario sobre lo
          // que pasó con lo que escribió.
          if (changed) {
            toast.success(`Servicio ${saved.code} actualizado.`)
          } else {
            toast.info(`No había nada que corregir: ${saved.code} quedó igual.`)
          }
          navigate(detailPath)
        }}
        onCancel={() => navigate(detailPath)}
      />
    </div>
  )
}

/**
 * El marco de la pantalla cuando no hay formulario que mostrar.
 *
 * El enlace de arriba es opcional porque no siempre corresponde: cuando el aviso de
 * adentro ya ofrece a dónde ir, repetirlo duplica el mismo destino con el mismo rótulo.
 */
function Shell({ withBackLink = true, children }: { withBackLink?: boolean; children: ReactNode }) {
  return (
    <div className="mx-auto max-w-[1024px] space-y-6 px-6 py-8">
      {withBackLink && <BackLink to={OPERACIONES_LANDING}>Volver a servicios</BackLink>}
      {children}
    </div>
  )
}
