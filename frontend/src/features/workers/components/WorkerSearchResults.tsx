import { useNavigate } from 'react-router-dom'
import type { WorkerResponse } from '../../../api'
import { workerDetailPath } from '../../../shared/paths'
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

// El nombre accesible de la fila tapa sus celdas: lleva cargo y estado para que dos
// homónimos no suenen iguales.
function rowLabel(worker: WorkerResponse): string {
  const cargo = worker.position ? `, ${worker.position}` : ''
  return `Ver la ficha de ${worker.fullName}${cargo}, ${worker.isActive ? 'activo' : 'inactivo'}`
}

/**
 * Resultados del padrón. La respuesta no pagina, así que la tabla va en una sola
 * página. Cada fila abre la ficha de ese trabajador.
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
  const navigate = useNavigate()
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
      onRowClick={(worker) => navigate(workerDetailPath(worker.id))}
      rowLabel={rowLabel}
    />
  )
}
