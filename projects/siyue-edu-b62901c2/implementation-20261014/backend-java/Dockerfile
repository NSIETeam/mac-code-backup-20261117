FROM maven:3.9.12-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml checkstyle.xml ./
COPY src ./src
RUN mvn -B -ntp verify

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S -g 10001 siyue && adduser -S -D -H -u 10001 -G siyue siyue
COPY --from=build --chown=10001:10001 /workspace/target/siyue-core-0.1.0.jar /app/siyue-core.jar
USER 10001:10001
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/siyue-core.jar"]
