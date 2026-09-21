FROM eclipse-temurin:21-jdk-jammy AS build

WORKDIR /build

ARG SBT_VERSION=1.11.6

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL \
      "https://repo1.maven.org/maven2/org/scala-sbt/sbt-launch/${SBT_VERSION}/sbt-launch-${SBT_VERSION}.jar" \
      -o /usr/local/lib/sbt-launch.jar

COPY project ./project
COPY build.sbt ./

RUN java -Dsbt.supershell=false -jar /usr/local/lib/sbt-launch.jar update

COPY src ./src

RUN java -Dsbt.supershell=false -jar /usr/local/lib/sbt-launch.jar assembly

FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

COPY --from=build /build/target/scala-2.13/parrot669-backend.jar /app/parrot669-backend.jar

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

EXPOSE 8080

CMD ["sh", "-c", "exec java $JAVA_OPTS -jar /app/parrot669-backend.jar"]
