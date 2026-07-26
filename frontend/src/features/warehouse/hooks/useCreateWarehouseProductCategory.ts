import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  createWarehouseProductCategory,
  type WarehouseProductCategoryResponse,
} from '../../../api'
import { warehouseKeys } from '../queryKeys'

async function performCreateWarehouseProductCategory(
  name: string,
): Promise<WarehouseProductCategoryResponse> {
  const { data } = await createWarehouseProductCategory({
    body: { name: name.trim() },
    throwOnError: true,
  })
  if (!data) {
    throw new Error('Respuesta vacía del backend en POST /warehouse/product-categories')
  }
  return data
}

/**
 * Crea una categoría al vuelo desde el form del producto (estándar del proyecto:
 * toda selección de catálogo puede crear sin salir de la pantalla). Un nombre
 * repetido vuelve 409 WH-010; el aviso sale como notificación, no anclado a un
 * campo, porque el nombre lo escribe el buscador del combobox y no un input del form.
 */
export function useCreateWarehouseProductCategory() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: performCreateWarehouseProductCategory,
    onSuccess: (created) => {
      // Se agrega a la lista en cache además de invalidar: quien la acaba de crear
      // la selecciona en el acto, y esperar el refetch dejaría el campo en blanco
      // con la categoría ya elegida por debajo.
      queryClient.setQueryData<WarehouseProductCategoryResponse[]>(
        warehouseKeys.productCategories(),
        // Ordenada por nombre, como la devuelve el backend: si no, la recién creada
        // aparece al final hasta que llega el refetch.
        (previous) =>
          [...(previous ?? []), created].sort((a, b) => a.name.localeCompare(b.name, 'es')),
      )
      queryClient.invalidateQueries({ queryKey: warehouseKeys.productCategories() })
    },
  })
}
