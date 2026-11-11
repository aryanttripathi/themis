# Themis

[![CI](https://github.com/aryanttripathi/themis/actions/workflows/ci.yml/badge.svg)](https://github.com/aryanttripathi/themis/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/io.github.aryanttripathi/themis-core)](https://central.sonatype.com/artifact/io.github.aryanttripathi/themis-core)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](LICENSE)

> Named after the Greek goddess of law and justice: Themis weighs your dependencies and code against the rules.

Themis scans a Java project (Maven or Gradle) and reports, in one place:

- **Licenses** of every direct and transitive dependency, classified by obligation (permissive, weak/strong/network copyleft, unknown)
- **Known vulnerabilities** in the exact resolved versions (OSV)
- **Safe upgrades**: the smallest version that fixes them *and* runs on your Java release
- **Java version** each library needs (read from bytecode)
- **Hardcoded secrets** and **PII exposure** (logs, `toString()`, responses) in your code and config

Only public package coordinates leave your machine; source code never does.

> Status: v0.1. Maven and Gradle resolution, licenses, OSV vulnerabilities, runtime-aware upgrade advice, Java version per jar, secrets, PII, suppressions/baseline, console/HTML/JSON/SARIF reports.

## Install

**CLI** with Homebrew (macOS, Linux):

```bash
brew install aryanttripathi/tap/themis
themis scan .
```

Or download `themis.jar` from the [latest release](https://github.com/aryanttripathi/themis/releases/latest) and run it with Java 17+:

```bash
java -jar themis.jar scan .
```

**Library** (Maven Central):

```xml
<dependency>
    <groupId>io.github.aryanttripathi</groupId>
    <artifactId>themis-core</artifactId>
    <version>0.1.0</version>
</dependency>
```

```kotlin
implementation("io.github.aryanttripathi:themis-core:0.1.0")
```

## Build from source

Requires JDK 17+ and Maven 3.9+.

```bash
mvn -B clean verify          # builds themis-cli/target/themis.jar and runs the tests
mvn -Pnetwork verify         # also run the tests that need Maven Central
```

Gradle integration tests run only when a Gradle installation is found (or `THEMIS_TEST_GRADLE` is set); otherwise they are skipped.

## Quick start

```bash
alias themis='java -jar /path/to/themis.jar'

cd my-java-project
themis scan .
```

`themis scan .` resolves dependencies with your own Maven/Gradle setup, runs every analyzer and prints a console summary. Above a threshold it writes an HTML report to `themis-report/` and opens it.

## Commands

| Command | What it does |
|---|---|
| `themis scan [DIR]` | Full scan: licenses, vulnerabilities, upgrades, Java version, secrets, PII |
| `themis deps [DIR]` | Resolve and list direct and transitive dependencies |
| `themis licenses [DIR]` | List dependency licenses by obligation |
| `themis vulns [DIR]` | List known vulnerabilities in dependencies (OSV) |
| `themis baseline [DIR]` | Record current findings so later scans report only new ones (`.themis-baseline.json`) |

Useful options (see `themis <command> --help` for all):

```bash
themis scan . --format console,html,json,sarif   # output formats
themis scan . --only secrets,pii                 # run only some analyzers
themis scan . --fail-on CRITICAL                 # exit 1 only on CRITICAL findings
themis scan . --offline                          # local cache and ~/.m2 only, no network
themis scan . --include-test                     # include test-scoped dependencies
themis scan . --no-open                          # don't open the HTML report
themis deps . --ignore-pom-repositories          # for untrusted projects
```

Analyzers: `licenses`, `vulns`, `javaversion`, `upgrade`, `secrets`, `pii`.

## Output formats

| Format | Where | Use |
|---|---|---|
| `console` | stdout | Local runs; summary when findings exceed `--console-limit` |
| `html` | `themis-report/` | Browsable report with suppressed-findings tab |
| `json` | `themis-report/` | Machine-readable, stable schema (see [LLD](docs/LLD.md)) |
| `sarif` | `themis-report/themis.sarif` | GitHub code scanning and other SARIF consumers |

## Configuration (`.themis.yml`)

Optional; `themis scan .` works with zero config. Precedence: CLI flags > `--config FILE` or the project's `.themis.yml` > defaults. Unknown keys are errors.

```yaml
javaRelease: 17
analyzers: [licenses, vulns, javaversion, upgrade, secrets, pii]
failOn:
  severity: HIGH
licenses:
  severity: {NETWORK_COPYLEFT: HIGH, STRONG_COPYLEFT: LOW, WEAK_COPYLEFT: NONE}
  allowArtifacts: ["com.mysql:*"]
privacy:
  privateArtifacts: ["com.mycompany.internal:*"]
  projectGroupPrivate: true
secrets:
  exclude: ["legacy/**"]
pii:
  extraFields: [customerId]
suppress:
  - id: "VULNERABILITY:GHSA-*:com.mysql:*"
    reason: "Driver only used by an internal batch job"
    until: 2027-03-31
```

Full reference: [docs/LLD.md section 13](docs/LLD.md#13-configuration-themisyml).

## Privacy

- Source code, config contents, file paths, project names and internal package names **never leave your machine**.
- Only `group:artifact:version` of **public** packages is sent to OSV.dev, deps.dev and Maven Central, over HTTPS, without cookies or credentials.
- Internal groups, snapshots and anything matching `privacy.privateArtifacts` are never looked up.
- Secret findings never store the secret: they carry a masked preview and a fingerprint.
- Dependency resolution uses your own Maven/Gradle setup (repositories and credentials), exactly as your build does.

## Library usage

```java
ScanReport report = Themis.scan(Path.of("."), ScanConfig.defaults());
report.findings().forEach(f -> System.out.println(f.severity() + " " + f.title()));
```

## Modules

| Module | Purpose |
|---|---|
| `themis-core` | Embeddable library: model, `ScanEngine`, analyzers, reporters |
| `themis-cli` | `themis` command (picocli), packaged as a runnable jar |

## Exit codes

| Code | Meaning |
|---|---|
| 0 | No findings at or above `--fail-on` (default `HIGH`) |
| 1 | Findings at or above the threshold |
| 2 | Usage, configuration or resolution error |

## Docs

- [Why](docs/WHY.md): the problem and goals
- [High-level design](docs/HLD.md): architecture, components, decisions
- [Low-level design](docs/LLD.md): model, interfaces, algorithms, config, report schema

## License

[Apache License 2.0](LICENSE). Copyright 2026 Aryant Tripathi.
