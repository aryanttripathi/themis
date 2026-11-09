# Why Themis?

> A single command that tells a Java team: *what we depend on, what we are legally obliged to do because of it, what is vulnerable, how to fix it without breaking our Java version, and whether we have leaked secrets or PII in our own code.*

**About the name.** Themis is the Greek goddess of divine law, order and justice, usually shown holding the scales of judgement. The tool does the same job for a codebase: it weighs every dependency against the rules (license obligations, security advisories, runtime compatibility, data-protection hygiene) and delivers a verdict.

## 1. The problem

A typical Spring Boot service declares 20-40 dependencies in its `pom.xml`. After transitive resolution it actually ships **150-300 jars**. Nobody on the team has read the license of most of them, and nobody knows which of them have known CVEs today.

The questions that come up again and again:

| Question | Who asks | How it is answered today |
|---|---|---|
| "Are we allowed to use this library commercially?" | Legal, tech leads, during due diligence | Someone opens each project's GitHub page by hand |
| "Are we affected by the new CVE in X?" | Security, on-call | `mvn dependency:tree \| grep`, then reading advisories |
| "Which version fixes it, and will it run on our Java 17?" | The developer who has to fix it | Trial and error: bump, build, see what breaks |
| "Did someone commit an API key or log customer phone numbers?" | Security, compliance (DPDP Act, GDPR) | Code review, if the reviewer notices |

Each question has a tool, but they are separate tools with separate reports, and none answers the fourth column well: **the safe upgrade that is compatible with our runtime**.

## 2. Real costs of not knowing

- **License risk.** A single AGPL or SSPL dependency in a SaaS backend can create an obligation to publish source code. A dependency with *no* declared license is technically "all rights reserved". These are discovered late: at acquisition due diligence, enterprise customer audits, or open-sourcing.
- **Transitive vulnerabilities.** Log4Shell (CVE-2021-44228) hit many teams through libraries they never declared directly. Direct dependencies are the easy part; the risk lives in the tree.
- **Upgrade paralysis.** Advisories say "fixed in 2.17.1" but not whether 2.17.1 requires a newer JDK. Teams postpone upgrades because the cost is unknown.
- **Secrets and PII leaks.** Hardcoded keys in `application.yml`, a `log.info("user {}", user)` on an entity with `@Data` that prints phone and email. These are cheap to catch statically and expensive to catch in production logs.

## 3. What Themis does

1. **Inventory**: resolve the full dependency tree (direct + transitive) of a Maven project.
2. **License compliance**: detect each dependency's license, normalize to SPDX, classify by obligation (permissive, weak copyleft, strong copyleft, network copyleft, unknown) and check against a configurable policy.
3. **Vulnerabilities**: query known advisories (OSV) for the exact resolved version.
4. **Upgrade advice**: recommend the *smallest* upgrade that fixes all known vulnerabilities **and** whose bytecode runs on the project's Java version.
5. **Runtime compatibility**: report the minimum Java version each library needs (read from class files, not guessed).
6. **Secrets and PII**: scan source (via AST) and config files for hardcoded credentials and PII flowing into logs / `toString()` / responses.
7. **One report**: HTML for humans, JSON for machines, SARIF for GitHub/GitLab PR annotations, with exit codes for CI gates.

## 4. Who it is for

- **Developers**: run locally before raising a PR; get a concrete "bump X from a to b" list.
- **CI pipelines**: fail the build on policy violations (e.g. any AGPL, any CRITICAL CVE).
- **Tech leads / security**: periodic report across services.
- **Legal / compliance**: a license inventory (bill of materials) on demand.

## 5. Goals

- Accurate dependency resolution using Maven's own resolver, not hand-rolled POM parsing.
- Zero-config first run: `themis scan .` must produce a useful report.
- Privacy by design: **source code never leaves the machine**; only package coordinates (`group:artifact:version`) are sent to public advisory APIs.
- Usable as a **library** (embeddable API), a **CLI**, and later a **Maven/Gradle plugin**.
- Low false-positive rate, with suppression and baseline support so teams keep it on.

## 6. Non-goals (for now)

- Legal advice. The tool classifies and flags; humans decide.
- Full taint/data-flow analysis for PII. Start with heuristics.
- Ecosystems other than Java/Maven. Gradle next; npm/pip only if the core abstractions prove right.
- Auto-applying upgrades. It recommends; a later version may open PRs.

## 7. How it compares

| Tool | Licenses | CVEs | Compatible upgrade | Java version per lib | Secrets | PII |
|---|---|---|---|---|---|---|
| OWASP Dependency-Check | - | Yes | - | - | - | - |
| license-maven-plugin | Yes | - | - | - | - | - |
| Snyk / Trivy | Yes | Yes | Partial | - | Partial | - |
| gitleaks | - | - | - | - | Yes | - |
| **Themis** | Yes | Yes | **Yes** | **Yes** | Yes | **Yes (heuristic)** |

The differentiators: one combined report, **runtime-aware upgrade suggestions**, and **PII exposure detection**.

## 8. Success criteria

- Scans a typical Spring Boot service (≈250 resolved deps) in under 30 seconds with a warm cache.
- License detected for ≥95% of dependencies; the rest explicitly flagged as `UNKNOWN`.
- Every recommended upgrade has bytecode compatible with the project's configured Java release.
- Secret detection false-positive rate low enough that a team keeps it enabled in CI after a month.
