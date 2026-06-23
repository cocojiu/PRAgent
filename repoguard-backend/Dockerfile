ARG JAVA_IMAGE=eclipse-temurin:26-jre-alpine
FROM ${JAVA_IMAGE} AS layers

WORKDIR /app

COPY target/repoguard-backend-0.0.1-SNAPSHOT.jar /app/repoguard-backend.jar
RUN java -Djarmode=tools -jar /app/repoguard-backend.jar extract --launcher --destination /app/extracted

FROM ${JAVA_IMAGE}

WORKDIR /app

RUN addgroup -S repoguard && adduser -S -G repoguard repoguard

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
