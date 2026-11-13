# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Coparent backend: the API for a co-parenting app (custody calendar, events, custody exchange requests, immutable messaging, push notifications). Spring Boot 4.1 on Java 25, MySQL, Flyway, Spring Security (JWT resource server), JPA, MapStruct, Lombok. Base package `com.vynk.coparent_backend`. The functional spec is `docs/cahier-des-charges-priorite-1.md` (French) and is the reference for every feature.

## Commands

Run from this directory, always through the wrapper:

```bash
./mvnw clean verify                      # build + all tests
./mvnw test -Dtest=CoparentBackendApplicationTests            # one test class
./mvnw test -Dtest=CoparentBackendApplicationTests#contextLoads  # one test method
./mvnw spring-boot:run                   # run the app
```

There is no linter or formatter configured.

## Environment gotchas

- **JDK**: `pom.xml` targets Java 25, so the build needs JDK 25+ (JDK 26 is installed at `/Library/Java/JavaVirtualMachines/jdk-26.jdk`). A shell still on JDK 17 fails to compile.
- **Maven version is pinned to 3.9.x** in `.mvn/wrapper/maven-wrapper.properties` on purpose: IntelliJ's Maven importer crashes with Maven 3.10.0 (`Guice/MissingImplementation: No implementation for ArtifactHandlerManager`). Do not bump it to 3.10.
- **Database**: tests use `@SpringBootTest` and start the full context, so Flyway connects to a real local MySQL. Without MySQL running, every test fails.
- **JWT secret**: `app.auth.jwt-secret` (env `JWT_SECRET`, at least 32 bytes) is required at startup; the local value is in `config/application.yaml`. Tests set their own secret and disable the Have I Been Pwned check via `@SpringBootTest(properties = ...)`.
- **Email**: by default (`app.mail.mode=smtp`) emails go to the SMTP server in `spring.mail.*`, which defaults to Mailpit on `localhost:1025`. Run `mailpit` (installed with `brew install mailpit`) and read the emails at http://localhost:8025. With `MAIL_MODE=log` nothing is sent and codes/links are written to the app log instead. In production set `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`, plus `spring.mail.properties.mail.smtp.auth=true` and `spring.mail.properties.mail.smtp.starttls.enable=true`.
- **IDE background build**: an IDE that auto-builds into `target/classes` (without MapStruct processing) can overwrite Maven's output while tests run; the symptom is `No qualifying bean of type '…Mapper'`, or `NoClassDefFoundError` on a mapper type. Turn off the IDE's automatic build or close it, then `./mvnw clean test`.
- **Credentials**: `src/main/resources/application.yaml` reads `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` (default URL `jdbc:mysql://localhost:3306/coparent?createDatabaseIfNotExist=true`). Local values live in `config/application.yaml`, which Spring Boot loads automatically from the working directory and which is git-ignored. Never move credentials into `src/`.

## Architecture

Packages are organized by feature (`user/`, `auth/`, `family/`), and each feature is split into layer sub-packages: `controller/`, `service/`, `repository/`, `entity/`, `mapper/`, `dto/`. New features follow the same shape.

- `user/`: `User` (+ `UserIdentity` for future Apple/Google sign-in), `UserService`, `GET /api/v1/users/me`.
- `auth/`: `/api/v1/auth/**` (signup, login, refresh, logout, email verify/resend, password forgot/reset). `service/` holds `AuthService` (the rules), `TokenService` (JWTs and opaque tokens), `PasswordPolicy` and `RateLimiter` (in-memory, single instance only); `email/` holds `EmailSender`, implemented by `SmtpEmailSender` (`@Async`; an SMTP failure is logged and never fails the request) or `LoggingEmailSender`, selected by `app.mail.mode`.
- `family/`: onboarding steps 2–5. `FamilyController` (`/api/v1/families`: create, `GET /me`, update, children, `PUT custody-schedule`, invitations) and `InvitationController` (`GET /api/v1/invitations/{token}` public preview, `POST …/accept`). Every family route goes through `FamilyAccess.requireMember`, which answers **404** to non-members, the same as for a family that does not exist. One family per user for now; at most `PARENT_A` + `PARENT_B`. A rhythm is saved as `PROPOSED` (re-saving replaces the user's own pending proposal); validation by parent B belongs to the custody feature. The invitation preview exposes only the inviter's first name, children's first names and ages, and the rhythm.
- Custody computation (`family/`): `GET /api/v1/families/{id}/custody?from=&to=` (at most 92 days) returns who has the children each night, the handovers in the range and `nextHandover` (first one after now, even outside the range). `CustodyService` picks the ACTIVE schedule, else the latest PROPOSED one (solo mode, or not yet approved), and answers 404 when there is none. `CustodyCalculator` is the pure computation: the regular rhythm repeats a night sequence from `startDate` (same sequences as the app preview; CUSTOM repeats its own letters); short holidays are split in halves (first half one night longer, `holidayFirstHalfEvenYears` first in even years, swapped in odd years, the year being the holiday's start year); summer by MONTHS (July / August) or FORTNIGHTS (1-15 / 16-end of July then August, alternating); a handover happens at `handoverTime` on the day the night's parent changes; without holiday rules the rhythm continues. Holiday nights run from the evening classes end (the day before `start`) to the night before school resumes.
- `bag/`: « Sac à préparer » for the next handover. `BagController` (`/api/v1/families/{id}/bag`: `GET`, `POST /items`, `DELETE /items/{itemId}`, `PUT`/`DELETE /items/{itemId}/check`); every call returns the whole bag (`handoverAt` in Paris time or null, `checkedCount`, `total`, items grouped by child). Items (`bag_items`) last from one handover to the next; a tick (`bag_checks`) is stored for one handover (`handover_at`, UTC instant of `CustodyService.nextHandover`), so ticks reset on their own after it. Ticking without a next handover → 409. Both parents add, tick and delete.
- `todo/`: « À traiter ». `ToDoController` (`GET /api/v1/families/{id}/to-do`) returns the open requests (`to_do_items`, `resolved_at` null, oldest first) split into `mine` (I am the `assignee`) and `theirs` (the other parent must answer), with `kind`, `subject`, first names and `requestedAt`. No feature creates rows yet (sample rows are inserted by hand); each future request (exchange, expense, rhythm approval…) adds a row and sets `resolved_at` when answered. `POST …/to-do/{itemId}/nudge` (« Relancer ») is for the parent who waits: once per Europe/Paris day (429), 409 for the assignee, 404 for an unknown or answered item; it sets `last_nudged_at` and returns the whole « À traiter ». No notification is sent yet (no push).
- School holidays: `src/main/resources/school-holidays.json` (zones A, B, C; official dates from data.education.gouv.fr, dataset `fr-en-calendrier-scolaire`, pupils' summer dates), read by `SchoolHolidays`. It covers 2025-2026 and 2026-2027: **add the next school year every year**, otherwise only the regular rhythm applies after the last listed date. Zone OTHER has no dates.
- `links/`: `AppLinksController`, public routes that open the invitation link (`<public-base-url>/invitation?token=…`) in the mobile app: `/.well-known/apple-app-site-association` (Universal Links), `/.well-known/assetlinks.json` (App Links), and the `/invitation` HTML landing page (button to `coparent://invitation?token=…`, store links, `Referrer-Policy: no-referrer`, no family data). Values in `app.links.*` (`LinksProperties`): placeholders until the real Team ID, bundle id, applicationId and signing fingerprints exist; Universal/App Links also need a public HTTPS domain.
- `config/`: `SecurityConfig` (stateless, `/api/v1/auth/**` public except `email/resend` and `email/verify`; the invitation preview and the `links/` routes are public too), typed `AuthProperties` (`app.auth.*`) and `LinksProperties` (`app.links.*`), UTC `Clock` bean.
- `common/`: `ApiException` + `GlobalExceptionHandler`, so every error is an RFC 9457 `ProblemDetail` with a neutral French message.

Auth model:
- Access token = HS256 JWT (15 min), `sub` = user UUID; controllers read it with `@AuthenticationPrincipal Jwt`.
- Refresh tokens rotate on every use and share a `familyId` per session; replaying a rotated token revokes the whole family. Password reset revokes all of the user's refresh tokens.
- Refresh tokens are 32 random bytes; only their SHA-256 hex is stored.
- Password reset uses an 8-digit code sent by email (30 min, single use) instead of the link described in spec §1. `POST /api/v1/auth/password/forgot` always answers the same way; `POST /api/v1/auth/password/reset` takes `email`, `code`, `newPassword`, `confirmPassword` and applies the password policy before checking the code. An unknown email and a wrong code both get the same 400 « Code incorrect ou expiré. ». The code is stored in `one_time_tokens` as an HMAC-SHA256 of `password-reset:userId:code` (`TokenService.hashPasswordResetCode`); `RateLimiter.Action.PASSWORD_RESET` limits requests per IP and per email, and after 5 wrong codes (`PASSWORD_RESET_FAILURE`) the code is cancelled.
- Email verification uses an 8-digit code typed in the app (15 min, single use), checked by the authenticated `POST /api/v1/auth/email/verify`. It is stored in `one_time_tokens` as an HMAC-SHA256 of `userId:code` keyed with the JWT secret (`TokenService.hashVerificationCode`), and attempts are limited by `RateLimiter.Action.EMAIL_VERIFY` (5 per 15 min per user).
- Error responses must not reveal whether an account exists (login and forgot-password answer identically).

Conventions:
- Entities: UUID ids via `@UuidGenerator(style = TIME)` mapped to `BINARY(16)`, `Instant` dates, `@Enumerated(STRING)` for MySQL `ENUM` columns, Lombok `@Getter` plus a protected no-arg constructor (no `@Data`).
- `spring.jpa.hibernate.ddl-auto=validate`: entities must match the Flyway schema exactly, or startup fails.
- Mappers are MapStruct interfaces with the Spring component model set globally (`-Amapstruct.defaultComponentModel=spring` in `pom.xml`), so no `componentModel` attribute is needed.
- Integration tests (`auth/AuthFlowIntegrationTest`) hit the local MySQL: they create users on the `@test.coparent.invalid` domain, delete only those afterwards, mock `EmailSender` to capture codes, and call `RateLimiter.reset()` before each test.

## Database migrations

Flyway migrations go in `src/main/resources/db/migration/` as `V<n>__<description>.sql` (MySQL dialect, via `flyway-mysql`). The user writes and imports migrations themselves; don't add or edit migration files unless asked. Never modify a migration that has already been applied; add a new version instead.

## Design rules from the spec

These cut across every feature, so keep them in mind when adding code:

- The backend is the single source of truth: it enforces access rights (by family and role, on every endpoint), custody computation, event visibility (private events never reach the other parent through any channel: API, WebSocket, exports) and message immutability. The app only displays and sends.
- Dates are stored in UTC and computed/returned for `Europe/Paris` (custody handovers, reminders, DST changes).
  - In migrations, date columns are `DATETIME(6)` with `DEFAULT (UTC_TIMESTAMP(6))`; never `TIMESTAMP` (ends in 2038, time-zone dependent) or `NOW()` (session local time).
  - In entities, map them to `java.time.Instant`, never `LocalDateTime`/`Date`. `hibernate.jdbc.time_zone: UTC` is set in `application.yaml`.
  - Local calendar values are not instants: a birth date is `DATE` → `LocalDate`, a custody handover time is `TIME` in Europe/Paris wall-clock time → `LocalTime` (e.g. 17:50 stays 17:50 across DST changes). Never store them as UTC. A `LocalTime` field needs `@JdbcType(LocalTimeJdbcType.class)`: otherwise `hibernate.jdbc.time_zone: UTC` converts it from the JVM zone (17:50 Paris stored as 16:50; fixed by V5 for `custody_schedules.handover_time`).
- Messages are immutable: no update/delete endpoints, protection at the database level, server timestamps, and a hash chained with the previous message.
- REST API documented with OpenAPI 3 (the app generates its types from it); real-time over authenticated WebSocket.
- No personal data in application logs or Sentry; push notification texts never include sensitive content.
