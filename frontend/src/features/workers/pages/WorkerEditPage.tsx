import type { ReactNode } from 'react'
import { useIsMutating } from '@tanstack/react-query'
import { UserX } from 'lucide-react'
import { useNavigate, useParams } from 'react-router-dom'
import { toast } from 'sonner'
import type { RoleResponse, WorkerDetailResponse } from '../../../api'
import { Alert } from '../../../shared/ui/Alert'
import { BackLink } from '../../../shared/ui/BackLink'
import { Button } from '../../../shared/ui/Button'
import { EmptyState } from '../../../shared/ui/EmptyState'
import { PageHeader } from '../../../shared/ui/PageHeader'
import { Spinner } from '../../../shared/ui/Spinner'
import { WORKERS_BASE, workerDetailPath } from '../../../shared/paths'
import { getApiErrorMessage, isNotFoundError } from '../../../shared/utils/getApiErrorMessage'
import { WorkerAuditFooter } from '../components/WorkerAuditFooter'
import { WorkerEditForm } from '../components/WorkerEditForm'
import { WorkerStatusBadge } from '../components/WorkerStatusBadge'
import { useDocumentTypes } from '../hooks/useDocumentTypes'
import { useSessionRankLevel } from '../hooks/useSessionRankLevel'
import { useWorker } from '../hooks/useWorker'
import { workerKeys } from '../queryKeys'
import { assignableRoles, canManageWorker } from '../rank'

/** El aviso del 404, al abrir o al guardar: los trabajadores no se borran, así que es un id que no existe. */
const NOT_FOUND = 'Este trabajador no existe'

/**
 * Edición de un trabajador. Sin el trabajador y los dos catálogos no hay formulario. Si su cargo
 * es de nivel igual o mayor al de la sesión, no se ofrece: el backend lo rechazaría igual.
 */
export function WorkerEditPage() {
  const id = Number(useParams().id)
  const navigate = useNavigate()
  const worker = useWorker(id)
  const documentTypes = useDocumentTypes()
  const { level, roles } = useSessionRankLevel()

  if (worker.isLoading || documentTypes.isLoading || roles.isLoading) {
    return (
      <Shell id={id}>
        <div className="flex justify-center py-10">
          <Spinner size={28} label="Cargando trabajador" className="text-accent" />
        </div>
      </Shell>
    )
  }

  if (isNotFoundError(worker.error)) {
    return (
      <Shell id={id} notFound>
        <EmptyState
          icon={UserX}
          title={NOT_FOUND}
          description="Revisa el enlace o búscalo en el padrón."
          action={
            <Button variant="secondary" onClick={() => navigate(WORKERS_BASE)}>
              Volver a trabajadores
            </Button>
          }
        />
      </Shell>
    )
  }

  // Solo sin datos: si falla la recarga de un catálogo ya cargado, el formulario sigue con lo escrito.
  if (!worker.data || !documentTypes.data || !roles.data || level === undefined) {
    return (
      <Shell id={id}>
        <div role="alert" className="flex flex-col items-center gap-3 py-10 text-center">
          <p className="text-sm text-fg-body">
            {getApiErrorMessage(
              worker.error ?? documentTypes.error ?? roles.error,
              'No se pudo cargar el formulario. Intenta de nuevo.',
            )}
          </p>
          <Button
            variant="secondary"
            onClick={() => {
              void worker.refetch()
              void documentTypes.refetch()
              void roles.refetch()
            }}
          >
            Reintentar
          </Button>
        </div>
      </Shell>
    )
  }

  const data = worker.data
  if (!canManageWorker(data.role.level, level)) {
    return (
      <Shell id={id} worker={data}>
        <Alert variant="warning" role="status" className="px-4 py-3 text-sm">
          No puedes modificar a un trabajador de tu nivel o superior.
        </Alert>
      </Shell>
    )
  }

  return (
    <Shell id={id} worker={data}>
      <WorkerEditForm
        worker={data}
        documentTypes={documentTypes.data}
        roles={roles.data}
        roleOptions={editableRoles(roles.data, level, data)}
        handleOwnError={(error) => {
          if (!isNotFoundError(error)) return false
          toast.error(`${NOT_FOUND}.`)
          navigate(WORKERS_BASE, { replace: true })
          return true
        }}
        onSaved={(saved) => {
          toast.success(`Se guardaron los cambios de ${saved.firstName} ${saved.lastName}.`)
          navigate(workerDetailPath(saved.id))
        }}
        onCancel={() => navigate(workerDetailPath(id))}
        reloadCatalog={(catalog) => void (catalog === 'roles' ? roles.refetch() : documentTypes.refetch())}
      />
      <WorkerAuditFooter
        createdAt={data.createdAt}
        createdBy={data.createdBy}
        updatedAt={data.updatedAt}
        updatedBy={data.updatedBy}
      />
    </Shell>
  )
}

/** Los cargos que se le pueden dar: los de la sesión y, si tiene usuario, solo los que inician sesión. */
function editableRoles(roles: readonly RoleResponse[], level: number, worker: WorkerDetailResponse): RoleResponse[] {
  const assignable = assignableRoles(roles, level)
  return worker.hasUser ? assignable.filter((role) => role.canLogin) : assignable
}

interface ShellProps {
  id: number
  worker?: WorkerDetailResponse
  /** Sin trabajador no hay ficha a la que volver: el enlace lleva a la búsqueda. */
  notFound?: boolean
  children: ReactNode
}

function Shell({ id, worker, notFound = false, children }: ShellProps) {
  // Como Cancelar: con la edición en vuelo, volver queda deshabilitado; al responder abriría la ficha igual.
  const saving = useIsMutating({ mutationKey: workerKeys.update(id) }) > 0
  return (
    <div className="mx-auto max-w-[860px] space-y-6 px-6 py-8">
      <BackLink to={notFound ? WORKERS_BASE : workerDetailPath(id)} disabled={saving}>
        {notFound ? 'Volver a trabajadores' : 'Volver a la ficha'}
      </BackLink>
      <PageHeader
        title="Editar trabajador"
        description={worker ? `${worker.firstName} ${worker.lastName}` : undefined}
        action={worker ? <WorkerStatusBadge isActive={worker.isActive} /> : undefined}
        divider
      />
      {children}
    </div>
  )
}
