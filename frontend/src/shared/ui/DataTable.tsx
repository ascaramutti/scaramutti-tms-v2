import type { ReactNode } from 'react'
import { ChevronLeft, ChevronRight } from 'lucide-react'
import { cn } from '../utils/cn'
import { Spinner } from './Spinner'
import { EmptyState } from './EmptyState'

export interface Column<T> {
  /** Clave única de la columna (no necesariamente un campo de `T`). */
  key: string
  header: string
  /** Render custom de la celda. Si se omite, muestra `row[key]` como string. */
  render?: (row: T) => ReactNode
  /** Alineación del contenido. Default `'left'`. */
  align?: 'left' | 'center' | 'right'
  /** Clases extra para la celda `<td>`. */
  className?: string
}

interface DataTableProps<T> {
  columns: Column<T>[]
  data: T[]
  keyExtractor: (row: T) => string | number
  /** Página actual (base 0). */
  page: number
  size: number
  total: number
  totalPages: number
  onPageChange: (page: number) => void
  /** Carga inicial (sin data previa). Muestra spinner en lugar de la tabla. */
  isLoading?: boolean
  /** Refetch en curso con data previa (paginar/filtrar). Atenúa la tabla. */
  isFetching?: boolean
  isError?: boolean
  errorMessage?: string
  onRetry?: () => void
  emptyTitle?: string
  emptyDescription?: string
  emptyAction?: ReactNode
  onRowClick?: (row: T) => void
  /** Rótulo accesible por fila clickeable (ej. "Ver cotización COT-1"). */
  rowLabel?: (row: T) => string
  /** Caption accesible de la tabla (sr-only). */
  caption?: string
}

const ALIGN_CLASSES = {
  left: 'text-left',
  center: 'text-center',
  right: 'text-right',
} as const

/**
 * Tabla paginada genérica. Maneja los tres estados terminales (loading inicial,
 * error sin data, vacío) además del render de datos. Pensada para todos los
 * listados del sistema (cotizaciones, clientes, etc.), por eso es agnóstica de `T`.
 *
 * Error con data previa: cuando un refetch falla pero ya había filas en pantalla
 * (caso típico con `keepPreviousData` al paginar/filtrar), NO se borra la tabla;
 * se mantiene visible y se muestra un aviso no destructivo arriba. El estado de
 * error a pantalla completa queda solo para cuando no hay nada que mostrar.
 */
export function DataTable<T>({
  columns,
  data,
  keyExtractor,
  page,
  size,
  total,
  totalPages,
  onPageChange,
  isLoading,
  isFetching,
  isError,
  errorMessage,
  onRetry,
  emptyTitle = 'Sin resultados',
  emptyDescription,
  emptyAction,
  onRowClick,
  rowLabel,
  caption,
}: DataTableProps<T>) {
  if (isLoading) {
    return (
      <div className="flex justify-center py-16">
        <Spinner size={28} label="Cargando" className="text-blue-600" />
      </div>
    )
  }

  // Error sin nada que mostrar → estado de error a pantalla completa.
  if (isError && data.length === 0) {
    return (
      <div
        role="alert"
        className="flex flex-col items-center justify-center px-6 py-16 text-center"
      >
        <p className="text-sm font-medium text-slate-700">
          {errorMessage ?? 'No se pudieron cargar los datos.'}
        </p>
        {onRetry && (
          <button
            type="button"
            onClick={onRetry}
            className="mt-4 inline-flex items-center rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 focus:outline-none focus:ring-2 focus:ring-blue-500"
          >
            Reintentar
          </button>
        )}
      </div>
    )
  }

  // Éxito sin resultados.
  if (data.length === 0) {
    return <EmptyState title={emptyTitle} description={emptyDescription} action={emptyAction} />
  }

  const from = page * size + 1
  const to = page * size + data.length
  const isFirst = page === 0
  const isLast = totalPages === 0 || page >= totalPages - 1
  const clickable = !!onRowClick

  return (
    <div className="space-y-3">
      {/* Error con data previa (ej. refetch al paginar falló): aviso no destructivo. */}
      {isError && (
        <div
          role="alert"
          className="flex items-center justify-between gap-3 rounded-lg border border-amber-200 bg-amber-50 px-4 py-2.5 text-sm text-amber-800"
        >
          <span>{errorMessage ?? 'No se pudieron actualizar los datos.'}</span>
          {onRetry && (
            <button
              type="button"
              onClick={onRetry}
              className="shrink-0 font-medium text-amber-900 underline underline-offset-2 hover:no-underline"
            >
              Reintentar
            </button>
          )}
        </div>
      )}

      <div className="rounded-xl border border-slate-200 bg-white shadow-sm">
        <div className="overflow-x-auto" aria-busy={isFetching}>
          <table className={cn('min-w-full divide-y divide-slate-200', isFetching && 'opacity-60')}>
            {caption && <caption className="sr-only">{caption}</caption>}
            <thead className="bg-slate-50">
              <tr>
                {columns.map((col) => (
                  <th
                    key={col.key}
                    scope="col"
                    className={cn(
                      'px-4 py-3 text-xs font-semibold uppercase tracking-wide text-slate-500',
                      ALIGN_CLASSES[col.align ?? 'left'],
                    )}
                  >
                    {col.header}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {data.map((row) => (
                <tr
                  key={keyExtractor(row)}
                  className={cn(
                    clickable &&
                      'cursor-pointer hover:bg-slate-50 focus:bg-slate-50 focus:outline-none',
                  )}
                  onClick={clickable ? () => onRowClick(row) : undefined}
                  onKeyDown={
                    clickable
                      ? (event) => {
                          if (event.key === 'Enter' || event.key === ' ') {
                            event.preventDefault()
                            onRowClick(row)
                          }
                        }
                      : undefined
                  }
                  tabIndex={clickable ? 0 : undefined}
                  role={clickable ? 'button' : undefined}
                  aria-label={clickable && rowLabel ? rowLabel(row) : undefined}
                >
                  {columns.map((col) => (
                    <td
                      key={col.key}
                      className={cn(
                        'px-4 py-3 align-middle text-sm text-slate-700',
                        ALIGN_CLASSES[col.align ?? 'left'],
                        col.className,
                      )}
                    >
                      {col.render
                        ? col.render(row)
                        : String((row as Record<string, unknown>)[col.key] ?? '')}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {totalPages > 1 && (
          <div className="flex items-center justify-between border-t border-slate-200 px-4 py-3">
            <span className="text-sm text-slate-600">
              Mostrando {from}–{to} de {total}
            </span>
            <div className="flex gap-1">
              <button
                type="button"
                onClick={() => onPageChange(page - 1)}
                disabled={isFirst}
                aria-label="Página anterior"
                className="inline-flex items-center rounded-lg p-1.5 text-slate-600 hover:bg-slate-100 disabled:cursor-not-allowed disabled:opacity-40"
              >
                <ChevronLeft className="h-5 w-5" aria-hidden="true" />
              </button>
              <button
                type="button"
                onClick={() => onPageChange(page + 1)}
                disabled={isLast}
                aria-label="Página siguiente"
                className="inline-flex items-center rounded-lg p-1.5 text-slate-600 hover:bg-slate-100 disabled:cursor-not-allowed disabled:opacity-40"
              >
                <ChevronRight className="h-5 w-5" aria-hidden="true" />
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
