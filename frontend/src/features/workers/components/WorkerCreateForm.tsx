import { useMemo } from 'react'
import type { DocumentTypeResponse, RoleResponse, WorkerDetailResponse } from '../../../api'
import { useCreateWorker } from '../hooks/useCreateWorker'
import { toWorkerRequest, workerCreateDefaults } from '../schemas/worker.schema'
import { WORKER_CREATE_ERRORS } from '../workerApiErrors'
import { WorkerForm } from './WorkerForm'

interface WorkerCreateFormProps {
  documentTypes: readonly DocumentTypeResponse[]
  /** El catálogo completo: la modalidad de licencia sale de acá. */
  roles: readonly RoleResponse[]
  /** Los cargos que la sesión puede asignar. */
  roleOptions: readonly RoleResponse[]
  onCreated: (created: WorkerDetailResponse) => void
  onCancel: () => void
  reloadCatalog: (catalog: 'documentTypes' | 'roles') => void
}

/** El alta: el formulario común, vacío, que crea. */
export function WorkerCreateForm({ documentTypes, roles, roleOptions, onCreated, onCancel, reloadCatalog }: WorkerCreateFormProps) {
  const createWorker = useCreateWorker()
  const defaultValues = useMemo(() => workerCreateDefaults(documentTypes), [documentTypes])
  return (
    <WorkerForm
      documentTypes={documentTypes}
      roles={roles}
      roleOptions={roleOptions}
      defaultValues={defaultValues}
      save={(values) => createWorker.mutateAsync(toWorkerRequest(values, roles))}
      errors={WORKER_CREATE_ERRORS}
      fallbackMessage="No se pudo dar de alta al trabajador. Intenta de nuevo."
      onSaved={onCreated}
      onCancel={onCancel}
      reloadCatalog={reloadCatalog}
    />
  )
}
