# ---- STAGE 1: Build the application ----
FROM maven:3.9.6-eclipse-temurin-17 AS build
WORKDIR /app

# Copy all backend files
COPY . .

# Build the Spring Boot application
RUN mvn clean package -DskipTests

# ---- STAGE 2: Run the application ----
FROM eclipse-temurin:17-jre
WORKDIR /app

# Copy the jar from the previous stage
COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

# Run the app
CMD ["java", "-jar", "app.jar"]
