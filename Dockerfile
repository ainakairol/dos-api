FROM eclipse-temurin:17-jdk

WORKDIR /app

COPY . .

RUN ./gradlew clean build --no-daemon

EXPOSE 10000

CMD ["java", "-jar", "build/libs/demo-0.1.jar"]
