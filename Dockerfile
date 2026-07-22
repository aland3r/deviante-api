FROM gradle:8.14-jdk21 AS build
WORKDIR /app
COPY . .
RUN gradle --no-daemon shadowJar

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*-all.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
