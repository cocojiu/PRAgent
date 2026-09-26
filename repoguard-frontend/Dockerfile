FROM nginxinc/nginx-unprivileged:1.31.6-alpine-slim@sha256:dcc9bf9c084901dddbbce305130a7295c5637b6a8fce3e29cf678d86336982e4

USER root
RUN apk upgrade --no-cache libcrypto3 libssl3
USER 101

ARG OCI_VERSION
ARG OCI_REVISION

LABEL org.opencontainers.image.version="${OCI_VERSION}" \
      org.opencontainers.image.revision="${OCI_REVISION}"

COPY dist/ /usr/share/nginx/html/
COPY nginx.conf /etc/nginx/nginx.conf
COPY nginx.ip.conf /etc/nginx/conf.d/default.conf

EXPOSE 8080
