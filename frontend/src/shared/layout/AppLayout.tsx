import { Outlet } from 'react-router-dom'
import { Sidebar } from './Sidebar'

/**
 * Layout principal para rutas autenticadas. Envuelve el contenido con un
 * sidebar fijo a la izquierda. El sidebar es desktop-first; en mobile queda
 * visible (se evaluará agregar toggle hamburguesa cuando aparezca el caso real).
 */
export function AppLayout() {
  return (
    <div className="min-h-screen flex bg-slate-50">
      <Sidebar />
      <main className="flex-1 overflow-x-hidden">
        <Outlet />
      </main>
    </div>
  )
}
