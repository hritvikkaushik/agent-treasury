# Multi-stage build: compile a Spring Boot fat jar, then run it on a slim JRE.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
# Pre-fetch dependencies (cached layer) for faster rebuilds.
RUN mvn -q -e -B dependency:go-offline || true
COPY src ./src
RUN mvn -q -B -DskipTests clean package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/*.jar app.jar
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "app.jar"]
