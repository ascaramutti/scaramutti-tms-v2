import { z } from 'zod'
import type {
  DocumentTypeResponse,
  DriverProfileMode,
  FleetResourceStatus,
  RoleResponse,
  WorkerDetailResponse,
  WorkerRequest,
  WorkerUpdateRequest,
} from '../../../api'
import { todayInLima } from '../../../shared/utils/limaDate'
import { trimToNull } from '../../../shared/utils/trimToNull'

/** Topes del contrato: el backend los exige igual. */
export const WORKER_NAME_MAX_LENGTH = 100
export const DOCUMENT_NUMBER_MAX_LENGTH = 20
export const LICENSE_MAX_LENGTH = 20
/** El motivo se pide al cambiar el número de documento; viaja recortado y así lo mide el backend. */
export const REASON_MIN_LENGTH = 10
export const REASON_MAX_LENGTH = 500

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
  /** Solo en la edición, y solo pesa si el número de documento cambió. */
  reason: string
}

export interface WorkerFormContext {
  documentTypes: readonly DocumentTypeResponse[]
  roles: readonly RoleResponse[]
  /** Hoy en Lima; inyectable para medir el borde en los tests. */
  today?: () => string
  /** En la edición, lo guardado: decide si se pide motivo y qué fecha de ingreso se respeta. */
  original?: Pick<WorkerFormValues, 'documentNumber' | 'hireDate'>
}

/** El motivo se pide si el número, ya recortado, difiere del guardado tal cual: así lo mide el backend. */
export function documentNumberChanged(
  documentNumber: string,
  original: Pick<WorkerFormValues, 'documentNumber'> | undefined,
): boolean {
  return original !== undefined && documentNumber.trim() !== original.documentNumber
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
 * Nombre y apellido: al menos una letra latina, más espacios, apóstrofo y guion, la misma regla que
 * exige el backend. Letra latina es letra de script latino: quedan afuera los números romanos, los
 * rellenos invisibles y las letras de otros alfabetos que se parecen a las nuestras. Se miden y
 * viajan en NFC: una tilde pegada como letra más marca entra; una marca que NFC no compone, no.
 */
export const PERSON_NAME = /^[' -]*(?=\p{L})\p{Script=Latin}(?:(?=\p{L})\p{Script=Latin}|[' -])*$/u
const PERSON_NAME_MESSAGE = 'Solo letras, espacios, apóstrofo o guion.'

/**
 * Como en el backend: pasado este largo no se normaliza, porque reordenar miles de marcas cuesta
 * tiempo cuadrático, y el tope de 100 lo rechaza igual. Componer junta a lo sumo cuatro caracteres.
 */
const MAX_NORMALIZED_LENGTH = 400

function toNfc(value: string): string {
  return value.length > MAX_NORMALIZED_LENGTH ? value : value.normalize('NFC')
}

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

export function buildWorkerFormSchema({ documentTypes, roles, today = todayInLima, original }: WorkerFormContext) {
  const patterns = new Map(documentTypes.map((type) => [type.id, compilePattern(type.validationPattern)]))
  return z
    .object({
      firstName: z
        .string()
        .trim()
        .overwrite(toNfc)
        .min(1, 'Indica el nombre.')
        .max(WORKER_NAME_MAX_LENGTH, 'Máximo 100 caracteres.')
        // Pasado el tope la regla no se mide: sobre un texto gigante desbordaría la pila. Sin cortar
        // la validación, para que el resto del formulario siga avisando en la misma vuelta.
        .refine((value) => value.length > WORKER_NAME_MAX_LENGTH || PERSON_NAME.test(value), PERSON_NAME_MESSAGE),
      lastName: z
        .string()
        .trim()
        .overwrite(toNfc)
        .min(1, 'Indica el apellido.')
        .max(WORKER_NAME_MAX_LENGTH, 'Máximo 100 caracteres.')
        // Pasado el tope la regla no se mide: sobre un texto gigante desbordaría la pila. Sin cortar
        // la validación, para que el resto del formulario siga avisando en la misma vuelta.
        .refine((value) => value.length > WORKER_NAME_MAX_LENGTH || PERSON_NAME.test(value), PERSON_NAME_MESSAGE),
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
        // La edición respeta la fecha guardada aunque sea futura: el backend las admite.
        .refine((value) => value <= today() || value === original?.hireDate, 'La fecha de ingreso no puede ser futura.'),
      hasLicense: z.boolean(),
      driver: z.object({
        licenseNumber: z.string(),
        licenseCategory: z.string(),
        status: z.enum(['AVAILABLE', 'MAINTENANCE', 'NOT_AVAILABLE']),
      }),
      reason: z.string(),
    })
    .superRefine((values, ctx) => {
      if (documentNumberChanged(values.documentNumber, original)) {
        const reason = values.reason.trim()
        if (reason.length < REASON_MIN_LENGTH) {
          ctx.addIssue({ code: 'custom', path: ['reason'], message: 'Indica el motivo del cambio, de al menos 10 caracteres.' })
        } else if (reason.length > REASON_MAX_LENGTH) {
          ctx.addIssue({ code: 'custom', path: ['reason'], message: 'Máximo 500 caracteres.' })
        }
      }
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
    reason: '',
  }
}

/**
 * La edición abre con lo guardado, licencia incluida aunque esté apagada: si el cargo la lleva,
 * aparece precargada. La casilla de un cargo opcional nace marcada solo con la licencia activa.
 */
export function workerEditDefaults(worker: WorkerDetailResponse): WorkerFormValues {
  return {
    firstName: worker.firstName,
    lastName: worker.lastName,
    documentTypeId: worker.documentType.id,
    documentNumber: worker.documentNumber,
    phone: worker.phone ?? '',
    role: worker.role.name,
    hireDate: worker.hireDate,
    hasLicense: worker.driver?.isActive ?? false,
    driver: {
      licenseNumber: worker.driver?.licenseNumber ?? '',
      licenseCategory: worker.driver?.licenseCategory ?? '',
      status: 'AVAILABLE',
    },
    reason: '',
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

function withoutStatus({ licenseNumber, licenseCategory }: NonNullable<WorkerRequest['driver']>) {
  return { licenseNumber, licenseCategory }
}

/**
 * El cuerpo de la edición: es un reemplazo, así que viaja todo; el motivo, solo si cambió el
 * número. La disponibilidad no: ausente, el backend conserva la guardada (y una licencia que nace
 * acá queda disponible), así una edición no pisa la que puso un viaje mientras estaba abierta.
 */
export function toWorkerUpdateRequest(
  values: WorkerFormValues,
  roles: readonly RoleResponse[],
  original: Pick<WorkerFormValues, 'documentNumber'>,
): WorkerUpdateRequest {
  const { driver, ...request } = toWorkerRequest(values, roles)
  return {
    ...request,
    ...(driver && { driver: withoutStatus(driver) }),
    reason: documentNumberChanged(values.documentNumber, original) ? values.reason.trim() : null,
  }
}
