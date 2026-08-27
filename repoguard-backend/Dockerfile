ARG JAVA_IMAGE=eclipse-temurin:25.0.3_9-jre-alpine@sha256:28db6fdf60e38945e43d840c0333aeaec66c15943070104f7586fd3c9d1665b0
FROM ${JAVA_IMAGE} AS layers

WORKDIR /app

COPY target/repoguard-backend-0.0.1-SNAPSHOT.jar /app/repoguard-backend.jar
RUN java -Djarmode=tools -jar /app/repoguard-backend.jar extract --launcher --destination /app/extracted

FROM ${JAVA_IMAGE}

ARG OCI_VERSION
ARG OCI_REVISION

LABEL org.opencontainers.image.version="${OCI_VERSION}" \
      org.opencontainers.image.revision="${OCI_REVISION}"

WORKDIR /app

RUN apk upgrade --no-cache libcrypto3 libssl3 openssl libexpat p11-kit p11-kit-trust \
    && addgroup -S repoguard \
    && adduser -S -G repoguard repoguard

COPY --from=layers /app/extracted/BOOT-INF/lib/ ./BOOT-INF/lib/
COPY --from=layers /app/extracted/org/ ./org/
COPY --from=layers /app/extracted/BOOT-INF/classpath.idx ./BOOT-INF/
COPY --from=layers /app/extracted/BOOT-INF/layers.idx ./BOOT-INF/
COPY --from=layers /app/extracted/META-INF/ ./META-INF/
COPY --from=layers /app/extracted/BOOT-INF/classes/ ./BOOT-INF/classes/

RUN chown -R repoguard:repoguard /app

EXPOSE 8081

USER repoguard

ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
