# Scaramutti TMS

Sistema de gestión para Transportes Scaramutti S.A.C. Cubre la operación de servicios de transporte, la emisión de cotizaciones comerciales y el control de almacén (entradas, retiros, stock y reportes).

## Stack

- **Base de datos:** PostgreSQL 16 (schema gestionado con Flyway)
- **Backend:** Quarkus 3.15 (Java 17, Maven)
- **Frontend:** React 18 + Vite + TypeScript
- **Contrato API:** OpenAPI 3.1

## Estructura del repositorio

```
scaramutti-tms-v2/
├── api/                ← Contrato OpenAPI de diseño (todos los módulos)
├── backend/            ← Servicio Quarkus
│   └── src/main/resources/
│       ├── META-INF/openapi.yaml  ← Spec runtime (endpoints implementados)
│       └── db/migration/          ← Cadena Flyway (fuente de verdad del schema)
├── frontend/           ← App React + Vite
├── db/                 ← Históricos pre-Flyway + seed de datos de empresa
├── .github/workflows/  ← CI (tests de backend por PR) y deploys
├── docker-compose.yml  ← BD local para desarrollo
└── README.md
```

Módulos del backend (vertical por dominio): `auth`, `clients`, `quotations`, `catalogs`, `cargotypes`, `settings`, `warehouse` (almacén), `sharedcatalogs` (catálogos compartidos read-only) y `shared` (infra transversal).

## Requisitos

- Docker (para la BD)
- Java 17+ y Maven 3.9+
- Node 20+ y npm

## Levantar el entorno local

### 1. Base de datos

```bash
docker compose up -d
```

Levanta PostgreSQL 16 vacío en `localhost:5432`. El schema NO se aplica a mano: lo crea Flyway al arrancar el backend (paso 2), ejecutando la cadena de `backend/src/main/resources/db/migration/` (`V001` = baseline con los schemas `public` y `cotizaciones`; `V002+` agrega `almacen` y sus incrementales). Reglas de la cadena en el `README.md` de esa carpeta.

Credenciales locales (definidas en `docker-compose.yml`):
- DB: `scaramutti_tms_dev`
- User: `scaramutti_user`
- Password: `dev_local_only`

### 2. Backend

```bash
cd backend
mvn quarkus:dev
```

Levanta Quarkus en modo desarrollo (hot reload) en `http://localhost:8080`. Al arrancar, Flyway aplica las migraciones pendientes y el `DevDataSeeder` siembra los fixtures de desarrollo (roles, usuarios, monedas, catálogos); el seeder solo corre en dev/test, nunca en prod.

Los defaults de `application.properties` apuntan a la BD local — `mvn quarkus:dev` funciona sin configuración extra. Para overridear (otra BD, claves JWT custom, etc.), copiar `backend/.env.example` a `backend/.env` y completar; Quarkus lee `.env` del root del módulo automáticamente.

Opcional: `psql -f db/seed_system_settings.sql` siembra los datos de la empresa emisora que usa el PDF de cotización (sin ellos el PDF degrada a vacío, no falla).

Endpoints útiles para verificar:
- `http://localhost:8080/api/v1/q/health` — estado del servicio + conexión a BD
- `http://localhost:8080/swagger` — Swagger UI
- `http://localhost:8080/openapi` — spec OpenAPI runtime (módulos implementados)

Usuarios seed disponibles en perfil `dev` (creados por `DevDataSeeder`):

| Username | Password | Rol | Estado |
|---|---|---|---|
| `admin` | `Admin1234` | `admin` | activo |
| `lcampos` | `Sales1234` | `sales` | activo |
| `inactivo` | `Inactivo1234` | `sales` | inactivo (para probar AUTH-002) |

Para probar autenticación: `POST /api/v1/auth/login` con `{ "username": "admin", "password": "Admin1234" }`. Usar el `token` devuelto como `Authorization: Bearer <token>` en endpoints protegidos.

### 3. Frontend

Antes de arrancar la primera vez, copiar el template de variables:

```bash
cp frontend/.env.example frontend/.env.local
```

Completar `VITE_API_BASE_URL` (por ejemplo `http://localhost:8080/api/v1` si el backend corre local).

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
| `cd backend && mvn test` | Correr tests del backend (suite hermética, es la misma del CI) |
| `cd frontend && npm run build` | Build de producción del frontend |
| `cd frontend && npm run lint` | Linter del frontend |

## Integración continua

Cada PR contra `develop` corre la suite completa del backend en GitHub Actions (`.github/workflows/backend-tests.yml`) sobre una BD virgen: las migraciones Flyway y los tests deben pasar sin depender de datos preexistentes.

## Convenciones del proyecto

- **Ramas:** `main` (producción), `develop` (integración), `feature/*` (trabajo)
- **Mensajes de commit:** Conventional Commits (`feat:`, `fix:`, `chore:`, etc.); header en inglés
- **Migraciones:** Flyway, numeración única secuencial con prefijo de módulo (`V00X__almacen_*`); una migración aplicada nunca se edita
- **Contrato:** `api/openapi.yaml` es el contrato de diseño; la spec runtime (`META-INF/openapi.yaml`) refleja lo implementado y es la que sirve Swagger
- **Modularización:** vertical por dominio. Ver `backend/src/main/java/com/scaramutti/tms/`
- **Schemas BD:** `public` (servicios, compartido), `cotizaciones` (módulo comercial) y `almacen` (módulo de almacén)
