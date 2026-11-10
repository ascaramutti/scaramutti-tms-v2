import { useEffect, useRef, useState } from 'react'
import type { ReactNode, Ref } from 'react'
import { KeyRound, Power, UserX } from 'lucide-react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { toast } from 'sonner'
import { useAuth } from '../../../shared/auth/AuthContext'
import { USERS_BASE, workerDetailPath } from '../../../shared/paths'
import { Alert } from '../../../shared/ui/Alert'
import { BackLink } from '../../../shared/ui/BackLink'
import { Button } from '../../../shared/ui/Button'
import { EmptyState } from '../../../shared/ui/EmptyState'
import { PageHeader } from '../../../shared/ui/PageHeader'
import { Spinner } from '../../../shared/ui/Spinner'
import { getApiErrorMessage, isForbiddenError, isNotFoundError } from '../../../shared/utils/getApiErrorMessage'
import { useSessionRankLevel } from '../../workers/hooks/useSessionRankLevel'
import { accountActions, REACTIVATE_BLOCKED_REASON } from '../accountActions'
import { AccountAuditFooter } from '../components/AccountAuditFooter'
import { AccountDetailCard } from '../components/AccountDetailCard'
import { AccountStatusDialog } from '../components/AccountStatusDialog'
import { AccountWorkerCard } from '../components/AccountWorkerCard'
import { ResetAccountPasswordDialog } from '../components/ResetAccountPasswordDialog'
import { useUserAccount } from '../hooks/useUserAccount'

const NOTICE = 'px-4 py-3 text-sm text-fg-body'
const LINK =
  'rounded font-medium text-accent hover:text-accent-hover hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-focus'

export function UserAccountDetailPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const { user } = useAuth()
  const { level } = useSessionRankLevel()
  const { data, isLoading, isError, error, refetch } = useUserAccount(Number(id))
  const [dialog, setDialog] = useState<'status' | 'reset' | null>(null)
  const shell = useRef<HTMLDivElement>(null)
  // Si el botón que abrió el diálogo desapareció al cerrar, el foco se perdería fuera de la página.
  useEffect(() => {
    const page = shell.current
    if (!dialog || !page) return
    return () => {
      if (document.activeElement === document.body) {
        const next =
          page.querySelector<HTMLElement>('header button') ?? page.querySelector<HTMLElement>(`a[href="${USERS_BASE}"]`)
        next?.focus()
      }
    }
  }, [dialog])

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

  const actions = accountActions(data, user, level)
  const closeDialog = () => setDialog(null)
  const goneToList = () => {
    toast.error('Esta cuenta no existe.')
    navigate(USERS_BASE, { replace: true })
  }
  return (
    <Shell ref={shell}>
      <PageHeader
        title={data.username}
        description={`${data.worker.fullName} · ${data.role.description}`}
        divider
        action={
          actions.canResetPassword ? (
            <div className="flex flex-wrap gap-3">
              <Button variant="secondary" onClick={() => setDialog('reset')}>
                <KeyRound className="mr-2 h-4 w-4" aria-hidden="true" />
                Restablecer contraseña
              </Button>
              {(actions.canDeactivate || actions.canReactivate) && (
                <Button
                  variant={data.isActive ? 'danger' : 'success'}
                  onClick={() => setDialog('status')}
                >
                  <Power className="mr-2 h-4 w-4" aria-hidden="true" />
                  {data.isActive ? 'Desactivar' : 'Reactivar'}
                </Button>
              )}
            </div>
          ) : undefined
        }
      />
      {actions.reactivateBlockedBy && (
        <Alert variant="warning" role={undefined} className={NOTICE}>
          {REACTIVATE_BLOCKED_REASON[actions.reactivateBlockedBy]}{' '}
          <Link to={workerDetailPath(data.worker.id)} className={LINK}>
            Ver la ficha de {data.worker.fullName}
          </Link>
        </Alert>
      )}
      {actions.isOwn && (
        <Alert variant="info" role={undefined} className={NOTICE}>
          Es tu cuenta: no puedes desactivarla. Tu contraseña la cambias desde Cambiar contraseña.
        </Alert>
      )}
      {actions.isOutOfRank && (
        <Alert variant="info" role={undefined} className={NOTICE}>
          Es de un cargo de tu nivel o superior: puedes verla, no modificarla.
        </Alert>
      )}
      <AccountDetailCard account={data} />
      <AccountWorkerCard worker={data.worker} />
      <AccountAuditFooter account={data} />
      {dialog === 'status' && <AccountStatusDialog account={data} onClose={closeDialog} onNotFound={goneToList} />}
      {dialog === 'reset' && <ResetAccountPasswordDialog account={data} onClose={closeDialog} onNotFound={goneToList} />}
    </Shell>
  )
}

function Shell({ children, ref }: { children: ReactNode; ref?: Ref<HTMLDivElement> }) {
  return (
    <div ref={ref} className="mx-auto max-w-[860px] space-y-6 px-6 py-8">
      <BackLink to={USERS_BASE}>Volver a usuarios</BackLink>
      {children}
    </div>
  )
}
