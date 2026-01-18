# christopherbell.dev

Personal website and application for Christopher Bell. The project combines a Spring Boot backend with a vanilla JavaScript frontend that uses Web Components and a simple pub/sub system.

## Tech Stack
- **Backend:** Java 21, Spring Boot 3, Gradle
- **Frontend:** Vanilla JavaScript, Web Components, Webpack 5, Babel
- **Build Tools:** Gradle Wrapper, Node.js (>=18), npm (>=9)
- **Database:** MongoDB

## Getting Started

### Prerequisites
- Java 21
- Node.js 18+
- npm 9+
- MongoDB

### Quickstart
```bash
npm install
npm run build
./gradlew bootRun
```

The webpack build outputs static assets to `src/main/resources/static`. `./gradlew bootRun` starts the Spring Boot application on [http://localhost:8080](http://localhost:8080).

### Building a JAR
To produce a runnable jar file:
```bash
./gradlew build
```
The artifact will be located under `build/libs/`.

### Environment
Set `SPRING_ACTIVE_PROFILE=local` for a local development profile and configure any required PostgreSQL connection details in `application.yml`.

### Reporting
Post reports are stored in the database and visible in Back Office for admins.
