# Schema Normalization & Entity Refactoring — Detailed Improvement Plan

This document describes the current entity refactoring implementation, confirms how data migration respects dependencies, and provides **concrete implementation steps** for each improvement. The plan supports creating new tables via **CREATE TABLE statements** or **Liquibase** and ensures the full migration pipeline is correct and runnable.

---

## 1. Current Implementation Summary

### 1.1 Flow

- **Phase 1:** `SchemaNormalizationAnalyzer` scans for JPA `@Entity` types, builds an `EntityProfile` per entity via `EntityMappingResolver`, collects them in `allProfiles`.
- **Phase 2:** All profiles are sent to the LLM in one batch. The LLM returns `EntityNormalizationReport` entries with optional `DataMigrationPlan`.
- **Artifact generation:** For each plan, the analyzer currently generates: (1) data migration INSERT-SELECT SQL, (2) compatibility view SQL, (3) INSTEAD OF triggers, (4) new JPA entity classes. It does **not** generate CREATE TABLE for new tables, nor drop FKs / rename of the old table.

### 1.2 Data Structures

- **DataMigrationPlan:** `sourceTable`, `baseTable`, `newTables`, `columnMappings` (viewColumn → targetTable/targetColumn), `foreignKeys` (fromTable/fromColumn → toTable/toColumn).
- **ViewDescriptor:** Same information with `viewName` = source table name.
- **TopologicalSorter:** Sorts tables by FK dependency; edge `(fromTable, fromColumn, toTable, toColumn)` means "fromTable depends on toTable", so **toTable (parent) comes before fromTable (child)** in the result.

### 1.3 Old–New Mapping: Current vs Plan

**Current implementation:** There is **no** clear, explicit mapping between old and new schema emitted for developers. The relationship exists only implicitly:

- The LLM returns a `DataMigrationPlan` (sourceTable, newTables, columnMappings, foreignKeys) used internally to generate Liquibase and Java.
- The compatibility view is created with the **old table name** and SQL that joins the new tables, but this is embedded in Liquibase XML, not in a dedicated mapping document.
- New JPA entities are generated under a `.normalized` package with names derived from `newTables`, but there is no file that states “old entity X → view name → new entities A, B” or “old column C → new table T, column C′”.

So developers cannot easily look up how to migrate code (repositories, services, queries) from the old entity to the new ones or how the view relates to the old table.

**Plan:** Yes. Section **11 (Old–New Mapping Artifact)** and the Summary (Section 12) require emitting a **structured mapping file** per refactor so that old ↔ new is explicit and machine- and human-readable. See Section 11 for format, location, and implementation steps.

### 1.4 What We Send to the LLM (No Source Code, No Getters/Setters)

**Current implementation:** We do **not** send entity source code. We send a **structured profile** only. For each entity we build an `EntityProfile` from the AST (field declarations and annotations) and serialize that to JSON. The payload is a JSON array of objects with:

- `entityName`, `tableName`
- `fields`: array of `{ javaName, columnName, isId, isNullable, columnType }`
- `relationships`: array of `{ javaName, annotationType, joinColumn, referencedColumn, targetEntity }`

Getters, setters, constructors, and all other methods are never included; we only iterate over `typeDecl.getFields()` and extract metadata. So token size is already reduced relative to sending full source.

**Further token reduction (optional):** If we need to shrink the input more (e.g. for very large schemas), we can use a **compact profile format** (Section 2.3 D): shorter JSON keys (e.g. `e` for entityName, `t` for tableName, `f`/`r` for fields/relationships), omit null/optional values, or abbreviate type names. The prompt and parser would need to accept the same mapping. This is optional and should not drop information the model needs for normalization analysis.

---

## 2. LLM Batching: All-at-Once vs Alternatives

### 2.1 Current Approach: Send All Entities in One Request

The analyzer sends **every** collected entity profile to the LLM in a **single** batch (`sendBatchToLLM(allProfiles)`). This is intentional:

- **Cross-entity reasoning:** The system prompt explicitly requires the model to "reason across entity boundaries": detect denormalized copies (e.g. `orderLine.productName` when `Product` is in the batch), missing FK relationships (e.g. `customerId: Long` when `Customer` exists), and ownership inversion. Those checks **require** seeing multiple entities in one context.
- **Single round-trip:** One API call reduces latency and avoids N separate requests.
- **Consistent output shape:** One response array aligns with one input array; retry logic (e.g. `max_continuations`) applies to the whole batch.

So for **correctness of cross-entity findings**, sending all entities at once is the right design when the schema is small enough to fit.

### 2.2 When All-at-Once Becomes a Problem

- **Response truncation:** The model may hit `max_tokens` and return incomplete JSON (e.g. the array is cut off mid-object). Today we retry the full request; we may still get truncation, and we lose any valid partial result.
- **Input token limit:** Very large schemas might not fit in the context window at all.
- **Cost and reliability:** One large request is expensive; full retries multiply cost and may still fail.

The goal is to **keep sending all entities at once** (so cross-entity analysis stays correct) but **make the agent handle large or truncated responses** and, where possible, **have the LLM return output in a way we can consume incrementally or in batches**.

---

### 2.3 Brainstorm: LLM Returns Response in Batches or Agent Handles Response Better

Ideas are grouped by theme: (A) response format and streaming, (B) multi-turn / continuation, (C) agent-side parsing and retry, (D) reducing output size.

#### A. Response format and streaming

| Idea | Description | Pros | Cons / notes |
|------|-------------|------|------------------|
| **NDJSON (newline-delimited JSON)** | Ask the LLM to return one JSON object per line, each line = one entity’s report. No top-level array. | Each line is self-contained; we can parse line-by-line as we receive. If the stream is truncated, we keep every complete line. Streaming APIs can deliver line-by-line. | Model must reliably emit one object per line; need to handle possible NDJSON vs “array” mode in parsing. |
| **Streaming + incremental array parse** | Keep array format but use a streaming JSON parser that emits each array element as it becomes complete. | No prompt change; we get partial results on truncation (all complete elements so far). | Requires a streaming JSON parser (e.g. Jackson `JsonParser` in streaming mode); last element may be truncated and must be discarded or repaired. |
| **Streaming API** | Use the provider’s streaming endpoint; process chunks as they arrive and accumulate text (or parse incrementally). | We can stop early if we have “enough” or detect truncation and switch to resume strategy. | Same parsing story as above; need to detect end-of-stream vs truncation. |
| **Request higher max_tokens** | Set a larger `max_tokens` (or equivalent) so the model has room to finish the full response. | Simple; no format or logic change. | May hit provider limits; doesn’t fix input-side context limit. |

#### B. Multi-turn / continuation (LLM “returns in batches” across turns)

| Idea | Description | Pros | Cons / notes |
|------|-------------|------|------------------|
| **Explicit “next batch” in prompt** | First request: “Analyze all entities. Return only the first N reports (e.g. 15) in the same JSON array format. If there are more, end with `"__continue": true`.” Second request: same system prompt + same full entity list + user message “Return only reports for entities starting from index N (same order as input). Omit entities already reported.” Repeat until no continuation. | We keep full context every time; each *response* is smaller, so less truncation per turn. | Multiple round-trips; we must send full input again (or a subset) and clearly define “index N” or entity names to avoid duplicates. |
| **Resume from last received entity** | If we detect truncation (e.g. incomplete JSON), we have a list of entity names we already got. Follow-up request: “Same input. Return reports **only** for entities not in this list: [received names]. Same JSON format.” | We only ask for the missing tail; smaller output on retry. | Requires robust truncation detection and consistent entity ordering (input order = output order). |
| **Two-phase: index then details** | Phase 1: “Given these entities, return only the list of entity **names** that have at least one violation (no details).” Phase 2: “For exactly these entities [list], return the full report (issues, dataMigrationPlan, etc.) in the same format as before.” We still send full profiles in Phase 2 so the model has context. | First response is tiny (list of names); we know how many entities need full output. We can then request full reports in one go or in chunks by name. | Two round-trips minimum; Phase 2 input is still large if we resend all profiles (so the model can reason); we could send only the subset of profiles for Phase 2 to reduce input size. |

#### C. Agent-side: handle responses better

| Idea | Description | Pros | Cons / notes |
|------|-------------|------|------------------|
| **Incremental parser** | As response bytes arrive (streaming or buffered), parse JSON incrementally. For a top-level array, emit each `EntityNormalizationReport` as soon as its element is complete. On truncation, keep all complete reports; log or retry for the rest. | We never throw away valid partial results; truncation becomes “we got reports 1..K, missing K+1..N.” | Need to integrate streaming parser and possibly retry-with-resume (see B). |
| **Truncation detection + retry with resume** | If parsing fails (e.g. truncated JSON), extract the last complete entity name from the partial buffer. Retry with: “Your previous response was truncated. You had already returned reports for: [names]. Return **only** the reports for the remaining entities in the same order: [remaining names]. Same JSON array format.” Send full profiles again (or only remaining) so model has context. | We recover missing reports without re-asking for everything; response on retry is smaller. | Prompt and logic must be clear about “remaining” order; duplicate detection when merging. |
| **Best-effort partial parse** | On parse failure, try to salvage: e.g. regex or line-by-line scan for `"entityName": "X"` and then extract the surrounding object. Accept partial or best-effort structure for the last element. | Can sometimes recover one more report from a truncated tail. | Fragile; may produce invalid or incomplete reports; use only as fallback. |
| **Merge results across retries** | On retry, don’t replace—merge. Key by `entityName`. First response gives reports for A, B, C (then truncation). Second response (resume) gives D, E. Merge into one list; dedupe by entity name. | Clear semantics for combining partial results. | Need to define order in merged list (e.g. input order). |

#### D. Reducing output (and input) size

| Idea | Description | Pros | Cons / notes |
|------|-------------|------|------------------|
| **Compact output format** | Ask for shorter keys and minimal whitespace (e.g. `entityName` → `e`, `dataMigrationPlan` → `plan`). Document the mapping in code. | Smaller response = less chance of truncation and lower token cost. | Less readable; prompt and parser must use the same mapping. |
| **Compress input profiles** | Abbreviate field names, drop redundant info, or use shorter type names in the JSON we send. | More entities fit in context; leaves more room for output. | Risk of losing information the model needs; test that analysis quality doesn’t drop. |
| **Split “heavy” vs “light”** | First call: “Return reports with only entityName, normalizationForm, violation, affectedFields (no dataMigrationPlan or liquibaseMigrationHint).” Second call: “For these entities [list], return **only** the dataMigrationPlan (and entityName) for each.” | First response is much smaller; we only request full plans for entities that need them. | Two phases; second call needs enough context (e.g. those entities’ profiles) for the model to generate correct plans. |

### 2.4 Recommended direction (keep all-at-once, handle response better)

Do **not** switch to per-entity or chunked input batching; keep **one request with all entities** so cross-entity analysis remains correct. Focus on:

1. **Response format:** Prefer **NDJSON** (one JSON object per line, one report per entity). This gives naturally incremental consumption: we can parse each line as it arrives and retain all complete reports if the stream is truncated. Update the system prompt to require NDJSON and document the format. Add a parser path that accepts either NDJSON or the current top-level array (for backward compatibility or non-streaming).
2. **Streaming + incremental parse:** Use the provider’s streaming API when available. Accumulate the stream; feed a streaming JSON parser (e.g. Jackson) or a line-based NDJSON parser. On stream end, if the last line or last array element is incomplete, treat as truncation.
3. **Truncation handling:** When truncation is detected: (a) keep all fully parsed reports; (b) determine “remaining” entity names (input order minus already received); (c) retry with a **resume** user message: same system prompt + full entity list + “Return reports only for these entities in order: [remaining]. Same format.” Merge retry results with existing reports by entity name; sort merged list by original input order. Optionally cap resume attempts (e.g. 2–3) to avoid infinite loops.
4. **Larger max_tokens:** Configure a higher output token limit where the API allows it, to reduce truncation for large schemas.
5. **Optional two-phase (large schemas):** If entity count is above a threshold (e.g. 80), run phase 1: “Return only entity names that have violations.” Phase 2: “Return full reports only for: [names].” Send full profiles in phase 2 so the model still has full context; only the *output* is restricted to fewer entities, which reduces response size.

**Concrete implementation steps (for NDJSON + streaming + resume):**

1. **Prompt:** In `normalization-system-prompt.md`, add an output-format option: “Return NDJSON: one JSON object per line, each line = one entity report (same structure as array element). No top-level array. Order of lines must match input entity order.” Keep the existing array format documented as acceptable so the parser can support both.
2. **Parser:** In `SchemaNormalizationAnalyzer` (or a dedicated `NormalizationResponseParser`), add a method that: (a) tries to parse the full response as a JSON array (current behavior); (b) if that fails, tries NDJSON: split by newline, parse each line as a JSON object, collect into a list; (c) if the last line is incomplete (parse error), treat as truncation and return the list of complete reports plus a “truncated” flag and the set of entity names already received.
3. **Streaming:** If the AI service exposes a streaming API, add a path that consumes the stream (e.g. by line for NDJSON or by chunk for array), runs the incremental parser, and stops when the stream ends. Set `lastResponseTruncated` when the last unit is incomplete.
4. **Resume:** In `afterAnalysisLoop()`, when `lastResponseTruncated` is true after parsing: compute `remainingNames = input order entity names minus alreadyReceived`. If `remainingNames` is non-empty and resume attempt &lt; max (e.g. 3), send a new request with the same system prompt and the same full `allProfiles` JSON, and user message: “Your previous response was truncated. Return reports **only** for these entities, in this order: [remainingNames]. Use the same format (NDJSON or array).” Parse the response; merge by entity name into `allReports`; sort the merged list by original profile order. Repeat until no truncation or max attempts.
5. **Config:** Add `schema_normalization.max_resume_attempts` (default 3) and `schema_normalization.response_format: "ndjson" | "array"` (default `ndjson` when streaming is used). Optionally `max_tokens` override for the normalization request.

---

## 3. Data Migration and Dependencies

### 3.1 Does Data Migration Respect Dependencies?

**Yes.** The current implementation **does** respect table dependencies when generating INSERT order.

- **Where:** `DataMigrationGenerator.generateMigrationSql(ViewDescriptor view)`.
- **How:** It uses `TopologicalSorter.sort(view.tables(), view.foreignKeys())` to obtain an ordered list of tables. It then iterates over this list and emits one `INSERT INTO table (...) SELECT ... FROM viewName` per table.
- **Semantics:** In `TopologicalSorter`, an edge `fromTable → toTable` means "fromTable depends on toTable" (child depends on parent). The sort returns tables so that every **parent (toTable) appears before its child (fromTable)**. Therefore you never insert into a child table before the table it references has been populated.

**Example:** For `customer` (base) and `address` (child with `address.customer_id → customer.id`), the FK is `(fromTable=address, toTable=customer)`. Sort returns `[customer, address]`. So we insert into `customer` first, then `address` — correct.

**Multiple parents:** If a table has FKs to two other tables (e.g. `order_line` references both `order` and `product`), both `order` and `product` have lower in-degree and appear before `order_line` in the sort. So insert order remains correct.

**Concrete verification step:** Add or extend a unit test in `DataMigrationGeneratorTest` that uses a three-level chain (e.g. `customer` → `address` → `phone`) and asserts that the order of generated INSERT statements is exactly `customer`, then `address`, then `phone`.

### 3.2 Edge Cases and Gaps

- **Cycles:** `TopologicalSorter.sort` already throws `IllegalArgumentException` if a cycle is detected; no change needed.
- **Tables with no FKs:** A table with in-degree 0 (e.g. the base table) appears first in the sort; correct.
- **Missing columns in plan:** If the LLM omits a column from `columnMappings`, that column is not migrated. Validation (see below) should catch this by comparing against `EntityProfile`.

---

## 4. CREATE TABLE: Raw SQL vs Liquibase

### 4.1 Requirement

New normalized tables must be created before data migration. The tool must support either:

- **Option A:** Raw SQL `CREATE TABLE ...` statements (e.g. via `LiquibaseGenerator.createRawSqlChangeset`), or  
- **Option B:** Liquibase `<createTable>` changesets (declarative, better for cross-dialect and rollback).

Configuration will choose the mode (e.g. `schema_normalization.ddl_mode: "raw_sql"` or `"liquibase"`).

### 4.2 Concrete Steps for DDL Generation

**Step 4.2.1 — Config**

- **File:** `generator.yml` (or wherever `schema_normalization` is read).
- **Add:** Under `schema_normalization`, add key `ddl_mode` with values `raw_sql` or `liquibase` (default e.g. `liquibase`). For the old table: **always rename** (do not drop initially); add `rename_old_table_to` (e.g. `customer_backup`) as the suffix or full name for the renamed table. Optionally support `drop_old_table_after_rename: false` (default) so we keep the backup; if set true, a later step could drop the renamed table (out of scope for initial implementation).

**Step 4.2.2 — New class: `NormalizedTableDDLGenerator`**

- **Package:** `sa.com.cloudsolutions.antikythera.examples.util`.
- **Input:** `ViewDescriptor` (or `DataMigrationPlan`) plus `EntityProfile` for the source entity (for column types and nullability).
- **Output (raw_sql mode):** `List<String>` of `CREATE TABLE` statements, one per table in **topological order** (use `TopologicalSorter.sort(view.tables(), view.foreignKeys())`). Each statement must include columns from `columnMappings` for that table, plus FK columns. Map Java types from `EntityProfile` (e.g. `Long` → `BIGINT`, `String` → `VARCHAR(255)`) for the SQL type. Add `PRIMARY KEY` for the base table’s PK column; for child tables either a surrogate `id` or the natural key. Add `REFERENCES parent(col)` for FK columns if desired, or emit `ALTER TABLE` after all CREATEs.
- **Output (liquibase mode):** `List<String>` of Liquibase changeset XML strings, each containing `<createTable tableName="...">` with `<column name="..." type="..."/>` children. Use the same topological order. Types: use Liquibase type names (e.g. `bigint`, `varchar(255)`). Optionally add `<addForeignKeyConstraint>` in a separate changeset after all createTable.

**Step 4.2.3 — Type mapping**

- **Location:** Inside `NormalizedTableDDLGenerator` or a small helper.
- **Logic:** Map `FieldProfile.columnType()` (e.g. `Long`, `String`, `Integer`, `BigDecimal`, `Boolean`, `LocalDate`) to DB types. For raw SQL: PostgreSQL/Oracle-friendly types (e.g. `BIGINT`, `VARCHAR(255)`, `NUMERIC(19,2)`). For Liquibase: use Liquibase’s type names so it can translate per dialect. Expose a minimal map (e.g. `Long` → `bigint`, `String` → `varchar(255)`) and default to `varchar(255)` for unknown types.

**Step 4.2.4 — LiquibaseGenerator extension (if using Liquibase mode)**

- **File:** `LiquibaseGenerator.java`.
- **Add method:** e.g. `createCreateTableChangeset(String changesetIdPrefix, String tableName, List<ColumnSpec> columns)` where `ColumnSpec` is a small record (name, type, nullable, primaryKey). The method builds a single changeSet containing `<createTable tableName="...">` and `<column name="..." type="..." />` (and `constraints primaryKey="true"` / `constraints nullable="false"` as needed). Return the XML string. Alternatively, have `NormalizedTableDDLGenerator` build the full changeSet string and use `createCompositeChangeset` to include it; then no new method is strictly required if the generator outputs full changeSet XML.

**Step 4.2.5 — Integration in SchemaNormalizationAnalyzer**

- **Method:** `generateMigrationArtifacts()`.
- **After** building `allChangesets` and **before** the loop that adds migration/view/triggers for each plan:
  - Read `schema_normalization.ddl_mode` (default `liquibase`).
  - For each plan, build `ViewDescriptor view = toViewDescriptor(plan)` and get `EntityProfile sourceProfile` (same as for entity generation).
  - Call `NormalizedTableDDLGenerator.generateCreateTableArtifacts(view, sourceProfile, ddlMode)` (or similar) to get a list of changesets in topological order.
  - Append these changesets to `allChangesets` **first** (so CREATE TABLE runs before any INSERT).
- **Then** add changesets to **drop foreign keys that reference the old table**, then **rename the old table** (never drop it initially). Renaming a table would break any foreign key constraints in *other* tables that reference it, so we must drop those FKs first. Add these changesets **after** the data migration INSERTs and **before** the createView changeset.
- **Order of changesets per plan:** (1) CREATE TABLE for each new table (topological order), (2) INSERT-SELECT for each new table (topological order), (3) Drop all FK constraints that reference the old table, (4) RENAME TABLE old table TO backup name, (5) CREATE VIEW, (6) INSTEAD OF triggers.

**Step 4.2.6 — Composite changeset ordering**

- Currently the loop adds: migration SQL, view, triggers. Change to: DDL changesets (create tables), then migration SQL, then drop FKs referencing old table, then rename old table, then view, then triggers. Ensure `allChangesets` is built in that order (e.g. collect per-plan lists and add in sequence).

---

## 5. Migration Pipeline Order — Concrete Steps

**File:** `SchemaNormalizationAnalyzer.java`, method `generateMigrationArtifacts()`.

**Current order (wrong):** (1) Data migration INSERTs, (2) View, (3) Triggers. Missing: CREATE TABLE; old table still exists when view is created.

**Target order:**

1. **CREATE TABLE** for each new table (topological order) — from `NormalizedTableDDLGenerator` (raw_sql or liquibase).
2. **Data migration** — existing `DataMigrationGenerator.generateMigrationSql(view)` (already in topological order).
3. **Drop foreign keys that reference the old table** — one or more changesets: for each table that has a FK constraint pointing to `sourceTable`, emit `ALTER TABLE &lt;referencing_table&gt; DROP CONSTRAINT &lt;fk_name&gt;` (or dialect-equivalent). This must happen before rename, because renaming the old table would invalidate those constraints.
4. **Rename old table** — one changeset: `ALTER TABLE &lt;sourceTable&gt; RENAME TO &lt;backupName&gt;` (e.g. `&lt;sourceTable&gt;_backup`). We **always rename**, never drop, so the old data is preserved as a backup.
5. **Create compatibility view** — existing `createViewChangeset(viewName, viewSql)`.
6. **INSTEAD OF triggers** — existing trigger changesets.

**Concrete change:** In the loop over `plans`, build a **per-plan** list of changesets in the order above, then add that list to `allChangesets`. Use a helper method e.g. `buildChangesetsForPlan(DataMigrationPlan plan, ViewDescriptor view, EntityProfile sourceProfile, LiquibaseGenerator liquibaseGenerator, ...)` that returns `List<String>` in the correct order.

---

## 6. Compatibility View JOIN Order — Concrete Steps

**Problem:** `buildCompatibilityViewSql` iterates over `view.foreignKeys()` in list order. For multi-level FKs (e.g. customer → address → phone), if the FK list has `phone → address` before `address → customer`, the JOIN for phone would reference `address` before it is in the FROM clause.

**Fix:** Build JOINs in **topological order**: the base table is the first in the sort; then add JOINs so that each table is joined only after its parent is already in the FROM clause.

**File:** `SchemaNormalizationAnalyzer.java`, method `buildCompatibilityViewSql(ViewDescriptor view)`.

**Steps:**

1. Compute `List<String> order = TopologicalSorter.sort(view.tables(), view.foreignKeys())`. The first element is the base table (or one of the roots); use it as the one in `FROM`. Actually the view has `view.baseTable()` — use that for the FROM. Then we need to add JOINs for every other table in `order`, in an order where the parent of each table is already present.
2. Build a map: for each table in `order`, get the FK that points to it (i.e. where `toTable == table`). The joining table is `fromTable`. So for each table T that is not the base, find the FK (fromTable=T, toTable=parent). Then we want to add `JOIN T ON T.fromColumn = parent.toColumn`. So we need to add JOINs in an order where the parent is already in the FROM. Topological order guarantees that when we process table T, its parent has already been processed (parent comes before T in order). So iterate over `order`; skip the first if it’s the base table; for each subsequent table T, find the FK entry where `fromTable == T`, get `toTable` (parent) and `fromColumn`/`toColumn`, and append `JOIN T ON T.fromColumn = parent.toColumn`.
3. Replace the current `for (ForeignKey fk : view.foreignKeys())` loop with this ordered construction. Code shape:  
   `String base = view.baseTable();`  
   `List<String> order = TopologicalSorter.sort(view.tables(), view.foreignKeys());`  
   Build SELECT and `FROM base` as today. Then:  
   `for (String table : order) { if (table.equals(base)) continue; Optional<ForeignKey> fk = view.foreignKeys().stream().filter(f -> f.fromTable().equals(table)).findFirst(); fk.ifPresent(f -> append JOIN for table using f.fromColumn and f.toTable, f.toColumn); }`

**File to touch:** Only `SchemaNormalizationAnalyzer.java`.

---

## 7. Validation of DataMigrationPlan — Concrete Steps

**Goal:** Before generating any artifacts, validate that the LLM’s `DataMigrationPlan` is consistent and complete.

**New class or static methods:** e.g. `DataMigrationPlanValidator` in package `sa.com.cloudsolutions.antikythera.examples.util` (or in the same package as the analyzer).

**Input:** `DataMigrationPlan plan`, `EntityProfile sourceProfile`.

**Checks:**

1. **Column coverage:** Every field in `sourceProfile.fields()` and every relationship that maps to a column (e.g. `joinColumn` in relationships) should appear as a `viewColumn` in some `columnMappings` entry. If a column is intentionally dropped, the LLM can omit it; then either allow it or add a strict mode. At minimum: every column that is in the source table and should exist in the new schema must be in columnMappings. Build a set of viewColumn from plan and a set of source columns from profile; report missing columns (in plan vs profile) and extra columns (in plan but not in profile) — at least warn on missing in plan.
2. **Table consistency:** Every `targetTable` in columnMappings and every `fromTable`/`toTable` in foreignKeys must be in `newTables`. Otherwise throw or collect errors.
3. **Base table in newTables:** `plan.baseTable()` must be in `plan.newTables()`.
4. **DAG:** TopologicalSorter.sort already throws on cycle; call it once during validation (e.g. `TopologicalSorter.sort(plan.newTables(), toForeignKeyList(plan))`) and catch; if cycle, add a validation error.
5. **Return:** A validation result type (e.g. `boolean valid`, `List<String> errors`, `List<String> warnings`).

**Integration:** In `SchemaNormalizationAnalyzer.generateMigrationArtifacts()`, before the loop over plans, and for each plan before generating artifacts: call the validator. If invalid, skip that plan and log errors (or throw). Optionally, in `parseBatchResponse` or after collecting reports, validate each plan that has dataMigrationPlan and filter out invalid ones so they don’t appear in the report.

**File:** New `DataMigrationPlanValidator.java`; call from `SchemaNormalizationAnalyzer.generateMigrationArtifacts()`.

---

## 8. Rename Old Table (Never Drop First) — Concrete Steps

**Requirement:** The compatibility view uses the old table name. The old table must be **renamed** (not dropped) before creating the view, so that the old data is preserved as a backup. **Renaming would break foreign keys:** any other table that has a FK constraint referencing the old table must have that constraint dropped before the rename.

**Strategy:** Always rename. Do not drop the old table in the initial migration.

**Step 1 — Discover FKs that reference the old table:**  
We need the set of (referencing_table, constraint_name) or (referencing_table, fk_column) for every FK that points to `sourceTable`. Options:

- **From entity model:** Scan all `EntityProfile` / `EntityMetadata` for relationships whose `targetEntity` (or resolved table name) is the entity that maps to `sourceTable`. For each such relationship, the *other* entity’s table is the referencing table, and its `joinColumn` is the FK column. Constraint names may be generated (e.g. `fk_&lt;referencing_table&gt;_&lt;sourceTable&gt;`) or read from annotations if available.
- **From LLM or schema:** Optionally extend `DataMigrationPlan` (or a separate discovery step) to include `externalForeignKeys`: list of { referencingTable, constraintName } or { referencingTable, fkColumn } that reference the source table. The LLM could infer this from the full entity set; or we derive it from the entity model as above.

**Step 2 — Generate changesets:**

1. **Drop FKs:** For each FK that references the old table, emit a changeset: `ALTER TABLE &lt;referencing_table&gt; DROP CONSTRAINT &lt;constraint_name&gt;`. Dialect-specific: PostgreSQL uses constraint names; Oracle/MySQL may differ. Use Liquibase’s `dropForeignKeyConstraint` or raw SQL with `dbms` if needed. Add preconditions so the changeset is skipped if the constraint is already gone.
2. **Rename old table:** One changeset: `ALTER TABLE &lt;sourceTable&gt; RENAME TO &lt;backupName&gt;`. Use a configurable backup name, e.g. `rename_old_table_to: "<sourceTable>_backup"` or a literal like `customer_backup`. Liquibase’s `renameTable` or raw SQL per dialect.

**Config:** Under `schema_normalization`:  
- `rename_old_table_to`: string. Either a literal name (e.g. `customer_backup`) or a pattern (e.g. `{sourceTable}_backup`) so the generator substitutes the source table name. **Required** (we always rename).  
- Optionally `drop_old_table_after_rename: false` (default) for documentation; if true in future, a separate step could drop the renamed table (out of scope for now).

**Order:** Drop FKs first, then rename. Both after data migration and before createView.

---

## 9. Trigger Creation (INSTEAD OF) — Concrete Steps

**Purpose:** The compatibility view has the old table name and shape, but it is read-only unless we attach **INSTEAD OF** triggers. So when application code (or legacy queries) issues INSERT, UPDATE, or DELETE against the view, the database runs our trigger logic instead and writes to the underlying normalized tables. Without triggers, DML on the view would fail.

**Current implementation:** `InsteadOfTriggerGenerator` produces dialect-specific DDL for:

- **INSERT:** One INSERT into each normalized table (in topological order), using `NEW.<viewColumn>` for values. Columns are grouped by target table from `ViewDescriptor.columnMappings`.
- **UPDATE:** One UPDATE per normalized table; SET clause uses only columns that map to that table; WHERE uses base PK (for base table) or FK to base (for child tables). Order is topological.
- **DELETE:** DELETE from each normalized table in **reverse** topological order (child tables first, then base), so FK constraints are satisfied. WHERE uses base PK value from `OLD.<pkViewColumn>` (and for child tables, the FK column = OLD.pkViewColumn).

PostgreSQL: trigger functions (`CREATE OR REPLACE FUNCTION fn_<view>_insert() RETURNS TRIGGER`) plus `CREATE TRIGGER ... INSTEAD OF ... FOR EACH ROW EXECUTE FUNCTION fn_...`. Oracle: inline trigger body with `:NEW`/`:OLD`, no separate function. Both use the same `ViewDescriptor` (view name, base table, column mappings, foreign keys).

**What the plan must cover:**

### 9.1 Order and placement

- Triggers are created **after** the view exists. One changeset per trigger type (insert, update, delete) per view, or one changeset per view that contains all three (dialect-specific). Current code uses `createDialectSqlChangeset` once per trigger type, so we get three changesets per view (insert, update, delete), each with `<sql dbms="postgresql">` and `<sql dbms="oracle">` blocks.
- **Order of creation:** No dependency between the three trigger types; order is arbitrary. Convention: insert, then update, then delete (as in current code).

### 9.2 Dialect support

- **PostgreSQL:** Supported. Uses `CREATE OR REPLACE FUNCTION` + `CREATE OR REPLACE TRIGGER` with `EXECUTE FUNCTION` (PG 11+).
- **Oracle:** Supported. Uses `CREATE OR REPLACE TRIGGER` with inline PL/SQL and `:NEW`/`:OLD`.
- **MySQL:** **Not supported.** MySQL does not support INSTEAD OF triggers on views. Document this clearly. Workarounds: application-layer compatibility (e.g. DAO that writes to new tables when the old “logical” entity is updated) or avoid view-based compatibility on MySQL.
- **H2:** Support is possible (H2 supports INSTEAD OF triggers) but not currently implemented in `InsteadOfTriggerGenerator`. Add H2 if needed; syntax differs slightly from PostgreSQL.

**Concrete step:** In the plan and in user-facing docs, state that INSTEAD OF triggers are generated for **PostgreSQL and Oracle only**; for MySQL, the compatibility view is read-only unless the application implements DML in code.

### 9.3 Naming and idempotency

- **Trigger names:** Current generator uses e.g. `trig_<viewName>_insert`, `trig_<viewName>_update`, `trig_<viewName>_delete`. Function names (PostgreSQL): `fn_<viewName>_insert`, etc. These must be unique per view and stable across runs.
- **Idempotency:** PostgreSQL uses `CREATE OR REPLACE FUNCTION` and `CREATE OR REPLACE TRIGGER`, so re-running the changeset is safe. Oracle uses `CREATE OR REPLACE TRIGGER`, so also idempotent. If a dialect requires DROP then CREATE, add a precondition (trigger exists) and generate DROP in rollback.

### 9.4 Rollback

- **Rollback of trigger changesets:** For each trigger, the rollback must drop the trigger (and on PostgreSQL, the function). Current Liquibase changesets have `<rollback><!-- manual rollback required --></rollback>`. **Concrete step:** Generate explicit rollback: PostgreSQL — `DROP TRIGGER IF EXISTS trig_<view>_insert ON <view>; DROP FUNCTION IF EXISTS fn_<view>_insert();` (and similarly for update/delete). Oracle — `DROP TRIGGER trig_<view>_insert;` etc. Add these to the changeset rollback block so Liquibase can roll back cleanly.

### 9.5 Semantics and limitations

- **1:1 vs 1:N:** The generated triggers assume one row in the view corresponds to one row in each underlying table (1:1 or single-child). If the view is built with JOINs that produce multiple rows per base row (1:N), INSERT/UPDATE/DELETE semantics are ambiguous; the current design does not define behavior for that case. Document that the view+triggers are intended for **1:1** (or single child row per base row) normalization only.
- **Nulls and optional child:** If the view uses LEFT JOIN and the child row is missing, UPDATE/DELETE still run the child-table statements (WHERE fk = OLD.pk); no row will match, so it is a no-op. INSERT always inserts into all tables; if the model has an optional child, the trigger does not currently skip child insert when certain columns are null—could be an enhancement (e.g. only INSERT into child if NEW.<childFk> IS NOT NULL).
- **Error handling:** Trigger bodies do not currently include exception handling. If an INSERT/UPDATE/DELETE fails (e.g. constraint violation), the whole statement fails. Optional: add BEGIN/EXCEPTION/END in Oracle or EXCEPTION block in PostgreSQL to log or re-raise.

### 9.6 Validation and testing

- **ViewDescriptor consistency:** Trigger generation assumes `findBasePk` returns the PK column mapping; if the base table has no mappings, update/delete triggers return empty string. Validator (Section 7) should ensure base table has at least one column mapping. For each child table, `deriveWhereSourceCol` requires an FK from that table to the base table; validator should ensure every child appears in `foreignKeys` with `toTable = baseTable`.
- **Testing:** Add or extend tests (e.g. `InsteadOfTriggerGeneratorTest`) to assert: (1) INSERT trigger contains INSERTs for each table in topological order; (2) UPDATE trigger contains UPDATEs with correct WHERE; (3) DELETE trigger contains DELETEs in reverse topological order. Optionally, integration test against a real DB (e.g. Testcontainers): create view, create triggers, run INSERT/UPDATE/DELETE against the view, then SELECT from the underlying tables and assert correct rows.

### 9.7 Summary of trigger-related changes

| Item | Action |
|------|--------|
| Document dialect support | State PostgreSQL + Oracle only; MySQL not supported (view read-only). |
| Rollback | Generate explicit DROP TRIGGER (and DROP FUNCTION for PostgreSQL) in each trigger changeset’s rollback block. |
| Optional: H2 | Add H2 trigger generation if the project needs it. |
| Optional: null-safe child INSERT | If view has optional child, consider skipping child INSERT when FK or key columns are null. |
| Validation | Ensure ViewDescriptor has base PK and every child has FK to base; document 1:1 assumption. |
| Tests | Unit tests for trigger DDL shape; optional integration test with real DB. |

---

## 10. Data Migration Script Improvements — Concrete Steps

**Idempotency:**

- **Option A:** Add a precondition to the data migration changeSet: e.g. `<preConditions onFail="MARK_RAN"><rowCount tableName="&lt;newTable&gt;" expected="0"/></preConditions>` for the first new table (or all). So re-run skips if data already migrated.
- **Option B:** In `DataMigrationGenerator`, add an optional parameter or config `idempotent: true` that generates `INSERT ... SELECT ... WHERE NOT EXISTS (SELECT 1 FROM new_table)` or dialect-specific "ON CONFLICT DO NOTHING" (PostgreSQL). This is more complex because we need to define the conflict target.

**Concrete step:** Implement Option A in the analyzer: when building the migration changesets, wrap each (or the first) in a precondition that new table is empty. Use Liquibase’s `<preConditions><tableExists/><rowCount .../></preConditions>`. Add to `LiquibaseGenerator` a method that creates a changeSet with preconditions, or append precondition XML when building the migration changeSet in the analyzer.

**Identity/surrogate columns:** If a new table has an `id` column that is GENERATED BY DEFAULT AS IDENTITY, the INSERT…SELECT must either omit `id` (so the DB generates it) or include it. Currently the generator includes all columns from columnMappings. If the plan says the child table has a new `id`, the LLM should include it in columnMappings with a value from the source (e.g. a new sequence or the old table’s id). Document this in the prompt. Optionally, add a flag per column in the plan like `generated: true` so the DDL generator and INSERT generator skip or handle it differently.

**Rollback:** For the "drop FKs", "rename old table", "create view", and "triggers" changesets, add a short comment or a separate rollback changeSet file that documents: drop view, drop triggers, drop new tables, rename backup table back to original name, re-add dropped FKs. No code change required for "document"; for actual rollback changeSet, add optional generation that drops view and new tables and restores the old table name from the backup (and optionally re-creates the dropped FKs).

---

## 11. Configuration — Concrete Steps

**File:** Wherever `schema_normalization` is read (e.g. `SchemaNormalizationAnalyzer` and `LiquibaseGenerator`).

**Add under `schema_normalization`:**

- `ddl_mode`: `"raw_sql"` | `"liquibase"` (default `"liquibase"`).
- `liquibase_master_file`: path to master changelog (override; if absent, fall back to `query_optimizer.liquibase_master_file`).
- `supported_dialects`: list of dialects for normalization (if absent, fall back to query_optimizer).
- `rename_old_table_to`: string (required). Backup name for the old table, e.g. `customer_backup` or pattern `{sourceTable}_backup`.
- Optionally `drop_old_table_after_rename`: boolean (default false); reserved for future use.
- `base_path`: for entity and mapping output (if absent, use existing base_path).

**File:** `LiquibaseGenerator.java` — `ChangesetConfig.fromConfiguration(String configSection)` is already parameterized. In `SchemaNormalizationAnalyzer.generateMigrationArtifacts()`, call `ChangesetConfig.fromConfiguration("schema_normalization")` and use that config when creating `LiquibaseGenerator`. If `schema_normalization` has no `liquibase_master_file`, then fall back to `fromConfiguration("query_optimizer")` for that value only, or document that users must set `schema_normalization.liquibase_master_file` for normalization.

---

## 12. Old–New Mapping Artifact — Concrete Steps

**Goal:** Provide a **single, explicit mapping** between the old schema and the new one so that developers (and tooling) can answer: “What view replaces the old table?”, “Which new tables/entities did this old entity become?”, and “Where did each old column go?”. The current implementation does not emit this; the plan includes it here.

**Contents (minimum):**

| Field | Meaning |
|-------|--------|
| `sourceTable` | Original (denormalized) table name. |
| `sourceEntity` | Original JPA entity class name (from `EntityProfile.entityName()`). |
| `viewName` | Name of the compatibility view (same as `sourceTable` after migration). |
| `newTables` | New normalized table names (order: topological / insert order). |
| `newEntities` | New JPA entity class names (e.g. PascalCase of `newTables`, as generated). |
| `columnMappings` | For each old column: `viewColumn` (old), `targetTable`, `targetColumn` (new). |
| `foreignKeys` | FK edges between new tables (fromTable, fromColumn, toTable, toColumn). |

**Format:** JSON or YAML. Example:

```json
{
  "sourceTable": "customer",
  "sourceEntity": "Customer",
  "viewName": "customer",
  "newTables": ["customer", "address"],
  "newEntities": ["Customer", "Address"],
  "columnMappings": [
    { "viewColumn": "id", "targetTable": "customer", "targetColumn": "id" },
    { "viewColumn": "name", "targetTable": "customer", "targetColumn": "name" },
    { "viewColumn": "street", "targetTable": "address", "targetColumn": "street" }
  ],
  "foreignKeys": [
    { "fromTable": "address", "fromColumn": "customer_id", "toTable": "customer", "toColumn": "id" }
  ]
}
```

**Where to write:** In `SchemaNormalizationAnalyzer.generateMigrationArtifacts()`, after generating changesets for a plan (or in a helper called from there), write one file per plan to e.g. `<base_path>/docs/normalization-mapping-<sourceTable>.json` (or a configurable path from `schema_normalization.mapping_output_dir`).

**Implementation:** Build a Map or POJO from the plan and the source `EntityProfile` (for `sourceEntity` and to derive `newEntities` from `newTables` using the same naming as entity generation). Serialize with ObjectMapper (JSON) or a YAML library; create the output directory if needed. Use `base_path` or `schema_normalization.base_path` for the base path.

---

## 13. Summary of Changes by File

| File / New file | Change |
|-----------------|--------|
| **SchemaNormalizationAnalyzer.java** | (1) Call validator for each plan; (2) Build changesets in order: DDL → migration → drop FKs referencing old table → rename old table → view → triggers; (3) Use NormalizedTableDDLGenerator for CREATE TABLE; (4) Add changesets to drop FKs that reference old table, then rename old table (never drop); (5) Fix buildCompatibilityViewSql to use topological order for JOINs; (6) Use ChangesetConfig.fromConfiguration("schema_normalization"); (7) Write mapping artifact. |
| **NormalizedTableDDLGenerator.java** (new) | Generate CREATE TABLE (raw SQL or Liquibase XML) from ViewDescriptor + EntityProfile in topological order; type mapping. |
| **DataMigrationPlanValidator.java** (new) | Validate column coverage, table consistency, base in newTables, DAG. |
| **LiquibaseGenerator.java** | Optional: add createCreateTableChangeset(...) for Liquibase createTable XML. Or not if DDL generator outputs full changeSet. |
| **DataMigrationGenerator.java** | No change for dependency order (already correct). Add unit test for 3-level dependency order. |
| **InsteadOfTriggerGenerator.java** | (1) Add explicit rollback DDL: DROP TRIGGER + DROP FUNCTION (PostgreSQL), DROP TRIGGER (Oracle); (2) Optional: H2 support; (3) Document 1:1 assumption. Extend tests for trigger DDL shape and (optional) integration test. |
| **LiquibaseGenerator.java** | When creating trigger changesets, support rollback content from generator (or generator emits full changeSet including rollback). |
| **generator.yml** | Add schema_normalization.ddl_mode, rename_old_table_to (required), liquibase_master_file, supported_dialects, base_path. |

---

## 14. Execution Order Checklist (Final)

For each `DataMigrationPlan`, the changesets must run in this order:

1. **CREATE TABLE** for each of `newTables` in **topological order** (parents first) — so referenced tables exist before tables that reference them.
2. **INSERT INTO new_table ... SELECT ... FROM old_table** for each of `newTables` in **topological order** — so referenced rows exist before inserting child rows (already implemented).
3. **Drop foreign key constraints** that reference the old table (from any other table in the schema). Renaming the old table would break these; they must be dropped first.
4. **RENAME TABLE old_table TO backup_name** (e.g. `old_table_backup`). Always rename; do not drop the old table initially, so data is preserved.
5. **CREATE VIEW old_table AS SELECT ...** (compatibility view).
6. **CREATE TRIGGER** (INSTEAD OF INSERT/UPDATE/DELETE) on the view.

Data migration dependency handling is already correct; the remaining work is DDL generation, pipeline order (DDL → migration → drop FKs → rename → view → triggers), discovery of FKs referencing the old table, view JOIN order, validation, configuration, and mapping artifact.