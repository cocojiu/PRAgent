ARG JAVA_IMAGE=eclipse-temurin:26-jre-alpine
FROM ${JAVA_IMAGE}

WORKDIR /app

COPY target/repoguard-backend-0.0.1-SNAPSHOT.jar /app/repoguard-backend.jar

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/repoguard-backend.jar"]
