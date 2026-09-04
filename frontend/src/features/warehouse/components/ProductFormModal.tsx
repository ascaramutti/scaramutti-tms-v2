import { Controller, useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { toast } from 'sonner'
import type { WarehouseProductResponse } from '../../../api'
import { Modal } from '../../../shared/ui/Modal'
import { SelectField } from '../../../shared/ui/SelectField'
import { TextField } from '../../../shared/ui/TextField'
import { Textarea } from '../../../shared/ui/Textarea'
import {
  getApiErrorMessage,
  isPreconditionFailedError,
} from '../../../shared/utils/getApiErrorMessage'
import { handleApiFormError } from '../../../shared/utils/handleApiFormError'
import { useWarehouseProductCategories } from '../hooks/useWarehouseProductCategories'
import { useWarehouseUnitsOfMeasure } from '../hooks/useWarehouseUnitsOfMeasure'
import {
  toProductCreateRequest,
  useCreateWarehouseProduct,
} from '../hooks/useCreateWarehouseProduct'
import {
  toProductUpdateRequest,
  useUpdateWarehouseProduct,
} from '../hooks/useUpdateWarehouseProduct'
import type { WarehouseProductWithEtag } from '../hooks/useWarehouseProduct'
import { productCreateSchema, type ProductCreateInput } from '../schemas/product.schema'
import { ProductAttributesField } from './ProductAttributesField'
import { ProductCategoryField } from './ProductCategoryField'
import { Alert } from '../../../shared/ui/Alert'

interface ProductEditModeProps {
  mode: 'edit'
  product: WarehouseProductWithEtag
  /** El detalle recarga para tomar la versión de quien haya editado antes (412). */
  onReloadRequested: () => void
}

interface ProductCreateModeProps {
  mode: 'create'
  /** Texto tipeado en el combobox que abrió el modal, para precargar el nombre. */
  initialName?: string
  onCreated: (product: WarehouseProductResponse) => void
}

type ProductFormModalProps = { isOpen: boolean; onClose: () => void } & (
  | ProductEditModeProps
  | ProductCreateModeProps
)

const FORM_FIELDS = [
  'name',
  'categoryId',
  'unitOfMeasureId',
  'brand',
  'partNumber',
  'minStock',
  'observations',
] as const

/** Defaults del alta: solo el nombre viene precargado desde el buscador. */
function toCreateDefaults(initialName: string): ProductCreateInput {
  return {
    name: initialName,
    categoryId: 0,
    unitOfMeasureId: 0,
    brand: '',
    partNumber: '',
    minStock: 0,
    attributes: [],
    observations: '',
  }
}

function toEditDefaults(product: WarehouseProductWithEtag): ProductCreateInput {
  return {
    name: product.name,
    categoryId: product.category.id,
    // La unidad viaja en el form aunque el PUT no la acepte: es el valor real del
    // producto y así el modal la muestra sin un estado paralelo.
    unitOfMeasureId: product.unitOfMeasure.id,
    brand: product.brand ?? '',
    partNumber: product.partNumber ?? '',
    minStock: product.minStock,
    attributes: Object.entries(product.attributes).map(([key, value]) => ({ key, value })),
    observations: product.observations ?? '',
  }
}

/**
 * Alta y edición del catálogo de un producto, en un solo modal: los campos, las
 * validaciones y los catálogos son los mismos, y separarlos en dos componentes
 * los dejaría desincronizados con el primer cambio.
 *
 * Lo único que cambia por modo es la unidad de medida: se elige al crear y es
 * inmutable al editar (el contrato ni siquiera la acepta en el PUT), y el
 * optimistic locking con `If-Match`, que solo existe en la edición.
 *
 * Se monta solo cuando está abierto para que los valores iniciales se calculen
 * al abrir: react-hook-form congela los defaults en el montaje, así que un modal
 * siempre montado mostraría datos viejos tras editar.
 */
export function ProductFormModal(props: ProductFormModalProps) {
  if (!props.isOpen) return null
  return <ProductForm {...props} />
}

function ProductForm(props: ProductFormModalProps) {
  const { onClose } = props
  const isCreate = props.mode === 'create'

  const categories = useWarehouseProductCategories()
  const units = useWarehouseUnitsOfMeasure()
  const createProduct = useCreateWarehouseProduct()
  const updateProduct = useUpdateWarehouseProduct()

  const {
    register,
    control,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<ProductCreateInput>({
    // Un solo schema para los dos modos. En edición la unidad no se puede cambiar,
    // pero el form la lleva con el valor real del producto, así que validarla nunca
    // bloquea; lo que el PUT no acepta lo descarta el mapper, no el schema.
    resolver: zodResolver(productCreateSchema),
    mode: 'onTouched',
    defaultValues: isCreate
      ? toCreateDefaults(props.initialName ?? '')
      : toEditDefaults(props.product),
  })

  // Sin el ETag del header no se puede armar el If-Match, y el PUT lo exige. Pasa
  // si el gateway no expone el header (falta `cors.exposed-headers=ETag`): mejor
  // decirlo antes de que el usuario escriba que fallar al guardar.
  const missingEtag = props.mode === 'edit' && !props.product._etag
  const versionConflict = isPreconditionFailedError(updateProduct.error)
  // Sin catálogo de unidades no hay `unitOfMeasureId` que enviar y el POST volvería 400.
  const blockedByUnits = isCreate && units.isError
  const isPending = createProduct.isPending || updateProduct.isPending

  function onSubmit(input: ProductCreateInput) {
    if (props.mode === 'create') {
      createProduct.mutate(toProductCreateRequest(input), {
        onSuccess: (created) => {
          toast.success(`Producto ${created.code} creado.`)
          props.onCreated(created)
          onClose()
        },
        onError: (error) => {
          handleApiFormError(error, {
            setError,
            fallbackMessage: 'No se pudo crear el producto. Intenta de nuevo.',
            allowedFields: FORM_FIELDS,
          })
        },
      })
      return
    }

    const { product } = props
    if (!product._etag) return
    updateProduct.mutate(
      {
        id: product.id,
        ifMatch: product._etag,
        body: toProductUpdateRequest(input, product.isActive),
      },
      {
        onSuccess: (updated) => {
          toast.success(`Producto ${updated.code} actualizado.`)
          onClose()
        },
        onError: (error) => {
          // El conflicto de versión no es un error de campo: se muestra en el
          // aviso de arriba, con la salida de recargar.
          if (isPreconditionFailedError(error)) return
          handleApiFormError(error, {
            setError,
            fallbackMessage: 'No se pudo guardar el producto. Intenta de nuevo.',
            allowedFields: FORM_FIELDS,
          })
        },
      },
    )
  }

  return (
    <Modal
      isOpen
      onClose={onClose}
      title={isCreate ? 'Nuevo producto' : 'Editar producto'}
      size="lg"
    >
      <form onSubmit={handleSubmit(onSubmit)} noValidate className="space-y-4">
        {versionConflict && props.mode === 'edit' && (
          <Alert variant="warning" role="alert" className="flex items-center justify-between gap-3 rounded-lg px-4 py-2.5 text-sm text-warning-fg">
            <span>
              {getApiErrorMessage(
                updateProduct.error,
                'Otro usuario editó este producto mientras lo modificabas.',
              )}{' '}
              Al recargar se pierde lo que escribiste.
            </span>
            <button
              type="button"
              onClick={props.onReloadRequested}
              className="shrink-0 font-medium text-warning-fg underline underline-offset-2 hover:no-underline"
            >
              Descartar y recargar
            </button>
          </Alert>
        )}

        {missingEtag && (
          <Alert as="p" bordered={false} role="alert" className="rounded-lg px-4 py-2.5 text-sm text-danger-fg">
            No se puede guardar: falta la versión del producto. Recarga la página e intenta de nuevo.
          </Alert>
        )}

        {blockedByUnits && (
          <Alert as="p" bordered={false} role="alert" className="rounded-lg px-4 py-2.5 text-sm text-danger-fg">
            No se pudieron cargar las unidades de medida, y el producto no se puede crear sin una.
          </Alert>
        )}

        <TextField
          id="product-name"
          label="Nombre"
          error={errors.name?.message}
          register={register('name')}
        />

        <Controller
          name="categoryId"
          control={control}
          render={({ field }) => (
            <ProductCategoryField
              categories={categories.data ?? []}
              categoriesLoading={categories.isLoading}
              categoriesError={categories.isError}
              value={field.value ?? null}
              onChange={field.onChange}
              onBlur={field.onBlur}
              error={errors.categoryId?.message}
            />
          )}
        />

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <TextField
            id="product-brand"
            label="Marca"
            error={errors.brand?.message}
            register={register('brand')}
          />
          <TextField
            id="product-part-number"
            label="Número de parte"
            error={errors.partNumber?.message}
            register={register('partNumber')}
          />
        </div>

        <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
          <TextField
            id="product-min-stock"
            label="Stock mínimo"
            type="number"
            min={0}
            step="any"
            error={errors.minStock?.message}
            register={register('minStock', { valueAsNumber: true })}
          />
          {isCreate ? (
            <div>
              <SelectField
                id="product-unit-of-measure"
                label="Unidad de medida"
                name="unitOfMeasureId"
                control={control}
                options={(units.data ?? []).map((unit) => ({
                  value: unit.id,
                  label: `${unit.code} · ${unit.name}`,
                }))}
                placeholder="Seleccionar…"
                disabled={units.isLoading}
                error={errors.unitOfMeasureId?.message}
              />
              <p className="mt-1 text-xs text-fg-muted">
                La unidad se fija al crear el producto y después no se puede cambiar.
              </p>
            </div>
          ) : (
            <div>
              <p className="mb-1.5 block text-sm font-medium text-fg-body">Unidad de medida</p>
              <p className="rounded-lg border border-border bg-surface-subtle px-3.5 py-2.5 text-sm text-fg-muted">
                {props.mode === 'edit' &&
                  `${props.product.unitOfMeasure.code} · ${props.product.unitOfMeasure.name}`}
              </p>
              <p className="mt-1 text-xs text-fg-muted">
                La unidad se fija al crear el producto y no se puede cambiar.
              </p>
            </div>
          )}
        </div>

        <ProductAttributesField control={control} register={register} errors={errors.attributes} />

        <Textarea
          id="product-observations"
          label="Observaciones"
          rows={3}
          error={errors.observations?.message}
          register={register('observations')}
        />

        <div className="flex justify-end gap-3 border-t border-border pt-4">
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg border border-border-strong bg-surface px-4 py-2.5 text-sm font-medium text-fg-body hover:bg-surface-subtle focus:outline-none focus-visible:ring-2 focus-visible:ring-focus"
          >
            Cancelar
          </button>
          <button
            type="submit"
            disabled={isSubmitting || isPending || missingEtag || blockedByUnits}
            className="rounded-lg bg-accent px-4 py-2.5 text-sm font-medium text-on-solid shadow-sm hover:bg-accent-hover focus:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-60"
          >
            {isPending
              ? isCreate
                ? 'Creando…'
                : 'Guardando…'
              : isCreate
                ? 'Crear producto'
                : 'Guardar cambios'}
          </button>
        </div>
      </form>
    </Modal>
  )
}
