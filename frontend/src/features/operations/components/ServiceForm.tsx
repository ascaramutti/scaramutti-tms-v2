import { useState, type ReactNode } from 'react'
import { Controller, useForm, useWatch, type Control } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { isAxiosError } from 'axios'
import { TriangleAlert } from 'lucide-react'
import type { CargoTypeResponse, ClientResponse, Problem, ServiceDetailResponse } from '../../../api'
import { useAuth } from '../../../shared/auth/AuthContext'
import { DateField } from '../../../shared/ui/DateField'
import { SelectField, type SelectOption } from '../../../shared/ui/SelectField'
import { Spinner } from '../../../shared/ui/Spinner'
import { TextField } from '../../../shared/ui/TextField'
import { Textarea } from '../../../shared/ui/Textarea'
import { PRIMARY_BUTTON, SECONDARY_BUTTON } from '../../../shared/ui/buttonStyles'
import { cn } from '../../../shared/utils/cn'
import { getApiErrorMessage } from '../../../shared/utils/getApiErrorMessage'
import { handleApiFormError } from '../../../shared/utils/handleApiFormError'
import { stripControlChars } from '../../../shared/utils/sanitizeText'
import { CargoTypeField } from '../../cargotypes/components/CargoTypeField'
import { useCurrencies } from '../../catalogs/hooks/useCurrencies'
import { useCreateService } from '../hooks/useCreateService'
import {
  SERVICE_DATE_MAX,
  SERVICE_DATE_MIN,
  SERVICE_OBSERVATIONS_MAX_LENGTH,
  TRIP_SCOPE_OPTIONS,
  serviceCreateFormSchema,
  toServiceCreateRequest,
  type ServiceCreateFormInput,
  type ServiceCreateFormValues,
} from '../schemas/service-create.schema'
import { canCreateCatalogEntry } from '../status/operationsPermissions'
import { isPastInLima, todayInLima } from '../utils/limaDate'
import { ServiceClientField } from './ServiceClientField'

/** Campos que aceptan un error de campo del backend. */
const FORM_FIELDS = [
  'clientId',
  'tripScope',
  'tentativeDate',
  'origin',
  'destination',
  'cargoTypeId',
  'weightKg',
  'lengthM',
  'widthM',
  'heightM',
  'price',
  'currencyId',
  'observations',
] as const

/**
 * El alta repetida dentro de los 30 segundos. No es una violación de unicidad: dos
 * viajes iguales separados en el tiempo se registran sin problema, así que el aviso
 * dice qué pasó en vez de sonar a error de datos.
 */
const DUPLICATE_SERVICE_CODE = 'OPS-007'

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
      <h2 className="mb-4 text-xs font-semibold uppercase tracking-wide text-slate-500">{title}</h2>
      <div className="space-y-4">{children}</div>
    </section>
  )
}

/**
 * Una medida estándar del catálogo, como texto para el campo.
 *
 * El cero se trata como ausente, porque una carga no mide cero metros y el catálogo
 * tiene ceros donde debería haber nulos. Vienen de dos lados: de las filas migradas
 * de v1, y del alta al vuelo de un tipo de carga, cuyo cuerpo (capturado, no leído)
 * manda 0 en las dimensiones que se dejan vacías y en el peso que no se toca. Leer
 * esos ceros como medidas dejaba el formulario con campos que él mismo rechaza, y el
 * viaje no se podía registrar.
 */
function toFieldText(value: number | null | undefined): string {
  return value == null || value === 0 ? '' : String(value)
}

interface ServiceFormProps {
  onCreated: (service: ServiceDetailResponse) => void
  onCancel: () => void
}

/**
 * Alta de un servicio. Formulario simple y no un asistente por pasos: un viaje se
 * carga de una sentada en cuatro bloques cortos (viaje, carga, precio y
 * observaciones), a diferencia de una cotización.
 *
 * El precio es obligatorio, así que a esta pantalla no llega el despacho: la ruta y
 * el botón del listado ya lo filtran, y el servidor lo rechaza con 403 aunque
 * llegara por otro camino.
 */
export function ServiceForm({ onCreated, onCancel }: ServiceFormProps) {
  const { user } = useAuth()
  const createService = useCreateService()
  const currenciesQuery = useCurrencies()

  const [selectedClient, setSelectedClient] = useState<ClientResponse | null>(null)
  const [selectedCargoType, setSelectedCargoType] = useState<CargoTypeResponse | null>(null)

  const canCreateCatalogs = canCreateCatalogEntry(user?.role)
  const currencyOptions: SelectOption[] = (currenciesQuery.data ?? []).map((currency) => ({
    value: currency.id,
    label: `${currency.code} — ${currency.name}`,
  }))

  const {
    control,
    register,
    handleSubmit,
    setValue,
    setError,
    trigger,
    formState: { errors, isSubmitting },
  } = useForm<ServiceCreateFormInput, unknown, ServiceCreateFormValues>({
    resolver: zodResolver(serviceCreateFormSchema),
    mode: 'onTouched',
    defaultValues: {
      tentativeDate: todayInLima(),
      origin: '',
      destination: '',
      weightKg: '',
      lengthM: '',
      widthM: '',
      heightM: '',
      price: '',
      observations: '',
    },
  })

  // `DateField` y `SelectField` tipan su `control` con el form clásico, donde lo que
  // entra y lo que sale son lo mismo. Acá no: los importes entran como texto y salen
  // convertidos. El cast solo concilia esa varianza de tipos; los datos que viajan
  // son los mismos.
  const fieldControl = control as unknown as Control<ServiceCreateFormInput>

  const tentativeDate = useWatch({ control, name: 'tentativeDate' })
  const observations = useWatch({ control, name: 'observations' })
  // El registro retroactivo es válido ("el viaje salió ayer y recién hoy se carga"),
  // así que la fecha pasada avisa y no bloquea. Sin el aviso, un año mal tecleado
  // pasa sin que nadie lo note.
  const tentativeDateIsPast = Boolean(tentativeDate) && isPastInLima(tentativeDate)

  function applyClient(client: ClientResponse | null) {
    setSelectedClient(client)
    setValue('clientId', client?.id ?? 0, { shouldValidate: true, shouldTouch: true })
  }

  // Al elegir el tipo de carga se copian su peso y sus medidas estándar, como hacía
  // v1: son el punto de partida del 90% de los viajes y quedan editables.
  function applyCargoType(cargoType: CargoTypeResponse | null) {
    setSelectedCargoType(cargoType)
    setValue('cargoTypeId', cargoType?.id ?? 0, { shouldValidate: true, shouldTouch: true })
    if (!cargoType) return
    setValue('weightKg', toFieldText(cargoType.standardWeight), { shouldValidate: true })
    setValue('lengthM', toFieldText(cargoType.standardLength), { shouldValidate: true })
    setValue('widthM', toFieldText(cargoType.standardWidth), { shouldValidate: true })
    setValue('heightM', toFieldText(cargoType.standardHeight), { shouldValidate: true })
  }

  const onSubmit = handleSubmit(async (values) => {
    try {
      const service = await createService.mutateAsync(toServiceCreateRequest(values))
      onCreated(service)
    } catch (error) {
      if (isDuplicateService(error)) {
        // Va al formulario y no a un campo: el origen que se marcaría en rojo no
        // tiene nada de malo, y el aviso habla del viaje entero.
        setError('root', {
          type: 'backend',
          message: 'Este mismo viaje se registró hace unos segundos. Revisa el listado antes de repetirlo.',
        })
        return
      }
      handleApiFormError(error, {
        setError,
        fallbackMessage: 'No se pudo registrar el servicio. Intenta de nuevo.',
        allowedFields: FORM_FIELDS,
      })
    }
  })

  if (currenciesQuery.isLoading) {
    return (
      <div className="flex justify-center py-16">
        <Spinner size={28} label="Cargando monedas" className="text-blue-600" />
      </div>
    )
  }

  // Sin monedas no hay alta posible: la moneda es obligatoria. El corte mira las dos
  // formas de quedarse sin ellas, el fallo y la lista vacía, porque para el usuario
  // son la misma: un desplegable habilitado y sin opciones del que se entera recién
  // después de llenar los doce campos.
  if (currenciesQuery.isError || currencyOptions.length === 0) {
    return (
      <div role="alert" className="flex flex-col items-center px-6 py-16 text-center">
        <p className="text-sm font-medium text-slate-700">
          {currenciesQuery.isError
            ? getApiErrorMessage(currenciesQuery.error, 'No se pudieron cargar las monedas.')
            : 'No hay monedas configuradas. Sin moneda no se puede registrar un servicio.'}
        </p>
        <button
          type="button"
          onClick={() => void currenciesQuery.refetch()}
          className="mt-4 inline-flex items-center rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 focus:outline-none focus:ring-2 focus:ring-blue-500"
        >
          Reintentar
        </button>
      </div>
    )
  }

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-5">
      {errors.root?.message && (
        <p
          role="alert"
          className="rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-sm text-amber-800"
        >
          {errors.root.message}
        </p>
      )}

      <Section title="Viaje">
        <ServiceClientField
          value={selectedClient}
          onChange={applyClient}
          onBlur={() => void trigger('clientId')}
          error={errors.clientId?.message}
          canCreate={canCreateCatalogs}
          disabled={isSubmitting}
        />

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <div>
            <label
              htmlFor="service-trip-scope"
              className="mb-1.5 block text-sm font-medium text-slate-700"
            >
              Ámbito del viaje
            </label>
            {/* Dos opciones fijas del contrato, sin catálogo detrás. No usa el
                `SelectField` compartido porque aquel normaliza el valor a número y
                el ámbito es un enum de texto. */}
            <Controller
              name="tripScope"
              control={control}
              render={({ field }) => (
                <select
                  id="service-trip-scope"
                  disabled={isSubmitting}
                  value={field.value ?? ''}
                  onChange={field.onChange}
                  onBlur={field.onBlur}
                  aria-invalid={Boolean(errors.tripScope)}
                  aria-describedby={errors.tripScope ? 'service-trip-scope-error' : undefined}
                  className={cn(
                    'w-full rounded-lg border bg-white px-3.5 py-2.5 text-sm text-slate-900 focus:border-blue-500 focus:outline-none focus:ring-2 focus:ring-blue-500',
                    errors.tripScope ? 'border-red-300' : 'border-slate-300',
                  )}
                >
                  <option value="">Elige el ámbito</option>
                  {TRIP_SCOPE_OPTIONS.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
              )}
            />
            {errors.tripScope?.message && (
              <p id="service-trip-scope-error" role="alert" className="mt-1.5 text-sm text-red-600">
                {errors.tripScope.message}
              </p>
            )}
          </div>

          <div>
            <DateField
              id="service-tentative-date"
              label="Fecha tentativa"
              name="tentativeDate"
              control={fieldControl}
              min={SERVICE_DATE_MIN}
              max={SERVICE_DATE_MAX}
              error={errors.tentativeDate?.message}
              disabled={isSubmitting}
            />
            {tentativeDateIsPast && !errors.tentativeDate && (
              <p
                id="service-tentative-date-past"
                role="alert"
                className="mt-1.5 flex items-center gap-1.5 text-xs text-amber-700"
              >
                <TriangleAlert className="h-3.5 w-3.5" aria-hidden="true" />
                La fecha ya pasó. Se registra igual (viaje cargado en retrospectiva).
              </p>
            )}
          </div>
        </div>

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <TextField
            id="service-origin"
            label="Origen"
            error={errors.origin?.message}
            disabled={isSubmitting}
            register={register('origin')}
          />
          <TextField
            id="service-destination"
            label="Destino"
            error={errors.destination?.message}
            disabled={isSubmitting}
            register={register('destination')}
          />
        </div>
      </Section>

      <Section title="Carga">
        <CargoTypeField
          id="service-cargo-type"
          value={selectedCargoType?.id ?? null}
          valueName={selectedCargoType?.name}
          onChange={applyCargoType}
          onBlur={() => void trigger('cargoTypeId')}
          error={errors.cargoTypeId?.message}
          canCreate={canCreateCatalogs}
          disabled={isSubmitting}
        />

        <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
          <TextField
            id="service-weight"
            label="Peso (kg)"
            type="number"
            min={0}
            step={0.01}
            error={errors.weightKg?.message}
            disabled={isSubmitting}
            register={register('weightKg')}
          />
          <TextField
            id="service-length"
            label="Largo (m)"
            type="number"
            min={0}
            step={0.01}
            error={errors.lengthM?.message}
            disabled={isSubmitting}
            register={register('lengthM')}
          />
          <TextField
            id="service-width"
            label="Ancho (m)"
            type="number"
            min={0}
            step={0.01}
            error={errors.widthM?.message}
            disabled={isSubmitting}
            register={register('widthM')}
          />
          <TextField
            id="service-height"
            label="Alto (m)"
            type="number"
            min={0}
            step={0.01}
            error={errors.heightM?.message}
            disabled={isSubmitting}
            register={register('heightM')}
          />
        </div>
      </Section>

      <Section title="Precio">
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <TextField
            id="service-price"
            label="Precio"
            type="number"
            min={0}
            step={0.01}
            error={errors.price?.message}
            disabled={isSubmitting}
            register={register('price')}
          />
          <SelectField
            id="service-currency"
            label="Moneda"
            name="currencyId"
            control={fieldControl}
            options={currencyOptions}
            placeholder="Elige la moneda"
            error={errors.currencyId?.message}
            disabled={isSubmitting}
          />
        </div>
      </Section>

      <Section title="Observaciones">
        <Textarea
          id="service-observations"
          label="Observaciones (opcional)"
          rows={3}
          maxLength={SERVICE_OBSERVATIONS_MAX_LENGTH}
          showCounter
          value={observations}
          error={errors.observations?.message}
          disabled={isSubmitting}
          sanitize={stripControlChars}
          register={register('observations')}
        />
      </Section>

      <div className="flex justify-end gap-2">
        <button type="button" onClick={onCancel} className={SECONDARY_BUTTON} disabled={isSubmitting}>
          Cancelar
        </button>
        <button type="submit" disabled={isSubmitting} className={PRIMARY_BUTTON}>
          {isSubmitting ? (
            <>
              <Spinner size={16} label="Registrando" />
              Registrando…
            </>
          ) : (
            'Registrar servicio'
          )}
        </button>
      </div>
    </form>
  )
}

/** `true` si el backend rechazó el alta por repetida (409 `OPS-007`). */
function isDuplicateService(error: unknown): boolean {
  if (!isAxiosError(error)) return false
  const problem = error.response?.data as Problem | undefined
  return problem?.code === DUPLICATE_SERVICE_CODE
}
