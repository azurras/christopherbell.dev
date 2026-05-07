# christopherbell.dev

Personal website and application for Christopher Bell. The project combines a Spring Boot backend with a vanilla JavaScript frontend (Webpack + Web Components).

## Modules
- `website` – Spring Boot application and frontend. Contains all Node tooling.
- `cbell-lib` – Reusable Java library shared across applications.

## Requirements
- Java 21+ (JDK). CI builds with Java 21; the Gradle wrapper is compatible with newer local JDKs such as Java 25.
- Node.js 18+
- npm 9+
- MongoDB

## Setup
```bash
# Install frontend dependencies and build assets
cd website
npm install
npm run build

# From the repo root
./gradlew :website:build
```

## Testing
```bash
./gradlew test
```

## Run locally
1. Start MongoDB (default local profile expects `mongodb://localhost:27019`).
2. Run the app:
```bash
./gradlew :website:bootRun
```

Notes:
- The default Spring profile is `local` (see `website/src/main/resources/application.yml`).
- `application-local.yml` sets the server port to `8081`.
- To override config, use environment variables such as `SPRING_PROFILES_ACTIVE=local` and `SPRING_DATA_MONGODB_URI=...`.

## Reporting
Post reports are stored in the database and visible in Back Office for admins.

## Deploy to production
1. Build static assets and the runnable JAR:
```bash
cd website
npm ci
npm run build
cd ..
./gradlew :website:build
```
2. Configure production settings (MongoDB URI, ports, secrets) via environment variables:
- `SPRING_PROFILES_ACTIVE=prod`
- `SPRING_DATA_MONGODB_URI=mongodb://<host>:<port>/<db>`
- `SERVER_PORT=8080` (optional override)

3. Run the JAR produced under `website/build/libs/`:
```bash
java -jar website/build/libs/<jar-name>.jar
```

Each module also has its own README with additional details.
