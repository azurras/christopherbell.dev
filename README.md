# christopherbell.dev

Personal website and application for Christopher Bell. The project combines a Spring Boot backend with a vanilla JavaScript frontend built from browser-native ES modules and Web Components.

## Modules
- `website` - Spring Boot application and static frontend.
- `cbell-lib` - Reusable Java library shared across applications.

## Requirements
- Java 21+ (JDK). CI builds with Java 21; the Gradle wrapper is compatible with newer local JDKs such as Java 25.
- MongoDB

## Setup
No npm install or frontend build step is required. Static JavaScript modules are served directly from `website/src/main/resources/static`.

```bash
./gradlew :website:build
```

On Windows PowerShell, use:
```powershell
.\gradlew.bat :website:build
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

On Windows PowerShell, use:
```powershell
.\gradlew.bat :website:bootRun
```

Notes:
- The default Spring profile is `local` (see `website/src/main/resources/application.yml`).
- `application-local.yml` sets the server port to `8081`.
- To run with another Spring profile, pass it to the Spring Boot app:
```bash
./gradlew :website:bootRun --args='--spring.profiles.active=prod'
```
- To override config, use environment variables such as `SPRING_PROFILES_ACTIVE=prod` and `SPRING_DATA_MONGODB_URI=...`.

## WSL Notes
If WSL reports `bad interpreter: /bin/sh^M`, convert the wrapper to Unix line endings and make it executable:
```bash
sed -i 's/\r$//' gradlew
chmod +x gradlew
```

Then run Gradle from the repo root with the `website` task path:
```bash
./gradlew :website:bootRun
```

## Reporting
Post reports are stored in the database and visible in Back Office for admins.

## Deploy to production
1. Build the runnable JAR:
```bash
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
