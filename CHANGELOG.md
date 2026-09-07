# Changelog

All notable changes to this project are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The `io.github.voodoo262.jdbc.moca.protocol` package is an implementation detail and is
**not** covered by those version guarantees; see its `package-info.java`.

## [Unreleased]

## [1.0.0] - 2026-09-07

First release.

### Added

- `java.sql.Driver` implementation for the MOCA command protocol over HTTP
  (`application/moca-xml`), auto-registered via `META-INF/services/java.sql.Driver`.
- `jdbc:moca:` URLs with `http`/`https`, an implied `http` default, and connection
  properties readable from either the URL query string or `DriverManager`.
- `Statement` and `PreparedStatement`. MOCA has no bind protocol, so parameters are escaped
  and inlined into MOCA literal syntax.
- Scroll-insensitive, read-only `ResultSet` with typed access to MOCA's column types,
  including its packed datetime format and Base64 binary values.
- `DatabaseMetaData`, with catalog browsing built on MOCA's own catalog commands
  (`list user tables description`, `list table columns`, `list table indexes`). Column types
  come from `comtyp`, and indexes and primary keys are derived from `list table indexes`.
- A bare `SELECT` is automatically wrapped in MOCA's native-SQL brackets, so a JDBC tool can
  view table data without knowing about the passthrough syntax.
- Transactions via the MOCA `commit`/`rollback` commands, driven by `setAutoCommit`.
- MOCA server errors surfaced as `SQLException` with the MOCA status as the vendor code and
  a standard `SQLState`.
- Zero runtime dependencies; ships as a single jar.

### Security

- Response XML is parsed with `DOCTYPE` declarations disabled, closing XXE and entity
  expansion.
- `DatabaseMetaData.getURL()` redacts a password supplied in the URL query string.
