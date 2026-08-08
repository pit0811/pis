FROM eclipse-temurin:17-jdk-alpine AS build
WORKDIR /app
COPY AgendaWeb.java .
RUN javac --release 17 --add-modules jdk.httpserver AgendaWeb.java

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=build /app/*.class ./
COPY styles.css .
RUN mkdir -p data
EXPOSE 10000
CMD ["java", "--add-modules", "jdk.httpserver", "AgendaWeb"]
