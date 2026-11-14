# TaskFlow Pro

A REST API for tracking work across projects. Admins create projects and name a manager; managers
build their team; team members create and update tasks, link related tasks together and attach files.
Access is enforced by role and by project membership.

Built with **Java 21**, **Spring Boot 4**, **Spring Security (cookie sessions with CSRF protection)**,
**Spring Data JPA**, **PostgreSQL**, **Flyway** and **Swagger UI**, with integration tests that run
against a real PostgreSQL in Docker.

## Features

- **Authentication:** register, log in and log out; server-side sessions; BCrypt password hashing
- **Account security:** change your own password; failed logins are rate limited
- **Swagger UI:** an interactive page for trying every endpoint from the browser
- **Roles:** `ROLE_ADMIN`, `ROLE_MANAGER`, `ROLE_EMPLOYEE`, with admin endpoints to manage them
- **Projects:** a manager plus a group of employees; only members can see a project
- **Tasks:** title, description, status, priority, due date, assignee, and comments
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
git clone https://github.com/YOUR-USERNAME/taskflow-pro.git
cd taskflow-pro
cp .env.example .env        # then edit .env and set DB_PASSWORD
```

### 2. Start PostgreSQL

```bash
docker compose up -d
```

### 3. Set the environment variables

The app reads its database password from the environment and refuses to start without it.

| Variable | Purpose |
|---|---|
| `DB_PASSWORD` | PostgreSQL password. Must match the one in `.env` |
| `DB_URL`, `DB_USERNAME` | Optional. Default to the local Docker database |
| `STORAGE_DIR` | Optional. Where attachments are saved (default `./data/uploads`) |
| `SESSION_COOKIE_SECURE` | Optional. Set to `true` when serving over HTTPS (default `false`) |

In IntelliJ, put them in the run configuration. From a terminal (PowerShell shown):

```powershell
$env:DB_PASSWORD = "the-password-from-.env"
./mvnw spring-boot:run
```

Then open **http://localhost:8080/swagger-ui.html**.

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

Endpoints other than the public auth ones need a logged-in session (the `JSESSIONID` cookie).
`POST`, `PUT` and `DELETE` requests must also send the CSRF token (see below).

| Method | Path | Who can call it |
|---|---|---|
| POST | `/api/auth/register` | anyone |
| GET | `/api/auth/csrf` | anyone (issues the CSRF cookie) |
| POST | `/api/auth/login` | anyone |
| POST | `/api/auth/logout` | logged-in user |
| GET | `/api/auth/me` | any logged-in user |
| PUT | `/api/auth/password` | logged-in user (needs the current password) |
| GET | `/api/admin/users` | admin |
| PUT | `/api/admin/users/{id}/roles` | admin |
| POST | `/api/projects` | admin (names the manager) |
| GET | `/api/projects`, `/api/projects/{id}` | project participants (admins: all) |
| PUT | `/api/projects/{id}` | project manager, admin (only an admin can change the manager) |
| DELETE | `/api/projects/{id}` | admin (removes its tasks, comments and files too) |
| PUT / DELETE | `/api/projects/{id}/members/{userId}` | project manager, admin |
| POST / GET | `/api/projects/{id}/tasks` (`?status=`) | project participants |
| GET | `/api/tasks/{id}` | project participants |
| PUT | `/api/tasks/{id}` | manager, admin, assignee, creator |
| DELETE | `/api/tasks/{id}` | manager, admin |
| POST / DELETE | `/api/tasks/{id}/links[/{linkId}]` | same as task edit |
| POST | `/api/tasks/{id}/attachments` (multipart, part `file`) | project participants |
| GET | `/api/tasks/{id}/attachments` | own uploads; manager and admin see all |
| GET / DELETE | `/api/attachments/{id}` | uploader, project manager, admin |
| POST / GET | `/api/tasks/{id}/comments` | project participants |
| PUT | `/api/comments/{id}` | the comment's author |
| DELETE | `/api/comments/{id}` | author, project manager, admin |

The project, task, comment and admin-user lists are paginated: add `?page=0&size=20` (pages start at 0,
size is capped at 100). They return `content` plus `page`, `size`, `totalElements` and `totalPages`,
ordered by id (oldest first).

### Trying it: Swagger UI (easiest)

1. Open `http://localhost:8080/swagger-ui.html`.
2. Expand `POST /api/auth/login`, click **Try it out**, enter your username and password, and **Execute**.
3. Your browser now holds the session cookie, so every other endpoint on the page runs as you.
   Swagger UI copies the CSRF token into the right header for writes automatically.

### Trying it: curl

A cookie jar holds the session, and writes must echo the CSRF cookie in a header:

```bash
# 1. get the CSRF cookie
curl -c jar.txt http://localhost:8080/api/auth/csrf
TOKEN=$(grep XSRF-TOKEN jar.txt | awk '{print $7}')

# 2. log in (stores the session cookie too)
curl -b jar.txt -c jar.txt -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" -H "X-XSRF-TOKEN: $TOKEN" \
  -d '{"username":"ross","password":"your-password"}'

# 3. reads need only the cookies; writes also need the header
curl -b jar.txt http://localhost:8080/api/projects
```

## Running the tests

```bash
./mvnw test
```

Docker must be running. The tests start a throwaway PostgreSQL with Testcontainers, apply the real
Flyway migrations, and exercise the API end to end:

- an admin creates a project with a manager, the manager adds a member, and the member creates a task
- a member uploads a file; another member cannot see or download it, but the project manager can
- session, CSRF and logout rules; a demoted admin loses access without logging in again
- login lockout after repeated failures, and changing a password
- who can edit and delete tasks, typed task links, and removing a member unassigning their tasks
- editing and deleting projects (including cleaning up files on disk), comments, and pagination

## Design decisions

- **Cookie sessions instead of JWT.** The planned UI is server-rendered pages in the same app, where
  the browser sends the session cookie automatically, with no token handling in JavaScript. The
  trade-off is that curl, Postman and mobile clients are clumsier to use (see "Trying it: curl").
- **CSRF protection is on**, because cookies are sent automatically. The token travels in a readable
  `XSRF-TOKEN` cookie that scripts echo back in the `X-XSRF-TOKEN` header. Registration is the only
  exempt write, since it has no session to hijack. Session cookies are `HttpOnly` and `SameSite=Lax`.
- **Login rate limiting.** 5 failed attempts for a username (or 20 from one IP) lock further attempts
  for the rest of a 15-minute window, even with the right password (HTTP 429 with `Retry-After`).
  Counters are in memory, so they reset on restart and aren't shared between servers; a multi-server
  deployment would need Redis. The same limit applies to wrong current-password guesses when changing a
  password.
- **Password changes need the current password**, so a stolen session alone can't take over an account.
- **Pagination is capped and sorted by id only**, so a client can't request unbounded pages or sort by an
  arbitrary column.
- **A new session id is issued at login** (session fixation defence).
- **Roles are re-read from the database on every request**, not trusted from the session, so a
  demoted or deleted user loses access immediately. Covered by a test.
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

- A server-rendered web UI (Thymeleaf) alongside the API
- Ending a user's other sessions when their password changes
- Searching and sorting task lists
- A Dockerfile for the app itself

## License

Choose a licence (for example MIT) and add a `LICENSE` file, or remove this section.
