Flyway Pg intentionally not generated in Step 1. Add only after verifying actual migration history.

## Rules for applied migrations

* A migration that has been applied to any environment is immutable. Flyway
  stores a CRC32 checksum per version in `flyway_schema_history`; editing the
  file afterwards makes startup fail with
  `Migration checksum mismatch for migration version NNN`.
* The forward fix is always a new `V0NN__*.sql` file, never an edit in place.
  Comments and formatting count as edits — the checksum covers the whole file.

## Local dev only: repairing a checksum mismatch

Use this only when the edited migration's effects are *already present* in the
database (verify each DDL object / backfill first). It rewrites the recorded
checksum to the value Flyway prints as `Resolved locally`:

```sql
-- example: version 044
UPDATE flyway_schema_history
SET checksum = <resolved-local-checksum>
WHERE version = '044' AND success;
```

Then restart the app and confirm
`Successfully validated N migrations` followed by
`Schema "public" is up to date`.

Never "fix" a mismatch with `spring.flyway.validate-on-migrate=false` or by
editing `flyway_schema_history` on a shared/production database. If the edited
file contains changes that were not applied, write them as a new migration
instead of repairing.
