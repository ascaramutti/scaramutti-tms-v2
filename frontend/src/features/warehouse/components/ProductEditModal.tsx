import { Controller, useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { toast } from 'sonner'
import { Modal } from '../../../shared/ui/Modal'
import { TextField } from '../../../shared/ui/TextField'
import { Textarea } from '../../../shared/ui/Textarea'
import { getApiErrorMessage, isPreconditionFailedError } from '../../../shared/utils/getApiErrorMessage'
import { handleApiFormError } from '../../../shared/utils/handleApiFormError'
import { useWarehouseProductCategories } from '../hooks/useWarehouseProductCategories'
import {
  toProductUpdateRequest,
  useUpdateWarehouseProduct,
} from '../hooks/useUpdateWarehouseProduct'
import type { WarehouseProductWithEtag } from '../hooks/useWarehouseProduct'
import { productFormSchema, type ProductFormInput } from '../schemas/product.schema'
import { ProductAttributesField } from './ProductAttributesField'
import { ProductCategoryField } from './ProductCategoryField'

interface ProductEditModalProps {
  isOpen: boolean
  onClose: () => void
  product: WarehouseProductWithEtag
  /** El detalle recarga para tomar la versión de quien haya editado antes (412). */
  onReloadRequested: () => void
}

const FORM_FIELDS = ['name', 'categoryId', 'brand', 'partNumber', 'minStock', 'observations'] as const

function toFormDefaults(product: WarehouseProductWithEtag): ProductFormInput {
  return {
    name: product.name,
    categoryId: product.category.id,
    brand: product.brand ?? '',
    partNumber: product.partNumber ?? '',
    minStock: product.minStock,
    attributes: Object.entries(product.attributes).map(([key, value]) => ({ key, value })),
    observations: product.observations ?? '',
  }
}

/**
 * Edición del catálogo de un producto. Se monta solo cuando está abierto para que
 * los valores iniciales se calculen al abrir: react-hook-form congela los defaults
 * en el montaje, así que un modal siempre montado mostraría datos viejos tras editar.
 */
export function ProductEditModal(props: ProductEditModalProps) {
  if (!props.isOpen) return null
  return <ProductEditForm {...props} />
}

function ProductEditForm({ onClose, product, onReloadRequested }: ProductEditModalProps) {
  const categories = useWarehouseProductCategories()
  const updateProduct = useUpdateWarehouseProduct()

  const {
    register,
    control,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<ProductFormInput>({
    resolver: zodResolver(productFormSchema),
    mode: 'onTouched',
    defaultValues: toFormDefaults(product),
  })

  // Sin el ETag del header no se puede armar el If-Match, y el PUT lo exige. Pasa
  // si el gateway no expone el header (falta `cors.exposed-headers=ETag`): mejor
  // decirlo antes de que el usuario escriba que fallar al guardar.
  const missingEtag = !product._etag
  const versionConflict = isPreconditionFailedError(updateProduct.error)

  function onSubmit(input: ProductFormInput) {
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
    <Modal isOpen onClose={onClose} title="Editar producto" size="lg">
      <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
        {versionConflict && (
          <div
            role="alert"
            className="flex items-center justify-between gap-3 rounded-lg border border-amber-200 bg-amber-50 px-4 py-2.5 text-sm text-amber-800"
          >
            <span>
              {getApiErrorMessage(
                updateProduct.error,
                'Otro usuario editó este producto mientras lo modificabas.',
              )}{' '}
              Al recargar se pierde lo que escribiste.
            </span>
            <button
              type="button"
              onClick={onReloadRequested}
              className="shrink-0 font-medium text-amber-900 underline underline-offset-2 hover:no-underline"
            >
              Descartar y recargar
            </button>
          </div>
        )}

        {missingEtag && (
          <p role="alert" className="rounded-lg bg-red-50 px-4 py-2.5 text-sm text-red-700">
            No se puede guardar: falta la versión del producto. Recarga la página e intenta de nuevo.
          </p>
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
          <div>
            <p className="mb-1.5 block text-sm font-medium text-slate-700">Unidad de medida</p>
            <p className="rounded-lg border border-slate-200 bg-slate-50 px-3.5 py-2.5 text-sm text-slate-500">
              {product.unitOfMeasure.code} · {product.unitOfMeasure.name}
            </p>
            <p className="mt-1 text-xs text-slate-500">
              La unidad se fija al crear el producto y no se puede cambiar.
            </p>
          </div>
        </div>

        <ProductAttributesField control={control} register={register} errors={errors.attributes} />

        <Textarea
          id="product-observations"
          label="Observaciones"
          rows={3}
          error={errors.observations?.message}
          register={register('observations')}
        />

        <div className="flex justify-end gap-3 border-t border-slate-200 pt-4">
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg border border-slate-300 bg-white px-4 py-2.5 text-sm font-medium text-slate-700 hover:bg-slate-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
          >
            Cancelar
          </button>
          <button
            type="submit"
            disabled={isSubmitting || updateProduct.isPending || missingEtag}
            className="rounded-lg bg-blue-600 px-4 py-2.5 text-sm font-medium text-white shadow-sm hover:bg-blue-700 focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500 focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-60"
          >
            {updateProduct.isPending ? 'Guardando…' : 'Guardar cambios'}
          </button>
        </div>
      </form>
    </Modal>
  )
}
