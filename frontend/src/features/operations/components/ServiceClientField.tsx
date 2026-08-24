import { useState } from 'react'
import type { ClientResponse } from '../../../api'
import { Combobox, type ComboboxOption } from '../../../shared/ui/Combobox'
import { useDebouncedValue } from '../../../shared/hooks/useDebouncedValue'
import { ClientCreateModal } from '../../clients/components/ClientCreateModal'
import { CLIENT_SEARCH_MIN_LENGTH, useClientsSearch } from '../../clients/hooks/useClientsSearch'

/** Espera entre la última tecla y la búsqueda, igual que el resto de los buscadores. */
const SEARCH_DEBOUNCE_MS = 300

function toOption(client: ClientResponse): ComboboxOption {
  return { id: client.id, label: client.name, sublabel: `RUC ${client.ruc}` }
}

interface ServiceClientFieldProps {
  /** Cliente elegido. Vive en el formulario, que necesita el objeto y no solo el id. */
  value: ClientResponse | null
  onChange: (client: ClientResponse | null) => void
  onBlur?: () => void
  error?: string
  /**
   * Si se ofrece el alta al vuelo. En falso el buscador sigue funcionando: se saca
   * el botón, no el campo.
   *
   * Por omisión NO se ofrece, al revés que el campo de tipo de carga. No es un
   * descuido: aquel nació dentro del asistente de cotizaciones y su default
   * permisivo es lo que preserva esa pantalla, mientras que este componente nace
   * sin consumidores, así que olvidar la prop no puede terminar mostrándole un
   * atajo a quien el servidor le responde 403.
   */
  canCreate?: boolean
  /**
   * Bloquea el campo mientras el formulario se envía. Sin esto, el botón de quitar
   * selección del combobox seguía vivo durante el envío y se podía desasociar el
   * cliente de un viaje que ya había salido.
   */
  disabled?: boolean
}

/**
 * Buscador de cliente del alta de un servicio, con alta al vuelo.
 *
 * Cotizaciones tiene su propio buscador de cliente y no se reusa: aquel lee el
 * formulario del asistente por contexto y escribe además el contacto, campos que un
 * servicio no tiene. Lo que sí se comparte es la pieza cara, el modal de alta, que
 * valida el RUC y explica el duplicado. Un campo común saldría de tener un tercer
 * consumidor, no de este.
 */
export function ServiceClientField({
  value,
  onChange,
  onBlur,
  error,
  canCreate = false,
  disabled = false,
}: ServiceClientFieldProps) {
  const [query, setQuery] = useState('')
  const [modalOpen, setModalOpen] = useState(false)
  const debouncedQuery = useDebouncedValue(query, SEARCH_DEBOUNCE_MS)
  const { data, isFetching } = useClientsSearch(debouncedQuery)

  const clients = data?.content ?? []

  function handleSelect(option: ComboboxOption) {
    const client = clients.find((candidate) => candidate.id === option.id)
    if (client) onChange(client)
  }

  return (
    <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
      <div className="sm:col-span-2">
        <Combobox
          id="service-client"
          label="Cliente"
          placeholder="Busca por nombre o RUC…"
          options={clients.map(toOption)}
          selected={value ? { id: value.id, label: value.name } : null}
          onQueryChange={setQuery}
          onSelect={handleSelect}
          onClear={() => onChange(null)}
          onBlur={onBlur}
          disabled={disabled}
          loading={isFetching}
          minChars={CLIENT_SEARCH_MIN_LENGTH}
          minCharsHint={`Ingresa al menos ${CLIENT_SEARCH_MIN_LENGTH} caracteres para buscar.`}
          emptyText="No se encontraron clientes."
          error={error}
          createLabel={canCreate ? 'Nuevo cliente' : undefined}
          onCreateClick={canCreate ? () => setModalOpen(true) : undefined}
        />
      </div>
      <div>
        <label
          htmlFor="service-client-ruc"
          className="mb-1.5 block text-sm font-medium text-slate-700"
        >
          RUC
        </label>
        {/* Vacío hasta elegir cliente; nunca editable. Nombre accesible propio para no
            chocar con el campo RUC del modal de alta, que sí se escribe. */}
        <input
          id="service-client-ruc"
          type="text"
          value={value?.ruc ?? ''}
          readOnly
          aria-label="RUC del cliente seleccionado"
          className="w-full cursor-default rounded-lg border border-slate-200 bg-slate-50 px-3.5 py-2.5 text-sm text-slate-600 focus:outline-none"
        />
      </div>
      {modalOpen && (
        <ClientCreateModal
          initialName={query}
          onClose={() => setModalOpen(false)}
          onCreated={(client) => {
            onChange(client)
            setModalOpen(false)
          }}
        />
      )}
    </div>
  )
}
