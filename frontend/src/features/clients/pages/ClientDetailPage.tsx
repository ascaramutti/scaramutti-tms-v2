import { FileQuestion, Pencil } from 'lucide-react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { BackLink } from '../../../shared/ui/BackLink'
import { Button } from '../../../shared/ui/Button'
import { buttonClasses } from '../../../shared/ui/buttonClasses'
import { EmptyState } from '../../../shared/ui/EmptyState'
import { PageHeader } from '../../../shared/ui/PageHeader'
import { Spinner } from '../../../shared/ui/Spinner'
import { CLIENTS_BASE } from '../../../shared/paths'
import { useAuth } from '../../../shared/auth/AuthContext'
import { CLIENT_EDIT_ROLES } from '../../../shared/auth/moduleRoles'
import { getApiErrorMessage, isNotFoundError } from '../../../shared/utils/getApiErrorMessage'
import { Badge } from '../../../shared/ui/Badge'
import { ClientDetailCard } from '../components/ClientDetailCard'
import { useClient } from '../hooks/useClient'

/**
 * Ficha de un cliente, de solo lectura, con el botón para corregirlo.
 *
 * Se mira antes de editar, igual que una cotización: la búsqueda trae acá y
 * desde acá se decide. El formulario vuelve a esta pantalla al guardar, así que
 * el cambio se ve donde se pidió.
 *
 * El botón de editar se muestra por rol aunque la ruta del formulario ya lo
 * exija: ofrecer una acción que va a terminar en "Sin acceso" es peor que no
 * ofrecerla. La autoridad sigue siendo la guarda de la ruta y el 403 del
 * backend; esto solo decide qué se dibuja.
 */
export function ClientDetailPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const { user } = useAuth()
  const clientId = Number(id)
  const { data, isLoading, isError, error, refetch } = useClient(clientId)
  const canEdit = user?.role !== undefined && CLIENT_EDIT_ROLES.includes(user.role)

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
      <PageHeader
        title={data.name}
        description={`RUC ${data.ruc}`}
        divider
        action={
          canEdit ? (
            <Link
              to={`${CLIENTS_BASE}/${clientId}/editar`}
              className={buttonClasses({ variant: 'secondary' })}
            >
              <Pencil className="mr-2 h-4 w-4" aria-hidden="true" />
              Editar
            </Link>
          ) : undefined
        }
      />
      {!data.isActive && <Badge>Inactivo</Badge>}
      <ClientDetailCard client={data} />
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
