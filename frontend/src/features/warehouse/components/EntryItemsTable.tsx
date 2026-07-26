import { useState } from 'react'
import { Plus, Trash2 } from 'lucide-react'
import { useFieldArray, useFormContext, useWatch } from 'react-hook-form'
import type { WarehouseProductSummary } from '../../../api'
import { cn } from '../../../shared/utils/cn'
import { formatCurrency } from '../../../shared/utils/formatters'
import {
  EMPTY_INVOICE_ITEM,
  INVOICE_ITEM_MAX_QUANTITY,
  type PurchaseInvoiceFormInput,
} from '../schemas/purchase-invoice.schema'
import { EntryProductField } from './EntryProductField'

interface EntryItemsTableProps {
  /** Código de moneda elegido en la cabecera, para formatear los importes. */
  currencyCode?: string
  /**
   * Producto ya elegido por fila al montar, alineado con `defaultValues.items`
   * (la edición siembra el prefill). En modo alta llega `undefined` → todo vacío.
   */
  initialSelectedProducts?: Array<WarehouseProductSummary | null>
}

const inputClasses =
  'w-full rounded-lg border bg-white px-3 py-2.5 text-sm text-slate-900 placeholder:text-slate-400 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-blue-500'

function lineTotal(quantity: number | undefined, unitPrice: number | undefined): number | null {
  if (typeof quantity !== 'number' || !Number.isFinite(quantity)) return null
  if (typeof unitPrice !== 'number' || !Number.isFinite(unitPrice)) return null
  return quantity * unitPrice
}

/**
 * Ítems de la factura: una fila por producto, con su cantidad, precio unitario y
 * subtotal, más el total de la factura al pie.
 *
 * NO usa `DataTable`: esa tabla envuelve el contenido en un contenedor con
 * scroll horizontal, que recorta el dropdown del combobox de producto.
 *
 * Los encabezados de la grilla son visuales y no etiquetan los campos, así que
 * cada input lleva su propio rótulo accesible con el número de fila (mismo
 * patrón que las características del producto).
 */
export function EntryItemsTable({ currencyCode, initialSelectedProducts }: EntryItemsTableProps) {
  const {
    control,
    register,
    formState: { errors },
  } = useFormContext<PurchaseInvoiceFormInput>()
  const { fields, append, remove } = useFieldArray({ control, name: 'items' })
  // El producto elegido se indexa por el id de fila de react-hook-form, no por
  // posición: quitar una fila del medio correría los índices y la fila siguiente
  // mostraría el producto de su vecina. Se siembra una vez con el prefill: los
  // `field.id` del primer render reflejan `defaultValues.items` en orden.
  const [selectedByRow, setSelectedByRow] = useState<
    Record<string, WarehouseProductSummary | null>
  >(() =>
    Object.fromEntries(fields.map((field, index) => [field.id, initialSelectedProducts?.[index] ?? null])),
  )

  const items = useWatch({ control, name: 'items' })
  const total = (items ?? []).reduce(
    (accumulator, item) => accumulator + (lineTotal(item?.quantity, item?.unitPrice) ?? 0),
    0,
  )

  function handleRemove(index: number, rowId: string) {
    remove(index)
    setSelectedByRow((previous) => {
      const next = { ...previous }
      delete next[rowId]
      return next
    })
  }

  /** Producto ya cargado en una fila anterior: se avisa, no se bloquea. */
  function isRepeated(index: number): boolean {
    const productId = items?.[index]?.productId
    if (!productId) return false
    return (items ?? []).slice(0, index).some((item) => item?.productId === productId)
  }

  return (
    <section className="space-y-3">
      <h2 className="text-sm font-semibold text-slate-900">Ítems</h2>

      {/* Encabezados: visuales, ocultos en móvil donde las filas se apilan. */}
      <div className="hidden gap-3 text-xs font-semibold uppercase tracking-wide text-slate-500 sm:grid sm:grid-cols-[minmax(0,1fr)_9rem_8rem_8rem_2.5rem]">
        <span>Producto</span>
        <span>Cantidad</span>
        <span>Precio unit.</span>
        <span>Subtotal</span>
        <span className="sr-only">Acciones</span>
      </div>

      <ul className="space-y-3">
        {fields.map((field, index) => {
          const itemErrors = errors.items?.[index]
          const selected = selectedByRow[field.id] ?? null
          const subtotal = lineTotal(items?.[index]?.quantity, items?.[index]?.unitPrice)
          return (
            <li
              key={field.id}
              className="grid gap-3 rounded-lg border border-slate-200 p-3 sm:grid-cols-[minmax(0,1fr)_9rem_8rem_8rem_2.5rem] sm:items-start sm:rounded-none sm:border-0 sm:p-0"
            >
              <div>
                <EntryProductField
                  index={index}
                  selected={selected}
                  onSelectedChange={(product) =>
                    setSelectedByRow((previous) => ({ ...previous, [field.id]: product }))
                  }
                  error={itemErrors?.productId?.message}
                />
                {isRepeated(index) && (
                  <p role="status" className="mt-1 text-xs text-amber-700">
                    Ya cargaste este producto en otra fila. Las cantidades se sumarán.
                  </p>
                )}
              </div>

              <div>
                <div className="flex items-center gap-1.5">
                  <input
                    {...register(`items.${index}.quantity`, { valueAsNumber: true })}
                    type="number"
                    // Sin `min`: el contrato exige cantidad > 0 y el atributo HTML solo
                    // sabe de mínimos inclusivos, así que `min={0}` daría por bueno el 0.
                    // El corte lo pone el schema.
                    // `step="any"`: hay unidades decimales (litros, galones) y un paso
                    // numérico fijo hace que el browser marque valores válidos como
                    // inválidos por redondeo.
                    step="any"
                    max={INVOICE_ITEM_MAX_QUANTITY}
                    aria-label={`Cantidad del ítem ${index + 1}`}
                    aria-invalid={!!itemErrors?.quantity}
                    className={cn(
                      inputClasses,
                      // Ancho fijo (no `flex-1`): así la unidad queda pegada al número
                      // y no estirada hasta el borde de la columna, separada de Precio.
                      'w-20 tabular-nums',
                      itemErrors?.quantity ? 'border-red-300' : 'border-slate-300',
                    )}
                  />
                  {/* La unidad va al costado del número: "1000 GAL" se lee de un vistazo. */}
                  {selected && (
                    <span className="whitespace-nowrap text-xs text-slate-500">
                      {selected.unitCode}
                    </span>
                  )}
                </div>
                {itemErrors?.quantity && (
                  <p role="alert" className="mt-1 text-xs text-red-600">
                    {itemErrors.quantity.message}
                  </p>
                )}
              </div>

              <div>
                <input
                  {...register(`items.${index}.unitPrice`, { valueAsNumber: true })}
                  type="number"
                  min={0}
                  step="any"
                  aria-label={`Precio unitario del ítem ${index + 1}`}
                  aria-invalid={!!itemErrors?.unitPrice}
                  className={cn(
                    inputClasses,
                    'tabular-nums',
                    itemErrors?.unitPrice ? 'border-red-300' : 'border-slate-300',
                  )}
                />
                {itemErrors?.unitPrice && (
                  <p role="alert" className="mt-1 text-xs text-red-600">
                    {itemErrors.unitPrice.message}
                  </p>
                )}
              </div>

              <p className="py-2 text-sm tabular-nums text-slate-700">
                {subtotal === null ? '—' : formatCurrency(subtotal, currencyCode ?? 'PEN')}
              </p>

              {/* La primera fila no se puede quitar: el contrato exige al menos un ítem. */}
              {fields.length > 1 && (
                <button
                  type="button"
                  onClick={() => handleRemove(index, field.id)}
                  aria-label={`Quitar el ítem ${index + 1}`}
                  className="justify-self-end rounded-lg p-2 text-slate-400 hover:bg-slate-100 hover:text-slate-600 focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
                >
                  <Trash2 className="h-4 w-4" aria-hidden="true" />
                </button>
              )}
            </li>
          )
        })}
      </ul>

      {/* Error del array completo (por ejemplo, el máximo de ítems del contrato).
          Zod lo deja en `root` o en el nodo mismo según la regla que falle. */}
      {(errors.items?.root?.message ?? errors.items?.message) && (
        <p role="alert" className="text-sm text-red-600">
          {errors.items?.root?.message ?? errors.items?.message}
        </p>
      )}

      <div className="flex flex-wrap items-center justify-between gap-3 border-t border-slate-200 pt-3">
        <button
          type="button"
          onClick={() => append(EMPTY_INVOICE_ITEM)}
          className="inline-flex items-center gap-1.5 rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-sm font-medium text-slate-700 hover:bg-slate-50 focus:outline-none focus-visible:ring-2 focus-visible:ring-blue-500"
        >
          <Plus className="h-4 w-4" aria-hidden="true" />
          Agregar ítem
        </button>
        {/* El total cambia al tipear cantidades: se anuncia para quien no lo ve. */}
        <p aria-live="polite" className="text-sm text-slate-600">
          Total de la factura:{' '}
          <span className="font-semibold tabular-nums text-slate-900">
            {formatCurrency(total, currencyCode ?? 'PEN')}
          </span>
        </p>
      </div>
    </section>
  )
}
