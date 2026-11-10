package io.themis.license;

import io.themis.cache.FileCache;
import io.themis.config.LicensePolicy;
import io.themis.config.ScanConfig;
import io.themis.engine.Analyzer;
import io.themis.engine.AnalyzerResult;
import io.themis.engine.ScanContext;
import io.themis.engine.Stage;
import io.themis.model.Dependency;
import io.themis.model.Finding;
import io.themis.model.FindingType;
import io.themis.model.LicenseCategory;
import io.themis.model.LicenseInfo;
import io.themis.model.Scope;
import io.themis.model.Severity;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Detects the license of every dependency ({@link LicenseLookup}), publishes a {@link LicenseInfo}
 * for each, and reports findings according to the {@link LicensePolicy} (docs/LLD.md section 5).
 */
public final class LicenseAnalyzer implements Analyzer {

    public static final String NAME = "licenses";

    private final LicenseLookup lookup;

    public LicenseAnalyzer(LicenseLookup lookup) {
        this.lookup = lookup;
    }

    /** Production wiring: bundled alias table, deps.dev at {@code depsDevBaseUrl}, cache at {@code cacheDir}. */
    public static LicenseAnalyzer create(java.nio.file.Path cacheDir, URI depsDevBaseUrl) {
        return new LicenseAnalyzer(new LicenseLookup(SpdxNormalizer.bundled(), new LicenseClassifier(),
                DepsDevClient.create(depsDevBaseUrl), new FileCache(cacheDir, java.time.Clock.systemUTC()),
                new LicenseTextMatcher()));
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Stage stage() {
        return Stage.DEPENDENCY;
    }

    @Override
    public AnalyzerResult analyze(ScanContext ctx) {
        ScanConfig config = ctx.config();
        LicensePolicy policy = config.licenses();
        Duration ttl = config.cacheTtlHours().isPresent()
                ? Duration.ofHours(config.cacheTtlHours().getAsInt()) : LicenseLookup.DEFAULT_TTL;
        List<Dependency> deps = ctx.graph().dependencies();
        List<LicenseInfo> infos = lookup.lookupAll(deps, ctx.metadata(),
                new LicenseLookup.Settings(policy.overrides(), config.offline(), ttl,
                        io.themis.privacy.LookupPrivacy.of(config.privacy(), ctx.project().coordinate())::isPublic));

        List<Finding> findings = new ArrayList<>();
        for (int i = 0; i < deps.size(); i++) {
            Dependency d = deps.get(i);
            LicenseInfo info = infos.get(i);
            if (policy.isAllowed(d.coordinate().ga())) continue;
            policy.severityFor(info.category(), d.scope())
                    .ifPresent(severity -> findings.add(finding(d, info, severity)));
        }
        return new AnalyzerResult(findings, List.copyOf(infos));
    }

    static Finding finding(Dependency d, LicenseInfo info, Severity severity) {
        String category = info.category().name().toLowerCase(Locale.ROOT).replace('_', ' ');
        String title = info.spdx()
                .map(spdx -> d.coordinate() + " is " + spdx + " (" + category + ")")
                .orElse(d.coordinate() + " has no recognised license (" + category + ")");

        Map<String, String> evidence = new LinkedHashMap<>();
        evidence.put("spdx", info.spdx().orElse(""));
        evidence.put("category", info.category().name());
        evidence.put("source", info.source());
        evidence.put("scope", d.scope().mavenName());
        evidence.put("declared", String.join(" | ", info.declared()));
        if (!info.notes().isEmpty()) evidence.put("notes", String.join(" | ", info.notes()));

        // Id per artifact+version: several versions of one artifact (multi-module) are separate findings.
        String id = FindingType.LICENSE + ":" + info.category() + ":" + d.coordinate();
        return new Finding(id, FindingType.LICENSE, severity, title, obligation(info.category()) + scopeNote(d.scope()),
                Optional.of(d.coordinate()), Optional.empty(), evidence);
    }

    /** One sentence on what the category obliges you to do. */
    static String obligation(LicenseCategory category) {
        return switch (category) {
            case NETWORK_COPYLEFT -> "Offering software that includes it over a network (e.g. as a hosted service) "
                    + "can oblige you to publish the complete source code of that software under the same license.";
            case STRONG_COPYLEFT -> "Distributing an application that includes it can oblige you to release the "
                    + "whole application's source code under the same license.";
            case WEAK_COPYLEFT -> "Changes to this library itself must be shared under the same license; using it "
                    + "unmodified as a separate library is generally fine.";
            case PROPRIETARY -> "Custom or commercial terms apply; check that your use and redistribution are allowed.";
            case UNKNOWN -> "No license could be determined, which legally means all rights reserved; check the "
                    + "project's terms and record them under licenses.overrides in .themis.yml.";
            case PERMISSIVE -> "Keep the copyright and license notices when redistributing.";
        };
    }

    private static String scopeNote(Scope scope) {
        return switch (scope) {
            case TEST -> " Test scope: never distributed, so the severity is capped at INFO.";
            case PROVIDED, SYSTEM -> " " + scope.mavenName() + " scope: supplied by the runtime environment rather "
                    + "than shipped by you, so the severity is lowered one level.";
            default -> "";
        };
    }
}
