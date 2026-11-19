-- =============================================================================
-- V016: inventory.units_of_measure.decimal_scale smallint -> integer
-- =============================================================================
-- Checkpoint 2C schema reconciliation (continuation of V014/V015):
-- the UnitOfMeasure entity maps decimalScale as int (JDBC INTEGER), while the
-- table was created with smallint (JDBC SMALLINT). Hibernate strict validation
-- (test profile, ddl-auto=validate) reports:
--   wrong column type encountered in column [decimal_scale] in table
--   [inventory.units_of_measure]; found [int2 (Types#SMALLINT)], but expecting
--   [integer (Types#INTEGER)]
-- Widening smallint -> integer is lossless for all stored values, and the
-- existing CHECK (decimal_scale BETWEEN 0 AND 6) remains enforced. The entity
-- mapping is intentionally left as int, matching every other integer column
-- convention in this schema (quantities, display_order, attempt_count, ...).
-- =============================================================================

ALTER TABLE inventory.units_of_measure
    ALTER COLUMN decimal_scale TYPE integer;
