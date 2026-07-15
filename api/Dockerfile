FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /workspace

COPY pom.xml .
RUN mvn dependency:go-offline

COPY src src
RUN mvn clean package -DskipTests

FROM eclipse-temurin:21-jre

RUN useradd --system --uid 10001 archive

WORKDIR /app

COPY --from=build \
  /workspace/target/research-archive-api-0.0.1-SNAPSHOT.jar \
  app.jar

USER archive

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
