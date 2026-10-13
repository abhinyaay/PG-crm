# syntax=docker/dockerfile:1

# ---------- build ----------
FROM node:20-alpine AS build
WORKDIR /app
ENV CI=true
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

# ---------- runtime ----------
FROM nginx:1.31-alpine AS runtime

# Non-root friendly: the master process runs as the unprivileged `nginx` user (uid 101).
# Docker (>= 20.10) sets net.ipv4.ip_unprivileged_port_start=0 inside containers, so port 80 still works.
RUN sed -i '/^user /d' /etc/nginx/nginx.conf \
 && sed -i 's#^pid .*#pid /tmp/nginx.pid;#' /etc/nginx/nginx.conf \
 && rm -f /etc/nginx/conf.d/default.conf \
 && mkdir -p /var/cache/nginx \
 && chown -R nginx:nginx /var/cache/nginx /var/log/nginx /etc/nginx/conf.d

COPY nginx/default.conf /etc/nginx/conf.d/default.conf
COPY nginx/security-headers.conf /etc/nginx/snippets/security-headers.conf
COPY --from=build --chown=nginx:nginx /app/dist /usr/share/nginx/html

USER nginx
EXPOSE 80
HEALTHCHECK --interval=30s --timeout=3s --retries=3 CMD wget -q -O /dev/null http://127.0.0.1/healthz || exit 1
CMD ["nginx", "-g", "daemon off;"]
