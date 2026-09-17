import { useMutation, useQueryClient } from '@tanstack/react-query'
import { updateClient, type ClientRequest, type ClientResponse } from '../../../api'
import { clientKeys } from '../queryKeys'
import type { CreateClientInput } from '../schemas/client.schema'

/**
 * Del formulario al cuerpo que pide el contrato.
 *
 * Los dos opcionales viajan como `null` y no como cadena vacía, por motivos
 * distintos. El teléfono, porque su patrón acepta nulo pero no la cadena vacía,
 * que volvería como un 400. El contacto, porque el backend la guarda como nulo
 * igual: mandarlo explícito evita depender de esa normalización. Es la misma
 * conversión que hace el alta al vuelo.
 *
 * Manda los cuatro campos editables y ninguno más. El estado de activo y la
 * fecha de creación son del servidor: no viajan en el cuerpo y el backend no los
 * toca en una edición.
 */
export function toClientUpdateRequest(values: CreateClientInput): ClientRequest {
  return {
    name: values.name.trim(),
    ruc: values.ruc.trim(),
    phone: values.phone?.trim() || null,
    contactName: values.contactName?.trim() || null,
  }
}

/**
 * Guardar la edición de un cliente.
 *
 * Invalida las búsquedas además del detalle: esa caché la comparten los combobox
 * del asistente de cotizaciones y del alta de servicios, y sin invalidarla
 * seguirían ofreciendo la razón social vieja hasta recargar la página.
 *
 * `throwOnError` no es opcional acá: sin él el 409 no llega como error y el
 * mapeo del duplicado a su campo no puede correr.
 */
export function useUpdateClient(id: number) {
  const queryClient = useQueryClient()
  return useMutation<ClientResponse, unknown, CreateClientInput>({
    mutationFn: async (values) => {
      const { data } = await updateClient({
        path: { id },
        body: toClientUpdateRequest(values),
        throwOnError: true,
      })
      if (!data) {
        throw new Error('Respuesta vacía del backend en PUT /clients/{id}')
      }
      return data
    },
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: clientKeys.detail(id) })
      void queryClient.invalidateQueries({ queryKey: clientKeys.searches() })
    },
  })
}
