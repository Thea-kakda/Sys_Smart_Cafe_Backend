# Backend Step 2: Identity, Authentication and RBAC

Extract this overlay into the Step 1 project root.

1. Merge `pom-step2-dependencies.xml` into the existing POM dependencies.
2. Merge `application-step2.yml` into `application.yml`.
3. Set `JWT_HMAC_SECRET_BASE64` to a Base64-encoded secret of at least 32 random bytes.
4. Review the Flyway version before applying `V001__identity_auth_rbac.sql`. Rename it only if your migration history already uses V001.
5. Keep `secure(true)` on the refresh cookie in production. The generated controller uses `secure(false)` only for local HTTP development.

## Endpoints

- POST `/api/v1/auth/register`
- POST `/api/v1/auth/login`
- POST `/api/v1/auth/refresh`
- POST `/api/v1/auth/logout`
- POST `/api/v1/auth/logout-all`
- GET `/api/v1/users/me`

## Known starter limitation

New Customer registration creates the User and credentials but does not yet seed/assign the CUSTOMER role because authoritative role seed IDs and CustomerProfile schema belong to the next module phase.
