# WealthOS MVP v1.0

A local, modular-monolith expense tracker: Spring Boot API, PostgreSQL, Flyway, and a Next.js/TypeScript dashboard.

## Current environment

- Docker and Docker Compose are installed.
- Java 17 is installed; the backend targets Java 21.
- Maven, Node.js/npm, and the PostgreSQL CLI are not currently installed. The database runs in Docker, so `psql` is optional.

Install a Java 21 JDK, Maven, and Node.js LTS before starting the app. On macOS with Homebrew: `brew install openjdk@21 maven node` (then follow Homebrew's PATH instructions for Java 21). Spring Boot 3.5.16 is pinned in `backend/pom.xml`.

## Start locally

1. Start PostgreSQL: `docker compose up -d db`
2. In one terminal: `cd backend && mvn spring-boot:run`
3. In another terminal: `cd frontend && npm install && npm run dev`
4. Open <http://localhost:3000>. The API is at <http://localhost:8080/api>.

The local database defaults are for development only and are in `compose.yaml`. Do not reuse them outside a local machine.

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

