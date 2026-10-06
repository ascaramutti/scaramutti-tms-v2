import { useMemo } from 'react'
import { useFormContext, useWatch } from 'react-hook-form'
import type { DocumentTypeResponse, RoleResponse, WorkerDetailResponse } from '../../../api'
import { Alert } from '../../../shared/ui/Alert'
import { Textarea } from '../../../shared/ui/Textarea'
import { useUpdateWorker } from '../hooks/useUpdateWorker'
import {
  documentNumberChanged,
  toWorkerUpdateRequest,
  workerEditDefaults,
  type WorkerFormValues,
} from '../schemas/worker.schema'
import { WORKER_UPDATE_ERRORS } from '../workerApiErrors'
import { WorkerForm } from './WorkerForm'

interface WorkerEditFormProps {
  worker: WorkerDetailResponse
  documentTypes: readonly DocumentTypeResponse[]
  roles: readonly RoleResponse[]
  roleOptions: readonly RoleResponse[]
  handleOwnError: (error: unknown) => boolean
  onSaved: (saved: WorkerDetailResponse) => void
  onCancel: () => void
  reloadCatalog: (catalog: 'documentTypes' | 'roles') => void
}

/** La edición: el mismo formulario del alta, abierto con lo guardado y reenviado entero. */
export function WorkerEditForm({ worker, roles, ...props }: WorkerEditFormProps) {
  const updateWorker = useUpdateWorker(worker.id)
  const defaultValues = useMemo(() => workerEditDefaults(worker), [worker])
  const original = useMemo(
    () => ({ documentNumber: worker.documentNumber, hireDate: worker.hireDate }),
    [worker.documentNumber, worker.hireDate],
  )
  const loaded = useMemo(() => comparableBody(defaultValues, roles, original), [defaultValues, roles, original])
  return (
    <WorkerForm
      {...props}
      roles={roles}
      defaultValues={defaultValues}
      original={original}
      save={(values) => updateWorker.mutateAsync(toWorkerUpdateRequest(values, roles, original))}
      errors={WORKER_UPDATE_ERRORS}
      fallbackMessage="No se pudieron guardar los cambios. Intenta de nuevo."
      isUnchanged={(values) => comparableBody(values, roles, original) === loaded}
      notice={worker.hasUser && <WorkerRoleChangeNotice roles={roles} originalRole={worker.role} />}
    >
      <WorkerReasonField original={original} />
    </WorkerForm>
  )
}

/**
 * Lo que viajaría, con los nombres en NFC: así un cambio que el backend no vería (espacios, la
 * forma de una tilde, un teléfono vacío contra nulo, una licencia que no viaja) no cuenta como
 * cambio, y "Modificado por" no se mueve por un guardado vacío.
 */
function comparableBody(
  values: WorkerFormValues,
  roles: readonly RoleResponse[],
  original: Pick<WorkerFormValues, 'documentNumber'>,
): string {
  const body = toWorkerUpdateRequest(values, roles, original)
  return JSON.stringify({
    ...body,
    firstName: body.firstName.normalize('NFC'),
    lastName: body.lastName.normalize('NFC'),
  })
}

/** El motivo aparece solo mientras el número difiere del guardado. */
function WorkerReasonField({ original }: { original: Pick<WorkerFormValues, 'documentNumber'> }) {
  const {
    register,
    control,
    formState: { errors, isSubmitting },
  } = useFormContext<WorkerFormValues>()
  const documentNumber = useWatch({ control, name: 'documentNumber' })
  if (!documentNumberChanged(documentNumber, original)) return null
  return (
    <Textarea
      id="trabajador-motivo"
      label="Motivo del cambio"
      rows={3}
      helperText="Cambiaste el número de documento: explica por qué, en al menos 10 caracteres."
      error={errors.reason?.message}
      disabled={isSubmitting}
      register={register('reason')}
    />
  )
}

interface WorkerRoleChangeNoticeProps {
  roles: readonly RoleResponse[]
  originalRole: RoleResponse
}

/** A quien tiene usuario, el cambio de cargo le cambia los permisos: se avisa cuál, con los nombres visibles. */
function WorkerRoleChangeNotice({ roles, originalRole }: WorkerRoleChangeNoticeProps) {
  const { control } = useFormContext<WorkerFormValues>()
  const role = useWatch({ control, name: 'role' })
  const newRole = roles.find((candidate) => candidate.name === role)
  if (!newRole || newRole.name === originalRole.name) return null
  return (
    <Alert variant="warning" role="status" className="px-4 py-3 text-sm">
      Este trabajador tiene usuario: al cambiar su cargo de {originalRole.description} a {newRole.description},
      cambian sus permisos en el sistema.
    </Alert>
  )
}
