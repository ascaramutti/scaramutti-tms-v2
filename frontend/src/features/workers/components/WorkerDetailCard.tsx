import type { WorkerDetailResponse } from '../../../api'
import { DetailCard, Field } from '../../operations/components/detail/DetailCard'
import { PersonNameRow } from '../../../shared/ui/PersonNameRow'
import { formatDateOnly } from '../../../shared/utils/formatters'
import { WorkerStatusBadge } from './WorkerStatusBadge'

/**
 * Datos del trabajador tal como los guarda el padrón, con el nombre primero y en sus tres
 * partes. La fecha de ingreso es un día sin hora: se formatea como tal, porque leída como
 * instante caería el día anterior en Lima.
 */
export function WorkerDetailCard({ worker }: { worker: WorkerDetailResponse }) {
  return (
    <DetailCard title="Datos del trabajador" headingId="trabajador-datos">
      <PersonNameRow
        firstName={worker.firstName}
        paternalLastName={worker.paternalLastName}
        maternalLastName={worker.maternalLastName}
        className="mt-4"
      />
      <dl className="mt-4 grid grid-cols-1 gap-4 sm:grid-cols-2">
        <Field label="Tipo de documento" value={worker.documentType.name} />
        <Field label="Número de documento" value={worker.documentNumber} />
        <Field label="Cargo" value={worker.role.description} />
        <Field label="Fecha de ingreso" value={formatDateOnly(worker.hireDate)} />
        <Field label="Teléfono" value={worker.phone ?? '—'} />
        <Field label="Tiene usuario" value={worker.hasUser ? 'Sí' : 'No'} />
        <Field label="Estado" value={<WorkerStatusBadge isActive={worker.isActive} />} />
      </dl>
    </DetailCard>
  )
}
