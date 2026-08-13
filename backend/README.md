# Backend

## Stack

- Java 25
- Spring Boot 3.5.16
- Gradle Wrapper 9.2.1
- PostgreSQL/PostGIS

## Run

Start PostGIS from the repository root, then run Spring Boot.

```bash
docker compose up -d postgres
cd backend
./gradlew bootRun
```

The API runs at `http://localhost:8080`; health is available at
`http://localhost:8080/actuator/health`.

## Test

Tests use an in-memory H2 database in PostgreSQL compatibility mode.

```bash
./gradlew test
```
