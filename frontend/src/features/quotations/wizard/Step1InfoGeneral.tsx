import { useFormContext } from 'react-hook-form'
import { DateField } from '../../../shared/ui/DateField'
import { SelectField } from '../../../shared/ui/SelectField'
import { TextField } from '../../../shared/ui/TextField'
import { ClientField } from './ClientField'
import { QuotationTypeCards } from './QuotationTypeCards'
import type { ImmutableField, WizardFormInput } from './quotation-wizard.schema'
import type { ClientResponse, CurrencyResponse, PaymentTermResponse } from '../../../api'

function todayISO(): string {
  const now = new Date()
  const year = now.getFullYear()
  const month = String(now.getMonth() + 1).padStart(2, '0')
  const day = String(now.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

interface Step1InfoGeneralProps {
  currencies: CurrencyResponse[]
  paymentTerms: PaymentTermResponse[]
  selectedClient: ClientResponse | null
  onClientChange: (client: ClientResponse | null) => void
  /** Campos inmutables (edición): bloquean el selector de tipo y el de cliente. */
  immutableFields?: ReadonlyArray<ImmutableField>
}

const SECTION = 'rounded-xl border border-slate-200 bg-white p-5 shadow-sm'
const SECTION_TITLE = 'text-sm font-semibold uppercase tracking-wide text-slate-500'
// En el grid de 4 columnas (lg) "Fecha tentativa (opcional)" envuelve a 2 líneas: reservar 2
// líneas de alto con el texto abajo mantiene los inputs alineados, sin aire extra en mobile.
const COMMERCIAL_LABEL = 'lg:flex lg:items-end lg:min-h-[2.5rem]'

export function Step1InfoGeneral({
  currencies,
  paymentTerms,
  selectedClient,
  onClientChange,
  immutableFields = [],
}: Step1InfoGeneralProps) {
  const {
    control,
    register,
    watch,
    setValue,
    formState: { errors },
  } = useFormContext<WizardFormInput>()
  const isTransporte = watch('quotationType') === 'TRANSPORTE'

  // PEN primero (la política es: backend ordena natural, el front reordena para UI).
  const currencyOptions = [...currencies]
    .sort((a, b) => (a.code === 'PEN' ? -1 : b.code === 'PEN' ? 1 : 0))
    .map((currency) => ({ value: currency.id, label: `${currency.code} (${currency.symbol})` }))
  const paymentOptions = paymentTerms.map((term) => ({ value: term.id, label: term.name }))

  return (
    <div className="space-y-6">
      <section>
        <h2 className={SECTION_TITLE}>Tipo de cotización</h2>
        <div className="mt-3">
          {/* Al cambiar de tipo se vacían los ítems: sus tipos de servicio dependen del
              tipo de cotización (TRANSPORTE vs ALQUILER) y no son intercambiables. */}
          <QuotationTypeCards
            control={control}
            onTypeChange={() => setValue('items', [], { shouldValidate: false })}
            disabled={immutableFields.includes('quotationType')}
          />
        </div>
      </section>

      <section className={`${SECTION} space-y-4`}>
        <h2 className={SECTION_TITLE}>Cliente</h2>
        <ClientField
          selectedClient={selectedClient}
          onClientChange={onClientChange}
          readOnly={immutableFields.includes('clientId')}
        />
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <TextField
            id="contactName"
            label="Persona de contacto"
            error={errors.contactName?.message}
            register={register('contactName')}
          />
          <TextField
            id="contactPhone"
            label="Teléfono de contacto (opcional)"
            type="tel"
            error={errors.contactPhone?.message}
            register={register('contactPhone')}
          />
        </div>
      </section>

      {isTransporte && (
        <section className={`${SECTION} space-y-4`}>
          <h2 className={SECTION_TITLE}>Ruta</h2>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
            <TextField
              id="origin"
              label="Origen"
              error={errors.origin?.message}
              register={register('origin')}
            />
            <TextField
              id="destination"
              label="Destino"
              error={errors.destination?.message}
              register={register('destination')}
            />
          </div>
        </section>
      )}

      <section className={`${SECTION} space-y-4`}>
        <h2 className={SECTION_TITLE}>Condiciones comerciales</h2>
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
          <SelectField
            id="currencyId"
            label="Moneda"
            name="currencyId"
            control={control}
            options={currencyOptions}
            placeholder="Selecciona"
            error={errors.currencyId?.message}
            labelClassName={COMMERCIAL_LABEL}
          />
          <SelectField
            id="paymentTermId"
            label="Condición de pago"
            name="paymentTermId"
            control={control}
            options={paymentOptions}
            placeholder="Selecciona"
            error={errors.paymentTermId?.message}
            labelClassName={COMMERCIAL_LABEL}
          />
          <DateField
            id="tentativeServiceDate"
            label="Fecha tentativa (opcional)"
            name="tentativeServiceDate"
            control={control}
            min={todayISO()}
            error={errors.tentativeServiceDate?.message}
            labelClassName={COMMERCIAL_LABEL}
          />
          <TextField
            id="validityDays"
            label="Validez (días)"
            type="number"
            min={1}
            max={365}
            step={1}
            error={errors.validityDays?.message}
            labelClassName={COMMERCIAL_LABEL}
            register={register('validityDays', {
              // Vacío → undefined (no NaN) para que zod muestre el mensaje requerido limpio.
              setValueAs: (value) => (value === '' ? undefined : Number(value)),
            })}
          />
        </div>
      </section>
    </div>
  )
}
