import type { FieldPath, UseFormSetError } from 'react-hook-form'
import { getApiErrorMessage } from '../../shared/utils/getApiErrorMessage'
import { handleApiFormError } from '../../shared/utils/handleApiFormError'
import type { WorkerFormValues } from './schemas/worker.schema'

type WorkerField = FieldPath<WorkerFormValues>

export interface WorkerApiError {
  /** Sin campo, el texto sale como aviso y el formulario queda como estaba. */
  field?: WorkerField
  message: string
  /** El catálogo que ya no coincide con el backend y hay que volver a pedir. */
  reload?: 'documentTypes' | 'roles'
}

/**
 * Textos propios por código, en lugar del detail del backend (va sin tildes y no
 * siempre tutea). La edición suma los suyos sobre este mapa.
 */
export const WORKER_CREATE_ERRORS: Record<string, WorkerApiError> = {
  'WRK-002': { field: 'documentNumber', message: 'Ya existe un trabajador con este documento.' },
  'WRK-003': {
    field: 'documentTypeId',
    message: 'Este tipo de documento ya no está vigente. Elige otro.',
    reload: 'documentTypes',
  },
  'WRK-004': { field: 'documentNumber', message: 'El número no tiene el formato de este tipo de documento.' },
  'WRK-005': { field: 'role', message: 'Este cargo ya no está vigente. Elige otro.', reload: 'roles' },
  'WRK-006': { field: 'role', message: 'No puedes asignar un cargo de tu nivel o superior.' },
  'WRK-007': { field: 'driver.licenseNumber', message: 'Ya existe una licencia registrada con este número.' },
  'WRK-008': {
    field: 'role',
    message: 'La licencia no corresponde con este cargo. Revisa el cargo elegido.',
    reload: 'roles',
  },
  'COM-003': { message: 'Tu rol no tiene permisos para esta acción.' },
  'WRK-013': { message: 'Otra operación estaba en curso y no se pudo guardar. Intenta de nuevo.' },
}

/**
 * La edición: el nivel ya no lo marca el cargo elegido (el desplegable no ofrece los de arriba)
 * sino el trabajador mismo, así que va como aviso. El 404 no está: lo resuelve la página.
 */
export const WORKER_UPDATE_ERRORS: Record<string, WorkerApiError> = {
  ...WORKER_CREATE_ERRORS,
  'WRK-006': { message: 'No puedes modificar a un trabajador de tu nivel o superior.' },
  'WRK-009': { field: 'reason', message: 'Indica el motivo del cambio, de al menos 10 caracteres.' },
  'WRK-011': { field: 'role', message: 'Este cargo no inicia sesión y el trabajador tiene usuario. Elige otro.' },
  'WRK-012': { field: 'role', message: 'No puedes cambiar tu propio cargo.' },
}

/** El cambio de estado no tiene formulario: cada código es un aviso en el diálogo. El 404 lo resuelve la página. */
const WORKER_STATUS_ERRORS: Record<string, string> = {
  'COM-003': WORKER_UPDATE_ERRORS['COM-003'].message,
  'WRK-006': WORKER_UPDATE_ERRORS['WRK-006'].message,
  'WRK-010': 'No puedes desactivarte a ti mismo.',
  'WRK-013': 'Otra operación estaba en curso y no se pudo cambiar el estado. Intenta de nuevo.',
}

/** El aviso de un cambio de estado fallido: el texto propio del código o, sin él, el del backend. */
export function workerStatusErrorMessage(error: unknown): string {
  const code = problemCodeOf(error)
  return (code && WORKER_STATUS_ERRORS[code]) || getApiErrorMessage(error, 'No se pudo cambiar el estado. Intenta de nuevo.')
}

function problemCodeOf(error: unknown): string | undefined {
  return (error as { response?: { data?: { code?: string } } })?.response?.data?.code
}

/** Los campos que el backend puede nombrar en un 400 de forma, en el orden del formulario. */
const WORKER_FIELDS: readonly WorkerField[] = [
  'firstName',
  'lastName',
  'documentTypeId',
  'documentNumber',
  'role',
  'hireDate',
  'phone',
  'reason',
  'driver.licenseNumber',
  'driver.licenseCategory',
]

/** El primero de los campos dados en el orden en que aparecen en el formulario. */
export function firstInFormOrder(fields: readonly WorkerField[]): WorkerField | undefined {
  return [...fields].sort((a, b) => WORKER_FIELDS.indexOf(a) - WORKER_FIELDS.indexOf(b))[0]
}

export function applyWorkerApiError(
  error: unknown,
  {
    errors,
    setError,
    reload,
    fallbackMessage,
  }: {
    errors: Record<string, WorkerApiError>
    setError: UseFormSetError<WorkerFormValues>
    reload: (catalog: 'documentTypes' | 'roles') => void
    fallbackMessage: string
  },
) {
  const code = problemCodeOf(error)
  const known = code ? errors[code] : undefined
  if (known?.reload) reload(known.reload)
  const codeFieldMap: Record<string, WorkerField> = {}
  const codeMessages: Record<string, string> = {}
  for (const [key, value] of Object.entries(errors)) {
    if (value.field) codeFieldMap[key] = value.field
    codeMessages[key] = value.message
  }
  handleApiFormError(error, {
    setError: (field, detail) => setError(field, detail),
    fallbackMessage,
    codeFieldMap,
    codeMessages,
    allowedFields: WORKER_FIELDS,
  })
}
