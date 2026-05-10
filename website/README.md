# christopherbell.dev

Personal website and application for Christopher Bell. The project combines a Spring Boot backend with a vanilla JavaScript frontend that uses browser-native ES modules, Web Components, and a simple pub/sub system.

## Tech Stack
- **Backend:** Java 21 target, Spring Boot 3, Gradle
- **Frontend:** Vanilla JavaScript, browser ES modules, Web Components
- **Build Tools:** Gradle Wrapper
- **Database:** MongoDB

## Getting Started

### Prerequisites
- Java 21+ (CI builds with Java 21; newer local JDKs such as Java 25 are supported by the wrapper)
- MongoDB

### Quickstart
Run commands from the repository root, not from inside `website`.

For Linux, macOS, or WSL:
```bash
./gradlew :website:bootRun
```

For Windows PowerShell:
```powershell
.\gradlew.bat :website:bootRun
```

The frontend is served directly from `website/src/main/resources/static`; no npm install or JavaScript build step is required. `:website:bootRun` starts the Spring Boot application.

To run with the production profile:
```bash
./gradlew :website:bootRun --args='--spring.profiles.active=prod'
```

### Building a JAR
To produce a runnable jar file:
```bash
./gradlew :website:build
```
The artifact will be located under `website/build/libs/`.

### Running tests
```bash
./gradlew test
```

### Environment
Set `SPRING_PROFILES_ACTIVE=local` for a local development profile and configure any required MongoDB connection details in `application.yml`.

### WSL
If WSL reports `bad interpreter: /bin/sh^M`, convert the wrapper to Unix line endings and make it executable:
```bash
sed -i 's/\r$//' gradlew
chmod +x gradlew
```

### Reporting
Post reports are stored in the database and visible in Back Office for admins.

### Reporting
Post reports are stored in the database and visible in Back Office for admins.
