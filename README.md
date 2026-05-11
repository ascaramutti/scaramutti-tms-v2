# Scaramutti TMS

Sistema de gestión para Transportes Scaramutti S.A.C. Cubre la operación de servicios de transporte y la emisión de cotizaciones comerciales.

## Stack

- **Base de datos:** PostgreSQL 16
- **Backend:** Quarkus 3.15 (Java 17, Maven)
- **Frontend:** React 18 + Vite + TypeScript
- **Contrato API:** OpenAPI 3.1 (`api/openapi.yaml`)

## Estructura del repositorio

```
scaramutti-tms-v2/
├── api/                ← Contrato OpenAPI (fuente de verdad)
├── backend/            ← Servicio Quarkus
├── frontend/           ← App React + Vite
├── db/                 ← baseline.sql + scripts de upgrade
├── docker-compose.yml  ← BD local para desarrollo
└── README.md
```

## Requisitos

- Docker (para la BD)
- Java 17+ y Maven 3.9+
- Node 20+ y npm

## Levantar el entorno local

### 1. Base de datos

```bash
docker compose up -d
```

Levanta PostgreSQL 16 en `localhost:5432`. La primera vez aplica `db/baseline.sql` automáticamente para crear los schemas `public` y `cotizaciones`.

Credenciales locales (definidas en `docker-compose.yml`):
- DB: `scaramutti_tms_dev`
- User: `scaramutti_user`
- Password: `dev_local_only`

### 2. Backend

```bash
cd backend
mvn quarkus:dev
```

Levanta Quarkus en modo desarrollo (hot reload) en `http://localhost:8080`.

Endpoints útiles para verificar:
- `http://localhost:8080/api/v1/q/health` — estado del servicio + conexión a BD
- `http://localhost:8080/swagger` — Swagger UI
- `http://localhost:8080/openapi` — spec OpenAPI runtime (módulos implementados)

Usuarios seed disponibles en perfil `dev` (creados por `DevDataSeeder`):

| Username | Password | Rol | Estado |
|---|---|---|---|
| `admin` | `Admin1234` | `ADMINISTRADOR` | activo |
| `lcampos` | `Vendedor1234` | `VENDEDOR` | activo |
| `inactivo` | `Inactivo1234` | `VENDEDOR` | inactivo (para probar AUTH-002) |

Para probar autenticación: `POST /api/v1/auth/login` con `{ "username": "admin", "password": "Admin1234" }`. Usar el `token` devuelto como `Authorization: Bearer <token>` en endpoints protegidos.

### 3. Frontend

```bash
cd frontend
npm install   # solo la primera vez
npm run dev
```

Levanta Vite en `http://localhost:5173`. La página principal hace un fetch al backend para verificar la conexión.

## Comandos útiles

| Comando | Descripción |
|---|---|
| `docker compose down` | Detener BD (preserva la data en el volumen) |
| `docker compose down -v` | Detener BD y borrar la data (reset total) |
| `cd backend && mvn test` | Correr tests del backend |
| `cd frontend && npm run build` | Build de producción del frontend |
| `cd frontend && npm run lint` | Linter del frontend |

## Convenciones del proyecto

- **Ramas:** `main` (producción), `develop` (integración), `feature/*` (trabajo)
- **Mensajes de commit:** Conventional Commits (`feat:`, `fix:`, `chore:`, etc.)
- **Modularización:** vertical por dominio. Ver `backend/src/main/java/com/scaramutti/tms/`
- **Schemas BD:** `public` (servicios) y `cotizaciones` (módulo comercial)
