# Gateway

Nginx que expone **un solo origin** y rutea por prefijo de path. Es la **única
aplicación pública** del sistema: tiene colgados el dominio, el certificado
gestionado y la allowlist de IPs. Si el gateway no arranca, no entra nadie.

| Prefijo | Destino |
|---|---|
| `/api/v1/*` | backend v2 (Quarkus) |
| `/cotizaciones/*` | frontend v2 (SPA React) |
| `/login` | 302 a `/cotizaciones/login` |
| `/cotizaciones` (exacto) | 302 a `/cotizaciones/` |
| `/api/v1` (exacto) | **308** a `/api/v1/` — preserva método y cuerpo, importa para POST y PUT |
| `/gateway/health` | 200 propio, no depende de ningún destino |
| `/*` (todo lo demás) | 302 a `/cotizaciones/` |

Hasta el cutover de agosto de 2026, `/*` y `/api/*` iban al sistema viejo (v1).
Sus datos viven ahora en el schema `operaciones` de v2 y su aplicación quedó
retirada, así que el último bloque redirige a la SPA: los enlaces guardados de
v1 llevan a la aplicación nueva en vez de a un error.

## Dos configuraciones, un solo ruteo

| Archivo | Dónde corre | Cómo llega a los destinos |
|---|---|---|
| `default.conf.template` | **Producción** (Azure Container Apps) | HTTPS contra `<app>.${INTERNAL_SUFFIX}`, que Terraform inyecta |
| `nginx.conf` | Compose de integración local | HTTP contra nombres de contenedor fijos |

⚠️ **Un cambio de ruteo va en los DOS.** Son variantes del mismo gateway, no
alternativas: la de producción no puede correr local (no existe el sufijo) y la
local no puede correr en Azure (los nombres de contenedor no resuelven ahí).

## Producción — construir y publicar

La imagen es `nginx:1.31-alpine` más la plantilla. **La versión va fija a
propósito**: con la etiqueta móvil, reconstruir traería un salto de versión sin
que nadie lo decida, en el único componente público. Nginx la procesa al arrancar
sustituyendo `${INTERNAL_SUFFIX}`; sin esa variable los destinos quedan rotos.

```bash
docker build --platform linux/amd64 -t ghcr.io/ascaramutti/gateway:<n> ./gateway
docker push ghcr.io/ascaramutti/gateway:<n>
az containerapp update -n gateway -g rg-scaramutti-tms \
  --image ghcr.io/ascaramutti/gateway:<n>
```

Tres cosas que se olvidan y cuestan caro:

1. **`--platform linux/amd64` siempre.** Azure no ejecuta arm64, y una imagen
   construida en una Mac sin especificar plataforma sale arm64 y el contenedor
   no arranca.
2. **La etiqueta se incrementa, nunca se pisa.** La anterior es el rollback.
3. **Actualizar también `apps.tf`** en el repo de infraestructura, que pinnea la
   etiqueta. Hoy no revierte el despliegue porque ignora los cambios de imagen,
   pero si la aplicación se recreara volvería a la versión vieja.

### Nginx resuelve los destinos AL ARRANCAR

De acá salen casi todos los incidentes de este componente. Si un destino no
resuelve, **nginx no levanta** — no degrada, no sirve un error: no arranca.

Y el gateway **duerme** (`min_replicas = 0`, con una hora de holgura), así que
vuelve a resolver en cada arranque en frío. O sea que un destino roto puede no
notarse durante horas y aparecer solo cuando alguien entra.

**Matiz que importa**: escalar una aplicación a cero **no** rompe la resolución
—el nombre interno de Container Apps resuelve exista o no una réplica—. Eso está
medido: las aplicaciones de v1 ya estaban en cero y el gateway arrancaba bien.
Que **borrar** la aplicación sí la rompa es lo esperable, pero **no está
verificado**: no se encontró fuente que lo confirme.

El orden vale bajo las dos hipótesis, así que se prescribe igual: al retirar un
destino, desplegar primero el gateway que ya no lo nombra, verificar, y recién
entonces tocar la aplicación.

```bash
curl https://<dominio>/gateway/health    # → ok, sin depender de ningún destino
```

## Entorno de integración local

`docker-compose.integration.yml` levanta la topología en la máquina de
desarrollo contra la **DB dev existente** del host. Usa a propósito los
**mismos `container_name` que la topología anterior** (`docker-compose.prod.yml`),
lo cual confunde al leer `docker ps`: los contenedores que dicen `-prod` son
locales. En Azure no existen los `container_name`.

Requisitos:

1. DB dev corriendo (`scaramutti-tms-db-dev` en `:5432`).
2. Si las credenciales difieren de los defaults, exportar
   `INTEGRATION_DB_USER` / `INTEGRATION_DB_PASSWORD` / `INTEGRATION_DB_NAME`.
3. Claves JWT de dev en `backend/src/main/resources/keys/` (`publickey.pem` está
   commiteada; **`privatekey.pem` es local y gitignored** — sin ella el backend
   v2 no arranca, con un error críptico de SmallRye JWT).

```bash
docker compose -f docker-compose.integration.yml up -d --build
curl -I http://localhost:8085/                  # → 302 a /cotizaciones/
open  http://localhost:8085/cotizaciones/
docker compose -f docker-compose.integration.yml down
```

> El compose todavía define los servicios de v1 y el repo hermano
> (`../scaramutti-tms`) sigue siendo necesario para construirlos, pero **ya no
> hay ruta que les llegue**. Retirarlos es limpieza pendiente.

## Topología anterior (Perú → Canadá)

`docker-compose.prod.yml` monta `nginx.conf` y documenta el rollback a la
infraestructura previa a Azure. **Después del cutover ese camino tampoco sirve
v1**, así que dejó de ser un rollback completo: sirve para volver a servir v2,
no el sistema viejo.
