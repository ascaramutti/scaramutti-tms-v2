import type { WorkerResponse } from '../../../api'
import { DataTable, type Column } from '../../../shared/ui/DataTable'
import { WorkerStatusBadge } from './WorkerStatusBadge'

interface WorkerSearchResultsProps {
  workers: WorkerResponse[]
  isLoading: boolean
  /** Las filas son las de la búsqueda anterior mientras llega la nueva. */
  isPlaceholderData: boolean
  isFetching: boolean
  isError: boolean
  errorMessage: string
  onRetry: () => void
}

const COLUMNS: Column<WorkerResponse>[] = [
  { key: 'fullName', header: 'Nombre completo', render: (worker) => worker.fullName },
  { key: 'position', header: 'Cargo', render: (worker) => worker.position ?? '—' },
  {
    key: 'isActive',
    header: 'Estado',
    render: (worker) => <WorkerStatusBadge isActive={worker.isActive} />,
  },
]

/**
 * Resultados del padrón. La respuesta no pagina, así que la tabla va en una sola
 * página. Las filas todavía no navegan: la ficha del trabajador llega con su
 * propia pantalla, y una fila que lleve a "no encontrado" es peor que ninguna.
 */
export function WorkerSearchResults({
  workers,
  isLoading,
  isPlaceholderData,
  isFetching,
  isError,
  errorMessage,
  onRetry,
}: WorkerSearchResultsProps) {
  return (
    <DataTable
      columns={COLUMNS}
      data={workers}
      keyExtractor={(worker) => worker.id}
      page={0}
      size={workers.length}
      total={workers.length}
      totalPages={1}
      onPageChange={() => {}}
      // Un vacío heredado de la búsqueda anterior no es la respuesta a esta: se muestra como carga.
      isLoading={isLoading || (isPlaceholderData && workers.length === 0)}
      loadingLabel="Buscando trabajadores"
      isFetching={isFetching}
      isError={isError}
      errorMessage={errorMessage}
      onRetry={onRetry}
      emptyTitle="No encontramos trabajadores con ese texto"
      emptyDescription="Revisa la escritura o prueba con el número de documento."
      caption="Trabajadores encontrados"
    />
  )
}
