import { useMutation, useQueryClient } from '@tanstack/react-query'
import { updateWarehouseProduct, type WarehouseProductUpdateRequest } from '../../../api'
import { readEtag } from '../../../shared/utils/etag'
import { warehouseKeys } from '../queryKeys'
import type { WarehouseProductWithEtag } from './useWarehouseProduct'
import type { ProductFormInput } from '../schemas/product.schema'

interface UpdateWarehouseProductVariables {
  id: number
  /** ETag OPACO del header del GET (no el `updatedAt` del body), para el header `If-Match`. */
  ifMatch: string
  body: WarehouseProductUpdateRequest
}

/** Texto opcional del form al valor del contrato: vacío es "sin valor", no cadena vacía. */
function toNullableText(value: string | undefined): string | null {
  const trimmed = value?.trim()
  return trimmed ? trimmed : null
}

/**
 * Form → body del PUT.
 *
 * - NO incluye la unidad de medida: se fija al crear y es inmutable (el contrato
 *   ni siquiera la acepta en `WarehouseProductUpdateRequest`).
 * - `isActive` viaja con el valor ACTUAL del producto. Dar de baja se difirió
 *   junto con la vista de inactivos y la reactivación (sin eso, un producto
 *   desactivado desaparece de Existencias sin forma de recuperarlo desde la UI).
 * - Las características se editan como filas para poder agregarlas y quitarlas,
 *   pero el contrato las quiere como objeto.
 */
export function toProductUpdateRequest(
  input: ProductFormInput,
  currentIsActive: boolean,
): WarehouseProductUpdateRequest {
  return {
    name: input.name.trim(),
    categoryId: input.categoryId,
    brand: toNullableText(input.brand),
    partNumber: toNullableText(input.partNumber),
    attributes: Object.fromEntries(
      input.attributes.map((attribute) => [attribute.key.trim(), attribute.value.trim()]),
    ),
    minStock: input.minStock,
    observations: toNullableText(input.observations),
    isActive: currentIsActive,
  }
}

async function performUpdateWarehouseProduct({
  id,
  ifMatch,
  body,
}: UpdateWarehouseProductVariables): Promise<WarehouseProductWithEtag> {
  const { data, headers } = await updateWarehouseProduct({
    path: { id },
    headers: { 'If-Match': ifMatch },
    body,
    throwOnError: true,
  })
  if (!data) {
    throw new Error('Respuesta vacía del backend en PUT /warehouse/products/{id}')
  }
  // El PUT devuelve un ETag nuevo en el header: lo adjunto para refrescar la cache del
  // detalle con la versión vigente (así una segunda edición seguida no choca 412).
  return { ...data, _etag: readEtag(headers) }
}

/**
 * Edita el catálogo de un producto (`PUT /warehouse/products/{id}`) con optimistic
 * locking vía `If-Match`. Los errores llegan como AxiosError para que el modal
 * distinga el 412 (otro usuario editó primero) del 409 WH-010 (identidad duplicada).
 *
 * En éxito refresca el detalle con el ETag nuevo e invalida los listados y los
 * indicadores: `minStock` mueve el contador de productos con stock bajo. El kardex
 * NO se invalida: editar el catálogo no genera movimientos (el contrato lo dice).
 */
export function useUpdateWarehouseProduct() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: performUpdateWarehouseProduct,
    onSuccess: (updated) => {
      queryClient.setQueryData(warehouseKeys.productDetail(updated.id), updated)
      queryClient.invalidateQueries({ queryKey: warehouseKeys.productLists() })
      queryClient.invalidateQueries({ queryKey: warehouseKeys.stats() })
    },
  })
}
