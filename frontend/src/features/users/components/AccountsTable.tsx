import { useNavigate } from 'react-router-dom'
import type { UserAccountSummaryResponse } from '../../../api'
import { userAccountDetailPath } from '../../../shared/paths'
import { DataTable, type Column } from '../../../shared/ui/DataTable'
import type { AccountStatusFilter } from '../accountStatusFilter'
import { AccountStatusBadge } from './AccountStatusBadge'

interface AccountsTableProps {
  accounts: UserAccountSummaryResponse[]
  status: AccountStatusFilter
  isLoading: boolean
  isPlaceholderData: boolean
  isFetching: boolean
  isError: boolean
  errorMessage: string
  onRetry?: () => void
}

const COLUMNS: Column<UserAccountSummaryResponse>[] = [
  {
    key: 'username',
    header: 'Usuario',
    render: (account) => <span className="font-mono">{account.username}</span>,
  },
  { key: 'worker', header: 'Trabajador', render: (account) => account.worker.fullName },
  { key: 'role', header: 'Cargo', render: (account) => account.role.description },
  {
    key: 'isActive',
    header: 'Estado',
    render: (account) => <AccountStatusBadge isActive={account.isActive} />,
  },
]

// El nombre accesible de la fila tapa sus celdas: repite el cargo y el estado, que si no, no se oyen.
function rowLabel(account: UserAccountSummaryResponse): string {
  const estado = account.isActive ? 'activa' : 'inactiva'
  return `Ver la cuenta ${account.username} de ${account.worker.fullName}, ${account.role.description}, ${estado}`
}

export function AccountsTable({
  accounts,
  status,
  isLoading,
  isPlaceholderData,
  isFetching,
  isError,
  errorMessage,
  onRetry,
}: AccountsTableProps) {
  const navigate = useNavigate()
  return (
    <DataTable
      columns={COLUMNS}
      data={accounts}
      keyExtractor={(account) => account.id}
      page={0}
      size={accounts.length}
      total={accounts.length}
      totalPages={1}
      onPageChange={() => {}}
      // Un vacío heredado del filtro anterior no es la respuesta a este: se muestra como carga.
      isLoading={isLoading || (isPlaceholderData && accounts.length === 0)}
      loadingLabel="Cargando cuentas"
      isFetching={isFetching}
      isError={isError}
      errorMessage={errorMessage}
      onRetry={onRetry}
      emptyTitle="No hay cuentas con este estado"
      emptyDescription={status === 'all' ? undefined : 'Cambia el filtro de estado para ver las demás.'}
      caption="Cuentas del sistema"
      onRowClick={(account) => navigate(userAccountDetailPath(account.id))}
      rowLabel={rowLabel}
    />
  )
}
