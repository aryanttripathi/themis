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
record Coordinate(String groupId, String artifactId, String version,
                  String extension,      // "jar" (default), "pom", "zip", ...; type test-jar = jar + classifier "tests"
                  String classifier) {   // "" for the main artifact
    Coordinate(String g, String a, String v);   // plain jar
    String ga();              // "groupId:artifactId"
    String artifactKey();     // "g:a:extension:classifier" (identity without version)
    String toString();        // "g:a:v", or "g:a:ext:v" / "g:a:ext:classifier:v" when non-default (Resolver style)
    static Coordinate parse(String);            // inverse of toString()
}

enum Scope { COMPILE, RUNTIME, PROVIDED, TEST, SYSTEM, IMPORT;
    static Scope fromMaven(String);  String mavenName();   // Locale.ROOT, safe under tr-TR
    static Scope widest(Scope, Scope);                     // compile > runtime > provided > system > test
}

record Dependency(
    Coordinate coordinate,     // one entry per artifact AND version
    Scope scope,               // widest among the modules that reach it
    boolean direct,            // declared in (one of) the module POM(s)
    List<Coordinate> path,     // declaring module -> ... -> this ("pulled in via"); most direct/shortest example
    Optional<Path> jarFile,    // resolved artifact file if it is a jar (system scope: the systemPath)
    List<Coordinate> modules   // modules whose resolved graph contains it (just the project if single-module)
) {}

record DependencyGraph(Coordinate root, List<Dependency> dependencies,
                       List<String> warnings) {}   // non-fatal problems, Maven wording

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
1. session  = resolver.open(projectDir, cfg)        (one build-tool session per scan, closed after step 2)
   project  = session.project()
2. graph    = session.resolveDependencies()
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
    boolean supports(Path projectDir);                    // pom.xml present?
    ProjectSession open(Path projectDir, ScanConfig cfg); // builds the project model once
}

interface ProjectSession extends AutoCloseable {          // one per scan
    ProjectInfo project();                                // from the effective model
    DependencyGraph resolveDependencies();                // throws ResolutionException
    void close();
}

record ResolverOptions(Path userSettings, Optional<Path> globalSettings, Optional<Path> localRepository,
                       Path settingsSecurity, boolean ignorePomRepositories) {
    static ResolverOptions defaults();  // ~/.m2/settings.xml, $MAVEN_HOME/conf/settings.xml, -Dmaven.repo.local, -Dsettings.security
    ResolverOptions withUserSettings(Path);  ResolverOptions withIgnorePomRepositories(boolean);
}

final class ResolutionException extends RuntimeException {
    List<String> unresolved();          // "g:a:ext[:classifier]:version" of every unresolvable artifact
}
```

`ProjectInspector` remains as a library convenience (`inspect(dir, cfg)` = open a session, return `project()`); the engine does not use it. `EmptyDependencyResolver(delegate)` takes project facts from another resolver and reports no dependencies (tests, source-only scans). Java-release detection lives in `JavaRelease` (`maven.compiler.release` > `.target` > `.source` > `java.version`; `1.8` -> 8, `21.0.2` -> 21; default 17).

Implemented in `io.themis.resolve.maven` on **Maven Resolver 1.9.27** (`maven-resolver-supplier`, i.e. `RepositorySystemSupplier`, no DI container) plus **Maven 3.9.16** `maven-resolver-provider`, `maven-model-builder` and `maven-settings-builder` (Maven 3.9.16 itself ships Resolver 1.9.27, so the pair is consistent). Nothing parses dependency trees by hand.

| Class | Role |
|---|---|
| `MavenDependencyResolver` | The `DependencyResolver`; `open` creates a `MavenProjectSession`. |
| `MavenProjectSession` | One scan: one `MavenRuntime`, the reactor with effective models built once, resolution + merge. |
| `MavenRuntime` | `RepositorySystem` + session from settings, two-phase model building with a per-runtime `ModelCache`, warnings. `AutoCloseable` (shuts the system down; also if session setup fails). |
| `SettingsLoader` | Global + user settings.xml merged and interpolated; each server/proxy password decrypted separately. |
| `SettingsProfiles` | settings.xml profiles -> POM "external profiles" (activation, properties, repositories). |
| `RepositoryModelResolver` | `ModelResolver` for the model builder: parents, BOM imports and version ranges resolved through the session. |
| `ReactorWorkspace` | `WorkspaceReader` serving module POMs of a multi-module build. |
| `MavenProjectInfo` | Effective model -> `ProjectInfo` (coordinate, Java release, effective source/resource roots). |
| `PomSafety` | Rejects project POMs with a DOCTYPE (XXE), silently (no parser noise on stderr). |

**Opening a session (reactor loading), mirroring Maven's two-phase project building:**
1. **Phase 1** (`setTwoPhaseBuilding(true)`) for the root POM and, recursively, for every `<modules>` entry of its phase-1 model (so modules of active profiles count): parents (on disk via `relativePath` or from repositories, e.g. `spring-boot-starter-parent`), inheritance, profile activation incl. settings profiles, interpolation. The module coordinate is taken from this model, so CI-friendly versions (`${revision}`, `${sha1}`, `${changelist}`, from root `<properties>` or `-D` system properties) are final. A coordinate still containing `${` fails loudly with `ResolutionException` instead of being looked up remotely.
2. Install `ReactorWorkspace` with all module coordinates -> POM files.
3. **Phase 2** for every module: `dependencyManagement` imports (BOMs, which may themselves be modules, imported by the root or a sibling), management injection, normalization. All models come from one `ModelCache`, so a parent/BOM shared by modules is read once.
4. `project()` = `MavenProjectInfo` of the root model.

**Session settings** (`MavenRepositorySystemUtils.newSession()`, i.e. Maven's own selectors and transformers):
- Local repository: `ResolverOptions.localRepository` > settings `<localRepository>` > `~/.m2/repository`.
- Settings mirrors, then Maven 3.8.1+'s `maven-default-http-blocker` (`external:http:*`, blocked) appended unless already configured; active proxies; server credentials (`DefaultAuthenticationSelector`, matched by server id).
- `ScanConfig.offline()` = resolver offline mode (local repository only; `file:` repositories included).
- System properties + `env.*` for interpolation and profile activation; settings `<activeProfiles>`.
- Transport defaults for an interactive CLI: `aether.connector.connectTimeout=10000`, `requestTimeout=60000`, `http.retryHandler.count=2`, `http.retryHandler.intervalMax=20000`, user agent `Themis/<version>`. Any `-D` system property of the same name overrides them. The default DF collector is kept.
- A repository listener records Maven's warnings for transitive POMs that are missing ("The POM for g:a:jar:v is missing, no dependency information available") or invalid ("... is invalid, transitive dependencies (if any) will not be available: <reason>"). Like mvn, these are warnings, not failures (lenient descriptor policy).

**`resolveDependencies()`:** for each module (reactor order):
1. **Collect + resolve** with `RepositorySystem.resolveDependencies`: root = module artifact; dependencies and managed dependencies (incl. imported BOM entries) from its effective model; repositories from the effective model (super-POM `central`, the project's, its parents' and active settings profiles' `<repositories>`) with mirrors/proxies/auth applied. Maven Resolver does nearest-wins mediation (equal depth: first declaration wins; conflict losers removed, as in `mvn dependency:tree`), scope derivation (e.g. compile under runtime -> runtime; a direct declaration's scope wins over the same artifact reached transitively), managed versions *and scopes* for transitives, optional (transitive optionals dropped), `<exclusions>`, version ranges (highest match, also for parents/imports).
2. A `DependencyFilter` keeps the wanted scopes and skips reactor siblings, so their jars (never built) are not requested; their POMs come from the workspace, so their dependencies are still collected.
3. **Walk** the resolved tree depth-first, pre-order, keyed by `g:a:extension:classifier` (classifier artifacts such as `linux-x86_64`/`linux-aarch_64` jars or a `test-jar` next to the main jar stay distinct). Each node becomes a `Dependency`: coordinate = `g:a:baseVersion` + extension + classifier, `Scope`, `direct` = child of the module root, `path` = module -> ... -> node (siblings are not emitted but walked through, so they appear in paths), `jarFile` = resolved file when the extension is `jar` (system scope: the `systemPath`), `modules` = [module]. A path guard stops cycles.
4. **Merge modules** by `g:a:extension:classifier:version`: one entry per distinct version (module `core` on 3.12.0 and `app` on 3.14.0 give two entries); for the same artifact and version, `modules` = union in reactor order, `scope` = widest (`compile > runtime > provided > system > test`: shipped first, then environment-supplied, then test), `direct` if direct anywhere, `path` = the most direct, then shortest, example.
5. The graph carries `runtime.warnings()` (descriptor problems, undecryptable passwords).

**Errors**: a model that cannot be built (missing parent/BOM, uninterpolated module version) or an artifact that cannot be resolved raises `ResolutionException`, naming the project, the unresolvable artifacts and the resolver's cause chain (e.g. "Blocked mirror for repositories: [...]"); the CLI prints `error: ...` and exits 2.

**Scopes.** `compile`, `runtime`, `provided` and `system` by default; `--include-test` adds `test`. `provided`/`system` jars are not shipped with the application but do run in production (servlet API, container libraries, vendor jars) and can carry vulnerabilities and license obligations, so they are reported as `Scope.PROVIDED` / `Scope.SYSTEM` and later analyzers decide their severity. `test` stays opt-in because it never reaches production.

**Settings.**
- Mirrors, proxies, servers, local repository, `<activeProfiles>`.
- Settings profiles are converted to POM "external profiles": the model builder evaluates their activation (`activeByDefault`, jdk, os, property, file, `<activeProfiles>`) and injects their `<properties>` and `<repositories>` into every model. For the *initial* repository list (used before any model exists, e.g. to fetch a remote parent) only profiles active by id or `activeByDefault` are considered.
- Passwords are decrypted per server/proxy. One that cannot be decrypted is dropped (never sent still encrypted), a warning names only the server/proxy id, and the scan continues without it.

**Security.**
- **Credentials follow the server id.** Like Maven, credentials in `<servers>` are sent to any repository whose id matches, including a repository declared by the (untrusted) POM being scanned. A maacme POM can therefore declare `<repository><id>corp-nexus</id><url>http://attacker/...</url>` and receive the corp-nexus credentials.
- **Plain HTTP is blocked** by the always-installed `external:http:*` blocker mirror (Maven 3.8.1+ behaviour, independent of `MAVEN_HOME`). A user's own mirror for that repository (or `mirrorOf *`) takes precedence. Like Maven's, the blocker does not cover `localhost` / `127.0.0.1` (Maven regards them as local), and it does nothing against HTTPS repositories declared by a POM.
- **`--ignore-pom-repositories`** (`ResolverOptions.ignorePomRepositories`; default off for now) resolves only from settings mirrors, settings profile repositories and Central. POM-declared repositories of the project, its parents and its dependencies' POMs (`setIgnoreArtifactDescriptorRepositories`) are never contacted.
- **Recommendation for CI and untrusted code:** configure a `mirrorOf *` mirror (all traffic goes to one trusted repository manager, so POM-declared URLs are never used), or run with `--ignore-pom-repositories`. Keep credentials only on the mirror's server id.
- **XXE:** project POMs (root and modules) are checked with a DTD-disallowing parser before Maven reads them. POMs fetched from repositories are parsed by Maven's MXParser, which does not resolve external entities (verified: such a POM fails with "could not resolve entity").

**Testing.**
- Component tests build a hermetic file repository in a `@TempDir` (`FixtureRepo`, published as the core test-jar for CLI tests; it also writes `maven-metadata.xml` for version ranges). Its generated settings.xml sets a temp local repository and mirrors `central` to an empty directory; there are no global settings and no settings-security.
- CLI tests go through `ThemisCli.newCommandLine(ResolverOptions)`, so `-Dmaven.repo.local`, `MAVEN_HOME` and the real `~/.m2` never leak into them; `ScanEngineTest` uses fixture options too.
- One test tagged `network` (excluded by default; `-Pnetwork` or `-Dgroups=network`) resolves `commons-text` from Maven Central with the real `~/.m2/settings.xml`.

**Deferred / not supported (Phase 1):**
- Fallback to `mvn dependency:tree` when the embedded resolver fails: deferred. The embedded resolver handles settings credentials, mirrors and proxies, so it is not needed for the common cases.
- Maven 4 POMs (model 4.1.0) and Maven 4 consumer/build POM features.
- Settings server `<configuration>` (custom HTTP headers, per-server timeouts); `.mvn/maven.config` / `.mvn/jvm.config` (e.g. `-Drevision` set there is not read; pass it as a system property).
- A module whose parent is a sibling found only by coordinates, not via `relativePath` (phase 1 runs before the workspace exists).
- Extensions (`.mvn/extensions.xml`, `<extensions>true</extensions>` plugins) that alter resolution, and custom artifact handlers beyond Maven's built-in types.

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
themis deps [DIR]                      (resolved list: coordinate, scope, direct/transitive, "in <modules>", "via <path>")
    --include-test
    --offline
Resolver flags (scan and deps):
    -s, --settings=FILE                Maven user settings (default ~/.m2/settings.xml)
    --ignore-pom-repositories          use only settings mirrors/profile repositories + Central (see 4, Security)
themis baseline [DIR]
themis version
```

Exit codes: `0` no findings at or above `--fail-on`; `1` findings above threshold; `2` usage/config/resolution error.

Resolution warnings (missing/invalid transitive POMs, undecryptable passwords) are printed to stderr as `warning: ...` by `scan` and `deps`; stdout stays parseable. Output is locale-independent (`Locale.ROOT`). Tests construct the CLI with `ThemisCli.newCommandLine(ResolverOptions)`; `main` passes `ResolverOptions.defaults()`.

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
     "requiredJavaRelease": 7, "modules": ["com.acme:order-svc:1.4.0"]}
  ],
  "warnings": ["The POM for com.example:legacy:jar:1.0 is missing, no dependency information available"],
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
- [x] `MavenDependencyResolver` using Maven Resolver 1.9 + Maven 3.9 model builder (effective POM, settings incl. profiles, multi-module reactor with two-phase model building, CI-friendly versions)
- [x] `DependencyResolver.open` / `ProjectSession`: one resolver session and one model build per scan, shared by project facts and resolution
- [x] Classifier/extension in `Coordinate`; one entry per artifact+version; `modules` and widest scope across modules
- [x] Scopes compile, runtime, provided, system by default; test opt-in
- [x] Warnings for missing/invalid transitive POMs and undecryptable passwords (`DependencyGraph.warnings`, stderr, report.json)
- [x] Security: always-on `external:http:*` blocker, `--ignore-pom-repositories`, DOCTYPE rejection (section 4)
- [x] CLI `deps` command; `--settings` / `--ignore-pom-repositories` on `scan` and `deps`; locale-independent output
- [x] Fixture repository/projects for component tests (`FixtureRepo`); hermetic CLI tests via `ThemisCli.newCommandLine(ResolverOptions)`; network IT tagged `network`
- [x] Shaded jar: merged Apache NOTICE, single Apache LICENSE
- [ ] `mvn dependency:tree` fallback (deferred, see section 4)

## 18. Phase 2 status (licenses): done

- `SpdxNormalizer` (bundled `aliases.tsv` + SPDX id list), `LicenseLookup` chain: config override -> effective POM (`pom` / `parent-pom`) -> deps.dev (cached 30 days in `~/.themis/cache`, skipped when `--offline`) -> license text inside the jar -> `UNKNOWN`. Multiple `<license>` entries are combined with `OR`.
- `LicenseAnalyzer` with default severities: NETWORK_COPYLEFT and STRONG_COPYLEFT = HIGH, PROPRIETARY = MEDIUM, UNKNOWN = LOW, WEAK_COPYLEFT = INFO. Test scope is capped at INFO; provided/system are lowered one level.
- `.themis.yml`: `licenses.severity` (category -> severity or NONE), `licenses.overrides`, `licenses.allowArtifacts`, `failOn.severity`, `network.offline`, `network.cacheTtlHours`, `analyzers`, `javaRelease`. Unknown keys fail with exit 2. Precedence: CLI flags > `--config FILE` or project `.themis.yml` > defaults.
- CLI: `themis licenses [DIR]`, `scan` prints a `Licenses :` summary, `--config`, locale-independent `--fail-on`. JSON report: `license {spdx, category, source}` per dependency.
- Verified on real projects: Spring Boot sample, `/tmp/rev/edge` (23/23 detected) and a copyleft mix (iText AGPL, MySQL GPL + FOSS exception, logback/H2/Jakarta dual licenses): 12/12 detected and classified correctly.
