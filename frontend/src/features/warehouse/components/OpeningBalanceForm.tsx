import { useState } from 'react'
import { Link } from 'react-router-dom'
import { isAxiosError } from 'axios'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import type {
  Problem,
  WarehouseOpeningBalanceResponse,
  WarehouseProductSummary,
} from '../../../api'
import { TextField } from '../../../shared/ui/TextField'
import { Textarea } from '../../../shared/ui/Textarea'
import { handleApiFormError } from '../../../shared/utils/handleApiFormError'
import {
  toOpeningBalanceRequest,
  useCreateWarehouseOpeningBalance,
} from '../hooks/useCreateWarehouseOpeningBalance'
import {
  DEFAULT_OPENING_BALANCE_VALUES,
  OPENING_BALANCE_MAX_QUANTITY,
  openingBalanceFormSchema,
  type OpeningBalanceFormInput,
} from '../schemas/opening-balance.schema'
import { WarehouseProductField } from './WarehouseProductField'

interface OpeningBalanceFormProps {
  onCreated: (openingBalance: WarehouseOpeningBalanceResponse) => void
}

/** Campos que aceptan un error de campo del backend. */
const FORM_FIELDS = ['productId', 'quantity', 'observations'] as const

/** `Problem.code` del error, si el backend lo mandó (RFC 7807). */
function apiErrorCode(error: unknown): string | null | undefined {
  if (!isAxiosError(error)) return undefined
  return (error.response?.data as Problem | undefined)?.code
}

/**
 * Registro del corte inicial de un producto: con cuánto arranca en el sistema.
 *
 * Vive inline en la pantalla (no en un modal) porque la carga es en tanda: el
 * operador recorre su inventario producto por producto y reabrir un modal cada vez
 * es fricción pura. Al guardar, el form se limpia y queda listo para el siguiente.
 *
 * El combobox permite dar de alta el producto al vuelo (igual que la entrada): el
 * caso típico del corte inicial es justamente incorporar algo que ya existía
 * físicamente en el depósito y nunca se cargó al sistema.
 *
 * Registrar es solo de `admin` (la pantalla no monta este form para el resto), y
 * el 403 del backend es la red de seguridad real.
 */
export function OpeningBalanceForm({ onCreated }: OpeningBalanceFormProps) {
  const createOpeningBalance = useCreateWarehouseOpeningBalance()
  const [selectedProduct, setSelectedProduct] = useState<WarehouseProductSummary | null>(
    null,
  )

  const {
    register,
    handleSubmit,
    reset,
    setValue,
    setError,
    trigger,
    formState: { errors, isSubmitting },
  } = useForm<OpeningBalanceFormInput>({
    resolver: zodResolver(openingBalanceFormSchema),
    mode: 'onTouched',
    defaultValues: DEFAULT_OPENING_BALANCE_VALUES,
  })

  // El producto del error queda pinneado aparte: si el operador ya eligió otro, el
  // link seguiría apuntando al que causó el conflicto.
  const [productWithMovements, setProductWithMovements] =
    useState<WarehouseProductSummary | null>(null)

  const isPending = isSubmitting || createOpeningBalance.isPending

  function applyProduct(product: WarehouseProductSummary | null) {
    setSelectedProduct(product)
    setProductWithMovements(null)
    setValue('productId', product?.id ?? 0, { shouldValidate: true, shouldTouch: true })
  }

  const onSubmit = handleSubmit((values) => {
    createOpeningBalance.mutate(toOpeningBalanceRequest(values), {
      onSuccess: (openingBalance) => {
        reset(DEFAULT_OPENING_BALANCE_VALUES)
        setSelectedProduct(null)
        setProductWithMovements(null)
        onCreated(openingBalance)
      },
      onError: (error) => {
        // WH-011 = el producto ya tiene movimientos. El detalle del backend lo dice
        // pero no muestra cuáles: el link al kardex deja verificarlo en un click.
        setProductWithMovements(apiErrorCode(error) === 'WH-011' ? selectedProduct : null)
        handleApiFormError(error, {
          setError,
          fallbackMessage: 'No se pudo registrar el corte inicial. Intenta de nuevo.',
          // Los tres errores de negocio se corrigen en el mismo lugar: eligiendo otro
          // producto. WH-009 ya tiene apertura, WH-011 ya tiene movimientos, WH-004
          // no existe o quedó inactivo entre la búsqueda y el envío.
          codeFieldMap: {
            'WH-009': 'productId',
            'WH-011': 'productId',
            'WH-004': 'productId',
          },
          allowedFields: FORM_FIELDS,
        })
      },
    })
  })

  return (
    <form onSubmit={onSubmit} noValidate className="space-y-4">
      {/* El envío entero se congela mientras la mutación está en vuelo: cambiar el
          producto a mitad de camino desalinearía lo que se ve con lo que se envió. */}
      <fieldset disabled={isPending} className="space-y-4 border-0 p-0">
        {/* El producto necesita sitio para buscar; la cantidad es un campo corto. */}
        <div className="grid grid-cols-1 gap-4 sm:grid-cols-3 sm:items-start">
          <div className="sm:col-span-2">
            <WarehouseProductField
              id="opening-balance-product"
              label="Producto"
              selected={selectedProduct}
              onSelectedChange={applyProduct}
              onBlur={() => trigger('productId')}
              error={errors.productId?.message}
              // El caso de uso típico es incorporar un producto que ya existía
              // físicamente en el depósito y nunca se cargó al sistema: darlo de
              // alta sin salir del form es parte del flujo, no una excepción.
              allowCreate
            />
            {productWithMovements && (
              // Va en su propio `role="alert"`: el error del combobox se anuncia sin
              // el link, y sin esto el lector de pantalla nunca se entera de la salida.
              <p role="alert" className="mt-1 text-xs text-fg-muted">
                <Link
                  to={`/cotizaciones/almacen/productos/${productWithMovements.id}`}
                  className="font-medium text-accent-hover underline underline-offset-2 hover:no-underline"
                >
                  Ver el kardex de {productWithMovements.name}
                </Link>
              </p>
            )}
          </div>
          <TextField
            id="opening-balance-quantity"
            label="Cantidad inicial"
            type="number"
            step="any"
            min={0}
            max={OPENING_BALANCE_MAX_QUANTITY}
            helperText={
              selectedProduct
                ? `Unidad: ${selectedProduct.unitCode}. Puede ser 0: deja constancia de que se contó y no había existencias.`
                : 'Puede ser 0: deja constancia de que se contó el producto y no había existencias.'
            }
            error={errors.quantity?.message}
            register={register('quantity', { valueAsNumber: true })}
          />
        </div>

        <Textarea
          id="opening-balance-observations"
          label="Observaciones (opcional)"
          rows={3}
          error={errors.observations?.message}
          register={register('observations')}
        />

        <div className="flex justify-end">
          <button
            type="submit"
            disabled={isPending}
            className="rounded-lg bg-accent px-4 py-2.5 text-sm font-medium text-on-solid shadow-sm hover:bg-accent-hover focus:outline-none focus-visible:ring-2 focus-visible:ring-focus focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-60"
          >
            {isPending ? 'Registrando…' : 'Registrar corte inicial'}
          </button>
        </div>
      </fieldset>
    </form>
  )
}
