FROM golang:1.25-bookworm AS go-runtime

FROM eclipse-temurin:21-jre-jammy

COPY --from=go-runtime /usr/local/go /usr/local/go
COPY common-standard-rule-builder.jar /app/common-standard-rule-builder.jar

WORKDIR /app
USER 1000:1000

ENTRYPOINT ["java", "-jar", "/app/common-standard-rule-builder.jar"]
