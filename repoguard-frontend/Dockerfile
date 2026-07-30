FROM nginxinc/nginx-unprivileged:1.31.3-alpine-slim@sha256:d9fa63891743a11eb56ca1b22d8cc227491df183ca3da2cf1bf2c6e010bf19a5

ARG OCI_VERSION
ARG OCI_REVISION

LABEL org.opencontainers.image.version="${OCI_VERSION}" \
      org.opencontainers.image.revision="${OCI_REVISION}"

COPY dist/ /usr/share/nginx/html/
COPY nginx.conf /etc/nginx/nginx.conf
COPY nginx.ip.conf /etc/nginx/conf.d/default.conf

EXPOSE 8080
