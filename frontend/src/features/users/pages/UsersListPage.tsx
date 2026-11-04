import { useState } from 'react'
import { PageHeader } from '../../../shared/ui/PageHeader'
import { getApiErrorMessage, isForbiddenError } from '../../../shared/utils/getApiErrorMessage'
import { AccountsFilterBar } from '../components/AccountsFilterBar'
import { AccountsTable } from '../components/AccountsTable'
import { useUserAccounts } from '../hooks/useUserAccounts'
import {
  ACCOUNT_STATUS_FILTER_DEFAULT,
  isActiveParam,
  type AccountStatusFilter,
} from '../accountStatusFilter'

// El conteo nombra el estado: con el mismo número de filas en dos filtros, un texto idéntico no
// se vuelve a leer y el cambio pasaría en silencio.
const STATUS_WORDS: Record<AccountStatusFilter, readonly [string, string] | null> = {
  active: ['activa', 'activas'],
  inactive: ['inactiva', 'inactivas'],
  all: null,
}

function accountsAnnouncement(count: number, status: AccountStatusFilter): string {
  const words = STATUS_WORDS[status]
  if (count === 0) return words ? `Sin cuentas ${words[1]}` : 'Sin cuentas'
  const noun = count === 1 ? 'cuenta' : 'cuentas'
  return words ? `${count} ${noun} ${words[count === 1 ? 0 : 1]}` : `${count} ${noun}`
}

export function UsersListPage() {
  const [status, setStatus] = useState<AccountStatusFilter>(ACCOUNT_STATUS_FILTER_DEFAULT)
  const { data, isLoading, isFetching, isPlaceholderData, isError, error, refetch } =
    useUserAccounts(isActiveParam(status))

  const accounts = data ?? []
  const forbidden = isError && isForbiddenError(error)
  const announcement =
    !isLoading && !isError && !isPlaceholderData ? accountsAnnouncement(accounts.length, status) : ''

  return (
    <div className="mx-auto max-w-[1024px] space-y-6 px-6 py-8">
      <PageHeader
        title="Usuarios"
        description="Las cuentas con las que se entra al sistema. Cada una es de un trabajador y tiene su cargo."
        divider
      />

      <AccountsFilterBar status={status} onStatusChange={setStatus} announcement={announcement} />

      <AccountsTable
        accounts={accounts}
        status={status}
        isLoading={isLoading}
        isPlaceholderData={isPlaceholderData}
        isFetching={isFetching}
        isError={isError}
        errorMessage={getApiErrorMessage(
          error,
          forbidden
            ? 'No tienes permiso para ver las cuentas.'
            : 'No se pudieron cargar las cuentas. Intenta de nuevo.',
        )}
        // Repetir un 403 no lo cambia.
        onRetry={forbidden ? undefined : () => void refetch()}
      />
    </div>
  )
}
