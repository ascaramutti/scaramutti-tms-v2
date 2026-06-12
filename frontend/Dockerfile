# ==========================================
# Stage 1: Build (Node.js)
# ==========================================
FROM node:22-alpine AS builder

WORKDIR /app

COPY package*.json ./
RUN npm install

COPY . .
ARG VITE_API_BASE_URL=/api/v1
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL
RUN npm run build

# ==========================================
# Stage 2: Production (Nginx)
# ==========================================
FROM nginx:alpine AS production

# Copiar el build estático bajo /cotizaciones/ (coincide con el `base` de Vite,
# para que las URLs de assets resuelvan directo contra el filesystem)
COPY --from=builder /app/dist /usr/share/nginx/html/cotizaciones

# Configuración nginx para SPA (rutas de /cotizaciones/ → su index.html)
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=3s --retries=3 \
    CMD wget --quiet --tries=1 --spider http://127.0.0.1/cotizaciones/ || exit 1

CMD ["nginx", "-g", "daemon off;"]
