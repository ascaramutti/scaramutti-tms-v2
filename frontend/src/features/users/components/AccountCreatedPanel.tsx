import { useEffect, useId, useRef } from 'react'
import { Link } from 'react-router-dom'
import type { UserAccountDetailResponse } from '../../../api'
import { userAccountDetailPath } from '../../../shared/paths'
import { buttonClasses } from '../../../shared/ui/buttonClasses'
import { Card } from '../../../shared/ui/Card'
import { OneTimeSecret } from '../../../shared/ui/OneTimeSecret'
import { Field } from '../../operations/components/detail/DetailCard'

export interface CreatedAccount {
  account: UserAccountDetailResponse
  temporaryPassword: string | null
}

interface AccountCreatedPanelProps {
  created: CreatedAccount
  onExpire: () => void
}

export function AccountCreatedPanel({ created, onExpire }: AccountCreatedPanelProps) {
  const { account, temporaryPassword } = created
  const headingId = useId()
  const headingRef = useRef<HTMLHeadingElement>(null)

  useEffect(() => {
    headingRef.current?.focus()
  }, [])

  // Al borrarse la temporal, "Copiar" se va con el foco encima: vuelve al título del panel.
  useEffect(() => {
    if (temporaryPassword === null && document.activeElement === document.body) {
      headingRef.current?.focus()
    }
  }, [temporaryPassword])

  return (
    <Card as="section" padding="md" aria-labelledby={headingId} className="space-y-5">
      <h2 id={headingId} ref={headingRef} tabIndex={-1} className="text-sm font-semibold text-fg focus:outline-none">
        Cuenta creada
      </h2>
      <p className="text-sm text-fg-body">
        Entrégale el usuario y la contraseña temporal a {account.worker.fullName} y pídele que la cambie desde "Cambiar
        contraseña" al entrar.
      </p>
      <dl className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <Field label="Usuario" value={<span className="font-mono">{account.username}</span>} />
        <Field label="Trabajador" value={`${account.worker.fullName} · ${account.role.description}`} />
      </dl>
      <OneTimeSecret label="Contraseña temporal" value={temporaryPassword} onExpire={onExpire} />
      <div className="flex justify-end">
        <Link to={userAccountDetailPath(account.id)} replace className={buttonClasses({ variant: 'primary' })}>
          Ver la cuenta
        </Link>
      </div>
    </Card>
  )
}
