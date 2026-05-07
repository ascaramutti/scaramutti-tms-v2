import { useEffect, useState } from 'react'
import './App.css'

interface BackendHello {
  message: string
  timestamp: string
}

function App() {
  const [hello, setHello] = useState<BackendHello | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    fetch('http://localhost:8080/api/v1/hello')
      .then((r) => {
        if (!r.ok) throw new Error(`HTTP ${r.status}`)
        return r.json() as Promise<BackendHello>
      })
      .then(setHello)
      .catch((e: Error) => setError(e.message))
  }, [])

  return (
    <main className="app">
      <h1>Scaramutti TMS</h1>
      <p className="subtitle">Frontend scaffold — Vite + React + TypeScript</p>

      <section className="status">
        <h2>Backend connection</h2>
        {error && <p className="error">Error: {error}</p>}
        {hello && (
          <>
            <p className="success">{hello.message}</p>
            <p className="muted">Server time: {hello.timestamp}</p>
          </>
        )}
        {!hello && !error && <p className="muted">Connecting…</p>}
      </section>
    </main>
  )
}

export default App
