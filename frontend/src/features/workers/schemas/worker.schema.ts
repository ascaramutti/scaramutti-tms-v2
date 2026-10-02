import { z } from 'zod'
import type {
  DocumentTypeResponse,
  DriverProfileMode,
  FleetResourceStatus,
  RoleResponse,
  WorkerRequest,
} from '../../../api'
import { todayInLima } from '../../../shared/utils/limaDate'
import { trimToNull } from '../../../shared/utils/trimToNull'

/** Topes del contrato: el backend los exige igual. */
export const WORKER_NAME_MAX_LENGTH = 100
export const DOCUMENT_NUMBER_MAX_LENGTH = 20
export const LICENSE_MAX_LENGTH = 20

export interface WorkerFormValues {
  firstName: string
  lastName: string
  documentTypeId: number | null
  documentNumber: string
  /** Vacío es "sin teléfono": viaja como `null`. */
  phone: string
  /** El nombre de sistema del cargo; `null` hasta que se elige. */
  role: string | null
  hireDate: string
  /** Solo pesa con un cargo de licencia opcional. */
  hasLicense: boolean
  driver: { licenseNumber: string; licenseCategory: string; status: FleetResourceStatus }
}

export interface WorkerFormContext {
  documentTypes: readonly DocumentTypeResponse[]
  roles: readonly RoleResponse[]
  /** Hoy en Lima; inyectable para medir el borde en los tests. */
  today?: () => string
}

export function driverProfileOf(
  role: string | null,
  roles: readonly RoleResponse[],
): DriverProfileMode | undefined {
  return roles.find((candidate) => candidate.name === role)?.driverProfile
}

/** La licencia viaja con un cargo que la exige, o con uno opcional si se marcó que la tiene. */
export function sendsDriver(values: Pick<WorkerFormValues, 'hasLicense'>, profile: DriverProfileMode | undefined) {
  return profile === 'REQUIRED' || (profile === 'OPTIONAL' && values.hasLicense)
}

/**
 * Nombre y apellido: letras, espacios, apóstrofo y guion. El backend todavía no la exige (llega en
 * el cambio siguiente); por ahora es una guarda del cliente. Se miden y viajan en NFC: una tilde
 * pegada como letra más marca entra, y lo guardado queda en una sola forma.
 */
const PERSON_NAME = /^[\p{L}' -]+$/u
const PERSON_NAME_MESSAGE = 'Solo letras, espacios, apóstrofo o guion.'

/**
 * El patrón del tipo se aplica al número entero, en modo Unicode como lo lee Java. Lo que
 * JavaScript no entiende no compila y queda para el backend; alguna clase de Java compila
 * acá con otro sentido, y ahí también decide el backend.
 */
function compilePattern(pattern: string | null | undefined): RegExp | null {
  if (!pattern) return null
  try {
    return new RegExp(`^(?:${pattern})$`, 'u')
  } catch {
    return null
  }
}

export function buildWorkerFormSchema({ documentTypes, roles, today = todayInLima }: WorkerFormContext) {
  const patterns = new Map(documentTypes.map((type) => [type.id, compilePattern(type.validationPattern)]))
  return z
    .object({
      firstName: z
        .string()
        .trim()
        .normalize('NFC')
        .min(1, 'Indica el nombre.')
        .max(WORKER_NAME_MAX_LENGTH, 'Máximo 100 caracteres.')
        .regex(PERSON_NAME, PERSON_NAME_MESSAGE),
      lastName: z
        .string()
        .trim()
        .normalize('NFC')
        .min(1, 'Indica el apellido.')
        .max(WORKER_NAME_MAX_LENGTH, 'Máximo 100 caracteres.')
        .regex(PERSON_NAME, PERSON_NAME_MESSAGE),
      documentTypeId: z
        .number()
        .int()
        .nullable()
        .refine((value) => value !== null, 'Elige el tipo de documento.'),
      documentNumber: z
        .string()
        .trim()
        .min(1, 'Indica el número de documento.')
        .max(DOCUMENT_NUMBER_MAX_LENGTH, 'Máximo 20 caracteres.'),
      phone: z
        .string()
        .trim()
        .refine((value) => value === '' || /^\d{9}$/.test(value), 'El teléfono debe tener 9 dígitos.'),
      role: z
        .string()
        .nullable()
        .refine((value) => !!value, 'Elige el cargo.'),
      // Guarda solo del cliente, decisión del proyecto: el backend acepta fechas
      // futuras a propósito, para las correcciones a mano.
      hireDate: z
        .string()
        .regex(/^\d{4}-\d{2}-\d{2}$/, 'Indica la fecha de ingreso.')
        .refine((value) => value <= today(), 'La fecha de ingreso no puede ser futura.'),
      hasLicense: z.boolean(),
      driver: z.object({
        licenseNumber: z.string(),
        licenseCategory: z.string(),
        status: z.enum(['AVAILABLE', 'MAINTENANCE', 'NOT_AVAILABLE']),
      }),
    })
    .superRefine((values, ctx) => {
      const type = documentTypes.find((candidate) => candidate.id === values.documentTypeId)
      const number = values.documentNumber.trim()
      if (type && number) {
        if (number.length > type.maxLength) {
          ctx.addIssue({
            code: 'custom',
            path: ['documentNumber'],
            message: `Máximo ${type.maxLength} caracteres para ${type.name}.`,
          })
        } else if (patterns.get(type.id)?.test(number) === false) {
          ctx.addIssue({
            code: 'custom',
            path: ['documentNumber'],
            message: `El número no tiene el formato de ${type.name}.`,
          })
        }
      }
      if (!sendsDriver(values, driverProfileOf(values.role, roles))) return
      const license = values.driver.licenseNumber.trim()
      if (!license) {
        ctx.addIssue({ code: 'custom', path: ['driver', 'licenseNumber'], message: 'Indica el número de licencia.' })
      } else if (license.length > LICENSE_MAX_LENGTH) {
        ctx.addIssue({ code: 'custom', path: ['driver', 'licenseNumber'], message: 'Máximo 20 caracteres.' })
      }
      if (values.driver.licenseCategory.trim().length > LICENSE_MAX_LENGTH) {
        ctx.addIssue({ code: 'custom', path: ['driver', 'licenseCategory'], message: 'Máximo 20 caracteres.' })
      }
    })
}

/** El alta abre con el primer tipo de documento y la fecha de hoy en Lima. */
export function workerCreateDefaults(
  documentTypes: readonly DocumentTypeResponse[],
  today: () => string = todayInLima,
): WorkerFormValues {
  return {
    firstName: '',
    lastName: '',
    documentTypeId: documentTypes[0]?.id ?? null,
    documentNumber: '',
    phone: '',
    role: null,
    hireDate: today(),
    hasLicense: false,
    // El alta siempre la crea disponible (decisión del dueño): no se elige.
    driver: { licenseNumber: '', licenseCategory: '', status: 'AVAILABLE' },
  }
}

/** El cuerpo del request. Sin licencia, la clave `driver` no viaja. */
export function toWorkerRequest(values: WorkerFormValues, roles: readonly RoleResponse[]): WorkerRequest {
  const request: WorkerRequest = {
    firstName: values.firstName.trim(),
    lastName: values.lastName.trim(),
    documentTypeId: values.documentTypeId as number,
    documentNumber: values.documentNumber.trim(),
    phone: trimToNull(values.phone),
    role: values.role as string,
    hireDate: values.hireDate,
  }
  if (sendsDriver(values, driverProfileOf(values.role, roles))) {
    request.driver = {
      licenseNumber: values.driver.licenseNumber.trim(),
      licenseCategory: trimToNull(values.driver.licenseCategory),
      status: values.driver.status,
    }
  }
  return request
}
