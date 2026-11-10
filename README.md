# Themis

> Named after the Greek goddess of law and justice: Themis weighs your dependencies and code against the rules.

Scans a Java/Maven project and reports, in one place:

- **Licenses** of every direct and transitive dependency, classified by obligation (permissive, weak/strong/network copyleft, unknown)
- **Known vulnerabilities** in the exact resolved versions
- **Safe upgrades**: the smallest version that fixes them *and* runs on your Java release
- **Java version** each library needs (read from bytecode)
- **Hardcoded secrets** and **PII exposure** (logs, `toString()`, responses) in your code and config

Only package coordinates leave your machine; source code never does.

> Status: v0.1. Maven and Gradle resolution, licenses, OSV vulnerabilities, runtime-aware upgrade advice, Java version per jar, secrets, PII, suppressions/baseline, console/HTML/JSON/SARIF reports. See [docs/WHY.md](docs/WHY.md), [docs/HLD.md](docs/HLD.md), [docs/LLD.md](docs/LLD.md).

## Docs

- [Why](docs/WHY.md): the problem and goals
- [High-level design](docs/HLD.md): architecture, components, decisions
- [Low-level design](docs/LLD.md): model, interfaces, algorithms, config, report schema

## Build

Requires JDK 17+ and Maven 3.9+.

```bash
mvn verify
java -jar themis-cli/target/themis.jar scan /path/to/project --format console,json
java -jar themis-cli/target/themis.jar deps /path/to/project --include-test   # add --ignore-pom-repositories for untrusted projects
mvn -Pnetwork verify     # also run the tests that need Maven Central
```

## Modules

| Module | Purpose |
|---|---|
| `themis-core` | Embeddable library: model, `ScanEngine`, analyzers, reporters |
| `themis-cli` | `themis` command (picocli), packaged as a runnable jar |

## Library usage

```java
ScanReport report = Themis.scan(Path.of("."), ScanConfig.defaults());
report.findings().forEach(f -> System.out.println(f.severity() + " " + f.title()));
```

## Exit codes

| Code | Meaning |
|---|---|
| 0 | No findings at or above `--fail-on` (default `HIGH`) |
| 1 | Findings at or above the threshold |
| 2 | Usage, configuration or resolution error |
