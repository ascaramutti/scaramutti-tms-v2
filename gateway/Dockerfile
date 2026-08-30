# Gateway de producción. Reproduce la imagen que corre en Azure: nginx
# mainline sobre Alpine más la plantilla, que nginx procesa al arrancar
# sustituyendo ${INTERNAL_SUFFIX}.
#
# ⚠️ Construir SIEMPRE para linux/amd64: Azure Container Apps no ejecuta
# arm64, y una imagen construida en una Mac sin especificar plataforma sale
# arm64 y el contenedor no arranca.
#
#   docker build --platform linux/amd64 -t ghcr.io/ascaramutti/gateway:<n> ./gateway
#   docker push ghcr.io/ascaramutti/gateway:<n>
#
# La etiqueta se INCREMENTA, nunca se pisa: la anterior es el rollback.
# Version acotada a proposito. `nginx:alpine` a secas trae lo que esa etiqueta
# apunte el dia del build: un salto de version sin que nadie lo decida, en el
# unico componente publico del sistema.
#
# Con `1.31-alpine` la reconstruccion queda dentro de la misma version menor y
# solo toma parches. Hoy eso significa 1.31.4 contra el 1.31.3 que corre en
# produccion. Subir de menor es una decision propia, no un efecto de
# reconstruir.
FROM nginx:1.31-alpine

COPY default.conf.template /etc/nginx/templates/default.conf.template
