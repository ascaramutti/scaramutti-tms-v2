import { useEffect, useRef, useState, type ReactNode, type Ref } from 'react'
import { useIsMutating } from '@tanstack/react-query'
import type { UserAccountCandidateResponse } from '../../../api'
import { USERS_BASE } from '../../../shared/paths'
import { BackLink } from '../../../shared/ui/BackLink'
import { PageHeader } from '../../../shared/ui/PageHeader'
import { AccountCandidatesTable } from '../components/AccountCandidatesTable'
import { AccountCreatedPanel, type CreatedAccount } from '../components/AccountCreatedPanel'
import { CreateAccountDialog } from '../components/CreateAccountDialog'
import { userAccountKeys } from '../queryKeys'

export function UserCreatePage() {
  // La temporal vive solo acá, en memoria de la página, y se va con ella.
  const [created, setCreated] = useState<CreatedAccount | null>(null)
  const [confirming, setConfirming] = useState<{ candidate: UserAccountCandidateResponse; row: number } | null>(null)
  const shell = useRef<HTMLDivElement>(null)
  // Tras un rechazo la lista se vuelve a pedir y la fila que abrió el diálogo puede irse con el foco:
  // va a la que quedó en su lugar.
  useEffect(() => {
    const page = shell.current
    if (!confirming || !page) return
    return () => {
      if (document.activeElement === document.body) {
        const rows = page.querySelectorAll<HTMLElement>('tbody button')
        const next = rows[Math.min(confirming.row, rows.length - 1)] ?? page.querySelector<HTMLElement>(`a[href="${USERS_BASE}"]`)
        next?.focus()
      }
    }
  }, [confirming])

  return (
    <Shell ref={shell}>
      {created ? (
        <AccountCreatedPanel
          created={created}
          onExpire={() => setCreated((current) => current && { ...current, temporaryPassword: null })}
        />
      ) : (
        <AccountCandidatesTable onCreate={(candidate, row) => setConfirming({ candidate, row })} />
      )}
      {confirming && (
        <CreateAccountDialog
          candidate={confirming.candidate}
          onClose={() => setConfirming(null)}
          onCreated={(response) => {
            setConfirming(null)
            setCreated({ account: response.account, temporaryPassword: response.temporaryPassword })
          }}
        />
      )}
    </Shell>
  )
}

function Shell({ children, ref }: { children: ReactNode; ref?: Ref<HTMLDivElement> }) {
  // Con el alta en vuelo, volver queda deshabilitado.
  const saving = useIsMutating({ mutationKey: userAccountKeys.create() }) > 0
  return (
    <div ref={ref} className="mx-auto max-w-[860px] space-y-6 px-6 py-8">
      <BackLink to={USERS_BASE} disabled={saving}>
        Volver a usuarios
      </BackLink>
      <PageHeader
        title="Nuevo usuario"
        description='Crea la cuenta de un trabajador. El sistema genera el usuario y una contraseña temporal; la persona la cambia desde "Cambiar contraseña".'
        divider
      />
      {children}
    </div>
  )
}
