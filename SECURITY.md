# Security Policy

## Supported versions

The latest release is supported. Fixes are not backported.

## Reporting a vulnerability

Please report security issues privately through
[GitHub Security Advisories](https://github.com/Voodoo262/moca-jdbc/security/advisories/new)
rather than opening a public issue.

Include what you need to demonstrate the problem — a connection URL shape, a MOCA response
that triggers it, or a short reproducer. Please redact real hostnames and credentials.

This is a personal project maintained in spare time; expect an acknowledgement within a week
or so rather than on a formal schedule.

## Scope

The parts of this driver where a security bug is most likely, and most worth reporting:

- **`MocaPreparedStatement.quote`** — MOCA has no bind-parameter protocol, so parameters are
  escaped and interpolated into command text. This escaping is the only boundary between a
  caller's value and executable MOCA. Same for the identifier patterns in
  `MocaDatabaseMetaData`, which reach the underlying database's native SQL.
- **`MocaResponseXml`** — parses XML received from the network.
- **Credential handling** — anything that causes a password to be logged, returned from a
  getter, or included in an exception message.

Out of scope: the driver defaults to plain `http` when the URL omits a scheme, and MOCA
credentials are then sent in cleartext. This is documented in the README rather than treated
as a vulnerability; use `https`.
