# Gateway de unificación v1 + v2

Nginx que expone **un solo origin** y rutea por prefijo de path:

| Prefijo | Destino |
|---|---|
| `/api/v1/*` | backend v2 (Quarkus) |
| `/cotizaciones/*` | frontend v2 (SPA React) |
| `/*` (resto, incl. `/api/*`) | frontend v1 (su nginx proxya `/api/` a su backend) |

Mismo origin = localStorage compartido entre las dos SPAs → es la base del login único.
Plan completo: `docs/PLAN_UNIFICACION_SSO.md`.

## Producción

El servicio `gateway` está en `docker-compose.prod.yml` (puerto **8085** del host).

**Prerequisitos (en orden):**
1. **Fase 1 desplegada** (`feature/sso-base-path`): el frontend v2 debe servir la SPA
   bajo `/cotizaciones/`. Con el v2 viejo (assets en `/assets/*`), el gateway rutearía
   esos assets a v1 → página en blanco.
2. **v1 y v2 corriendo** en la red compartida `scaramutti-tms_default` (nginx resuelve
   los upstreams al arrancar).
3. **`CORS_ORIGINS` del backend v2** (`.env` en Canadá): agregar `http://scaramutti-tms`
   (el hostname unificado) manteniendo los viejos durante la transición, y recrear el
   backend (`up -d --force-recreate backend`).

```bash
docker compose -f docker-compose.prod.yml up -d gateway
curl http://localhost:8085/gateway/health   # → ok
```

> **Nota operativa**: nginx resuelve los nombres de contenedor **al arrancar**
> (mismo patrón que el nginx del frontend v2). Implicancias: (1) el gateway
> necesita que los contenedores de v1 y v2 existan al iniciar; (2) si se
> recrea un contenedor destino y cambia su IP, reiniciar el gateway:
> `docker restart scaramutti-tms-gateway-prod`.

### Apache (Servidor Perú) — VirtualHost del hostname unificado

```apache
<VirtualHost *:80>
    ServerName scaramutti-tms
    ProxyPreserveHost On
    ProxyPass / http://<IP_TAILSCALE_HOST_DOCKER>:8085/
    ProxyPassReverse / http://<IP_TAILSCALE_HOST_DOCKER>:8085/
</VirtualHost>
```

> Los valores reales de IPs/hosts de la infraestructura viven en la
> documentación interna (`docs/`, fuera del control de versiones).

Y en el `hosts` de cada PC cliente: `<IP_RELAY> scaramutti-tms`.
Los hostnames viejos (`tms.local`, `scaramutti-tms-v2`) siguen funcionando durante la transición.

## Entorno de integración local

`docker-compose.integration.yml` levanta la topología completa en la máquina de
desarrollo (gateway + v1 + v2) contra la **DB dev existente** del host.

Requisitos:
1. Repo v1 como hermano (`../scaramutti-tms`).
2. DB dev corriendo (`scaramutti-tms-db-dev` en `:5432`, con usuarios/catálogos).
3. Si las credenciales de la DB dev difieren de los defaults, exportar
   `INTEGRATION_DB_USER` / `INTEGRATION_DB_PASSWORD` / `INTEGRATION_DB_NAME`.
4. Claves JWT de dev en `backend/src/main/resources/keys/` (`publickey.pem` está
   commiteada; **`privatekey.pem` es local/gitignored** — sin ella el backend v2
   no arranca, con un error críptico de SmallRye JWT).
5. **Fases 1 y 4a presentes en el working tree** (mergeadas o en la rama actual):
   el build del frontend v2 debe incluir el base path `/cotizaciones/`.
6. (Opcional, para probar con el hostname real) en `/etc/hosts` del Mac:
   `127.0.0.1 scaramutti-tms`

```bash
docker compose -f docker-compose.integration.yml up -d --build
open http://scaramutti-tms:8085/                # v1
open http://scaramutti-tms:8085/cotizaciones/   # v2
docker compose -f docker-compose.integration.yml down
```

Usa los **mismos `container_name` que prod** a propósito: así el
`gateway/nginx.conf` que se prueba acá es byte-idéntico al que se despliega.
No puede convivir con los contenedores de prod (no aplica: corre en la máquina dev).
