# Contributing

Issues and pull requests are welcome.

## Building

```sh
./gradlew build     # compile, test, javadoc, jar
./gradlew test      # tests only
```

Nothing else is needed: the project has no dependencies, and the tests run the driver
end-to-end over real HTTP against a fake MOCA server (`FakeMocaServer`) rather than against
a live one. There is no server to configure and no credential to obtain.

## What CI enforces

- `-Werror` with `-Xlint:all`. A new warning fails the build.
- Javadoc with `-Xdoclint:all,-missing`. Missing docs are fine; a broken `@link` or `@see`
  is not.
- Sources are UTF-8, and the build says so explicitly — do not remove `options.encoding`.

## Conventions

- Match the surrounding style: Allman braces, `final` on parameters and locals, four-space
  indent. `.editorconfig` covers the mechanical parts.
- Comments should explain *why*, especially where MOCA and JDBC disagree and the driver had
  to pick one. Most of the existing comments are of this kind; please keep that up rather
  than describing what the code plainly does.
- Behaviour changes need a test. The suite asserts on what goes on the wire, which is the
  thing that actually matters for a driver.

## Things to know before changing them

- **`META-INF/services/java.sql.Driver`** must name `MocaDriver`. If it goes stale the driver
  silently stops auto-registering; `MocaDriverTest.driverIsAutoRegisteredViaServiceLoader`
  is what catches that.
- **The `protocol` package is internal.** Its classes are public only so the JDBC layer can
  reach them, and they are not covered by semantic versioning.
- **`MocaPreparedStatement` must not route through `execute(String)`** and friends — JDBC
  requires a `PreparedStatement` to reject those, so the internal path uses the
  `doExecute*` helpers instead.
- **The version comes from the jar manifest**, not a literal. Release tags must match
  `build.gradle`; CI checks it.
