FROM nginxinc/nginx-unprivileged:1.31.7-alpine-slim@sha256:b94a56c52fd315bbd8f1f4368d64c25983351c978313e02feff2c0b35e856f2d

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
