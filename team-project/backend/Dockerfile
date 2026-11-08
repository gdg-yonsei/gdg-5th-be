FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY proto ./proto
COPY backend ./backend
RUN mvn -f backend/pom.xml -B package -DskipTests
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /workspace/backend/target/chat-backend-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
