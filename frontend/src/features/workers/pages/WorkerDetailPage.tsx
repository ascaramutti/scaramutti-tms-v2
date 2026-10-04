import type { ReactNode } from 'react'
import { Pencil, UserX } from 'lucide-react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { BackLink } from '../../../shared/ui/BackLink'
import { Button } from '../../../shared/ui/Button'
import { buttonClasses } from '../../../shared/ui/buttonClasses'
import { EmptyState } from '../../../shared/ui/EmptyState'
import { PageHeader } from '../../../shared/ui/PageHeader'
import { Spinner } from '../../../shared/ui/Spinner'
import { WORKERS_BASE, workerEditPath } from '../../../shared/paths'
import { getApiErrorMessage, isNotFoundError } from '../../../shared/utils/getApiErrorMessage'
import { WorkerAuditFooter } from '../components/WorkerAuditFooter'
import { WorkerDetailCard } from '../components/WorkerDetailCard'
import { WorkerDriverCard } from '../components/WorkerDriverCard'
import { useSessionRankLevel } from '../hooks/useSessionRankLevel'
import { useWorker } from '../hooks/useWorker'
import { canManageWorker } from '../rank'

/**
 * Ficha de un trabajador. Sale del endpoint del detalle y no de la fila de la búsqueda,
 * que trae menos datos. Un 404 es un id que nunca existió: los trabajadores no se borran.
 * Se edita en su propia pantalla, y solo si el cargo es de nivel menor al de la sesión.
 */
export function WorkerDetailPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const { data, isLoading, isError, error, refetch } = useWorker(Number(id))
  const { level } = useSessionRankLevel()

  if (isLoading) {
    return (
      <Shell>
        <div className="flex justify-center py-10">
          <Spinner size={28} label="Cargando trabajador" className="text-accent" />
        </div>
      </Shell>
    )
  }

  if (isNotFoundError(error)) {
    return (
      <Shell>
        <EmptyState
          icon={UserX}
          title="Este trabajador no existe"
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

  if (isError || !data) {
    return (
      <Shell>
        <div role="alert" className="flex flex-col items-center gap-3 py-10 text-center">
          <p className="text-sm text-fg-body">
            {getApiErrorMessage(error, 'No se pudo cargar el trabajador. Intenta de nuevo.')}
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
        title={`${data.firstName} ${data.lastName}`}
        description={data.role.description}
        divider
        action={
          // Solo sobre cargos de nivel menor al de la sesión; el admin, siempre. Un inactivo también se edita.
          level !== undefined && canManageWorker(data.role.level, level) ? (
            <Link to={workerEditPath(data.id)} className={buttonClasses({ variant: 'secondary' })}>
              <Pencil className="mr-2 h-4 w-4" aria-hidden="true" />
              Editar
            </Link>
          ) : undefined
        }
      />
      <WorkerDetailCard worker={data} />
      {data.driver && <WorkerDriverCard driver={data.driver} />}
      <WorkerAuditFooter
        createdAt={data.createdAt}
        createdBy={data.createdBy}
        updatedAt={data.updatedAt}
        updatedBy={data.updatedBy}
      />
    </Shell>
  )
}

function Shell({ children }: { children: ReactNode }) {
  return (
    <div className="mx-auto max-w-[860px] space-y-6 px-6 py-8">
      <BackLink to={WORKERS_BASE}>Volver a trabajadores</BackLink>
      {children}
    </div>
  )
}
