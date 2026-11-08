# ---- Etapa 1: compilar con Maven ------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
# Capa de dependencias cacheada: si solo cambia el código, no se vuelven a descargar.
RUN mvn -B -q dependency:go-offline || true
COPY src ./src
RUN mvn -B -q -DskipTests package

# ---- Etapa 2: imagen liviana solo con el JRE --------------------------------
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/demo.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
