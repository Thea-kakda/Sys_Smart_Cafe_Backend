# Backend Step 3: Store + Customer Profile + CUSTOMER Role

Overlay this ZIP onto the merged Step 1 and Step 2 project.

## Implements

- Public Store list and Store details
- Customer Profile read and update
- Store and Customer JPA entities
- Store and Customer repositories
- Organization and Customer Flyway schemas
- CUSTOMER and initial Staff role seeds
- Default development Store seed
- Customer registration initialization service

## Endpoints

- `GET /api/v1/stores`
- `GET /api/v1/stores/{storeId}`
- `GET /api/v1/customers/me`
- `PUT /api/v1/customers/me`

## Required Step 2 patch

Apply the small `AUTHENTICATION_SERVICE_PATCH.md` change so Customer Profile and CUSTOMER role assignment are created during registration.

## Migration warning

`V002` assumes Step 2 identity migration is `V001`. If your real Flyway history differs, choose the next unused version before first application. Never rename an applied migration.

## Security note

The default BKK1 Store is a development seed. Replace it with controlled environment-specific seed data before production.
