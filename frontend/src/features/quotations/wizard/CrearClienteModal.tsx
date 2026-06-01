import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Modal } from '../../../shared/ui/Modal'
import { Spinner } from '../../../shared/ui/Spinner'
import { TextField } from '../../../shared/ui/TextField'
import { handleApiFormError } from '../../../shared/utils/handleApiFormError'
import { useCreateClient } from '../../clients/hooks/useCreateClient'
import { createClientSchema, type CreateClientInput } from '../../clients/schemas/client.schema'
import type { ClientResponse } from '../../../api'

interface CrearClienteModalProps {
  /** Texto tipeado en el combobox, para precargar la razón social. */
  initialName?: string
  onClose: () => void
  onCreated: (client: ClientResponse) => void
}

const CLIENT_FIELDS = ['name', 'ruc', 'phone', 'contactName'] as const

const PRIMARY =
  'inline-flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white shadow-sm hover:bg-blue-700 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:ring-offset-2 disabled:cursor-not-allowed disabled:bg-blue-300'
const SECONDARY =
  'inline-flex items-center rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 hover:bg-slate-50 focus:outline-none focus:ring-2 focus:ring-blue-500'

/**
 * Modal de creación de cliente al vuelo. `POST /clients`; un 409 (RUC duplicado,
 * `CLI-001`) se rutea al campo RUC con `handleApiFormError`. Se monta solo cuando
 * está abierto (form fresco con la razón social precargada del texto buscado).
 */
export function CrearClienteModal({ initialName = '', onClose, onCreated }: CrearClienteModalProps) {
  const createClient = useCreateClient()
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<CreateClientInput>({
    resolver: zodResolver(createClientSchema),
    defaultValues: { name: initialName, ruc: '', phone: '', contactName: '' },
  })

  const onSubmit = handleSubmit(async (values) => {
    try {
      const client = await createClient.mutateAsync({
        name: values.name,
        ruc: values.ruc,
        phone: values.phone || null,
        contactName: values.contactName || null,
      })
      onCreated(client)
    } catch (error) {
      handleApiFormError(error, {
        setError,
        fallbackMessage: 'No se pudo crear el cliente. Intenta de nuevo.',
        codeFieldMap: { 'CLI-001': 'ruc' },
        allowedFields: CLIENT_FIELDS,
      })
    }
  })

  return (
    <Modal isOpen onClose={onClose} title="Nuevo cliente">
      <form onSubmit={onSubmit} noValidate className="space-y-4">
        <TextField
          id="cliente-name"
          label="Razón social"
          error={errors.name?.message}
          disabled={isSubmitting}
          register={register('name')}
        />
        <TextField
          id="cliente-ruc"
          label="RUC"
          error={errors.ruc?.message}
          disabled={isSubmitting}
          register={register('ruc')}
        />
        <TextField
          id="cliente-contact"
          label="Persona de contacto (opcional)"
          error={errors.contactName?.message}
          disabled={isSubmitting}
          register={register('contactName')}
        />
        <TextField
          id="cliente-phone"
          label="Teléfono (opcional)"
          error={errors.phone?.message}
          disabled={isSubmitting}
          register={register('phone')}
        />
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" onClick={onClose} className={SECONDARY}>
            Cancelar
          </button>
          <button type="submit" disabled={isSubmitting} className={PRIMARY}>
            {isSubmitting ? (
              <>
                <Spinner size={16} label="Creando" />
                Creando…
              </>
            ) : (
              'Crear cliente'
            )}
          </button>
        </div>
      </form>
    </Modal>
  )
}
