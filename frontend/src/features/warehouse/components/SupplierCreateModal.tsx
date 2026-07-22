import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { Modal } from '../../../shared/ui/Modal'
import { Spinner } from '../../../shared/ui/Spinner'
import { TextField } from '../../../shared/ui/TextField'
import { PRIMARY_BUTTON, SECONDARY_BUTTON } from '../../../shared/ui/buttonStyles'
import { cn } from '../../../shared/utils/cn'
import { handleApiFormError } from '../../../shared/utils/handleApiFormError'
import {
  toSupplierRequest,
  useCreateWarehouseSupplier,
} from '../hooks/useCreateWarehouseSupplier'
import { supplierFormSchema, type SupplierFormInput } from '../schemas/supplier.schema'
import type { WarehouseSupplierResponse } from '../../../api'

interface SupplierCreateModalProps {
  /** Texto tipeado en el combobox, para precargar la razón social. */
  initialName?: string
  onClose: () => void
  onCreated: (supplier: WarehouseSupplierResponse) => void
}

const SUPPLIER_FIELDS = ['name', 'ruc', 'phone', 'contactName'] as const

/**
 * Alta de proveedor al vuelo desde el form de la entrada. Se monta solo cuando
 * está abierto, así el nombre se precarga con lo que el usuario venía buscando.
 *
 * El 409 de duplicado (WH-010) no se rutea a un campo: el mismo código sale
 * tanto por el nombre como por el RUC, así que no hay forma de saber cuál chocó.
 * El `detail` del backend, que sí lo dice, se muestra como notificación.
 */
export function SupplierCreateModal({
  initialName = '',
  onClose,
  onCreated,
}: SupplierCreateModalProps) {
  const createSupplier = useCreateWarehouseSupplier()
  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<SupplierFormInput>({
    resolver: zodResolver(supplierFormSchema),
    mode: 'onTouched',
    defaultValues: { name: initialName, ruc: '', phone: '', contactName: '' },
  })

  const onSubmit = handleSubmit(async (values) => {
    try {
      const supplier = await createSupplier.mutateAsync(toSupplierRequest(values))
      onCreated(supplier)
    } catch (error) {
      handleApiFormError(error, {
        setError,
        fallbackMessage: 'No se pudo crear el proveedor. Intenta de nuevo.',
        allowedFields: SUPPLIER_FIELDS,
      })
    }
  })

  return (
    <Modal isOpen onClose={onClose} title="Nuevo proveedor">
      <form onSubmit={onSubmit} noValidate className="space-y-4">
        <TextField
          id="supplier-name"
          label="Razón social"
          error={errors.name?.message}
          disabled={isSubmitting}
          register={register('name')}
        />
        <TextField
          id="supplier-ruc"
          label="RUC (opcional)"
          error={errors.ruc?.message}
          disabled={isSubmitting}
          register={register('ruc')}
        />
        <TextField
          id="supplier-contact"
          label="Persona de contacto (opcional)"
          error={errors.contactName?.message}
          disabled={isSubmitting}
          register={register('contactName')}
        />
        <TextField
          id="supplier-phone"
          label="Teléfono (opcional)"
          error={errors.phone?.message}
          disabled={isSubmitting}
          register={register('phone')}
        />
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" onClick={onClose} className={SECONDARY_BUTTON}>
            Cancelar
          </button>
          <button
            type="submit"
            disabled={isSubmitting}
            className={cn(PRIMARY_BUTTON, 'gap-2 disabled:cursor-not-allowed disabled:bg-blue-300')}
          >
            {isSubmitting ? (
              <>
                <Spinner size={16} label="Creando" />
                Creando…
              </>
            ) : (
              'Crear proveedor'
            )}
          </button>
        </div>
      </form>
    </Modal>
  )
}
