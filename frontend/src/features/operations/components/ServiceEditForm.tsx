import { useState, type ReactNode } from 'react'
import { useForm, useWatch, type Control, type UseFormRegisterReturn } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { isAxiosError } from 'axios'
import type { CurrencyResponse, Problem, ServiceDetailResponse } from '../../../api'
import { DateField } from '../../../shared/ui/DateField'
import { FIELD_FOCUS_INVALID, fieldClasses } from '../../../shared/ui/fieldClasses'
import { SelectField, type SelectOption } from '../../../shared/ui/SelectField'
import { Spinner } from '../../../shared/ui/Spinner'
import { TextField } from '../../../shared/ui/TextField'
import { Textarea } from '../../../shared/ui/Textarea'
import { getApiErrorMessage, isPreconditionFailedError } from '../../../shared/utils/getApiErrorMessage'
import { handleApiFormError } from '../../../shared/utils/handleApiFormError'
import { stripControlChars } from '../../../shared/utils/sanitizeText'
import { cn } from '../../../shared/utils/cn'
import { nowInLimaForInput } from '../utils/limaDate'
import { useServiceCurrencies } from '../hooks/useServiceCurrencies'
import { useUpdateService } from '../hooks/useUpdateService'
import {
  JUSTIFICATION_MAX_LENGTH,
  JUSTIFICATION_MIN_LENGTH,
  REAL_DATE_TIME_MIN,
  serviceEditFormSchema,
  toServiceEditFormValues,
  toServiceUpdateRequest,
  type ServiceEditFormInput,
  type ServiceEditFormValues,
} from '../schemas/service-edit.schema'
import {
  SERVICE_DATE_MAX,
  SERVICE_DATE_MIN,
  SERVICE_OBSERVATIONS_MAX_LENGTH,
} from '../schemas/service-fields.schema'
import type { ServiceWithEtag } from '../hooks/useService'
import { Button } from '../../../shared/ui/Button'
import { Card } from '../../../shared/ui/Card'
import { Alert } from '../../../shared/ui/Alert'

/** Campos que aceptan un error de campo del backend, cuando están en pantalla. */
const FORM_FIELDS = [
  'tentativeDate',
  'origin',
  'destination',
  'weightKg',
  'lengthM',
  'widthM',
  'heightM',
  'price',
  'currencyId',
  'observations',
  'startDateTime',
  'endDateTime',
  'justification',
] as const

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <Card as="section">
      <h2 className="mb-4 text-xs font-semibold uppercase tracking-wide text-slate-500">{title}</h2>
      <div className="space-y-4">{children}</div>
    </Card>
  )
}

/**
 * Un campo de fecha y hora, en hora de Perú.
 *
 * Input crudo y no el `TextField` compartido, que solo admite los tipos de texto: es el
 * mismo camino que tomó el modal que fija estas fechas, y por la misma razón. Cambiar el
 * compartido para esto sería tocar una pieza de todo el sistema desde una pantalla.
 *
 * El `min` es la ventana que la columna admite, la misma que ya acota a la fecha
 * tentativa: le evita al usuario elegir un año de una cifra, que el `datetime-local`
 * acepta y el servidor rechaza con un 400 sobre el formulario entero.
 *
 * El `max` es el AHORA de Lima, igual que en los diálogos que fijan estas fechas: una
 * fecha real no puede estar en el futuro, y corregirla no puede ser más permisivo que
 * ponerla.
 *
 * Es una ayuda del selector y NO la guarda: la que decide es la del schema, que lee el
 * reloj al enviar. Por eso no importa que este atributo quede viejo en un formulario
 * abierto hace rato (React no re-renderiza porque avance el reloj): lo peor que puede
 * pasar es que el selector no ofrezca el último minuto, y el envío igual se valida contra
 * la hora de ese momento.
 */
function RealDateTimeField({
  id,
  label,
  error,
  disabled,
  register,
}: {
  id: string
  label: string
  error?: string
  disabled?: boolean
  register: UseFormRegisterReturn
}) {
  return (
    <div>
      <label htmlFor={id} className="mb-1.5 block text-sm font-medium text-slate-700">
        {label}
      </label>
      <input
        id={id}
        type="datetime-local"
        // El paso al minuto, explícito, igual que en el modal que fija estas fechas: la
        // precisión con la que este formulario trabaja se escribe acá y no se hereda de
        // un default ajeno.
        step={60}
        min={REAL_DATE_TIME_MIN}
        max={nowInLimaForInput()}
        disabled={disabled}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? `${id}-error` : undefined}
        {...register}
        className={cn('w-full', fieldClasses({ invalid: !!error }), error && FIELD_FOCUS_INVALID)}
      />
      {error && (
        <p id={`${id}-error`} role="alert" className="mt-1.5 text-sm text-red-600">
          {error}
        </p>
      )}
    </div>
  )
}

interface ServiceEditFormProps {
  service: ServiceWithEtag
  /** Trae el viaje de nuevo, descartando lo escrito. Solo se ofrece ante un 412. */
  onReload: () => void
  /** El viaje guardado, y si el servidor llegó a escribir algo (ver RN-OP10). */
  onSaved: (service: ServiceDetailResponse, changed: boolean) => void
  onCancel: () => void
}

/**
 * Edición de un servicio, con justificación obligatoria.
 *
 * Los mismos bloques que el alta menos los tres campos inmutables (cliente, ámbito y
 * tipo de carga), más las dos fechas reales y la justificación. No reusa `ServiceForm`
 * porque los dos formularios se parecen en los campos y no en lo que hacen: aquel elige
 * cliente y tipo de carga con sus buscadores y su alta al vuelo, copia las medidas
 * estándar del catálogo y avisa por la fecha pasada; este corrige lo que ya existe y
 * exige explicar por qué. Fundirlos habría dejado un componente con dos modos y la mitad
 * de sus piezas apagadas en cada uno. Lo que sí comparten son las REGLAS, que viven en
 * `service-fields.schema`.
 *
 * El precio es obligatorio, así que a esta pantalla no llega el despacho: el mismo
 * cuerpo que lo exige es lo que le devuelve un 403.
 */
export function ServiceEditForm({ service, onReload, onSaved, onCancel }: ServiceEditFormProps) {
  const currenciesQuery = useServiceCurrencies()
  const updateService = useUpdateService(service.id)

  /*
   * Las fechas reales se ofrecen SOLO si el viaje ya las tiene, que es la condición del
   * contrato: acá se corrigen, no se fijan. Las fija la transición de estado (el inicio
   * al ponerse en ruta, el fin al completar), y mandarlas antes es un 400.
   *
   * Se mira la fecha y no el estado, por la misma razón que el servidor: un viaje que
   * llegó del sistema anterior sin la fecha tampoco se corrige por acá, aunque su estado
   * diga que ya arrancó.
   */
  const hasStartDateTime = Boolean(service.startDateTime)
  const hasEndDateTime = Boolean(service.endDateTime)

  const currencyOptions: SelectOption[] = (currenciesQuery.data ?? []).map((currency) => ({
    value: currency.id,
    label: `${currency.code} — ${currency.name}`,
  }))

  if (currenciesQuery.isLoading) {
    return (
      <div className="flex justify-center py-16">
        <Spinner size={28} label="Cargando monedas" className="text-blue-600" />
      </div>
    )
  }

  // Sin el catálogo no se puede abrir el formulario: la moneda del viaje llega por su
  // código y su id sale de acá, así que sin lista no hay valor inicial que poner. Se
  // corta antes de montar el formulario en vez de dejarlo abrir con el selector vacío,
  // que es la forma de guardar con otra moneda sin notarlo.
  if (currenciesQuery.isError || currencyOptions.length === 0) {
    return (
      <CatalogAlert
        message={
          currenciesQuery.isError
            ? getApiErrorMessage(currenciesQuery.error, 'No se pudieron cargar las monedas.')
            : 'No hay monedas configuradas. Sin moneda no se puede editar un servicio.'
        }
        onRetry={() => void currenciesQuery.refetch()}
      />
    )
  }

  /*
   * La moneda se resuelve ACÁ y no adentro del formulario, aunque su valor inicial lo
   * necesite: `toServiceEditFormValues` revienta si el código del viaje no está en el
   * catálogo, y adentro eso sería un throw en pleno render. No hay ErrorBoundary en la
   * app (`grep -rn ErrorBoundary src` no devuelve nada), así que el árbol entero se
   * desmonta y el usuario ve una pantalla en blanco, sin barra y sin vuelta al detalle.
   * Preguntando antes, el mismo problema aterriza en el aviso que ya existe.
   */
  const initial = tryFormValues(service, currenciesQuery.data ?? [])
  if ('error' in initial) {
    return <CatalogAlert message={initial.error} onRetry={() => void currenciesQuery.refetch()} />
  }

  return (
    <EditFields
      service={service}
      initialValues={initial.values}
      currencyOptions={currencyOptions}
      hasStartDateTime={hasStartDateTime}
      hasEndDateTime={hasEndDateTime}
      updateService={updateService}
      onReload={onReload}
      onSaved={onSaved}
      onCancel={onCancel}
    />
  )
}

interface EditFieldsProps extends ServiceEditFormProps {
  initialValues: ServiceEditFormInput
  currencyOptions: SelectOption[]
  hasStartDateTime: boolean
  hasEndDateTime: boolean
  updateService: ReturnType<typeof useUpdateService>
  onReload: () => void
}

/** El aviso que reemplaza al formulario cuando no se lo puede abrir con datos ciertos. */
function CatalogAlert({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <div role="alert" className="flex flex-col items-center px-6 py-16 text-center">
      <p className="text-sm font-medium text-slate-700">{message}</p>
      <Button variant="secondary" onClick={onRetry} className="mt-4">
        Reintentar
      </Button>
    </div>
  )
}

/**
 * Los valores iniciales, o el motivo por el que no se pudieron armar.
 *
 * Envuelve el `throw` de `toServiceEditFormValues` para que la decisión de no abrir el
 * formulario (que está bien) no se exprese desmontando la aplicación entera.
 *
 * Devuelve el mensaje del error y no uno propio: acá adentro se arman trece campos, y la
 * moneda es solo uno de los que pueden fallar (una fecha ilegible del servidor también).
 * Reconstruir el motivo desde afuera le atribuiría a la moneda cualquier fallo, y el
 * usuario iría a mirar un catálogo que está bien. Por eso los dos caminos que llegan acá
 * traen un mensaje escrito para leerse, en castellano.
 */
function tryFormValues(
  service: ServiceWithEtag,
  currencies: readonly CurrencyResponse[],
): { values: ServiceEditFormInput } | { error: string } {
  try {
    return { values: toServiceEditFormValues(service, currencies) }
  } catch (error) {
    return { error: error instanceof Error ? error.message : 'No se pudo abrir el formulario.' }
  }
}

/**
 * El formulario propiamente dicho, montado recién con el catálogo en la mano.
 *
 * Va aparte para que los valores iniciales se calculen UNA vez, al montar: react-hook-form
 * congela sus `defaultValues` en el primer render, así que armarlos en el componente de
 * arriba los dejaría fijados con el catálogo todavía vacío, y la moneda del viaje nunca
 * aparecería seleccionada.
 */
function EditFields({
  service,
  initialValues,
  currencyOptions,
  hasStartDateTime,
  hasEndDateTime,
  updateService,
  onReload,
  onSaved,
  onCancel,
}: EditFieldsProps) {
  const {
    control,
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<ServiceEditFormInput, unknown, ServiceEditFormValues>({
    resolver: zodResolver(serviceEditFormSchema),
    mode: 'onTouched',
    defaultValues: initialValues,
  })

  // Mismo cast que en el alta: los importes entran como texto y salen convertidos, y los
  // campos compartidos tipan su `control` con el form clásico. Solo concilia la varianza.
  const fieldControl = control as unknown as Control<ServiceEditFormInput>

  const observations = useWatch({ control, name: 'observations' })
  const justification = useWatch({ control, name: 'justification' })
  const [stale, setStale] = useState(false)
  /*
   * Sin ETag no hay guardado posible: el contrato exige `If-Match` y sin él contesta 412
   * SIEMPRE. Se dice acá, con su causa, en vez de dejar que el usuario llene el
   * formulario y lea "el viaje cambió mientras lo editabas", que sería mandarlo a buscar
   * un conflicto que no existe: lo que falta es un header del gateway
   * (`cors.exposed-headers=ETag`), o sea configuración y no un estado del viaje. Mismo
   * tratamiento que la edición de un retiro en almacén.
   */
  const missingEtag = service._etag === null

  const visibleFields = FORM_FIELDS.filter(
    (field) =>
      (field !== 'startDateTime' || hasStartDateTime) &&
      (field !== 'endDateTime' || hasEndDateTime),
  )

  const onSubmit = handleSubmit(async (values) => {
    try {
      const { data } = await updateService.mutateAsync({
        ifMatch: service._etag,
        body: toServiceUpdateRequest(values),
      })
      /*
       * Los dos 200 no son el mismo. El contrato (RN-OP10) dice que un cuerpo sin
       * cambios reales responde 200 y NO escribe nada: ni auditoría, ni bitácora, ni
       * versión. Tratarlos igual le canta al usuario "actualizado" sobre una escritura
       * que no ocurrió, y de paso descarta su justificación sin decírselo, después de
       * haberle prometido que quedaba en la bitácora.
       *
       * Se distinguen por la versión, que es lo único que el servidor mueve cuando
       * escribe de verdad, y que además es el dato que ya viene en la respuesta.
       */
      onSaved(data, data.updatedAt !== service.updatedAt)
    } catch (error) {
      const blocking = blockingMessage(error)
      if (blocking) {
        // Va al aviso del formulario y no a un toast: los dos casos dicen que el viaje
        // cambió por debajo mientras se editaba, y el usuario tiene el formulario lleno.
        // Un toast se va solo, justo cuando hay que leerlo dos veces para entender qué
        // hacer.
        setError('root', { type: 'backend', message: blocking })
        // El 412 además deja el formulario sin salida: la versión con la que abrió ya no
        // corre, y como se congela al montar, reintentar manda la misma y vuelve a fallar
        // para siempre. La salida es traer el viaje de nuevo, y cuesta lo escrito.
        setStale(isPreconditionFailedError(error))
        return
      }
      handleApiFormError(error, {
        setError,
        fallbackMessage: 'No se pudieron guardar los cambios. Intenta de nuevo.',
        // Solo los campos que ESTÁN en pantalla: las dos fechas reales se ofrecen apenas
        // el viaje las tiene, y marcar un campo que no se renderizó deja al usuario
        // apretando Guardar sin que pase nada visible. Lo que no puede ver, no lo puede
        // corregir, así que ese error sale por el toast del manejador genérico, que al
        // menos se ve.
        allowedFields: visibleFields,
      })
    }
  })

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-5">
      {missingEtag && (
        <Alert as="p" role="alert" className="rounded-xl px-4 py-3 text-sm text-red-700">
          No se puede guardar: falta la versión del viaje. Recarga la página e intenta de nuevo.
        </Alert>
      )}

      {errors.root?.message && (
        <Alert variant="warning" role="alert" className="rounded-xl px-4 py-3 text-sm text-amber-800">
          <p>{errors.root.message}</p>
          {stale && (
            /*
             * El mismo botón que las transiciones, con el mismo nombre y la misma
             * honestidad: NO reintenta con una versión fresca. Reenviar el formulario
             * con el ETag nuevo mandaría los campos tal como se cargaron, incluido el
             * que la otra persona acaba de cambiar, y lo pisaría en silencio. Eso
             * convertiría el bloqueo optimista en un "gana el último que aprieta".
             */
            <Button variant="secondary" onClick={onReload} className="mt-2">
              Descartar y recargar
            </Button>
          )}
        </Alert>
      )}

      <Section title="Viaje">
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <DateField
            id="service-edit-tentative-date"
            label="Fecha tentativa"
            name="tentativeDate"
            control={fieldControl}
            min={SERVICE_DATE_MIN}
            max={SERVICE_DATE_MAX}
            error={errors.tentativeDate?.message}
            disabled={isSubmitting}
          />
        </div>

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <TextField
            id="service-edit-origin"
            label="Origen"
            error={errors.origin?.message}
            disabled={isSubmitting}
            register={register('origin')}
          />
          <TextField
            id="service-edit-destination"
            label="Destino"
            error={errors.destination?.message}
            disabled={isSubmitting}
            register={register('destination')}
          />
        </div>
      </Section>

      <Section title="Carga">
        <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
          <TextField
            id="service-edit-weight"
            label="Peso (kg)"
            type="number"
            min={0}
            step={0.01}
            error={errors.weightKg?.message}
            disabled={isSubmitting}
            register={register('weightKg')}
          />
          <TextField
            id="service-edit-length"
            label="Largo (m)"
            type="number"
            min={0}
            step={0.01}
            error={errors.lengthM?.message}
            disabled={isSubmitting}
            register={register('lengthM')}
          />
          <TextField
            id="service-edit-width"
            label="Ancho (m)"
            type="number"
            min={0}
            step={0.01}
            error={errors.widthM?.message}
            disabled={isSubmitting}
            register={register('widthM')}
          />
          <TextField
            id="service-edit-height"
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
            id="service-edit-price"
            label="Precio"
            type="number"
            min={0}
            step={0.01}
            error={errors.price?.message}
            disabled={isSubmitting}
            register={register('price')}
          />
          <SelectField
            id="service-edit-currency"
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

      {(hasStartDateTime || hasEndDateTime) && (
        <Section title="Fechas reales">
          {/* Solo las que el viaje YA tiene: acá se corrigen, no se fijan. Un viaje que
              todavía no arrancó no muestra ningún campo, y el bloque entero desaparece. */}
          <p className="text-xs text-slate-500">
            Se corrigen en hora de Perú. Las fija el viaje al iniciarse y al cerrarse; acá solo se
            enmiendan.
          </p>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            {hasStartDateTime && (
              <RealDateTimeField
                id="service-edit-start"
                label="Inicio real"
                error={errors.startDateTime?.message}
                disabled={isSubmitting}
                register={register('startDateTime')}
              />
            )}
            {hasEndDateTime && (
              <RealDateTimeField
                id="service-edit-end"
                label="Fin real"
                error={errors.endDateTime?.message}
                disabled={isSubmitting}
                register={register('endDateTime')}
              />
            )}
          </div>
        </Section>
      )}

      <Section title="Observaciones">
        <Textarea
          id="service-edit-observations"
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

      <Section title="Justificación">
        <Textarea
          id="service-edit-justification"
          label="Motivo del cambio"
          rows={3}
          maxLength={JUSTIFICATION_MAX_LENGTH}
          showCounter
          value={justification}
          helperText={`Mínimo ${JUSTIFICATION_MIN_LENGTH} caracteres. Si hay algo que corregir, queda en la bitácora del viaje junto a la lista de lo que cambió.`}
          error={errors.justification?.message}
          disabled={isSubmitting}
          sanitize={stripControlChars}
          register={register('justification')}
        />
      </Section>

      <div className="flex justify-end gap-2">
        <Button variant="secondary" onClick={onCancel} disabled={isSubmitting}>
          Cancelar
        </Button>
        <Button type="submit" variant="primary" disabled={isSubmitting || missingEtag}>
          {isSubmitting ? (
            <>
              <Spinner size={16} label="Guardando" />
              Guardando…
            </>
          ) : (
            'Guardar cambios'
          )}
        </Button>
      </div>
    </form>
  )
}

/**
 * El mensaje de los dos rechazos que no se arreglan corrigiendo un campo, o `null` si el
 * error no es de esos.
 *
 * Los dos dicen lo mismo desde ángulos distintos: el viaje dejó de ser el que se abrió.
 * Con el 409 salió del circuito (lo cancelaron o lo eliminaron) y con el 412 alguien lo
 * editó en el medio, así que la versión que se está por pisar ya no es la última. Se
 * nombra el `detail` del servidor, que es el que sabe qué pasó, y se agrega qué hacer,
 * que el servidor no dice.
 */
function blockingMessage(error: unknown): string | null {
  if (!isAxiosError(error)) return null
  const problem = error.response?.data as Problem | undefined
  const detail = problem?.detail
  if (error.response?.status === 409 && problem?.code === 'OPS-004') {
    return `${detail ?? 'El viaje salió del circuito.'} Vuelve al detalle para ver en qué estado quedó.`
  }
  if (error.response?.status === 412) {
    return `${detail ?? 'El viaje cambió mientras lo editabas.'} Trae la versión actual para no pisar lo que hizo otra persona: se pierde lo que escribiste acá.`
  }
  return null
}
