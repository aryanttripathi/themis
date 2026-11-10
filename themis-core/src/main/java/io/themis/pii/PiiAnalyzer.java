package io.themis.pii;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.NodeList;
import com.github.javaparser.ast.body.CallableDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import io.themis.engine.Analyzer;
import io.themis.engine.AnalyzerResult;
import io.themis.engine.ScanContext;
import io.themis.engine.Stage;
import io.themis.model.Finding;
import io.themis.model.FindingType;
import io.themis.model.Location;
import io.themis.model.Severity;
import io.themis.source.SourceFiles;
import io.themis.source.SourceFiles.SourceFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Personal data that leaks through logs, {@code toString()} and exception messages, found on the
 * Java syntax tree of the project's main sources (test sources are skipped). Runs locally.
 *
 * <ul>
 *   <li>{@code pii-in-log} (HIGH): a PII field, variable or getter passed to a logger or System.out/err</li>
 *   <li>{@code entity-in-log} (HIGH): an object whose generated/hand-written toString() prints PII, logged whole</li>
 *   <li>{@code pii-tostring} (MEDIUM): Lombok @Data/@ToString/@Value classes, records and hand-written
 *       toString() methods that include PII fields</li>
 *   <li>{@code pii-in-exception} (MEDIUM): PII concatenated into an exception message</li>
 * </ul>
 * Heuristic by design (no type solving across files beyond simple names), tuned for low noise.
 */
public final class PiiAnalyzer implements Analyzer {

    public static final String NAME = "pii";
    private static final Set<String> LOG_METHODS = Set.of("trace", "debug", "info", "warn", "warning", "error", "fatal",
            "severe", "fine", "finer", "finest", "log");
    private static final Set<String> PRINT_METHODS = Set.of("println", "print", "printf", "format");

    private record ParsedFile(SourceFile file, List<String> lines, CompilationUnit cu) {
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public Stage stage() {
        return Stage.SOURCE;
    }

    @Override
    public AnalyzerResult analyze(ScanContext ctx) throws IOException {
        var source = ctx.config().source();
        PiiVocabulary vocab = PiiVocabulary.of(source.extraPiiFields(), source.ignorePiiFields());
        JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));

        List<ParsedFile> files = new ArrayList<>();
        for (SourceFile f : SourceFiles.list(ctx.project().rootDir(), source.exclude(), n -> n.endsWith(".java"))) {
            if (f.isTest()) continue;
            List<String> lines;
            try {
                lines = Files.readAllLines(f.absolute(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                continue;
            }
            ParseResult<CompilationUnit> r = parser.parse(String.join("\n", lines));
            r.getResult().ifPresent(cu -> files.add(new ParsedFile(f, lines, cu)));
        }

        Map<String, Finding> out = new LinkedHashMap<>();
        // Pass 1: types whose toString() prints PII (also the "entities" for pass 2).
        Map<String, List<String>> piiPrintingTypes = new HashMap<>();
        for (ParsedFile pf : files) {
            for (TypeDeclaration<?> type : pf.cu().findAll(TypeDeclaration.class)) {
                List<String> fields = printedPiiFields(type, vocab);
                if (fields.isEmpty()) continue;
                piiPrintingTypes.put(type.getNameAsString(), fields);
                Node at = type.getName();
                add(out, pf, at, "pii-tostring", Severity.MEDIUM, "toString() of " + type.getNameAsString() + " prints PII ("
                        + String.join(", ", fields) + ")", "Exclude the fields (@ToString.Exclude, or a custom toString() "
                        + "that masks them): any log or error message containing this object will contain them.", String.join(",", fields));
            }
        }
        // Pass 2: logging, printing and exception messages.
        for (ParsedFile pf : files) {
            for (MethodCallExpr call : pf.cu().findAll(MethodCallExpr.class)) {
                if (!isLogOrPrint(call)) continue;
                for (Expression arg : call.getArguments()) {
                    Optional<String[]> pii = piiIn(arg, vocab);
                    if (pii.isPresent()) {
                        add(out, pf, call, "pii-in-log", Severity.HIGH, "PII (" + pii.get()[0] + ") written to log in " + where(pf, call),
                                "Do not log personal data: log an internal id, or mask the value (e.g. ******1234).", pii.get()[1]);
                        break;
                    }
                    Optional<String> entity = entityType(arg, piiPrintingTypes);
                    if (entity.isPresent()) {
                        add(out, pf, call, "entity-in-log", Severity.HIGH, entity.get() + " (has " + String.join(", ",
                                piiPrintingTypes.get(entity.get())) + ") written to log in " + where(pf, call),
                                "Its toString() prints personal data: log its id instead, or exclude the fields from toString().", entity.get());
                        break;
                    }
                }
            }
            for (ObjectCreationExpr created : pf.cu().findAll(ObjectCreationExpr.class)) {
                String type = created.getType().getNameAsString();
                if (!(type.endsWith("Exception") || type.endsWith("Error"))) continue;
                for (Expression arg : created.getArguments()) {
                    Optional<String[]> pii = piiIn(arg, vocab);
                    if (pii.isPresent()) {
                        add(out, pf, created, "pii-in-exception", Severity.MEDIUM, "PII (" + pii.get()[0] + ") in exception message in "
                                + where(pf, created), "Exception messages end up in logs and API error bodies: leave the value out.", pii.get()[1]);
                        break;
                    }
                }
            }
        }
        return new AnalyzerResult(List.copyOf(out.values()), List.of());
    }

    /** PII fields a type's toString() prints: Lombok @Data/@ToString/@Value, records, or a hand-written toString(). */
    private static List<String> printedPiiFields(TypeDeclaration<?> type, PiiVocabulary vocab) {
        Set<String> out = new LinkedHashSet<>();
        if (type instanceof RecordDeclaration rec) {
            rec.getParameters().forEach(p -> vocab.match(p.getNameAsString()).ifPresent(m -> out.add(p.getNameAsString())));
        }
        if (type instanceof ClassOrInterfaceDeclaration cls && !cls.isInterface()) {
            Optional<Boolean> explicitOnly = lombokToString(cls);
            if (explicitOnly.isPresent()) {
                for (FieldDeclaration fd : cls.getFields()) {
                    if (fd.isStatic() || hasAnnotation(fd.getAnnotations(), "Exclude")) continue;
                    if (explicitOnly.get() && !hasAnnotation(fd.getAnnotations(), "Include")) continue;
                    fd.getVariables().forEach(v -> vocab.match(v.getNameAsString()).ifPresent(m -> out.add(v.getNameAsString())));
                }
            }
        }
        for (MethodDeclaration m : type.getMethodsBySignature("toString")) {
            m.getBody().ifPresent(body -> {
                body.findAll(NameExpr.class).forEach(n -> vocab.match(n.getNameAsString()).ifPresent(x -> out.add(n.getNameAsString())));
                body.findAll(FieldAccessExpr.class).forEach(n -> vocab.match(n.getNameAsString()).ifPresent(x -> out.add(n.getNameAsString())));
            });
        }
        return List.copyOf(out);
    }

    /** Empty: no Lombok toString. Otherwise whether it prints only {@code @ToString.Include} fields. An explicit @ToString wins over @Data/@Value. */
    private static Optional<Boolean> lombokToString(ClassOrInterfaceDeclaration cls) {
        boolean generated = false;
        for (AnnotationExpr a : cls.getAnnotations()) {
            String n = a.getNameAsString();
            String simple = n.substring(n.lastIndexOf('.') + 1);
            if (simple.equals("ToString")) {
                return Optional.of(a instanceof NormalAnnotationExpr na && na.getPairs().stream()
                        .anyMatch(p -> p.getNameAsString().equals("onlyExplicitlyIncluded") && p.getValue().toString().equals("true")));
            }
            if (simple.equals("Data") || (simple.equals("Value") && !n.startsWith("org.springframework"))) generated = true;
        }
        return generated ? Optional.of(false) : Optional.empty();
    }

    private static boolean hasAnnotation(NodeList<AnnotationExpr> annotations, String simpleName) {
        return annotations.stream().anyMatch(a -> a.getNameAsString().endsWith(simpleName));
    }

    private static boolean isLogOrPrint(MethodCallExpr call) {
        String method = call.getNameAsString();
        Optional<Expression> scope = call.getScope();
        if (scope.isEmpty()) return false;
        String s = scope.get().toString();
        if (PRINT_METHODS.contains(method)) return s.equals("System.out") || s.equals("System.err");
        if (!LOG_METHODS.contains(method)) return false;
        String last = s.substring(s.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return last.equals("log") || last.equals("logger") || last.endsWith("logger") || last.endsWith("_log") || last.equals("slf4j");
    }

    /** First PII-named variable, field or getter inside the expression (string literals ignored): {label, identifier}. */
    private static Optional<String[]> piiIn(Expression e, PiiVocabulary vocab) {
        List<Node> nodes = new ArrayList<>();
        nodes.add(e);
        nodes.addAll(e.findAll(Node.class));
        for (Node n : nodes) {
            if (n instanceof StringLiteralExpr) continue;
            String id = null;
            if (n instanceof NameExpr name) id = name.getNameAsString();
            else if (n instanceof FieldAccessExpr fa) id = fa.getNameAsString();
            else if (n instanceof MethodCallExpr mc && mc.getArguments().isEmpty()) {
                String m = mc.getNameAsString();
                id = m.startsWith("get") && m.length() > 3 ? m.substring(3) : m.startsWith("is") && m.length() > 2 ? m.substring(2) : m;
            }
            if (id == null) continue;
            Optional<String> label = vocab.match(id);
            if (label.isPresent()) return Optional.of(new String[]{label.get(), id});
        }
        return Optional.empty();
    }

    /** If the argument is a variable/field/parameter whose declared type prints PII, that type's simple name. */
    private static Optional<String> entityType(Expression arg, Map<String, List<String>> piiTypes) {
        if (!(arg instanceof NameExpr name)) return Optional.empty();
        String var = name.getNameAsString();
        for (Node n = arg; n != null; n = n.getParentNode().orElse(null)) {
            if (n instanceof CallableDeclaration<?> callable) {
                for (Parameter p : callable.getParameters()) {
                    if (p.getNameAsString().equals(var)) return known(p.getType().asString(), piiTypes);
                }
                for (VariableDeclarator v : callable.findAll(VariableDeclarator.class)) {
                    if (v.getNameAsString().equals(var)) return known(v.getType().asString(), piiTypes);
                }
            }
            if (n instanceof TypeDeclaration<?> type) {
                for (FieldDeclaration fd : type.getFields()) {
                    for (VariableDeclarator v : fd.getVariables()) {
                        if (v.getNameAsString().equals(var)) return known(v.getType().asString(), piiTypes);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> known(String typeName, Map<String, List<String>> piiTypes) {
        String simple = typeName.replaceAll("<.*>", "");
        simple = simple.substring(simple.lastIndexOf('.') + 1);
        return piiTypes.containsKey(simple) ? Optional.of(simple) : Optional.empty();
    }

    private static String where(ParsedFile pf, Node node) {
        return pf.file().relative().toString().replace('\\', '/') + ":" + node.getBegin().map(p -> p.line).orElse(1);
    }

    private static void add(Map<String, Finding> out, ParsedFile pf, Node node, String rule, Severity severity, String title,
                            String detail, String field) {
        int line = node.getBegin().map(p -> p.line).orElse(1);
        int col = node.getBegin().map(p -> p.column).orElse(1);
        if (SourceFiles.ignored(pf.lines(), line)) return;
        String rel = pf.file().relative().toString().replace('\\', '/');
        Map<String, String> evidence = new LinkedHashMap<>();
        evidence.put("rule", rule);
        evidence.put("field", field);
        evidence.put("file", rel);
        String id = FindingType.PII + ":" + rule + ":" + rel + ":" + field + ":" + pf.lines().get(line - 1).trim().hashCode();
        out.putIfAbsent(id, new Finding(id, FindingType.PII, severity, title, detail, Optional.empty(),
                Optional.of(new Location(pf.file().relative(), line, col)), evidence));
    }
}
