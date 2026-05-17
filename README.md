# Diploma Thesis Management System — Backend

Spring Boot 3.5 / Java 21 / PostgreSQL / JWT auth backend for a university thesis workflow system.

## Features

- JWT authentication with role-based access (`STUDENT`, `MENTOR`, `ADMIN`, `COMMITTEE`, `ARCHIVE`)
- Full thesis workflow (12 steps: eligibility → topic → mentor → application → versions → committee → defense → archive)
- PDF version uploads with comments
- Committee formation and review
- Defense scheduling with cancellation/rescheduling
- Grade recording and archiving
- Notification logging with async email sending
- Audit trail for every status change
- Swagger UI at `/swagger-ui.html`

## Prerequisites

- **JDK 21** ([Eclipse Temurin](https://adoptium.net) recommended)
- **PostgreSQL 12+** running on `localhost:5432`
- **Maven** (or use the bundled `mvnw` wrapper)

## Setup

### 1. Create the database

```sql
CREATE DATABASE diploma_system;
```

### 2. Configure credentials

Edit `src/main/resources/application.properties` and set your PostgreSQL password:

```properties
spring.datasource.username=postgres
spring.datasource.password=YOUR_PASSWORD_HERE
```

### 3. Run

```bash
./mvnw spring-boot:run        # macOS / Linux
mvnw.cmd spring-boot:run      # Windows
```

The app starts at **http://localhost:8080**.

Hibernate auto-creates all tables on first run (`ddl-auto=update`).

## Test accounts (auto-seeded)

All passwords: **`password123`**

| Role | Email |
|---|---|
| STUDENT | `student@test.com` (index `2024/001`) |
| MENTOR | `mentor@test.com` |
| MENTOR | `mentor2@test.com` |
| MENTOR | `mentor3@test.com` |
| ADMIN | `admin@test.com` |
| COMMITTEE | `committee@test.com` |
| ARCHIVE | `archive@test.com` |

## Explore the API

Open **http://localhost:8080/swagger-ui.html** — try out every endpoint with the **Authorize** button (use the JWT token from `/api/auth/login`).

## Project structure

```
src/main/java/com/praksa/
├── config/         # Async, OpenAPI, DataInitializer
├── controller/     # REST endpoints
├── dto/            # Request/response DTOs
├── exception/      # Custom exceptions + GlobalExceptionHandler
├── model/          # JPA entities + enums
├── repository/     # Spring Data JPA repositories
├── security/       # JWT, SecurityConfig, filter
└── service/        # Business logic (interfaces + impls)
```

## Architecture decisions

- **Service layer owns business logic** — controllers just route, services validate roles + statuses
- **Every status change goes through `transitionStatus()`** — guarantees `thesis_status_history` row
- **Files stored on disk, paths in DB** — never PDFs in a `bytea` column
- **`@Async` for emails** — HTTP responses don't wait for SMTP
- **DTOs everywhere out of services** — no JPA entities leak to controllers (no `LazyInitializationException`)

## Frontend

The matching React frontend lives in a separate repo: [praksa-frontend](#).

## License

MIT — academic project.
