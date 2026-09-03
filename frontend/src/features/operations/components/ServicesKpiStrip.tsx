import {
  CheckCircle2,
  ClipboardList,
  Hourglass,
  Route,
  Truck,
  User,
} from 'lucide-react'
import type { ServiceStatsResponse } from '../../../api'
import { formatDateOnly } from '../../../shared/utils/formatters'
import { KpiTile } from '../../../shared/ui/KpiTile'
import { Alert } from '../../../shared/ui/Alert'

interface ServicesKpiStripProps {
  data?: ServiceStatsResponse
  isLoading: boolean
  isError: boolean
  errorMessage?: string
  onRetry: () => void
}

/**
 * Recurso en servicio sobre su padrón. Se muestran los dos números y la palabra
 * que los separa, para que "3 de 5" no se pueda leer como un total de 35.
 *
 * Devuelve `undefined` y no un guion cuando no hay dato: el guion lo pone el tile, que es
 * quien decide cómo se dibuja un indicador sin valor.
 */
function ratioValue(ratio: { active: number; total: number } | undefined) {
  if (!ratio) return undefined
  return (
    <>
      {ratio.active}
      {/* El denominador es el padrón vigente de ESE recurso (todos los
          conductores de alta, o todos los tractos), no los principales: ser
          principal es un rol por asignación, no un atributo del recurso. */}
      <span className="text-base font-normal text-slate-500"> de {ratio.total} de alta</span>
    </>
  )
}

/**
 * Indicadores del tablero operativo: los seis datos de `GET /services/stats`.
 * Ningún número se calcula acá.
 *
 * Los tiles NO son interactivos, y es una decisión, no un olvido: los tres primeros
 * contadores son globales (sin ventana de tiempo) y "Completados" cuenta por fecha
 * de fin real dentro del ciclo semanal, mientras que el listado de abajo filtra por
 * fecha tentativa. Un tile que aplicara su propio filtro mostraría un número arriba
 * y otro distinto en la tabla, sin forma de explicar la diferencia.
 *
 * Como los contadores no reaccionan a los filtros de la tabla, se dice a la vista
 * y también en el rótulo accesible del grupo.
 */
export function ServicesKpiStrip({
  data,
  isLoading,
  isError,
  errorMessage,
  onRetry,
}: ServicesKpiStripProps) {
  return (
    <div className="space-y-3">
      {/* Los indicadores son contexto: si fallan, se degrada el strip y la tabla sigue trabajando. */}
      {isError && (
        <Alert variant="warning" role="alert" className="flex items-center justify-between gap-3 rounded-lg px-4 py-2.5 text-sm text-amber-800">
          <span>{errorMessage ?? 'No se pudieron cargar los indicadores operativos.'}</span>
          <button
            type="button"
            onClick={onRetry}
            // Nombre propio: si el listado también falla, su botón "Reintentar"
            // queda en la misma pantalla y serían dos nombres idénticos.
            aria-label="Reintentar cargar los indicadores"
            className="shrink-0 font-medium text-amber-900 underline underline-offset-2 hover:no-underline"
          >
            Reintentar
          </button>
        </Alert>
      )}

      <div
        role="group"
        aria-label="Resumen operativo (no depende de los filtros del listado)"
        // Corta en 3: el contenedor de la página está topeado en 1024px, así que
        // un salto a 6 columnas en pantallas más anchas angostaría los tiles en
        // vez de aprovechar el espacio.
        className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3"
      >
        <KpiTile
          icon={ClipboardList}
          label="Pend. asignación"
          value={data?.pendingAssignment}
          isLoading={isLoading}
        />

        <KpiTile
          icon={Hourglass}
          label="Pend. inicio"
          value={data?.pendingStart}
          isLoading={isLoading}
        />

        {/* `Route` y no `Truck`: el tile de tractos usa el camión, y estos dos
            son justamente los que no hay que confundir (uno cuenta viajes, el
            otro unidades). */}
        <KpiTile
          icon={Route}
          label="En ruta"
          value={data?.inProgress}
          isLoading={isLoading}
        />

        <KpiTile
          icon={CheckCircle2}
          // El único contador con ventana de tiempo: el rótulo la nombra para que
          // no se lea como un total, que es lo que son los otros tres.
          label="Completados (semana)"
          value={data?.completedThisWeek}
          isLoading={isLoading}
        />

        {/* Cuenta SOLO conductores principales: quien maneja como refuerzo está
            en ruta y no entra acá. Sin decirlo, "3 de 5" con cuatro personas
            manejando se lee como un sistema roto. */}
        <KpiTile
          icon={User}
          label="Conductores en ruta (principales)"
          value={ratioValue(data?.driversOnRoad)}
          isLoading={isLoading}
        />

        {/* Cuenta SOLO tractos, pese a que el campo del contrato se llame
            "units": las carretas y las escoltas no participan de ningún
            indicador. Por eso el rótulo nombra el tracto y no la unidad. */}
        <KpiTile
          icon={Truck}
          label="Tractos en ruta (principales)"
          value={ratioValue(data?.unitsOnRoad)}
          isLoading={isLoading}
        />
      </div>

      <p className="text-xs text-slate-500">
        Estos indicadores no dependen de los filtros del listado.
      </p>

      {data && (
        <p className="text-xs text-slate-500">
          {/* `weekCycle.end` es el martes INCLUSIVE (la etiqueta), no el miércoles
              exclusivo con el que se consulta: se imprime tal cual viene.
              Son date-only y van por `formatDateOnly`: pasarlos por `new Date()`
              los interpretaría como medianoche UTC y en Lima retrocederían un día. */}
          Semana operativa: {formatDateOnly(data.weekCycle.start)} al{' '}
          {formatDateOnly(data.weekCycle.end)} (miércoles a martes)
        </p>
      )}
    </div>
  )
}
