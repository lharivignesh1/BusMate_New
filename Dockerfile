FROM maven:3.9-eclipse-temurin-17
WORKDIR /app
COPY . /app
WORKDIR /app/backend
EXPOSE 8080
CMD ["sh", "-c", "mvn -B clean compile exec:java"]
