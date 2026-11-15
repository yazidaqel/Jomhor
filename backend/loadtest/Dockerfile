# The boot jar on a JRE 25 image, for the smoke compose (backend/compose.yaml profile smoke). Context: backend/, after
# `gradlew bootJar` (Dockerfile.dockerignore sends that jar only).
FROM eclipse-temurin:25-jre
COPY build/libs/*.jar /app/jomhor.jar
EXPOSE 8080
# The heap follows the container's mem_limit.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70", "-jar", "/app/jomhor.jar"]
