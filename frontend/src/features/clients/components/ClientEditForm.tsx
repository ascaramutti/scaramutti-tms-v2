import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import type { ClientResponse } from '../../../api'
import { Alert } from '../../../shared/ui/Alert'
import { Button } from '../../../shared/ui/Button'
import { Card } from '../../../shared/ui/Card'
import { TextField } from '../../../shared/ui/TextField'
import { handleApiFormError } from '../../../shared/utils/handleApiFormError'
import { isNotFoundError } from '../../../shared/utils/getApiErrorMessage'
import { createClientSchema, type CreateClientInput } from '../schemas/client.schema'
import { useUpdateClient } from '../hooks/useUpdateClient'

interface ClientEditFormProps {
  client: ClientResponse
  onSaved: (saved: ClientResponse) => void
  /** El cliente dejó de existir mientras se editaba. */
  onGone: () => void
  onCancel: () => void
}

/** Los campos que el backend puede nombrar en un 400; cualquier otro se ignora. */
const CLIENT_FIELDS = ['name', 'ruc', 'phone', 'contactName'] as const

export function ClientEditForm({ client, onSaved, onGone, onCancel }: ClientEditFormProps) {
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isDirty, isSubmitting },
  } = useForm<CreateClientInput>({
    resolver: zodResolver(createClientSchema),
    // Los dos opcionales entran como cadena vacía y no como nulo: el estado de
    // "sin cambios" se calcula contra estos valores, y con nulo acá un campo
    // vacío contaría como cambio desde el primer dibujo y el botón de guardar
    // nacería habilitado.
    defaultValues: {
      name: client.name,
      ruc: client.ruc,
      phone: client.phone ?? '',
      contactName: client.contactName ?? '',
    },
  })

  const updateClient = useUpdateClient(client.id)

  const onSubmit = handleSubmit(async (values) => {
    try {
      onSaved(await updateClient.mutateAsync(values))
    } catch (error) {
      // El "ya no existe" se atiende ANTES del mapeo genérico: si cayera ahí,
      // saldría como un aviso más y dejaría al usuario en un formulario que ya
      // no puede guardar nada.
      if (isNotFoundError(error)) {
        onGone()
        return
      }
      handleApiFormError(error, {
        setError,
        fallbackMessage: 'No se pudieron guardar los cambios. Intenta de nuevo.',
        // El alta solo mapea el RUC duplicado; la edición mapea los dos, así que
        // cada mensaje aparece bajo el campo que hay que corregir.
        codeFieldMap: { 'CLI-001': 'ruc', 'CLI-002': 'name' },
        allowedFields: CLIENT_FIELDS,
      })
    }
  })

  return (
    <Card as="form" onSubmit={onSubmit}>
      {/* Permanente y sin botón de cerrar: se lee al abrir, antes de tocar nada.
          Sin `role` a propósito: está desde que se abre y nada cambió, así que
          anunciarlo interrumpiría en vez de acompañar. */}
      <Alert variant="info" role={undefined} className="mb-6 px-4 py-3 text-sm text-fg-body">
        Si cambias la razón social o el RUC, las cotizaciones y los servicios ya registrados de
        este cliente se mostrarán e imprimirán con los datos nuevos.
      </Alert>

      {/* RUC y teléfono comparten fila porque son los dos únicos de largo fijo y
          corto; la razón social y el contacto admiten 200 y 100 caracteres y al
          lado del RUC dejarían media fila vacía. Por eso el orden de los dos
          opcionales no es el del alta al vuelo, que los apila de a uno. */}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <div className="sm:col-span-2">
          <TextField
            id="cliente-name"
            label="Razón social"
            error={errors.name?.message}
            disabled={isSubmitting}
            register={register('name')}
          />
        </div>
        <TextField id="cliente-ruc" label="RUC" error={errors.ruc?.message} disabled={isSubmitting}
            register={register('ruc')} />
        <TextField
          id="cliente-phone"
          label="Teléfono (opcional)"
          error={errors.phone?.message}
          disabled={isSubmitting}
            register={register('phone')}
        />
        <div className="sm:col-span-2">
          <TextField
            id="cliente-contact"
            label="Persona de contacto (opcional)"
            error={errors.contactName?.message}
            disabled={isSubmitting}
            register={register('contactName')}
          />
        </div>
      </div>

      <div className="mt-6 flex items-center justify-end gap-3">
        {/* Un botón deshabilitado no recibe foco y el lector de pantalla lo
            saltea, así que el motivo se dice en texto visible al lado. */}
        {!isDirty && <p className="text-sm text-fg-muted">Cambia algún dato para guardar.</p>}
        <Button type="button" variant="secondary" onClick={onCancel}>
          Cancelar
        </Button>
        <Button
          type="submit"
          disabled={!isDirty || isSubmitting}
          className="disabled:cursor-not-allowed disabled:bg-accent-disabled"
        >
          {isSubmitting ? 'Guardando…' : 'Guardar'}
        </Button>
      </div>
    </Card>
  )
}
