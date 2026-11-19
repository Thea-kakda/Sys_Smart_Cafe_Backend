# MinIO Object Storage

## 1. What MinIO is

[MinIO](https://min.io/) is a self-hosted, S3-compatible object store. It holds
binary objects (images, PDFs, generated documents) under opaque *object keys*
rather than a traditional filesystem tree.

## 2. Why Smart Coffee uses MinIO

Product / category images, user avatars, invoices, receipts and documents are
binaries. PostgreSQL stores only their **metadata and object key**; the bytes
live in MinIO. This keeps the database small, makes files independently
scalable, and lets us serve them through short-lived presigned URLs instead of
exposing a public bucket.

```
                    Smart Coffee Frontend
                             |
                             v
                    Spring Boot API  ──► controls upload / download / delete
                             |              and signs every read URL
                 +-----------+-----------+
                 |                       |
                 v                       v
            PostgreSQL                  MinIO
        (metadata / objectKey)     (actual binary objects)
```

## 3. Endpoints

| Purpose        | Local (host dev)      | Docker-to-Docker  |
|----------------|-----------------------|-------------------|
| S3 API         | `http://127.0.0.1:9000` | `http://minio:9000` |
| Web Console    | `http://127.0.0.1:9001` | `http://minio:9001` |

Console / API ports are defined in `compose.yaml` and are **not** changed here.

## 4. Bucket

The application owns **one private bucket**:

```
smart-coffee-storage
```

There is no multi-bucket design: callers address objects **by key only**; the
bucket is fixed by configuration and cannot be chosen by the frontend.

## 5. Storage structure (object-key prefixes)

MinIO has no real folders — these are key **prefixes**:

```
smart-coffee-storage/
├── products/       # product images:        products/{uuid}-{safeFilename}
├── categories/     # category images:       categories/{uuid}-{safeFilename}
├── users/          # user avatars:          users/{uuid}-{safeFilename}
├── invoices/       # generated invoices:    invoices/{orderId}/{filename}
├── receipts/       # generated receipts:    receipts/{receiptId}.txt
└── documents/      # misc documents:        documents/{uuid}-{safeFilename}
```

Each prefix is materialized with an idempotent `.keep` marker object so it is
visible in the MinIO Console. No OS folders are created for these paths.

## 6. Environment variables

| Variable          | Meaning                          | Local default        |
|-------------------|----------------------------------|----------------------|
| `MINIO_ENDPOINT`  | S3 API endpoint                  | `http://localhost:9000` |
| `MINIO_ACCESS_KEY`| Access key                       | `minioadmin`         |
| `MINIO_SECRET_KEY`| Secret key                       | `minioadmin`         |
| `MINIO_BUCKET`    | The single private bucket        | `smart-coffee-storage` |

`minioadmin / minioadmin` are **LOCAL DEVELOPMENT ONLY**. For any shared or
production environment you **must** change `MINIO_ROOT_USER` /
`MINIO_ROOT_PASSWORD` (see `compose.yaml`) and supply strong credentials
externally. Never commit real secrets; `.env.example` holds placeholders only.

> Do **not** expose `MINIO_SECRET_KEY` / `MINIO_ROOT_PASSWORD` to any frontend
> (React / Next.js / Vite). They must never appear in `NEXT_PUBLIC_*` /
> `VITE_*` variables. The browser talks only to the Spring Boot API.

## 7. Starting MinIO

MinIO is a service in `compose.yaml` with a **persistent named volume**
(`minio_data:/data`), so recreating the container does not drop objects:

```bash
cd BACKEND/smart-coffee-backend
docker compose up -d minio
```

When you run the backend via `spring-boot-docker-compose`, `compose.yaml` is
started automatically (lifecycle `start-only`).

## 8. Accessing the console

Open `http://127.0.0.1:9001` and sign in with the `MINIO_ROOT_USER` /
`MINIO_ROOT_PASSWORD` values (local dev: `minioadmin` / `minioadmin`). You will
see the `smart-coffee-storage` bucket and its prefixes once the backend has
started (see §10).

## 9. How uploads work

1. Frontend sends a file to a Spring Boot API (authenticated + RBAC enforced by
   the application's existing security model).
2. The backend **never trusts a client-supplied bucket or full object key**. It
   sanitizes the filename and generates a safe key of the form
   `{allowed-prefix}{generated-id}-{safeFilename}`.
3. `FileValidator` checks content type + size against a configurable allowlist
   **before** anything is written.
4. `MinioObjectStorageService` writes the object into `smart-coffee-storage`.
5. PostgreSQL stores the **object key** (and metadata) — never a URL, never a
   credential, never the bucket inside the key.

## 10. Startup provisioning (idempotent)

On boot, `MinioBucketProvisioner` runs:

1. connects via the existing `MinioClient` (`config/MinioConfig.java`),
2. checks whether `smart-coffee-storage` exists,
3. creates it **only if missing**,
4. creates the `.keep` markers **only if missing**.

It is safe to run repeatedly, never deletes existing objects, and never fails
merely because the bucket already exists. If MinIO is temporarily unreachable,
provisioning logs a warning and startup continues — connectivity is surfaced by
the health endpoint rather than crashing the whole API.

## 11. Private bucket / security model

The bucket stays **PRIVATE**. There is no anonymous read/write policy. Reads
happen one of two ways:

- the backend streams the object itself (`download`), or
- the backend issues a **short-lived presigned GET URL** for direct, time-boxed
  browser access.

Catalog product/category images (`app.catalog`) are no longer served from a
public base URL — `DefaultCatalogImageUrlResolver` now returns presigned URLs
(configurable via `app.catalog.image-url-ttl-seconds`, default 900s / 15 min).
Presigning is computed locally, so resolving many images in one response does
**not** cause N+1 calls to MinIO.

Path security is enforced centrally by `StoragePathPolicy`, which rejects `../`
traversal, absolute paths, backslashes and null bytes, and confines every key to
the allowed prefix set (`app.storage.allowed-prefixes`).

## 12. Health check

A MinIO `HealthIndicator` (`infrastructure/observability/MinioHealthIndicator`,
bean name `minio`) reports availability via a single lightweight
`bucketExists` probe when `/actuator/health` is hit. It performs no per-request
storage checks and never logs credentials.

## 13. Configuration reference (`application.yml`)

```yaml
app:
  minio:
    endpoint: ${MINIO_ENDPOINT:http://localhost:9000}
    access-key: ${MINIO_ACCESS_KEY:minioadmin}
    secret-key: ${MINIO_SECRET_KEY:minioadmin}
    bucket: ${MINIO_BUCKET:smart-coffee-storage}
  storage:
    allowed-prefixes: [products/, categories/, users/, invoices/, receipts/, documents/]
    allowed-content-types: [image/jpeg, image/png, image/webp, application/pdf, text/plain]
    max-file-size-bytes: ${STORAGE_MAX_FILE_SIZE_BYTES:10485760}
    signed-url-ttl-seconds: ${STORAGE_SIGNED_URL_TTL_SECONDS:900}
  catalog:
    image-url-ttl-seconds: ${CATALOG_IMAGE_URL_TTL_SECONDS:900}
```

## 14. Manual steps

None required for local development: the bucket and prefixes are provisioned
automatically on startup. In **production**, set strong
`MINIO_ROOT_USER` / `MINIO_ROOT_PASSWORD` (and `MINIO_ACCESS_KEY` /
`MINIO_SECRET_KEY` for a non-root service user), supply an HTTPS
`MINIO_ENDPOINT`, and keep the bucket private.
