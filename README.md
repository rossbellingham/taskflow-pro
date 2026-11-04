# TaskFlow Pro

A REST API for tracking work across projects. Admins create projects and name a manager; managers
build their team; team members create and update tasks, link related tasks together and attach files.
Access is enforced by role and by project membership.

Built with **Java 21**, **Spring Boot 4**, **Spring Security (stateless JWT)**, **Spring Data JPA**,
**PostgreSQL** and **Flyway**, with integration tests that run against a real PostgreSQL in Docker.

## Features

- **Authentication:** register and log in; short-lived JWT access tokens; BCrypt password hashing
- **Roles:** `ROLE_ADMIN`, `ROLE_MANAGER`, `ROLE_EMPLOYEE`, with admin endpoints to manage them
- **Projects:** a manager plus a group of employees; only members can see a project
- **Tasks:** title, description, status, priority, due date, assignee
- **Typed task links:** `BLOCKS`, `DUPLICATES` and `RELATES_TO`, within one project
- **Attachments:** files stored on disk; employees access only their own uploads, while the project
  manager and admins can access every file in the project
- **Schema migrations** with Flyway; Hibernate only validates the schema, it never changes it

## Getting started

### Prerequisites

- JDK 21
- Docker (for the database and for running the tests)

### 1. Clone and configure

```bash
git clone https://github.com/rossbellingham/taskflow-pro.git
cd taskflow-pro
cp .env.example .env        # then edit .env and set DB_PASSWORD
```

### 2. Start PostgreSQL

```bash
docker compose up -d
```

### 3. Set the environment variables

The app reads its secrets from the environment and refuses to start without them.

| Variable | Purpose |
|---|---|
| `DB_PASSWORD` | PostgreSQL password. Must match the one in `.env` |
| `JWT_SECRET` | Base64 signing key, at least 32 bytes. Generate with `openssl rand -base64 48` |
| `DB_URL`, `DB_USERNAME` | Optional. Default to the local Docker database |
| `CORS_ORIGINS` | Optional. Allowed browser origins (default `http://localhost:3000`) |
| `STORAGE_DIR` | Optional. Where attachments are saved (default `./data/uploads`) |

In IntelliJ, put them in the run configuration. From a terminal (PowerShell shown):

```powershell
$env:DB_PASSWORD = "the-password-from-.env"
$env:JWT_SECRET  = "<output of openssl rand -base64 48>"
./mvnw spring-boot:run
```

Flyway creates the schema on first start.

### 4. Create the first admin

Registration only ever creates employees, so the first admin is made in the database. Register a
normal account through the API, then promote it:

```bash
docker exec -it taskflow-db psql -U taskflow -d taskflow
```

```sql
INSERT INTO user_roles (user_id, role)
SELECT id, 'ROLE_ADMIN' FROM users WHERE lower(username) = 'your-username';
```

From then on, an admin can manage roles through the API.

## API overview

All endpoints except register and login need the header `Authorization: Bearer <token>`.

| Method | Path | Who can call it |
|---|---|---|
| POST | `/api/auth/register` | anyone |
| POST | `/api/auth/login` | anyone |
| GET | `/api/auth/me` | any logged-in user |
| GET | `/api/admin/users` | admin |
| PUT | `/api/admin/users/{id}/roles` | admin |
| POST | `/api/projects` | admin (names the manager) |
| GET | `/api/projects`, `/api/projects/{id}` | project participants (admins: all) |
| PUT / DELETE | `/api/projects/{id}/members/{userId}` | project manager, admin |
| POST / GET | `/api/projects/{id}/tasks` (`?status=`) | project participants |
| GET | `/api/tasks/{id}` | project participants |
| PUT | `/api/tasks/{id}` | manager, admin, assignee, creator |
| DELETE | `/api/tasks/{id}` | manager, admin |
| POST / DELETE | `/api/tasks/{id}/links[/{linkId}]` | same as task edit |
| POST | `/api/tasks/{id}/attachments` (multipart, part `file`) | project participants |
| GET | `/api/tasks/{id}/attachments` | own uploads; manager and admin see all |
| GET / DELETE | `/api/attachments/{id}` | uploader, project manager, admin |

Example:

```bash
# log in
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"ross","password":"your-password"}'
# -> {"accessToken":"...","tokenType":"Bearer","expiresInSeconds":900}

# call a protected endpoint
curl http://localhost:8080/api/projects -H "Authorization: Bearer <accessToken>"
```

## Running the tests

```bash
./mvnw test
```

Docker must be running. The tests start a throwaway PostgreSQL with Testcontainers, apply the real
Flyway migrations, and exercise the API end to end:

- an admin creates a project with a manager, the manager adds a member, and the member creates a task
- a member uploads a file; another member cannot see or download it, but the project manager can

## Design decisions

- **Stateless JWT.** No server-side sessions, so the API suits a separate frontend or mobile client.
  Access tokens are short-lived (15 minutes). CSRF protection is off because no cookies are used.
- **Roles are checked against the database on every request**, not trusted from the token, so a
  demoted or deleted user loses access immediately.
- **Flyway owns the schema.** Hibernate runs with `ddl-auto: validate`, so a mismatch between an
  entity and a migration fails at startup instead of silently drifting.
- **404 instead of 403 for resources you can't see.** Project, task and attachment ids can't be
  probed to find out what exists.
- **Files live on disk under random UUID names.** The user-supplied filename is only metadata, which
  rules out path traversal and name clashes. Downloads are always sent as attachments with a
  neutral content type, so an uploaded HTML or SVG file can't execute in a browser.
- **Generic login errors.** Wrong password and unknown user return the same response, so accounts
  can't be enumerated.
- **Self-registration grants the lowest role only.** Elevated roles are assigned by an admin.
- **Responses use DTOs**, never entities, so password hashes can't leak.

## Roadmap

- Refresh tokens and token revocation on logout
- Rate limiting on login
- Pagination on list endpoints
- Project deletion and task comments
- More integration tests for the edit and delete rules

## License

Choose a licence (for example MIT) and add a `LICENSE` file, or remove this section.
