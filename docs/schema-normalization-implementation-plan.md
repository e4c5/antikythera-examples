# Schema Normalization — Implementation Plan (for AI Coding Agent)

This document is a **task-oriented implementation plan** derived from the detailed improvement plan. Execute the phases in order; later phases depend on earlier ones. Reference: `schema-normalization-improvement-plan.md`.

**Codebase context:** `antikythera-examples`; main entry: `SchemaNormalizationAnalyzer`; key types: `DataMigrationPlan`, `ViewDescriptor` (in `InsteadOfTriggerGenerator`), `EntityProfile`, `EntityMetadata` (from antikythera), `TopologicalSorter`, `DataMigrationGenerator`, `InsteadOfTriggerGenerator`, `LiquibaseGenerator`.

---

## Phase 0: Configuration

**Goal:** Add `schema_normalization` configuration keys so later phases can read them.

**File:** `generator.yml` (or the config file used by `Settings.getProperty("schema_normalization")`).

**Add under key `schema_normalization`:**

| Key | Type | Default / note |
|-----|------|-----------------|
| `ddl_mode` | string | `"liquibase"` (or `"raw_sql"`) |
| `rename_old_table_to` | string | **Required.** Backup name for old table, e.g. `customer_backup` or pattern `{sourceTable}_backup` |
| `liquibase_master_file` | string | Optional; fallback to `query_optimizer.liquibase_master_file` |
| `supported_dialects` | list of strings | Optional; fallback to query_optimizer |
| `base_path` | string | For entity and mapping output |
| `mapping_output_dir` | string | Optional; default e.g. `docs` under base_path for mapping JSON files |

**Acceptance:** `Settings.getProperty("schema_normalization")` returns a map containing these keys when present in config.

---

## Phase 1: DataMigrationPlanValidator

**Goal:** Validate each `DataMigrationPlan` before generating artifacts; skip or fail fast on invalid plans.

**New file:** `antikythera-examples/src/main/java/sa/com/cloudsolutions/antikythera/examples/util/DataMigrationPlanValidator.java`

**Input:** `DataMigrationPlan plan`, `EntityProfile sourceProfile`.

**Output:** A result type, e.g. `ValidationResult(boolean valid, List<String> errors, List<String> warnings)`.

**Validation rules:**

1. **Table consistency:** Every `targetTable` in `plan.columnMappings()` and every `fromTable` / `toTable` in `plan.foreignKeys()` must be in `plan.newTables()`. Else add error.
2. **Base table:** `plan.baseTable()` must be in `plan.newTables()`. Else add error.
3. **DAG:** Call `TopologicalSorter.sort(plan.newTables(), toFkList(plan))` where `toFkList` converts `DataMigrationPlan.foreignKeys()` to `List<InsteadOfTriggerGenerator.ForeignKey>`. If it throws (cycle), add error.
4. **Column coverage (warn):** Build set of column names from `sourceProfile` (fields’ column names + relationships’ join columns). Build set of `viewColumn` from `plan.columnMappings()`. If any profile column is missing from mappings, add warning (optional: strict mode as error).

**Public API:** e.g. `static ValidationResult validate(DataMigrationPlan plan, EntityProfile sourceProfile)`.

**Acceptance:** Unit test: valid plan returns valid=true; plan with cycle or base not in newTables returns valid=false and non-empty errors.

---

## Phase 2: NormalizedTableDDLGenerator

**Goal:** Generate CREATE TABLE (raw SQL or Liquibase changeset XML) for each table in `DataMigrationPlan`, in topological order.

**New file:** `antikythera-examples/src/main/java/sa/com/cloudsolutions/antikythera/examples/util/NormalizedTableDDLGenerator.java`

**Input:** `ViewDescriptor view` (or `DataMigrationPlan` + view name), `EntityProfile sourceProfile`, `String ddlMode` (`"raw_sql"` or `"liquibase"`).

**Output:** `List<String>` — either raw SQL statements or Liquibase changeSet XML strings, one per table, in **topological order** (`TopologicalSorter.sort(view.tables(), view.foreignKeys())`).

**Logic:**

- For each table in topological order, collect `ColumnMapping` entries where `sourceTable` equals the table.
- For each column: resolve Java type from `sourceProfile` by matching `viewColumn` to profile field column name; map to DB type (e.g. Long→BIGINT, String→VARCHAR(255), Integer→INTEGER, BigDecimal→NUMERIC(19,2), Boolean→BOOLEAN). Default unknown to VARCHAR(255).
- **raw_sql:** Emit `CREATE TABLE <table> ( <col> <type>, ... PRIMARY KEY (...), ... )`; for FK columns add `REFERENCES parent(col)` or omit and rely on separate ALTER. Use types appropriate for PostgreSQL/Oracle.
- **liquibase:** Emit a changeSet with `<createTable tableName="...">` and `<column name="..." type="..."/>` (Liquibase type names: bigint, varchar(255), etc.). Add `constraints primaryKey="true"` / `nullable="false"` as needed.

**Type mapping helper:** Centralize Java type → SQL/Liquibase type in the same class or a small helper.

**Acceptance:** Given a ViewDescriptor with two tables (e.g. customer, address) and a sourceProfile with types, output two CREATE TABLE (or two changeSets) in order customer then address, with correct column names and types.

---

## Phase 3: Discover FKs referencing old table and generate drop/rename changesets

**Goal:** Before creating the view, generate changesets that (1) drop every FK constraint that references the old table, (2) rename the old table to the backup name.

**Where:** Implement in `SchemaNormalizationAnalyzer` or a helper used by it (e.g. `ExternalFKDiscovery` + changeset building).

**Step 3a — Discover FKs that reference the old table**

- **Input:** `String sourceTable`, `List<EntityProfile> allProfiles` (and optionally entity→table resolution). The entity that maps to `sourceTable` is the “current” entity; we need every *other* entity that has a relationship pointing to it.
- **Logic:** For each `EntityProfile p` in `allProfiles`, for each `RelationshipProfile r` in `p.relationships()`, resolve `r.targetEntity()` to a table name (e.g. via `EntityMappingResolver.getTableNameForEntity(r.targetEntity())` or by matching entity name to profile and taking `tableName`). If that table equals `sourceTable`, then `p.tableName()` is the **referencing table** and `r.joinColumn()` is the FK column. Constraint name: use convention e.g. `fk_<referencingTable>_<sourceTable>` or derive from JPA if available.
- **Output:** List of `(referencingTable, constraintName)` or `(referencingTable, fkColumn)`.

**Step 3b — Generate changesets**

- **Drop FKs:** For each (referencingTable, constraintName), generate one changeset. Use Liquibase `<dropForeignKeyConstraint baseTableName="referencingTable" constraintName="constraintName"/>` or raw SQL `ALTER TABLE referencingTable DROP CONSTRAINT constraintName`. Use `LiquibaseGenerator.createRawSqlChangeset` with dialect-specific SQL if needed (e.g. `dbms="postgresql"` and `dbms="oracle"`).
- **Rename old table:** Read `rename_old_table_to` from config; if it contains `{sourceTable}`, substitute the plan’s source table name. Generate one changeset: Liquibase `renameTable` or raw SQL `ALTER TABLE sourceTable RENAME TO backupName`. Add to the same per-plan changeset list **after** the drop-FK changesets.

**Acceptance:** For a schema where table `order` has a FK to `customer`, when normalizing `customer`, the generated changesets include (1) DROP CONSTRAINT on `order`, (2) RENAME TABLE customer TO customer_backup (or configured name).

---

## Phase 4: Fix compatibility view JOIN order

**Goal:** Build the view SELECT so JOINs are in topological order (parent table in FROM before child is joined).

**File:** `SchemaNormalizationAnalyzer.java`, method `buildCompatibilityViewSql(ViewDescriptor view)`.

**Current bug:** The loop over `view.foreignKeys()` uses list order; for multi-level FKs (e.g. customer → address → phone) the JOIN for `phone` may reference `address` before `address` is in the FROM clause.

**Change:**

1. Compute `List<String> order = TopologicalSorter.sort(view.tables(), view.foreignKeys())`.
2. Build SELECT clause as today: from `view.columnMappings()`, each entry `sourceTable.sourceColumn AS viewColumn`.
3. Build FROM clause: `FROM view.baseTable()` (the base table).
4. For JOINs: iterate over `order`; skip the first element if it equals `view.baseTable()`. For each remaining table `table`, find the FK where `fk.fromTable().equals(table)`. Then append `JOIN table ON table.fk.fromColumn() = fk.toTable().fk.toColumn()`. Use the parent table’s alias/name (e.g. `fk.toTable()`) which is already in the FROM/JOIN list because of topological order.

**Acceptance:** For a ViewDescriptor with tables customer, address, phone and FKs address→customer, phone→address, the generated SQL has FROM customer JOIN address ON ... JOIN phone ON ... (and no reference to a table before it appears).

---

## Phase 5: Reorder migration pipeline and integrate DDL, drop FKs, rename

**Goal:** Generate changesets in the correct order and integrate NormalizedTableDDLGenerator, FK drop, and rename.

**File:** `SchemaNormalizationAnalyzer.java`, method `generateMigrationArtifacts()`.

**Target order per plan:**

1. CREATE TABLE for each new table (topological order) — from `NormalizedTableDDLGenerator`.
2. Data migration INSERT-SELECT — from `DataMigrationGenerator.generateMigrationSql(view)` (existing).
3. Drop FK constraints that reference the old table — from Phase 3.
4. Rename old table to backup name — from Phase 3.
5. Create compatibility view — existing `createViewChangeset(view.viewName(), viewSql)`.
6. INSTEAD OF triggers (insert, update, delete) — existing trigger changesets.

**Concrete changes:**

- Before the loop over `plans`, read `schema_normalization.ddl_mode` (default `"liquibase"`) and `rename_old_table_to`.
- For each plan: run `DataMigrationPlanValidator.validate(plan, sourceProfile)`; if invalid, log errors and skip this plan.
- Replace the current loop body with a helper that returns the full list of changesets in the order above, e.g. `buildChangesetsForPlan(DataMigrationPlan plan, ViewDescriptor view, EntityProfile sourceProfile, List<EntityProfile> allProfiles, LiquibaseGenerator liquibaseGenerator, DataMigrationGenerator dataMigrationGenerator, InsteadOfTriggerGenerator triggerGenerator, String ddlMode, String renameOldTableTo)`.
- In that helper: (1) call `NormalizedTableDDLGenerator` to get create-table changesets; (2) call `DataMigrationGenerator.generateMigrationSql(view)` and wrap each SQL in `createRawSqlChangeset`; (3) call FK discovery (Phase 3) and add drop-FK and rename changesets; (4) build view SQL with `buildCompatibilityViewSql(view)` and add view changeset; (5) add trigger changesets. Append all to a list and return.
- Use `ChangesetConfig.fromConfiguration("schema_normalization")` when constructing `LiquibaseGenerator`; if `schema_normalization.liquibase_master_file` is null, fall back to `fromConfiguration("query_optimizer")` for that setting.

**Acceptance:** Running the analyzer on a project with one normalization plan produces a single Liquibase file (or stdout) where the changesets for that plan appear in the order: create tables → insert data → drop FKs → rename table → create view → triggers.

---

## Phase 6: Old–new mapping artifact

**Goal:** Write one JSON file per plan that maps old table/entity to view name, new tables/entities, and column mappings.

**File:** `SchemaNormalizationAnalyzer.java` (in `generateMigrationArtifacts()` or a helper called from it).

**When:** After building changesets for a plan (e.g. inside `buildChangesetsForPlan` or right after it), write the mapping file.

**Path:** `<base_path>/<mapping_output_dir>/normalization-mapping-<sourceTable>.json`. If `mapping_output_dir` is not set, use `docs`. Create parent directories if needed.

**Content:** A JSON object with: `sourceTable`, `sourceEntity` (from `sourceProfile.entityName()`), `viewName` (same as sourceTable), `newTables` (list, topological order), `newEntities` (list: PascalCase of each table, same as entity generation), `columnMappings` (list of `{ viewColumn, targetTable, targetColumn }` from plan), `foreignKeys` (list of `{ fromTable, fromColumn, toTable, toColumn }` from plan). Serialize with `ObjectMapper`; use existing `base_path` from settings.

**Acceptance:** After running the analyzer, the file `docs/normalization-mapping-customer.json` (or configured path) exists and contains the above structure for a plan whose source table is `customer`.

---

## Phase 7: Trigger rollback DDL

**Goal:** Each INSTEAD OF trigger changeset must have an explicit rollback that drops the trigger (and on PostgreSQL, the function).

**File:** `InsteadOfTriggerGenerator.java`

**Change:** Add a method (or extend the API) so that for each trigger type (insert, update, delete) we can get not only the create DDL but also the rollback DDL. For PostgreSQL: `DROP TRIGGER IF EXISTS trig_<view>_insert ON <view>; DROP FUNCTION IF EXISTS fn_<view>_insert();` (and same for update, delete). For Oracle: `DROP TRIGGER trig_<view>_insert;` (repeat for update, delete).

**File:** `SchemaNormalizationAnalyzer.java` (or wherever trigger changesets are built)

**Change:** When calling `liquibaseGenerator.createDialectSqlChangeset(...)` for triggers, use a variant that accepts rollback SQL per dialect, or build the changeSet XML to include `<rollback>...</rollback>` with the generated drop statements. If `LiquibaseGenerator` does not support custom rollback for dialect SQL, add an overload e.g. `createDialectSqlChangeset(String id, Map<DatabaseDialect, String> sqlByDialect, Map<DatabaseDialect, String> rollbackByDialect)` and use it for triggers.

**Acceptance:** The generated Liquibase for a trigger changeSet includes a rollback block that drops the trigger (and PostgreSQL function) so that Liquibase rollback works.

---

## Phase 8: LiquibaseGenerator support for trigger rollback (if needed)

**Goal:** If Phase 7 requires extending LiquibaseGenerator, add a method that produces a changeSet with dialect-specific SQL and dialect-specific rollback.

**File:** `LiquibaseGenerator.java`

**Add:** e.g. `createDialectSqlChangesetWithRollback(String id, Map<DatabaseDialect, String> sqlByDialect, Map<DatabaseDialect, String> rollbackByDialect)`. For each dialect, emit `<sql dbms="...">` for main body and in `<rollback>` emit `<sql dbms="...">` for that dialect’s rollback. Use this when generating trigger changeSets from the analyzer.

**Acceptance:** A changeSet created with this method contains both sql and rollback blocks per dialect.

---

## Phase 9: Tests and small fixes

**Goal:** Add/update tests and fix any duplicate section heading in the improvement plan doc.

**DataMigrationGeneratorTest:** Add a test that uses a three-level chain (e.g. tables customer, address, phone with FKs address→customer, phone→address). Assert that the order of generated INSERT statements is customer, then address, then phone.

**DataMigrationPlanValidator:** Add unit tests: valid plan passes; plan with baseTable not in newTables fails; plan with cycle in foreignKeys fails.

**InsteadOfTriggerGeneratorTest:** Assert (1) INSERT trigger DDL contains INSERTs for each table in topological order; (2) UPDATE trigger contains UPDATEs with WHERE on base PK or FK; (3) DELETE trigger contains DELETEs in reverse topological order. Optionally assert that generated rollback DDL (Phase 7) is present and well-formed.

**NormalizedTableDDLGenerator:** Add unit test with a two-table ViewDescriptor and EntityProfile; assert two CREATE TABLE (or two changeSets) in correct order with expected column names and types.

**Fix doc:** In `schema-normalization-improvement-plan.md`, fix duplicate "## 12." (one should be "## 13. Summary of Changes by File") and remove stray "t" at end of file if present.

**Acceptance:** All new and modified classes have unit tests; existing tests still pass.

---

## Execution order summary

| Phase | Depends on | Delivers |
|-------|------------|----------|
| 0 | — | Config keys in generator.yml |
| 1 | — | DataMigrationPlanValidator |
| 2 | — | NormalizedTableDDLGenerator |
| 3 | 0 | FK discovery + drop/rename changeset generation |
| 4 | — | buildCompatibilityViewSql fix |
| 5 | 0, 1, 2, 3, 4 | generateMigrationArtifacts reorder + integration |
| 6 | 0, 5 | Mapping JSON file per plan |
| 7 | — | Trigger rollback DDL in InsteadOfTriggerGenerator |
| 8 | 7 | LiquibaseGenerator rollback support (if needed) |
| 9 | 1–8 | Tests and doc fix |

Implement in this order. After Phase 5, the full pipeline (create tables → migrate → drop FKs → rename → view → triggers) should run; Phases 6–8 add mapping artifact and trigger rollback; Phase 9 locks in quality.
