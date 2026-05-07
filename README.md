# Scaramutti TMS

Sistema de gestión para Transportes Scaramutti S.A.C. Cubre la operación de servicios de transporte y la emisión de cotizaciones comerciales.

## Stack

- **Base de datos:** PostgreSQL 16
- **Backend:** *(por definir)*
- **Frontend:** *(por definir)*

## Estructura

```
db/
  baseline.sql    # Esquema completo de la base de datos
```

## Levantar la base de datos en local

```bash
docker run -d --name scaramutti-tms-db-dev \
  -e POSTGRES_USER=scaramutti_user \
  -e POSTGRES_PASSWORD=<tu_password> \
  -e POSTGRES_DB=scaramutti_tms_dev \
  -p 5432:5432 \
  postgres:16-alpine

docker cp db/baseline.sql scaramutti-tms-db-dev:/tmp/baseline.sql
docker exec scaramutti-tms-db-dev \
  psql -U scaramutti_user -d scaramutti_tms_dev -f /tmp/baseline.sql
```

## Schemas

| Schema | Propósito |
|---|---|
| `public` | Operación de servicios de transporte |
| `cotizaciones` | Emisión y administración de cotizaciones |
