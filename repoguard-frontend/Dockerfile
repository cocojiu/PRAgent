FROM nginxinc/nginx-unprivileged:1.31.6-alpine-slim@sha256:c81a27f28bc2d9c2da8998444e653c7b85b9bbbaa92e44ef18d8920784e06507

USER root
RUN apk upgrade --no-cache libcrypto3 libssl3 pcre2
USER 101

ARG OCI_VERSION
ARG OCI_REVISION
ARG REVIEW_PROGRESS_STREAM=false

LABEL org.opencontainers.image.version="${OCI_VERSION}" \
      org.opencontainers.image.revision="${OCI_REVISION}" \
      io.repoguard.frontend.review-progress-stream="${REVIEW_PROGRESS_STREAM}"

COPY dist/ /usr/share/nginx/html/
COPY nginx.conf /etc/nginx/nginx.conf
COPY nginx.ip.conf /etc/nginx/conf.d/default.conf

EXPOSE 8080
