FROM nginxinc/nginx-unprivileged:1.31.6-alpine-slim@sha256:1517d8c358e2e093957ebee087afa9eb19a32f5d7ecb711b7429e369cb998224

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
