import { useMutation, useQueryClient } from '@tanstack/react-query'
import { createCargoType, type CargoTypeRequest, type CargoTypeResponse } from '../../../api'
import { cargoTypeKeys } from '../queryKeys'

async function performCreateCargoType(body: CargoTypeRequest): Promise<CargoTypeResponse> {
  const { data } = await createCargoType({ body, throwOnError: true })
  if (!data) {
    throw new Error('Respuesta vacía del backend en POST /cargo-types')
  }
  return data
}

/**
 * Crea un tipo de carga al vuelo (desde el wizard). `throwOnError: true` para que
 * el 409 (CGT-001, nombre duplicado) llegue como AxiosError y el form lo mapee con
 * `handleApiFormError`. Invalida las búsquedas para que el nuevo aparezca.
 */
export function useCreateCargoType() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: performCreateCargoType,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: cargoTypeKeys.searches() })
    },
  })
}
