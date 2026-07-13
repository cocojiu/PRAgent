FROM nginxinc/nginx-unprivileged:1.30.3-alpine3.23@sha256:b3f2436575bd5be7386518084d842dac414ab4962712afa31e99e0942a56e3b2

ARG OCI_VERSION
ARG OCI_REVISION

LABEL org.opencontainers.image.version="${OCI_VERSION}" \
      org.opencontainers.image.revision="${OCI_REVISION}"

COPY dist/ /usr/share/nginx/html/
COPY nginx.conf /etc/nginx/nginx.conf
COPY nginx.ip.conf /etc/nginx/templates/default.conf.template

EXPOSE 8080
