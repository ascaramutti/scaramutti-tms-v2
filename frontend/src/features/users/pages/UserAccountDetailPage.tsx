import type { ReactNode } from 'react'
import { UserX } from 'lucide-react'
import { useNavigate, useParams } from 'react-router-dom'
import { USERS_BASE } from '../../../shared/paths'
import { BackLink } from '../../../shared/ui/BackLink'
import { Button } from '../../../shared/ui/Button'
import { EmptyState } from '../../../shared/ui/EmptyState'
import { PageHeader } from '../../../shared/ui/PageHeader'
import { Spinner } from '../../../shared/ui/Spinner'
import { getApiErrorMessage, isForbiddenError, isNotFoundError } from '../../../shared/utils/getApiErrorMessage'
import { AccountAuditFooter } from '../components/AccountAuditFooter'
import { AccountDetailCard } from '../components/AccountDetailCard'
import { AccountWorkerCard } from '../components/AccountWorkerCard'
import { useUserAccount } from '../hooks/useUserAccount'

export function UserAccountDetailPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const { data, isLoading, isError, error, refetch } = useUserAccount(Number(id))

  if (isLoading) {
    return (
      <Shell>
        <div className="flex justify-center py-10">
          <Spinner size={28} label="Cargando cuenta" className="text-accent" />
        </div>
      </Shell>
    )
  }

  if (isNotFoundError(error)) {
    return (
      <Shell>
        <EmptyState
          icon={UserX}
          title="Esta cuenta no existe"
          description="Revisa el enlace o búscala en la lista de usuarios."
          action={
            <Button variant="secondary" onClick={() => navigate(USERS_BASE)}>
              Volver a usuarios
            </Button>
          }
        />
      </Shell>
    )
  }

  if (isError || !data) {
    const forbidden = isForbiddenError(error)
    return (
      <Shell>
        <div role="alert" className="flex flex-col items-center gap-3 py-10 text-center">
          <p className="text-sm text-fg-body">
            {getApiErrorMessage(
              error,
              forbidden ? 'No tienes permiso para ver esta cuenta.' : 'No se pudo cargar la cuenta. Intenta de nuevo.',
            )}
          </p>
          {/* Repetir un 403 no lo cambia. */}
          {!forbidden && (
            <Button variant="secondary" onClick={() => void refetch()}>
              Reintentar
            </Button>
          )}
        </div>
      </Shell>
    )
  }

  return (
    <Shell>
      <PageHeader title={data.username} description={`${data.worker.fullName} · ${data.role.description}`} divider />
      <AccountDetailCard account={data} />
      <AccountWorkerCard worker={data.worker} />
      <AccountAuditFooter account={data} />
    </Shell>
  )
}

function Shell({ children }: { children: ReactNode }) {
  return (
    <div className="mx-auto max-w-[860px] space-y-6 px-6 py-8">
      <BackLink to={USERS_BASE}>Volver a usuarios</BackLink>
      {children}
    </div>
  )
}
