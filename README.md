# WealthOS MVP v1.0

A local, modular-monolith expense tracker: Spring Boot API, PostgreSQL, Flyway, and a Next.js/TypeScript dashboard.

## Development environment

- Java 21, Maven, Node.js/npm, Docker, and Docker Compose are installed and verified on this Mac.
- Java 21 is selected using `JAVA_HOME` and `PATH` in `~/.zshrc`. Existing terminal windows can load it with `source ~/.zshrc`.
- PostgreSQL runs in Docker, so the local `psql` CLI is optional.

For a new machine, install a Java 21 JDK, Maven, Node.js LTS, and Docker Desktop before starting the app. Spring Boot 3.5.16 is pinned in `backend/pom.xml`; the frontend uses Next.js 16 with a checked-in npm lockfile.

## Start locally

1. Start PostgreSQL: `docker compose up -d db`
2. In one terminal: `cd backend && mvn spring-boot:run`
3. In another terminal: `cd frontend && npm ci && npm run dev -- --hostname localhost`
4. Open <http://localhost:3000>. The API is at <http://localhost:8080/api>.

The local database defaults are for development only and are in `compose.yaml`. Do not reuse them outside a local machine.

Keep Docker Desktop running while using the app. If the API cannot connect to PostgreSQL, restart the database with `docker compose up -d --wait db`.

## Setup checks

The backend compiled and Flyway applied the first migration. Transaction create/read/update/delete, category creation/deletion, dashboard totals, CSV import, and repeated-import duplicate detection passed local API checks using temporary records, which were removed afterward. The frontend production build passed, and npm reported zero known vulnerabilities after updating dependencies.

## MVP scope

- Create, view, edit, and delete income/expense transactions.
- Manage categories and view monthly income, spending, balance, and category totals.
- Import CSV files using the headers `date,description,amount,type,category,account`. Amounts are positive; type is `INCOME` or `EXPENSE`. Duplicate rows are skipped using a stable SHA-256 fingerprint.
- Flyway owns database schema changes. The application is organized by domain modules inside one backend deployment.

CSV upload and transaction APIs are local-only features. No bank, Gmail, or other account connection is configured.

## API

- `GET/POST /api/transactions`, `GET/PUT/DELETE /api/transactions/{id}`
- `GET/POST /api/categories`, `PUT/DELETE /api/categories/{id}`
- `POST /api/imports/csv` (multipart field `file`)
- `GET /api/dashboard?month=2026-10`

