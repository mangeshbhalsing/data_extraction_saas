# Data Extraction SaaS — Spring Boot API

Java 21 and Spring Boot 4.1.2 modular-monolith backend, following the supplied HLD/LLD with a local file-backed H2 database. The API has session-based Spring Security, organization-scoped document workflows, an in-process asynchronous worker, document analysis for PDF/Word/Excel/CSV, extraction review, analytics, and CSV exports.

## Open in IntelliJ IDEA

1. Choose **File → Open** and select `C:\Mangesh\data_extraction_saas` or its `pom.xml`.
2. Set the Project SDK to `C:\Mangesh\software\jdk-21.0.12.1_windows-x64_bin\jdk-21.0.12.1`.
3. Let IntelliJ import the Maven project and resolve dependencies.
4. To use the local demo accounts, set `APP_SEED_DEMO_USERS=true` in the run configuration environment variables. The default database file is created at `C:\Mangesh\data_extraction_saas\data\extractiq.mv.db` when the working directory is the project root. Then run `DataExtractionSaasApplication`.

The application listens on port 8080. `http://localhost:8080/` returns public API information, `http://localhost:8080/actuator/health` returns operational health, and the Angular UI runs at `http://localhost:4200/`.

## Implemented API

All JSON endpoints are under `/api/v1`.

| Method | Endpoint | Purpose |
| --- | --- | --- |
| `GET` | `/auth/csrf` | Initialize CSRF cookie for browser clients |
| `POST` | `/auth/register` | Register a user and private workspace |
| `POST` | `/auth/login` | Start an authenticated server session |
| `POST` | `/auth/logout` | End the current session |
| `GET` | `/users/me` | Current authenticated user |
| `GET` | `/admin/users` | Admin-only directory of accounts across workspaces |
| `PATCH` | `/admin/users/{userId}/access` | Admin-only role and sign-in access update |
| `POST` | `/documents` | Create an upload session |
| `PUT` | `/documents/{documentId}/content` | Upload raw file bytes to the returned local upload URL |
| `POST` | `/documents/{documentId}/confirm-upload` | Confirm an uploaded document |
| `GET` | `/documents` | List documents |
| `GET` | `/documents/{documentId}` | Get document metadata |
| `GET` | `/documents/{documentId}/status` | Get upload status |
| `DELETE` | `/documents/{documentId}` | Delete document metadata and content |
| `POST` | `/documents/{documentId}/extractions` | Queue an extraction job (`Idempotency-Key` supported) |
| `GET` | `/jobs/{jobId}` | Get job status and progress |
| `POST` | `/jobs/{jobId}/retry` | Retry a failed job (up to three attempts) |
| `GET` | `/extractions` | List current-workspace extraction results |
| `GET/PATCH` | `/extractions/{id}/results` | Read or correct extracted data |
| `POST` | `/extractions/{id}/validate` | Validate extracted data |
| `POST` | `/extractions/{id}/approve` | Approve validated data |
| `GET` | `/records` | Search approved structured records |
| `GET` | `/analytics/summary`, `/analytics/charts` | Workspace metrics from persisted data |
| `POST/GET` | `/exports` | Create/list CSV exports |
| `GET` | `/exports/{id}/download` | Download an organization-owned export |
| `GET` | `/organization`, `/organization/members` | Current workspace and members |
| `GET` | `/usage` | Workspace usage counts and limits |
| `GET` | `/audit` | Admin-only recent mutation audit events |

All endpoints above are under `/api/v1`. Except registration, login, CSRF initialization, and health, API requests require an authenticated session. Unsafe requests require the `X-XSRF-TOKEN` header paired with the CSRF cookie.

## Local demo accounts

| Role | Email | Password |
| --- | --- | --- |
| Super Admin | `superadmin@extractiq.local` | `SuperAdmin@ChangeMe123!` |
| Admin | `admin@extractiq.local` | `Admin@ChangeMe123!` |
| User | `user@extractiq.local` | `User@ChangeMe123!` |

Demo account seeding is disabled by default. Set `APP_SEED_DEMO_USERS=true` in the backend run configuration before startup to create these demo accounts and their workspaces in H2. These are local development credentials only; change or remove them before deploying the application. Registering users creates an individual workspace in H2.

The **Users & access** screen is available to administrators from the navigation. Administrators can assign the `USER` or `ADMIN` role and suspend/reactivate account sign-in. Admin changes apply to active sessions on their next request, and a suspended account is signed out immediately. The final active administrator cannot be disabled or demoted, and administrators cannot revoke their own access. These operations are also enforced by the API.

### H2 connection settings

The application uses the embedded H2 driver in file mode, with automatic local server mode enabled so an H2 client can also connect while the application is running.

| Setting | Value |
| --- | --- |
| JDBC URL | `jdbc:h2:file:C:/Mangesh/data_extraction_saas/data/extractiq;AUTO_SERVER=TRUE` |
| Driver | `org.h2.Driver` |
| User | `sa` |
| Password | blank by default |

The URL, username, and password can be overridden with `DB_URL`, `DB_USERNAME`, and `DB_PASSWORD`. H2 sessions and application records share this database. Keep the database local and configure a strong password before exposing the application beyond development.

### Create an upload session

```http
POST /api/v1/documents
Content-Type: application/json

{"filename":"invoice-001.pdf","contentType":"application/pdf","sizeBytes":245760}
```

Returns `201 Created` with a `documentId`, a relative `uploadUrl`, and `expiresInSeconds`. Upload the exact declared number of bytes to that URL using `PUT` and the same `Content-Type`. Supported content types are PDF, DOC/DOCX, XLS/XLSX, JPEG, PNG, and CSV. The default maximum is 20 MiB.

### Start extraction

```http
POST /api/v1/documents/{documentId}/extractions
Content-Type: application/json
Idempotency-Key: invoice-001-run-1

{"documentType":"INVOICE","outputFormat":"STRUCTURED_JSON"}
```

Returns `202 Accepted` with `jobId`, `status: QUEUED`, `progress: 0`, and `statusUrl`. Repeating the request with the same key for the same document returns the original job. The local worker parses CSV, XLS/XLSX worksheets, DOCX tables/text, DOC text, and text-based PDF content. Results include a deterministic extractive summary, extracted tables, and bar-chart data derived from numeric columns. Scanned PDFs need OCR and return `NO_EXTRACTABLE_CONTENT` when no readable text is found. Image OCR and external AI providers are not configured. Use `documentType: DOCUMENT_ANALYSIS` for the new analysis flow.

## Current implementation boundary

Accounts, organizations, documents and bytes, jobs, results, exports, sessions, and audit events are persisted in local H2. The worker itself remains in-process; a job interrupted during a restart may need to be retried. Authentication uses BCrypt password hashes, HTTP-only server session cookies, CSRF cookie/header checks, session ID rotation on login, and organization scoping in document/job/result/export APIs. H2 file mode is for local development, not a production multi-instance database. Analysis runs locally and does not call an external AI service. PDF text extraction is not OCR; scanned PDFs/images, a durable queue/worker, object storage, and production deployment settings remain to be connected. Set `APP_COOKIE_SECURE=true` behind HTTPS before production use. Error responses use a consistent JSON shape with timestamp, status, code, message, and path.

Future infrastructure work can replace local H2 with PostgreSQL and managed migrations, object storage, a durable queue/worker, and configured OCR/AI providers while keeping these API modules and contracts.

## Tests

Run `mvn test` from the project root, or run the Maven `test` lifecycle from IntelliJ's Maven tool window. The current suite covers upload-session validation and expiry, supported file types and size limits, content-type and content-length checks, upload/delete state, extraction job lifecycle, idempotency, structured errors, and the end-to-end HTTP flow.
