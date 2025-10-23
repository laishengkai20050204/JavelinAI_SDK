# Repository Guidelines

## Project Structure & Module Organization
- `src/main/java/com/example` contains the Spring Boot application entry point plus feature packages: `controller` for HTTP endpoints, `service` and `service/impl` for orchestration, and `tools` for pluggable agent tooling.
- `src/main/resources` holds configuration (`application.yaml`), static assets, and templates consumed by WebFlux.
- Tests live in `src/test/java/com/example`; mirror the production package path when adding new cases.
- Maven wrapper scripts (`mvnw`, `mvnw.cmd`) and the `pom.xml` sit at the root; keep them versioned with any build updates.

## Build, Test & Development Commands
- `./mvnw spring-boot:run` (or `.\mvnw.cmd spring-boot:run` on Windows) starts the reactive API on port 8080 using the current config.
- `./mvnw clean package` produces a runnable JAR under `target/`, rebuilding generated sources.
- `./mvnw test` executes the JUnit 5 suite; pair with `-Dspring.profiles.active=test` when loading test-only config.
- `./mvnw verify` runs the full lifecycle, including integration-style checks and Reactor-specific assertions.

## Coding Style & Naming Conventions
- Target the Java level declared in `pom.xml` (currently 25); use records or sealed types where helpful but stay compatible with the toolchain.
- Prefer Spring stereotypes (`@Service`, `@RestController`) and Lombok annotations already in use; annotate new beans consistently.
- Follow 4-space indentation, brace-on-same-line formatting, and descriptive class names ending in `Controller`, `Service`, or `Impl` to match existing patterns.
- Keep DTOs immutable where possible and locate them under `service.impl.dto` alongside similar types.

## Testing Guidelines
- Write JUnit 5 tests under matching packages, e.g., `src/test/java/com/example/service/impl/AiServiceImplTests.java`.
- Use `@SpringBootTest` sparingly; prefer slicing (`@WebFluxTest`, `@DataMongoTest`, etc.) and Reactor’s `StepVerifier` from `reactor-test`.
- Ensure new behavior includes both success and failure-path assertions; aim to keep coverage for critical services near existing levels.
- Run `./mvnw test` locally before raising a pull request and attach failing logs if CI reproduces issues.

## Commit & Pull Request Guidelines
- Adopt an imperative, concise subject using Conventional Commit prefixes (`feat:`, `fix:`, `refactor:`); avoid one-letter messages seen in older history.
- Group related changes per commit and document reasoning in the body when touching complex flows like `AiServiceImpl`.
- Pull requests should include: summary of the change, testing evidence (`./mvnw test` output or screenshots), and links to tracked issues.
- Request at least one review for service-level changes and confirm configuration impacts (ports, timeouts, keys) in the PR description.

## Configuration & Security Tips
- Never commit real API keys; `application.yaml` reads `AI_API_KEY` from the environment—supply it via `.env`, shell exports, or CI secrets.
- Document new config flags inside `application.yaml` with inline comments and note defaults in the PR.
- When adding tools, register them in `ToolRegistry` and update any sample payloads in `src/main/resources` to prevent drift.
