# Build en una etapa, runtime en otra: la imagen final no lleva Maven
# ni el código fuente, sólo el JRE y el jar.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
# El pom primero y solo: así la capa de dependencias se cachea y no se
# vuelve a descargar Maven Central cada vez que cambia una línea de código.
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=build /app/target/*.jar app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java","-jar","/app/app.jar"]
