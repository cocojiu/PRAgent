FROM nginxinc/nginx-unprivileged:1.31.5-alpine-slim@sha256:c94666682d7ecbfa0a1767fbe882cd1d82509333d15716c765f42bbef0d3809f

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
