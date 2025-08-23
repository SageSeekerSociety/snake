# Repository Guidelines

## Project Structure & Module Organization
- `backend-common`: Shared entities, DTOs, utils, and constants.
- `backend-controller`: Spring Boot API, Flyway migrations at `src/main/resources/db/migration`, OpenAPI spec at `openapi.yml`.
- `backend-worker`: Async task processing (AMQP, MinIO, caching).
- `docker-compose-deploy`: Local/production compose files, Grafana/Prometheus/Loki configs.
- Tests live in `src/test/kotlin` per module. Kotlin sources in `src/main/kotlin`.

## Build, Test, and Development Commands
- `./mvnw clean verify`: Build all modules, run tests, generate reports.
- `./mvnw -pl backend-controller spring-boot:run`: Run controller locally.
- `./mvnw -pl backend-worker spring-boot:run`: Run worker locally.
- `./mvnw -pl backend-controller -am generate-sources`: Regenerate API from `openapi.yml`.
- `./mvnw spotless:apply`: Format Kotlin/Java/POM/Markdown.
- `./rebuild.sh`: Package JARs and build local Docker images via compose.

## Coding Style & Naming Conventions
- Kotlin with ktfmt (KOTLINLANG) via Spotless; 4-space indentation, no tabs.
- Packages follow `org.rucca.snake.*`.
- Classes/records: PascalCase; methods/fields: camelCase; constants: UPPER_SNAKE_CASE.
- OpenAPI-generated models end with `DTO` (see `backend-controller/.../model`). Do not edit generated files; update `openapi.yml` and regenerate.

## Testing Guidelines
- Frameworks: JUnit 5 + `kotlin-test` (module tests under `src/test/kotlin`).
- Run tests: `./mvnw test` (module-scoped: `-pl <module>`).
- Coverage: JaCoCo reports at `<module>/target/site/jacoco/index.html`. Keep meaningful coverage for business logic; add tests for new endpoints/services.
- Test naming: mirror package structure; use descriptive method names (e.g., `shouldReturn404WhenPlayerMissing`).

## Commit & Pull Request Guidelines
- Use Conventional Commits: `feat:`, `fix:`, `perf:`, `refactor:`, `chore:`, optional scope e.g., `fix(worker): ...`. Reference issues with `(#123)` when applicable.
- PRs must include: clear description, linked issue, test evidence (logs or coverage), and if API changes, the updated `openapi.yml` and regeneration notes.
- Screenshots/log snippets welcome for observability changes (metrics/traces/logs).

## Security & Configuration Tips
- Never commit secrets. For Docker deploy, set `.env` in `docker-compose-deploy` (e.g., `JWT_SECRET`, `DB_PASSWORD`, `EMAIL_*`).
- Validate Flyway migrations and roll-forward only; keep schema changes in `backend-controller` migrations.

