import { useId } from 'react'
import type { UserAccountCandidateResponse } from '../../../api'
import { useAuth } from '../../../shared/auth/AuthContext'
import { Button } from '../../../shared/ui/Button'
import { DataTable, type Column } from '../../../shared/ui/DataTable'
import { FIELD_HELPER } from '../../../shared/ui/fieldClasses'
import { getApiErrorMessage, isForbiddenError } from '../../../shared/utils/getApiErrorMessage'
import { useAccountCandidates } from '../hooks/useAccountCandidates'

interface AccountCandidatesTableProps {
  onCreate: (candidate: UserAccountCandidateResponse, row: number) => void
}

export function AccountCandidatesTable({ onCreate }: AccountCandidatesTableProps) {
  const { user } = useAuth()
  const headingId = useId()
  const { data, isLoading, isFetching, isError, error, refetch } = useAccountCandidates()
  const forbidden = isError && isForbiddenError(error)
  const candidates = data ?? []
  const help =
    user?.role === 'admin'
      ? 'Solo aparecen los activos y con cargo que inicia sesión.'
      : 'Solo aparecen los activos, con cargo que inicia sesión y de un nivel menor al tuyo.'

  const columns: Column<UserAccountCandidateResponse>[] = [
    { key: 'fullName', header: 'Nombre completo', render: (candidate) => candidate.fullName },
    { key: 'role', header: 'Cargo', render: (candidate) => candidate.role.description },
    {
      key: 'create',
      header: <span className="sr-only">Acciones</span>,
      align: 'right',
      render: (candidate) => (
        <Button
          variant="success"
          aria-label={`Crear usuario de ${candidate.fullName}`}
          onClick={() => onCreate(candidate, candidates.indexOf(candidate))}
        >
          Crear usuario
        </Button>
      ),
    },
  ]

  return (
    <section aria-labelledby={headingId} className="space-y-3">
      <h2 id={headingId} className="text-sm font-semibold text-fg">
        Trabajadores sin cuenta
      </h2>
      <DataTable
        columns={columns}
        data={candidates}
        keyExtractor={(candidate) => candidate.workerId}
        page={0}
        size={candidates.length}
        total={candidates.length}
        totalPages={1}
        onPageChange={() => {}}
        isLoading={isLoading}
        loadingLabel="Cargando trabajadores"
        isFetching={isFetching}
        isError={isError}
        errorMessage={getApiErrorMessage(
          error,
          forbidden
            ? 'No tienes permiso para ver los trabajadores sin cuenta.'
            : 'No se pudieron cargar los trabajadores. Intenta de nuevo.',
        )}
        // Repetir un 403 no lo cambia.
        onRetry={forbidden ? undefined : () => void refetch()}
        emptyTitle="No hay trabajadores pendientes de cuenta."
        emptyDescription={help}
        caption="Trabajadores sin cuenta"
      />
      {candidates.length > 0 && <p className={FIELD_HELPER}>{help}</p>}
    </section>
  )
}
