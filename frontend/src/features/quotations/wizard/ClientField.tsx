import { useState } from 'react'
import { useFormContext } from 'react-hook-form'
import { Combobox, type ComboboxOption } from '../../../shared/ui/Combobox'
import { useDebouncedValue } from '../../../shared/hooks/useDebouncedValue'
import { CLIENT_SEARCH_MIN_LENGTH, useClientsSearch } from '../../clients/hooks/useClientsSearch'
import { CrearClienteModal } from './CrearClienteModal'
import type { ClientResponse } from '../../../api'
import type { WizardFormInput } from './quotation-wizard.schema'

function toOption(client: ClientResponse): ComboboxOption {
  return { id: client.id, label: client.name, sublabel: `RUC ${client.ruc}` }
}

interface ClientFieldProps {
  /** Cliente seleccionado. Vive en el WizardForm (no acá) para persistir entre
   * steps: este componente se desmonta al cambiar de step. */
  selectedClient: ClientResponse | null
  onClientChange: (client: ClientResponse | null) => void
}

/**
 * Combobox de cliente (búsqueda async, minLength 3) + creación al vuelo. El
 * `clientId` vive en el form (fuente de verdad para validar); el objeto cliente
 * lo administra el WizardForm (para el label + precargar contacto).
 */
export function ClientField({ selectedClient, onClientChange }: ClientFieldProps) {
  const {
    setValue,
    trigger,
    formState: { errors },
  } = useFormContext<WizardFormInput>()
  const [query, setQuery] = useState('')
  const [modalOpen, setModalOpen] = useState(false)
  const debouncedQuery = useDebouncedValue(query, 300)
  const { data, isFetching } = useClientsSearch(debouncedQuery)

  const options = (data?.content ?? []).map(toOption)

  function applyClient(client: ClientResponse) {
    onClientChange(client)
    setValue('clientId', client.id, { shouldValidate: true, shouldTouch: true })
    // contactName/contactPhone son snapshot: se precargan del cliente, editables.
    setValue('contactName', client.contactName ?? '', { shouldValidate: true })
    setValue('contactPhone', client.phone ?? '', { shouldValidate: true })
  }

  function handleSelect(option: ComboboxOption) {
    const client = data?.content.find((item) => item.id === option.id)
    if (client) applyClient(client)
  }

  function handleClear() {
    onClientChange(null)
    setValue('clientId', 0, { shouldValidate: true })
    // Limpiar el snapshot de contacto: si no, queda el del cliente anterior asociado
    // a "ningún cliente" y se arrastraría al POST.
    setValue('contactName', '', { shouldValidate: true })
    setValue('contactPhone', '', { shouldValidate: true })
  }

  return (
    <>
      {/* Cliente (2) + RUC (1) en una fila. El RUC siempre visible y de solo lectura. */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <div className="sm:col-span-2">
          <Combobox
            id="clientId"
            label="Cliente"
            placeholder="Buscá por nombre o RUC…"
            options={options}
            selected={selectedClient ? { id: selectedClient.id, label: selectedClient.name } : null}
            onQueryChange={setQuery}
            onSelect={handleSelect}
            onClear={handleClear}
            onBlur={() => trigger('clientId')}
            loading={isFetching}
            minChars={CLIENT_SEARCH_MIN_LENGTH}
            minCharsHint={`Ingresá al menos ${CLIENT_SEARCH_MIN_LENGTH} caracteres para buscar.`}
            emptyText="No se encontraron clientes."
            error={errors.clientId?.message}
            createLabel="Nuevo cliente"
            onCreateClick={() => setModalOpen(true)}
          />
        </div>
        <div>
          <label htmlFor="cliente-ruc-display" className="mb-1.5 block text-sm font-medium text-slate-700">
            RUC
          </label>
          {/* Vacío hasta elegir cliente; luego muestra su RUC. Siempre de solo lectura. */}
          <input
            id="cliente-ruc-display"
            type="text"
            value={selectedClient?.ruc ?? ''}
            readOnly
            // Accessible name propio: evita colisionar con el campo "RUC" del modal de
            // crear cliente (el <label> visible sigue siendo "RUC").
            aria-label="RUC del cliente seleccionado"
            className="w-full cursor-default rounded-lg border border-slate-200 bg-slate-50 px-3.5 py-2.5 text-sm text-slate-600 focus:outline-none"
          />
        </div>
      </div>
      {modalOpen && (
        <CrearClienteModal
          initialName={query}
          onClose={() => setModalOpen(false)}
          onCreated={(client) => {
            applyClient(client)
            setModalOpen(false)
          }}
        />
      )}
    </>
  )
}
