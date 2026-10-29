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

## Browse the database visually

Run `docker compose up -d adminer`, then open <http://localhost:8081>. This optional Adminer database viewer is bound to your Mac's loopback interface.

Choose PostgreSQL and sign in with server `db`, username `wealthos`, password `wealthos_local_only`, and database `wealthos`. Open `transactions` and choose **Select data** to see the saved entries. The `categories` table contains their category details. Stop the viewer with `docker compose stop adminer`.

## Setup checks

The backend compiled and Flyway applied the first migration. Transaction create/read/update/delete, category creation/deletion, dashboard totals, CSV import, and repeated-import duplicate detection passed local API checks using temporary records, which were removed afterward. The frontend production build passed, and npm reported zero known vulnerabilities after updating dependencies.

## MVP scope

- Create, view, edit, and delete income/expense transactions.
- Manage categories and view monthly income, spending, balance, and category totals.
- Import CSV files using the headers `date,description,amount,type,category,account`. Amounts are positive; type is `INCOME` or `EXPENSE`. Duplicate rows are skipped using a stable SHA-256 fingerprint.
- Flyway owns database schema changes. The application is organized by domain modules inside one backend deployment.

CSV upload and transaction APIs are local-only features. No bank, Gmail, or other account connection is configured.

## Gmail transaction tracking

WealthOS now has its own **Connect Gmail** flow and background Gmail sync. Configure a Google Desktop OAuth client, then authorize the expected finance account in the dashboard. The app requests read-only access, filters bank senders, encrypts refresh tokens locally, and shares duplicate detection and the review queue with SMS capture. See [Gmail setup](docs/gmail-setup.md) for the one-time Google project setup and local credentials import. A Gmail integration connected in the chat does not authorize this separate application.

## iPhone SMS capture

The **Automatic SMS capture** panel previews alerts, shows collector health, and provides a review queue. Opt-in collection from approved bank senders in Mac Messages is implemented but requires explicit consent and macOS access before it can run. See [the setup and privacy guide](docs/iphone-sms-capture.md). No actual Messages access is required to try the parser with the fictional example.

The API and database ports are bound to this Mac's loopback interface. The collector authenticates with a local token, records only complete INR payments automatically when enabled, and sends uncertain candidates for review. Raw SMS text is not persisted. Source message IDs and bank references prevent repeated alerts from double-counting; payments with different references remain distinct.

## API

- `GET/POST /api/transactions`, `GET/PUT/DELETE /api/transactions/{id}`
- `GET/POST /api/categories`, `PUT/DELETE /api/categories/{id}`
- `POST /api/imports/csv` (multipart field `file`)
- `GET /api/dashboard?month=2026-10`
- `GET /api/capture/status`, `GET /api/capture/alerts`
- `POST /api/capture/preview`, `POST /api/capture/alerts`
- `POST /api/capture/alerts/{id}/accept`, `POST /api/capture/alerts/{id}/ignore`
- `POST /api/capture/incoming`, `POST /api/capture/heartbeat` (collector token required)
- `GET /api/gmail/status`, `POST /api/gmail/connect`, `POST /api/gmail/sync`, `POST /api/gmail/disconnect`
- `GET /api/gmail/authorize`, `GET /api/gmail/oauth/callback` (browser authorization flow)

