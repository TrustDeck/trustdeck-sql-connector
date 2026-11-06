# TrustDeck SQL Connector

This Apache-2.0 application reads rows from one table of a SQL database and calls TrustDeck through its Java client with one configured operation. The primary target is Microsoft SQL Server; PostgreSQL is supported as well, and other databases can be used by adding their JDBC driver (see [`drivers/README.md`](drivers/README.md)). The connector only reads from the database. It does not create projects, entity types, domains, schemas, or permissions.

## Run

Prerequisites are Java 21, Maven 3.8.7 (the verified host version; Maven 3.x with the same POM/plugin compatibility is supported), Docker/Compose, a SQL login with SELECT permission on the source table, and an existing TrustDeck project/type/domain. The Docker builder supplies Maven 3.9.11 explicitly. Use a confidential Keycloak client with service accounts and client-credentials access. Grant the exact resource permissions described in the Permissions section below; the JWT subject must be the TrustDeck service-account user's UUID. Keycloak realm-admin roles alone do not grant TrustDeck permissions.

The connector uses the released `org.trustdeck:client:2.0.0` package from Maven Central:

```sh
mvn clean verify
```

No GitHub Packages credentials or local client checkout are required. Never put credentials in the POM, image, build args, or history:

```sh
docker build --file docker/Dockerfile -t trustdeck-sql-connector:1.0.0 .
```

Maven does not source `.env`. In WSL:

```sh
cp .env.example .env
vim .env
set -a
source ./.env
set +a
mvn spring-boot:run
```

The generic classpath `application.yaml` requires `TRUSTDECK_OPERATION`, the database connection (`DB_URL`, or `DB_DIALECT`/`DB_HOST`/`DB_NAME`), `DB_USERNAME`, `DB_PASSWORD`, `SOURCE_TABLE`, `SOURCE_KEY_COLUMN`, backend/token URLs, and service-account credentials. It has no sample mapping defaults.

The root `config/instance` directory is retained for deployment-only overrides: Compose mounts its optional `application.yaml` at `/app/config/application.yaml` and its `truststores` directory read-only, while `SPRING_CONFIG_ADDITIONAL_LOCATION` imports that directory. It is not packaged as application configuration and contains no credentials. Entity field mappings and driver-specific connection properties belong there.

## Database Connection

The connection is given either as a complete JDBC URL (`DB_URL`) or as `DB_DIALECT`, `DB_HOST`, optional `DB_PORT`, and `DB_NAME`. A complete URL takes precedence. Only SQL logins (username and password) are supported.

| `DB_DIALECT` | Database | URL built from host and name | Row limit |
|---|---|---|---|
| `SQLSERVER` | SQL Server 2012+ | `jdbc:sqlserver://host:1433;databaseName=name;encrypt=true;trustServerCertificate=false` | `SELECT TOP (n)` |
| `POSTGRESQL` | PostgreSQL 12+ | `jdbc:postgresql://host:5432/name?sslmode=verify-full` | `LIMIT n` |
| `OTHER` | Any database with SQL standard `FETCH FIRST` | `DB_URL` required | `FETCH FIRST n ROWS ONLY` |
| `OTHER_LIMIT` | Any database with `LIMIT`, e.g. MySQL | `DB_URL` required | `LIMIT n` |

When `DB_DIALECT` is empty, it is detected from `DB_URL`. Built URLs always require TLS with certificate and host name validation; the host and database name may only contain plain characters, so they cannot inject further URL settings. A server certificate that is not signed by a public CA needs a truststore. Put it into `config/instance/truststores` and reference it in `config/instance/application.yaml`:

```yaml
connector:
  database:
    connection-properties:
      # SQL Server
      trustStore: /app/truststores/sqlserver.jks
      trustStorePassword: ${DB_TRUSTSTORE_PASSWORD}
      # PostgreSQL (PEM file)
      # sslrootcert: /app/truststores/postgres-ca.pem
```

The connection pool marks its connections read-only, but not every driver enforces this (SQL Server does not). Grant the SQL login SELECT on the source table and nothing else. Only the SQL Server and PostgreSQL drivers are included; [`drivers/README.md`](drivers/README.md) explains why other drivers are not shipped and how to add one at runtime.

## Source Table and Progress

The connector reads one table or view, configured by `SOURCE_SCHEMA` (optional), `SOURCE_TABLE`, `SOURCE_KEY_COLUMN`, an optional `SOURCE_PROGRESS_COLUMN`, and an optional `SOURCE_WHERE` filter. Custom SELECT statements are not supported. All names are looked up in the database catalog at startup and matched case-insensitively; startup fails if the table or a configured column does not exist or is ambiguous (configure the schema in that case). Only names reported by the database are inserted into SQL, quoted with the driver's identifier quote.

Rows are read page by page (`SOURCE_PAGE_SIZE`, default 500), ordered by the progress column and then by the key column. Each query continues strictly after the last completed row (keyset pagination), so no offsets are used and indexed tables stay fast. The requirements are:

- The key column identifies a row uniquely. A warning is logged if no primary key or single-column unique index covers it.
- The progress column (default: the key column) only increases for new rows, for example an `IDENTITY` column, an insertion time stamp, or a SQL Server `rowversion`. Rows that later appear with a smaller progress value than an already completed row are not read. Changes to already completed rows are not detected.
- Rows with a NULL key or progress value cannot be ordered and are never read.
- Supported key and progress types are integers, decimals, text, dates, time stamps (with and without offset), UUIDs, and binary values such as `rowversion`.

SQL Server compares a `datetime` column with a `datetime2` parameter after converting the column, which changes its stored fractions (`.003` becomes `.0033333`). The connector therefore casts parameters for `datetime` and `smalldatetime` progress columns to the column type, so a row is never read twice. Prefer `datetime2`, `bigint`, or `rowversion` progress columns for new tables.

`SOURCE_WHERE` is inserted in parentheses and combined with the progress condition. It must not contain `;`, `--`, `/*`, or `*/`, and its parentheses and quotes must be balanced. The connector runs the complete query once at startup, so a filter the database cannot execute stops the startup. Example: `SOURCE_WHERE=Status = 'final' AND (Site IN ('A', 'B'))`.

After a row is completed (confirmed by TrustDeck, or written to the dead-letter or review file), its position is stored atomically in the progress file (`CONNECTOR_PROGRESS_FILE`, default `state/progress.json`). A restart continues after that row. The file records the operation, table, key and progress column, and filter it belongs to; if any of them changes, startup fails instead of continuing at a position that may not fit. Move the progress file away to start again from the first row.

## Run Modes

`CONNECTOR_MODE` selects how the table is read:

| Mode | Behavior |
|---|---|
| `ONE_SHOT` (default) | Reads all matching rows, then sends a summary mail (if `SMTP_TO` is set) and exits with status 0. A second start continues after the last completed row and normally finds nothing to do. |
| `POLLING` | Reads all matching rows, then keeps querying for new rows every `CONNECTOR_POLL_INTERVAL` (default 60s). |

In both modes, an outage of TrustDeck, Keycloak, the database, or a state file pauses processing at the first row that is not completed; the readiness probe reports DOWN while liveness stays UP. Recovery probes run every `RECOVERY_PROBE_INTERVAL` (default 15s) and check the TrustDeck resources involved, the source query, and the state files. Processing resumes once all probes succeed. An outage never stops a one-shot import early. An unexpected failure that a pause cannot fix (for example an unsupported key type) logs an error, sends a mail, and exits with status 1.

Once per minute the connector logs the number of rows read, the throughput, the last completed key, and the counts of successful, dead-lettered, and reviewed rows.

## Mappings

Values are column names of the source table, matched case-insensitively. A constant domain (`TRUSTDECK_DOMAIN`) wins over the domain column. Provide only the mappings required by the selected operation. Mappings used exclusively by other operations may be omitted. Optional mappings may also be omitted. Every required mapping is checked at startup.

SQL values are converted as follows:

| Target | Accepted column types | Conversion |
|---|---|---|
| Identifier, id type, psn, domain, entity type, username, validity duration | Text, integers, decimals, UUID | Text unchanged (leading zeros kept); numbers as plain decimal text. Floating-point, boolean, date, and binary columns are rejected because their text form is ambiguous. NULL and blank values count as absent. |
| Valid from / valid to | Time stamp, time stamp with offset, date, text | With offset: converted to UTC. Without offset (e.g. `datetime2`): interpreted in `MAPPING_SOURCE_TIMEZONE`, ambiguous or nonexistent local times (daylight-saving changes) are rejected. Dates stand for the start of the day. Text: ISO-8601 with offset, or `MAPPING_DATETIME_FORMAT` in the source timezone. |
| Entity id | UUID, text | Parsed as UUID. |
| Entity data fields | Text, integers, decimals, floating-point, boolean, date/time, UUID | Text stays text; numbers stay exact JSON numbers; booleans stay booleans; date/time values become ISO-8601 text. Binary columns are rejected. |

Entity data fields are YAML list entries under `connector.mapping.common.entity-fields` in the instance YAML, each with `column` (or `constant`), `target-path` (JSON Pointer, e.g. `/patient/birthDate`), and optional `required`.

`src/main/resources/example-pseudonymization.yaml` is an opt-in synthetic example of a pseudonym import from a SQL Server table. Load it with `mvn spring-boot:run -Dspring-boot.run.arguments=--spring.config.additional-location=classpath:/example-pseudonymization.yaml` after replacing the placeholder values. It is not imported during normal startup.

Supported operations are `CREATE_PSEUDONYM`, `UPDATE_PSEUDONYM`, `DELETE_PSEUDONYM`, `CREATE_ENTITY`, `UPDATE_ENTITY`, `DELETE_ENTITY`, and `CREATE_ENTITY_AND_PSEUDONYM`. Updates require explicit old selectors and use the standard endpoint unless mapped identifier/domain/value changes require `updateComplete`; nothing moves or regenerates implicitly. Deletes use one exact pseudonym selector and never the domain-wide delete batch. Entity update/delete require a mapped TrustDeck UUID. Combined creation keeps the completed entity stage for retries of the same row and never rolls it back.

### Operation Configuration

Each instance has one fixed `TRUSTDECK_OPERATION`. The following settings are the minimum operation-specific mappings; the common database, source, URL, and OAuth settings remain required.

| Operation | Required settings | Optional or additional settings |
|---|---|---|
| `CREATE_PSEUDONYM` | `TRUSTDECK_DOMAIN` or `SOURCE_DOMAIN_COLUMN`; identifier and id-type columns | Pseudonym value, validity, and time stamp settings |
| `UPDATE_PSEUDONYM` | Domain as above; identifier and id-type columns; old identifier and old id-type columns | New identifier/type/value, old/new domain, validity, and provenance columns |
| `DELETE_PSEUDONYM` | Domain as above; psn column, or identifier plus id-type columns | |
| `CREATE_ENTITY` | `TRUSTDECK_PROJECT`; fixed or mapped entity type | `mapping.common.entity-fields` in instance YAML supplies entity data |
| `UPDATE_ENTITY` | Entity settings above; entity id column | `mapping.common.entity-fields` supplies replacement data; per-field `required` and `constant` rules |
| `DELETE_ENTITY` | Entity settings above; entity id column | No entity field mappings are needed |
| `CREATE_ENTITY_AND_PSEUDONYM` | Entity settings above; domain | `mapping.common.entity-fields` supplies entity data; pseudonym value, validity, and provenance columns |

The column environment variables are the `SOURCE_*_COLUMN` entries in `.env.example`; common mappings are under `connector.mapping.common` and update selectors/replacements are under `connector.mapping.update-pseudonym`. `TRUSTDECK_PROJECT` is always required, because the client API is project-scoped; `TRUSTDECK_ENTITY_TYPE` is ignored for pseudonym-only operations.

## Permissions

Grant the service-account user only the permissions required by the selected operation: `project:read`, `type:read`, and the enabled entity CRUD permissions for entity operations; `domain:read` on each configured domain; `pseudonym:create` for pseudonym creation and `pseudonym:create-batch` for imports with a psn column; `pseudonym:read` and `pseudonym:delete` for exact lookup/deletion; `pseudonym:update` and `pseudonym:update-complete` when the corresponding update paths are enabled; `entity:list-pseudonyms` for combined creation; and `pseudonym:read` to explain rejected imports. Do not grant domain/project/type creation, global administration, `entity:resolve-linkage`, `pseudonym:read-batch`, or `pseudonym:delete-batch`. On the database side, grant SELECT on the source table only.

## Delivery and Recovery

A row is completed only after the TrustDeck call succeeded or its entry was written to a recovery file and forced to disk. Rows are completed strictly in read order. Two local JSON Lines files (one JSON object per line) take the role of the Kafka connector's recovery topics:

| File | Default | Content |
|---|---|---|
| Dead-letter file | `state/dlt.jsonl` (`CONNECTOR_DLT_FILE`) | Rows that failed definitively (`FAILED_DLT`) and rows whose outcome is unknown (`UNKNOWN_OUTCOME`) |
| Review file | `state/review.jsonl` (`CONNECTOR_REVIEW_FILE`) | Record-linkage conflicts (`PENDING_LINKAGE_REVIEW`) and imported pseudonyms that contradict the domain (`PENDING_CONFLICT_REVIEW`) |

An entry identifies the row by `sourceTable`, `keyColumn`, and `sourceKey`, and contains the operation, resolved target, sanitized failure category, HTTP status, attempts, completed stage, mapped provenance user, and, for mapping problems, a `detail` naming the affected column. Other column values are never copied; the row stays in the database. The files, like the progress file, are created readable for their owner only. Example:

```json
{"schemaVersion":"1","status":"FAILED_DLT","recordedAt":"2026-11-06T10:15:02.113Z","sourceTable":"\"dbo\".\"Patients\"","keyColumn":"Id","sourceKey":"4711","operation":"CREATE_PSEUDONYM","resolvedTarget":"clinic-a","failureCategory":"INVALID_MAPPING","attempts":0,"detail":"identifier-column must resolve to a nonblank value.","targetProject":"synthetic-project"}
```

Row mapping failures go directly to the dead-letter file. Transient operation failures use bounded retries (`RECOVERY_MAX_ATTEMPTS`, `RECOVERY_INITIAL_BACKOFF`, honoring `Retry-After`). A definitive client error (4xx except 401/403/408/429) is not retried and goes to the dead-letter file as `REJECTED_BY_TRUSTDECK`. A true client `RecordLinkageConflictException` goes to the review file.

For imported pseudonyms (`CREATE_PSEUDONYM` with a psn column), TrustDeck answers an exact duplicate with 200 and every other rejection with a body-less 422. The connector explains a 422 by looking up the pseudonym and the identifier in the target domain (requires `pseudonym:read`): an identical existing mapping counts as success, a contradicting one goes to the review file with category `PSEUDONYM_CONFLICT_IDENTIFIER_HAS_OTHER_PSEUDONYM` or `PSEUDONYM_CONFLICT_PSEUDONYM_ASSIGNED_ELSEWHERE`, and anything else stays `REJECTED_BY_TRUSTDECK`. TrustDeck's `idpsnidx` index makes identifier plus pseudonym unique across all domains, so importing a pair that already exists in another domain is rejected and cannot be explained by a lookup in the target domain. Consecutive rows of a page with the same domain and validity settings are sent as one batch of up to `TRUSTDECK_BATCH_SIZE`; every row the batch did not confirm is processed individually. TrustDeck answers 403 for a domain that does not exist, so a missing or unauthorized domain pauses processing; the log names the domain, and processing resumes once it exists and is readable.

There is no exactly-once HTTP claim. The crash window is after a successful TrustDeck call or recovery entry but before the progress file update; a restart performs that row's operation or entry again. Rows are re-run manually by fixing the cause and running a one-shot instance with a `SOURCE_WHERE` that selects the affected keys and its own progress file; there is no replay endpoint.

SMTP transport uses Spring Boot's standard `spring.mail.*` settings. Connector-specific `SMTP_FROM`, `SMTP_TO`, `SUBJECT_PREFIX`, and `SMTP_COOLDOWN` remain separate; `SMTP_TO` is empty by default, so mail is optional and no connection attempt occurs. Mails are sent when processing pauses and resumes, when a one-shot import completes (with its summary), and when the connector stops because of a fatal error. Mail failure never affects processing.

### Statistics

Row and page counters are maintained continuously without retaining historical snapshots in memory. When enabled, TrustDeck project and system statistics are sampled at `CONNECTOR_STATISTICS_INTERVAL` and streamed into a hidden temporary file in the configured output directory. The active segment is finalized and atomically published after 24 hours, after reaching 256 MiB, or during graceful shutdown. Age and size limits are configurable. In the statistics files, `records` counts rows read and `batches` counts pages.

## Deployment

`connector.sh` wraps the Compose calls. `standalone` uses only the base file, `trustdeck` adds the overlay that joins the external `trustdeck` network, so `.env` can address `trustdeck-backend` and `trustdeck-keycloak` by container name. `start` checks `.env` and the instance files, prepares the state and statistics directories, builds the image, recreates the container, and waits for readiness (or, for a short one-shot import, for its successful end); `stop`, `restart`, `logs`, and `build` do what they say. `status` also prints the last pause, recovery, and completion events. `progress` shows the last completed row, the latest throughput line, and a breakdown of the dead-letter and review files (needs `jq` for the breakdown). Set `CONNECTOR_PROJECT` to run several instances side by side (default project: `trustdeck-sql-connector`).

```sh
  ./connector.sh trustdeck start
  ./connector.sh trustdeck progress
  CONNECTOR_PROJECT=trustdeck-sql-connector-b ./connector.sh standalone start
```

The equivalent plain Compose calls are:

```sh
  docker compose --env-file .env -p trustdeck-sql-connector-a -f docker/compose.yaml up -d
  docker compose --env-file .env -p trustdeck-sql-connector-b -f docker/compose.yaml -f docker/compose.trustdeck-network.yaml up -d
```

The progress, dead-letter, and review files are written to `/app/state`, which is mounted from `STATE_HOST_DIR` (default `state/` in the repository root), so they survive container recreation. Keep the `CONNECTOR_*_FILE` settings relative (`state/...`) so the files land in this mount. Statistics are written to `/app/statistics`, mounted from `STATISTICS_HOST_DIR`. Both directories must be writable by UID 10001; `connector.sh` creates them and hands them over, otherwise Docker would create them owned by root. Instances with different tables or operations need their own state directories.

The default restart policy `on-failure:5` (`CONNECTOR_RESTART_POLICY`) leaves a finished one-shot import (status 0) stopped and retries a failed start a few times. Outages never stop the container. The console log uses the TrustDeck backend format (`[LEVEL]<tab>yyyy-MM-dd HH:mm:ss,SSS Class:line message`, see `logback-spring.xml`). Third-party libraries log warnings only; set `LOGGING_LEVEL_ROOT=INFO` to see driver and pool details, and `LOG_COLORS=never` for log collectors without ANSI support.

Behind an HTTP proxy, export `HTTP_PROXY`, `HTTPS_PROXY`, and `NO_PROXY` (or the lowercase variants) in the shell that runs `docker compose build`. Compose passes them as build arguments; `apt-get` uses them directly, and Maven receives a temporary `settings.xml` generated from them (`docker/maven-proxy.sh`). They are not stored in the image. For a private Maven repository, pass a complete `settings.xml` instead, which takes precedence: `docker build -f docker/Dockerfile --secret id=maven_settings,src=$HOME/.m2/settings.xml -t trustdeck-sql-connector:1.0.0 .`

The base Compose file requires only an `.env`, read-only instance YAML/truststore/driver mounts, the state directory, and a free health port (default 18081). The override joins an already existing external `trustdeck` network. Health is exposed only at `/actuator/health/readiness` and `/actuator/health/liveness`.

## Verification and Limitations

The local verification matrix is `mvn clean verify` (including strict Javadocs), both Compose config commands, the root-context Docker build, and shellcheck for the scripts. The test suite covers configuration validation, dialects, value conversion, keyset paging (H2), progress and recovery files, row processing, the runner's modes and outage handling, the HTTP contracts of all seven operations, and the complete application against H2. With Docker available, it also runs the complete application against disposable SQL Server 2022 and PostgreSQL 17 containers; without Docker, these tests are skipped. The SQL Server test uses a SELECT-only SQL login, a `datetime` progress column, and `datetimeoffset` values; the PostgreSQL test uses a UUID key, a `timestamptz` progress column, and a filter. Generate and review the dependency/license inventory with `mvn org.codehaus.mojo:license-maven-plugin:2.7.0:add-third-party -Dlicense.includedScopes=compile,runtime`; inspect `target/generated-sources/license/THIRD-PARTY.txt`, including the TrustDeck client and all transitives.

Before live deployment, verify synthetic resources and permissions with the TrustDeck service-account UUID, the SELECT-only SQL login, TLS with certificate validation for both database and TrustDeck, readiness/liveness, and a real end-to-end operation on a test table. The connector creates no backend resources and never writes to the database.

Architectural boundaries, not missing work, are strict exactly-once HTTP effects, original database-user REST audit attribution, automatic record-linkage resolution, detection of changed or late-arriving rows (rows behind the stored position), and the unavoidable crash window between a successful HTTP call or recovery entry and the progress update. The backend's timezone-free `LocalDateTime` and mixed local/UTC comparisons cannot be fully repaired here. Identifier-only reads are ambiguous in multiple-pseudonym domains. Batch 206 responses can lack safe correlation for generated, repeated, or reduced results; the connector then reports unknown outcomes.

Dependency/license sources: Spring Boot and Spring JDBC are Apache-2.0 (`https://spring.io/projects/spring-boot`); HikariCP is Apache-2.0 (`https://github.com/brettwooldridge/HikariCP`); Jackson is Apache-2.0 (`https://github.com/FasterXML/jackson`); the Microsoft JDBC Driver for SQL Server is MIT (`https://github.com/microsoft/mssql-jdbc`); the PostgreSQL JDBC driver is BSD-2-Clause (`https://github.com/pgjdbc/pgjdbc`); Lombok is MIT (`https://github.com/projectlombok/lombok`). Test-only: JUnit is EPL-2.0 (`https://github.com/junit-team/junit5`); Testcontainers is MIT (`https://github.com/testcontainers/testcontainers-java`); H2 is MPL-2.0 or EPL-1.0 (`https://github.com/h2database/h2database`). The TrustDeck client and this application are Apache-2.0. These are summaries of direct dependencies, not a complete notice. The generated report is the distribution review source for resolved transitives; do not describe EPL components as Apache relicensed.
