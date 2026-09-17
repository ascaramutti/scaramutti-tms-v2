import { FileQuestion } from 'lucide-react'
import { useNavigate, useParams } from 'react-router-dom'
import { toast } from 'sonner'
import { BackLink } from '../../../shared/ui/BackLink'
import { Button } from '../../../shared/ui/Button'
import { EmptyState } from '../../../shared/ui/EmptyState'
import { PageHeader } from '../../../shared/ui/PageHeader'
import { Spinner } from '../../../shared/ui/Spinner'
import { CLIENTS_BASE } from '../../../shared/paths'
import { getApiErrorMessage, isNotFoundError } from '../../../shared/utils/getApiErrorMessage'
import { ClientEditForm } from '../components/ClientEditForm'
import { useClient } from '../hooks/useClient'

/**
 * Corregir los datos de un cliente.
 *
 * El id no numérico no llega acá: la ruta lo desvía antes de evaluar permisos.
 * El hook de lectura igual no dispara con un id que no sea un entero positivo,
 * por si alguna vez se monta esta pantalla fuera de esa ruta.
 *
 * Al guardar y al cancelar se vuelve al detalle, que es de donde se entró. El
 * "ya no existe" va a la búsqueda, porque el detalle de un cliente borrado
 * tampoco tiene nada que mostrar.
 *
 * El "ya no existe" se resuelve distinto según cuándo aparece. Al abrir se
 * muestra en el lugar y se ofrece la vuelta, como las otras pantallas de edición
 * del producto: un rebote automático parpadea y se lleva la explicación de por
 * qué desapareció lo que el usuario acababa de elegir. Al guardar sí se vuelve
 * solo, porque el formulario en pantalla está editando un fantasma y quedarse
 * invita a un segundo intento.
 */
export function ClientEditPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const clientId = Number(id)
  const detailPath = `${CLIENTS_BASE}/${clientId}`
  const { data, isLoading, isError, error, refetch } = useClient(clientId)

  if (isLoading) {
    return (
      <Shell>
        <div className="flex justify-center py-10">
          <Spinner size={28} label="Cargando cliente" className="text-accent" />
        </div>
      </Shell>
    )
  }

  if (isNotFoundError(error)) {
    return (
      <Shell>
        <EmptyState
          icon={FileQuestion}
          title="Este cliente ya no existe"
          description="Puede que alguien lo haya dado de baja mientras lo buscabas."
          action={
            <Button variant="secondary" onClick={() => navigate(CLIENTS_BASE)}>
              Volver a clientes
            </Button>
          }
        />
      </Shell>
    )
  }

  if (isError || !data) {
    return (
      <Shell>
        <div role="alert" className="flex flex-col items-center gap-3 py-10 text-center">
          <p className="text-sm text-fg-body">
            {getApiErrorMessage(error, 'No se pudo cargar el cliente. Intenta de nuevo.')}
          </p>
          <Button variant="secondary" onClick={() => void refetch()}>
            Reintentar
          </Button>
        </div>
      </Shell>
    )
  }

  return (
    <Shell>
      <PageHeader title={`Editar ${data.name}`} description={`RUC ${data.ruc}`} divider />
      <ClientEditForm
        client={data}
        onSaved={(saved) => {
          toast.success(`${saved.name} actualizado.`)
          navigate(detailPath)
        }}
        onGone={() => {
          toast.error('Este cliente ya no existe.')
          navigate(CLIENTS_BASE, { replace: true })
        }}
        onCancel={() => navigate(detailPath)}
      />
    </Shell>
  )
}

function Shell({ children }: { children: React.ReactNode }) {
  return (
    <div className="mx-auto max-w-[860px] space-y-6 px-6 py-8">
      <BackLink to={CLIENTS_BASE}>Volver a clientes</BackLink>
      {children}
    </div>
  )
}
