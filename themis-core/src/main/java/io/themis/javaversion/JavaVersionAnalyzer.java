package io.themis.javaversion;

import io.themis.engine.Analyzer;
import io.themis.engine.AnalyzerResult;
import io.themis.engine.ScanContext;
import io.themis.engine.Stage;
import io.themis.model.Dependency;
import io.themis.model.Finding;
import io.themis.model.FindingType;
import io.themis.model.Severity;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Flags dependencies whose bytecode needs a newer Java release than the project targets. */
public final class JavaVersionAnalyzer implements Analyzer {

    @Override
    public String name() {
        return "javaversion";
    }

    @Override
    public Stage stage() {
        return Stage.DEPENDENCY;
    }

    @Override
    public AnalyzerResult analyze(ScanContext ctx) {
        int projectRelease = ctx.project().javaRelease();
        List<Finding> findings = new ArrayList<>();
        List<Object> data = new ArrayList<>();

        for (Dependency dep : ctx.graph().dependencies()) {
            if (dep.jarFile().isEmpty()) continue;
            Path jar = dep.jarFile().get();
            ClassFileVersion.JarVersion v;
            try {
                v = ClassFileVersion.scanJar(jar);
            } catch (IOException e) {
                continue; // unreadable jar: skip, do not fail the whole analyzer
            }
            if (v.requiredRelease().isEmpty()) continue;

            int required = v.requiredRelease().getAsInt();
            data.add(new JavaRequirement(dep.coordinate(), required, v.multiRelease()));

            if (required > projectRelease) {
                findings.add(Finding.forDependency(
                        "requires-java-" + required, FindingType.JAVA_VERSION, Severity.HIGH,
                        dep.coordinate() + " requires Java " + required,
                        "Project targets Java " + projectRelease + "; loading this library will fail with "
                                + "UnsupportedClassVersionError.",
                        dep.coordinate(),
                        Map.of("requiredRelease", String.valueOf(required),
                                "projectRelease", String.valueOf(projectRelease),
                                "source", "bytecode")));
            }
        }
        return new AnalyzerResult(findings, data);
    }
}
