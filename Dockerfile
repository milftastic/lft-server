# Build stage: compile and package the server with the Gradle wrapper.
FROM eclipse-temurin:11-jdk AS build
WORKDIR /src
COPY . .
# Strip Windows line endings in case the repo was checked out on Windows.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew --no-daemon installDist

# Run stage: just a JRE and the packaged app.
FROM eclipse-temurin:11-jre
WORKDIR /app
COPY --from=build /src/build/install/lft-server /app
EXPOSE 8080
CMD ["/app/bin/lft-server"]
