import { useMutation } from '@tanstack/react-query'
import { changePassword, type ChangePasswordRequest, type LoginResponse } from '../../../api'
import { useAuth } from '../../../shared/auth/AuthContext'

// `throwOnError: true` hace que axios rechace la promesa en 4xx, asi react-query
// dispara `onError` con un AxiosError que el caller puede parsear (Problem body).
async function performChangePassword(body: ChangePasswordRequest): Promise<LoginResponse | null> {
  const { data } = await changePassword({ body, throwOnError: true })
  // Sin sesión es la versión anterior del servidor (su 204, en un despliegue a medias o una vuelta
  // atrás): esa no revoca, así que la clave cambió y los tokens de antes siguen valiendo.
  return data?.token ? data : null
}

// gcTime 0: la caché de mutaciones guarda las variables, y acá son las dos contraseñas. Así no
// siguen en memoria una vez que la pantalla se desmonta. La sesión nueva se guarda acá, apenas
// vuelve, y no en la pantalla después del spinner mínimo: desde el cambio, la versión nueva del
// servidor ya no renueva el refresh de antes.
export function useChangePasswordMutation() {
  const { replaceSession } = useAuth()
  return useMutation({
    mutationFn: performChangePassword,
    onSuccess: (session) => {
      if (session) replaceSession(session.token, session.refreshToken ?? null, session.user)
    },
    gcTime: 0,
  })
}
