# Themis: Low-Level Design

Status: Draft v0.1 · Companion to [HLD.md](HLD.md)

## 1. Modules and packages

```
themis/
├── pom.xml                         parent (Java 17, dependency versions)
├── themis-core/                    the library: no CLI concerns
│   └── io.themis
│       ├── model/                  immutable domain records
│       ├── engine/                 ScanEngine, ScanContext, Analyzer SPI
│       ├── config/                 ScanConfig, LicensePolicy, loader
│       ├── resolve/                DependencyResolver SPI + maven/ impl
│       ├── license/                LicenseAnalyzer, SpdxNormalizer, LicenseClassifier
│       ├── vuln/                   VulnerabilityAnalyzer, OsvClient
│       ├── javaversion/            JavaVersionAnalyzer, ClassFileVersion
│       ├── upgrade/                UpgradeAdvisor, VersionCatalog
│       ├── secrets/                SecretAnalyzer, rules/, Entropy
│       ├── pii/                    PiiAnalyzer, PiiFieldMatcher
│       ├── suppress/               Suppressions, Baseline
│       ├── report/                 ReportWriter SPI + json/, html/, sarif/
│       └── cache/                  FileCache
├── themis-cli/                     picocli entry point, packaged as a runnable jar
│   └── io.themis.cli
└── docs/
```

Rule: `cli` depends on `core`; `core` never depends on `cli`. A future `themis-maven-plugin` module will also depend only on `core`.

## 2. Domain model (`io.themis.model`)

All model types are Java `record`s (immutable, value equality).

```java
record Coordinate(String groupId, String artifactId, String version) {
    String ga();              // "groupId:artifactId"
    String toString();        // "g:a:v"
}

enum Scope { COMPILE, RUNTIME, PROVIDED, TEST, SYSTEM, IMPORT }

record Dependency(
    Coordinate coordinate,
    Scope scope,
    boolean direct,
    List<Coordinate> path,     // root -> ... -> this ("pulled in via")
    Optional<Path> jarFile     // resolved artifact in ~/.m2, if available
) {}

record DependencyGraph(Coordinate root, List<Dependency> dependencies) {}

record ProjectInfo(
    Path rootDir,
    Coordinate coordinate,
    int javaRelease,           // from maven.compiler.release / source / target, default 17
    List<Path> sourceRoots,
    List<Path> resourceRoots
) {}

enum Severity { INFO, LOW, MEDIUM, HIGH, CRITICAL }

enum FindingType { LICENSE, VULNERABILITY, JAVA_VERSION, UPGRADE, SECRET, PII }

record Location(Path file, int line, int column) {}

record Finding(
    String id,                 // stable fingerprint, used for suppression/baseline
    FindingType type,
    Severity severity,
    String title,
    String detail,
    Optional<Coordinate> dependency,
    Optional<Location> location,
    Map<String, String> evidence   // e.g. source="pom", spdx="AGPL-3.0-only"
) {}

record ScanReport(
    String toolVersion,
    Instant startedAt,
    Duration duration,
    ProjectInfo project,
    DependencyGraph graph,
    List<Finding> findings,
    List<AnalyzerError> errors
) {}

record AnalyzerError(String analyzer, String message) {}
```

Analyzer-specific details (license info, advisories, upgrade suggestions) are carried in typed records too, so the report can render rich tables:

```java
enum LicenseCategory { PERMISSIVE, WEAK_COPYLEFT, STRONG_COPYLEFT, NETWORK_COPYLEFT, PROPRIETARY, UNKNOWN }

record LicenseInfo(Coordinate dep, List<String> spdxIds, LicenseCategory category, String source) {}

record Advisory(String id, List<String> aliases, Severity severity, String summary,
                List<String> fixedVersions, String url) {}

record UpgradeSuggestion(Coordinate current, String recommendedVersion,
                         int requiredJavaRelease, List<String> fixes, boolean sameMajor) {}
```

## 3. Engine (`io.themis.engine`)

```java
interface Analyzer {
    String name();                              // "licenses", "vulns", ...
    Stage stage();                              // DEPENDENCY, ADVISOR, SOURCE
    AnalyzerResult analyze(ScanContext ctx) throws Exception;
}

enum Stage { DEPENDENCY, ADVISOR, SOURCE }

final class ScanContext {
    ProjectInfo project();
    DependencyGraph graph();
    ScanConfig config();
    <T> List<T> resultsOf(Class<T> type);       // advisors read earlier stage outputs
}

record AnalyzerResult(List<Finding> findings, List<Object> data) {}
```

### `ScanEngine.scan(Path projectDir, ScanConfig cfg)`

```
1. project  = projectInspector.inspect(projectDir)
2. graph    = resolver.resolve(project, cfg)
3. ctx      = new ScanContext(project, graph, cfg)
4. run all enabled DEPENDENCY + SOURCE analyzers in parallel   (they are independent)
5. run ADVISOR analyzers (need vuln + javaversion data)
6. findings = suppressions.filter(allFindings)
7. sort by severity desc, then type
8. return ScanReport
```

Each analyzer runs inside `try/catch`; an exception becomes an `AnalyzerError` and the scan continues. Concurrency uses `Executors.newFixedThreadPool(n)` on Java 17 (virtual threads once we raise the baseline to 21).

## 4. Dependency resolution (`resolve`)

```java
interface DependencyResolver {
    boolean supports(Path projectDir);           // pom.xml present?
    DependencyGraph resolve(ProjectInfo project, ScanConfig cfg);
}
```

`MavenDependencyResolver` (Phase 1):
1. Build the effective model with `maven-model-builder` (parent POMs, properties, BOM imports).
2. Use `RepositorySystem.collectDependencies` + `resolveDependencies` (Maven Resolver) with the user's `~/.m2/settings.xml` mirrors and the local repo.
3. Walk the `DependencyNode` tree depth-first, recording `path` for each node. Mediated/omitted nodes (conflict losers) are skipped, keeping the winner, which matches `mvn dependency:tree`.
4. Default scopes: `compile`, `runtime`; `--include-test` adds `test`.

Fallback (if the embedded resolver fails, e.g. private repo auth): run `mvn -q dependency:tree -DoutputType=json` and parse the output.

Multi-module projects: resolve each module and merge, de-duplicating by coordinate.

## 5. License analysis (`license`)

### Lookup order (first hit wins, source recorded in evidence)
1. User override in config (`licenses.overrides["g:a"]`).
2. `<licenses>` of the artifact's POM.
3. Walk `<parent>` POMs up to 5 levels.
4. deps.dev: `GET /v3/systems/maven/packages/{g:a}/versions/{v}` -> `licenses`.
5. `META-INF/LICENSE*` inside the jar (text fingerprint match against known licenses).
6. Otherwise `UNKNOWN`.

### SPDX normalization
`SpdxNormalizer` maps names and URLs to SPDX ids using a bundled alias table:

| Raw value | SPDX |
|---|---|
| "The Apache Software License, Version 2.0", `apache.org/licenses/LICENSE-2.0` | `Apache-2.0` |
| "MIT License", "The MIT License" | `MIT` |
| "GNU Affero General Public License v3" | `AGPL-3.0-only` |
| "Eclipse Public License - v 2.0" | `EPL-2.0` |

Matching: lowercase, strip punctuation and the words "the"/"license"/"version", then exact alias lookup, then URL lookup.

### Classification
`LicenseClassifier` maps SPDX ids to `LicenseCategory`:

| Category | SPDX ids (initial set) |
|---|---|
| PERMISSIVE | Apache-2.0, MIT, BSD-2-Clause, BSD-3-Clause, ISC, 0BSD, Unlicense, CC0-1.0 |
| WEAK_COPYLEFT | LGPL-2.1-*, LGPL-3.0-*, MPL-2.0, EPL-1.0, EPL-2.0, CDDL-1.0, CDDL-1.1 |
| STRONG_COPYLEFT | GPL-2.0-*, GPL-3.0-* |
| NETWORK_COPYLEFT | AGPL-3.0-*, SSPL-1.0 |
| UNKNOWN | anything else |

Dual-licensed artifacts (e.g. `EPL-2.0 OR GPL-2.0-with-classpath-exception`) take the **least restrictive** option, recorded in evidence.

### Policy -> Finding
```yaml
licenses:
  deny:  [NETWORK_COPYLEFT, STRONG_COPYLEFT]   # -> HIGH
  warn:  [WEAK_COPYLEFT, UNKNOWN]              # -> MEDIUM / LOW
  allowArtifacts: ["org.mariadb.jdbc:mariadb-java-client"]
```

## 6. Vulnerabilities (`vuln`)

`OsvClient`:
- `POST https://api.osv.dev/v1/querybatch` with up to 1000 queries:
  `{"package":{"ecosystem":"Maven","name":"g:a"},"version":"v"}`
- Batch response returns only ids; then `GET /v1/vulns/{id}` per unique id (cached for 24 h).
- Severity: parse the CVSS v3/v4 vector in `severity[]` -> score -> `Severity`
  (≥9.0 CRITICAL, ≥7.0 HIGH, ≥4.0 MEDIUM, >0 LOW); fall back to `database_specific.severity`.
- Fixed versions: from `affected[].ranges[].events[].fixed` for the matching package.

HTTP: `java.net.http.HttpClient`, 10 s timeout, 3 retries with exponential backoff, honours `HTTPS_PROXY`.

## 7. Java version (`javaversion`)

`ClassFileVersion.read(JarFile)`:
1. Iterate entries ending in `.class`, skipping `module-info.class` and `META-INF/versions/**`.
2. Read the first 8 bytes: magic `0xCAFEBABE`, minor (u2), **major (u2)**.
3. Take the **maximum** major over all classes (one class compiled for 21 makes the jar need 21).
4. Java release = `major - 44` (52 -> 8, 55 -> 11, 61 -> 17, 65 -> 21).
5. Multi-release jars (`Multi-Release: true` in the manifest): the base version is the requirement; versioned entries are reported as "enhanced on N+".

Finding: if `requiredRelease > project.javaRelease` -> `JAVA_VERSION`, `HIGH` (the build may compile but fail at runtime with `UnsupportedClassVersionError`).

## 8. Upgrade advisor (`upgrade`)

Input: a vulnerable dependency `d` with advisories `A`, project release `J`.

```
candidates = versionCatalog.versionsAfter(d.ga, d.version)   // maven-metadata.xml, sorted (ComparableVersion)
             .filter(v -> !isPreRelease(v))                    // drop -alpha, -beta, -RC, -M, -SNAPSHOT

for v in candidates (ascending):
    if osv.hasAdvisories(d.ga, v) with severity >= threshold: continue
    req = javaVersion.of(download(d.ga, v))                    // cached per version
    if req > J: continue
    return UpgradeSuggestion(d, v, req, A.ids, major(v) == major(d.version))

return "no compatible fix; newest fix requires Java X" (if one exists)
```

Notes:
- Ascending order gives the smallest jump, which minimizes breaking changes.
- Same-major candidates are tried first, then the next major.
- If `d` is transitive, the suggestion names the **direct** dependency to bump (from `path[1]`) and also offers a `<dependencyManagement>` override.
- Jar downloads for version checks go to the cache, not the project.

## 9. Secret detection (`secrets`)

Scanned files: `**/*.java` (AST) and `*.properties`, `*.yml`, `*.yaml`, `*.env`, `Dockerfile`, `*.json`, `*.xml` (line-based), excluding `target/`, `.git/`, `node_modules/`.

### Rules
```java
record SecretRule(String id, String name, Pattern pattern, Severity severity, double minEntropy) {}
```

| id | Detects | Pattern (simplified) |
|---|---|---|
| `aws-access-key` | AWS access key id | `\b(AKIA\|ASIA)[0-9A-Z]{16}\b` |
| `private-key` | PEM private key | `-----BEGIN (RSA \|EC \|OPENSSH )?PRIVATE KEY-----` |
| `jwt` | JSON Web Token | `eyJ[\w-]+\.eyJ[\w-]+\.[\w-]+` |
| `github-token` | GitHub PAT | `\bgh[pousr]_[A-Za-z0-9]{36,}\b` |
| `slack-token` | Slack token | `\bxox[baprs]-[A-Za-z0-9-]{10,}` |
| `generic-assignment` | `password=...`, `apiKey: ...` | key name matches `(?i)(pass(word)?\|secret\|token\|api[_-]?key\|credential)` and value entropy ≥ 3.5 |
| `jdbc-password` | password in a JDBC URL | `jdbc:[^\s]*password=[^&\s]+` |

### AST pass (JavaParser)
- `VariableDeclarator` / `FieldDeclaration` with a sensitive name and a `StringLiteralExpr` initializer.
- `MethodCallExpr` like `setPassword("...")`, `withApiKey("...")`, `new BasicAWSCredentials("...", "...")` with literal args.
- Ignore values that are placeholders: `${...}`, `changeme`, `<...>`, empty, or values shorter than 8 characters.

Shannon entropy: `H = -Σ p(c)·log2 p(c)` over characters of the value.

Findings record the location and a **masked** value (`AKIA****WXYZ`); full secrets are never written to reports.

## 10. PII detection (`pii`)

### PII vocabulary (configurable)
`email, phone, mobile, msisdn, aadhaar, pan, dob, dateOfBirth, address, pincode, passport, ssn, cardNumber, upi, ifsc, accountNumber, firstName, lastName, fullName`

`PiiFieldMatcher` matches identifiers in camelCase / snake_case after normalization (`customerPhoneNo` -> tokens `customer, phone, no` -> hit `phone`).

### Heuristics (Phase 6)
| Rule | Pattern in AST | Severity |
|---|---|---|
| `pii-in-log` | `log.info/debug/warn/error(...)` where an argument is a PII field access, a PII getter (`getEmail()`), or a variable named like PII | HIGH |
| `entity-in-log` | Logger argument whose type is a class containing PII fields (needs symbol solver) | MEDIUM |
| `pii-tostring` | Class with PII fields annotated `@Data` / `@ToString` without `@ToString.Exclude` on them, or a hand-written `toString()` that concatenates them | MEDIUM |
| `pii-in-exception` | `throw new XException("..." + email)` | MEDIUM |
| `pii-in-response` | Controller method returning an entity with PII fields directly (no DTO) | LOW |

Logger detection: receiver named `log`/`logger`/`LOG` or of type `org.slf4j.Logger`, `java.util.logging.Logger`, `org.apache.logging.log4j.Logger`; also `System.out.println`.

## 11. Suppressions (`suppress`)

Three mechanisms:
1. Annotation in code: `@SuppressScan("pii-in-log")` on a method/class, or comment `// themis:ignore secret` on the line.
2. Config:
   ```yaml
   suppress:
     - id: "VULN:GHSA-xxxx-xxxx:com.fasterxml.jackson.core:jackson-databind"
       reason: "Not reachable: we do not deserialize untrusted input"
       until: 2027-01-31
   ```
3. Baseline: `themis baseline` writes `.themis-baseline.json` with the fingerprints of current findings; later scans report only new ones.

Fingerprint (`Finding.id`): `TYPE:ruleOrAdvisoryId:ga[:file:normalizedLineContentHash]`. Line *content* hash rather than line *number*, so unrelated edits do not invalidate suppressions.

## 12. Configuration (`.themis.yml`)

```yaml
javaRelease: 17                 # override autodetection
analyzers: [licenses, vulns, javaversion, upgrade, secrets, pii]
scopes: [compile, runtime]
failOn:
  severity: HIGH                # exit 1 if any finding >= HIGH
licenses:
  deny: [NETWORK_COPYLEFT]
  warn: [STRONG_COPYLEFT, UNKNOWN]
  overrides:
    "com.example:legacy-lib": "Apache-2.0"
secrets:
  exclude: ["src/test/**"]
pii:
  extraFields: [customerId]
network:
  offline: false
  cacheTtlHours: 24
```

Precedence: CLI flags > `.themis.yml` in the project > built-in defaults.

## 13. CLI (`themis-cli`)

```
themis scan [DIR]
    --format=console,json,html,sarif   (default console,html)
    --output=DIR                       (default ./themis-report)
    --only=licenses,vulns,...
    --fail-on=LOW|MEDIUM|HIGH|CRITICAL|NONE
    --java-release=N
    --offline | --no-network
    --include-test
    --config=FILE
themis baseline [DIR]
themis version
```

Exit codes: `0` no findings at or above `--fail-on`; `1` findings above threshold; `2` usage/config/resolution error.

## 14. Report JSON shape (v1)

```json
{
  "schemaVersion": 1,
  "tool": {"name": "themis", "version": "0.1.0"},
  "project": {"coordinate": "com.acme:order-svc:1.4.0", "javaRelease": 17},
  "summary": {"dependencies": 243, "direct": 31,
              "bySeverity": {"CRITICAL": 1, "HIGH": 4, "MEDIUM": 9, "LOW": 3}},
  "dependencies": [
    {"coordinate": "org.yaml:snakeyaml:1.33", "scope": "COMPILE", "direct": false,
     "path": ["com.acme:order-svc:1.4.0", "org.springframework.boot:spring-boot-starter:3.0.0", "org.yaml:snakeyaml:1.33"],
     "license": {"spdx": ["Apache-2.0"], "category": "PERMISSIVE", "source": "pom"},
     "requiredJavaRelease": 7}
  ],
  "findings": [
    {"id": "VULNERABILITY:GHSA-mjmj-j48q-9wg2:org.yaml:snakeyaml", "type": "VULNERABILITY",
     "severity": "HIGH", "title": "SnakeYaml constructor deserialization RCE",
     "dependency": "org.yaml:snakeyaml:1.33",
     "evidence": {"aliases": "CVE-2022-1471", "fixed": "2.0"},
     "upgrade": {"recommended": "2.0", "requiredJavaRelease": 8, "sameMajor": false,
                 "bumpDirect": "org.springframework.boot:spring-boot-starter"}}
  ],
  "errors": []
}
```

## 15. Caching (`cache`)

`~/.themis/cache/{namespace}/{sha256(key)}.json` with `{storedAt, value}`.

| Namespace | Key | TTL |
|---|---|---|
| `osv-query` | `g:a:v` | 24 h |
| `osv-vuln` | advisory id | 24 h |
| `license` | `g:a:v` | 30 days |
| `metadata` | `g:a` (version list) | 24 h |
| `javaversion` | `g:a:v` | forever (released jars are immutable) |

## 16. Testing strategy

| Level | What |
|---|---|
| Unit | `ClassFileVersion` on generated class bytes, `SpdxNormalizer` alias table, `LicenseClassifier`, entropy, each secret regex (positive + negative cases), `PiiFieldMatcher` tokenization |
| Component | Analyzers against fixture projects in `src/test/resources/fixtures/*` (a POM with an AGPL dep, a class with a hardcoded AWS key, an entity with `@Data` + phone) |
| Contract | `OsvClient` against recorded responses (WireMock), so tests run offline |
| End-to-end | CLI on a sample Spring Boot repo; assert JSON report and exit code |

## 17. Phase 1 implementation checklist

- [x] Multi-module Maven skeleton, Java 17
- [x] Domain model records
- [x] `Analyzer` / `DependencyResolver` / `ReportWriter` interfaces
- [x] `ScanEngine` with stage ordering and per-analyzer error isolation
- [x] `LicenseClassifier` + `ClassFileVersion` (pure logic, unit-tested)
- [x] CLI `scan` / `version` commands, console + JSON output
- [ ] `MavenDependencyResolver` using Maven Resolver
- [ ] Fixture projects for component tests
