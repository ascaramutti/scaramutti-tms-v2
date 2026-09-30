import type { WorkerDriverProfileResponse } from '../../../api'
import { DetailCard, Field } from '../../operations/components/detail/DetailCard'
import { DRIVER_STATUS_LABELS } from '../../operations/status/resourcePresentation'
import { Badge } from '../../../shared/ui/Badge'

/**
 * La licencia de conducir del trabajador: la llevan conductores, escoltas y ayudantes, así
 * que sale por tener ficha y no por el cargo. Apagada no se borra: se sigue viendo, con
 * "Deshabilitada" junto al título, y su disponibilidad ya no dice nada: se muestra como raya.
 */
export function WorkerDriverCard({ driver }: { driver: WorkerDriverProfileResponse }) {
  return (
    <DetailCard
      title="Licencia de conducir"
      headingId="trabajador-licencia"
      action={driver.isActive ? undefined : <Badge>Deshabilitada</Badge>}
    >
      <dl className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-2">
        <Field label="N.° de licencia" value={driver.licenseNumber} />
        <Field label="Categoría" value={driver.licenseCategory ?? '—'} />
        <Field label="Disponibilidad" value={driver.isActive ? DRIVER_STATUS_LABELS[driver.status] : '—'} />
      </dl>
    </DetailCard>
  )
}
