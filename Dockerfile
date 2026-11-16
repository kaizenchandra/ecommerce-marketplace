FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
COPY src src
RUN --mount=type=cache,target=/root/.m2 mvn -B -Dmaven.wagon.http.retryHandler.count=3 package -DskipTests
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --gid 10001 marketplace && useradd --uid 10001 --gid 10001 --no-create-home marketplace
COPY --from=build /workspace/target/ecommerce-marketplace-1.0.0-SNAPSHOT.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java","-XX:MaxRAMPercentage=70","-jar","app.jar"]
