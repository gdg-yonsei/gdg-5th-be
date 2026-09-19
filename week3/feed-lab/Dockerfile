# syntax=docker/dockerfile:1
# 한 번 빌드해서 app / loadgen 두 이미지를 만든다 (docker compose 가 target 으로 골라 쓴다).
FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew                      # Windows 에서 빌드하면 실행 권한이 빠질 수 있다
COPY app/build.gradle app/
COPY loadgen/build.gradle loadgen/
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -q help
COPY app app
COPY loadgen loadgen
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -q :app:bootJar :loadgen:installDist

FROM eclipse-temurin:25-jre AS app
COPY --from=build /src/app/build/libs/app.jar /app.jar
ENTRYPOINT ["java", "-Xms512m", "-Xmx1g", "-jar", "/app.jar"]

FROM eclipse-temurin:25-jre AS loadgen
COPY --from=build /src/loadgen/build/install/loadgen /loadgen
ENV JAVA_OPTS="-Xms256m -Xmx512m"
ENTRYPOINT ["/loadgen/bin/loadgen"]
