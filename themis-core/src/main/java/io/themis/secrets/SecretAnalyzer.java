package io.themis.secrets;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
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
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hardcoded credentials in the project's own files. Runs locally; the secret value itself is never
 * stored: findings carry a masked preview ({@code AKIA****LE}) and a fingerprint of the line with
 * the secret masked out.
 *
 * <ul>
 *   <li>Every text file: known token formats (AWS, private keys, GitHub, Slack, Google, Stripe, JWT),
 *       credentials embedded in URLs, password/token/api-key assignments in config files.</li>
 *   <li>Java (syntax tree): string constants and setters with secret-like names, Spring
 *       {@code @Value("${x.password:default}")} defaults.</li>
 * </ul>
 * Files under test directories are reported at LOW. {@code themis:ignore} on the line (or the comment
 * line above) suppresses it.
 */
public final class SecretAnalyzer implements Analyzer {

    public static final String NAME = "secrets";

    record Rule(String id, String label, Pattern pattern, int group, Severity severity) {
    }

    static final List<Rule> KNOWN = List.of(
            new Rule("private-key", "Private key", Pattern.compile("(-----BEGIN (?:[A-Z]+ )*PRIVATE KEY-----)"), 1, Severity.CRITICAL),
            new Rule("aws-access-key", "AWS access key", Pattern.compile("\\b((?:AKIA|ASIA)[0-9A-Z]{16})\\b"), 1, Severity.CRITICAL),
            new Rule("github-token", "GitHub token", Pattern.compile("\\b(gh[pousr]_[A-Za-z0-9]{36,}|github_pat_[A-Za-z0-9_]{60,})\\b"), 1, Severity.CRITICAL),
            new Rule("stripe-live-key", "Stripe live key", Pattern.compile("\\b((?:sk|rk)_live_[0-9a-zA-Z]{20,})\\b"), 1, Severity.CRITICAL),
            new Rule("slack-token", "Slack token", Pattern.compile("\\b(xox[baprs]-[A-Za-z0-9-]{10,})"), 1, Severity.HIGH),
            new Rule("slack-webhook", "Slack webhook URL", Pattern.compile("(https://hooks\\.slack\\.com/services/[A-Za-z0-9/]{20,})"), 1, Severity.HIGH),
            new Rule("google-api-key", "Google API key", Pattern.compile("\\b(AIza[0-9A-Za-z_-]{35})\\b"), 1, Severity.HIGH),
            new Rule("jwt", "JSON Web Token", Pattern.compile("\\b(eyJ[A-Za-z0-9_-]{8,}\\.eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,})"), 1, Severity.HIGH),
            new Rule("url-credentials", "Credentials in URL", Pattern.compile("\\b[a-zA-Z][a-zA-Z0-9+.\\-]*://[^\\s:/@'\"]+:([^\\s@/'\"]+)@"), 1, Severity.HIGH),
            new Rule("jdbc-password", "Password in JDBC URL", Pattern.compile("jdbc:[^\\s'\"]*[?&;]password=([^&;\\s'\"]+)"), 1, Severity.HIGH));

    static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(?i)(password|passwd|pwd|secret|token|api[_.-]?key|apikey|access[_.-]?key|private[_.-]?key|client[_.-]?secret|credentials?|auth[_.-]?key)");
    private static final Pattern NOT_A_SECRET_KEY = Pattern.compile(
            "(?i).*(url|uri|endpoint|path|file|enabled|expiry|expiration|ttl|length|header|name|type|timeout|validity|"
                    + "algorithm|policy|prefix|suffix|required|regex|pattern|field|label|count|size|location|store-type|issuer)$");
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "^\\s*(?:-\\s*)?(?:export\\s+)?[\"']?([A-Za-z0-9_.\\-\\[\\]]+)[\"']?\\s*[:=]\\s*(.*?)\\s*$");
    private static final Set<String> TEXT_EXTENSIONS = Set.of(".properties", ".yml", ".yaml", ".env", ".json", ".xml",
            ".conf", ".cfg", ".ini", ".toml", ".sh", ".gradle", ".kts", ".tf", ".tfvars", ".pem", ".key", ".java", ".sql", ".txt");

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
        Path root = ctx.project().rootDir();
        List<SourceFile> files = SourceFiles.list(root, ctx.config().source().exclude(), SecretAnalyzer::isCandidate);
        Map<String, Finding> findings = new LinkedHashMap<>();
        JavaParser parser = new JavaParser(new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
        for (SourceFile f : files) {
            List<String> lines;
            try {
                lines = Files.readAllLines(f.absolute(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                continue;   // binary or non-UTF-8: not a config/source file we can judge
            }
            boolean java = f.name().endsWith(".java");
            for (int i = 0; i < lines.size(); i++) {
                scanLine(f, lines, i + 1, !java, findings);
            }
            if (java) scanJava(parser, f, lines, findings);
        }
        return new AnalyzerResult(List.copyOf(findings.values()), List.of());
    }

    static boolean isCandidate(String lowerName) {
        if (lowerName.startsWith(".env") || lowerName.startsWith("dockerfile") || lowerName.equals("docker-compose.yml")) return true;
        int dot = lowerName.lastIndexOf('.');
        return dot >= 0 && TEXT_EXTENSIONS.contains(lowerName.substring(dot));
    }

    private void scanLine(SourceFile f, List<String> lines, int lineNo, boolean configFile, Map<String, Finding> out) {
        String line = lines.get(lineNo - 1);
        if (line.length() > 2000 || SourceFiles.ignored(lines, lineNo)) return;
        boolean known = false;
        Matcher assignment = ASSIGNMENT.matcher(line);
        String assignedKey = assignment.matches() ? assignment.group(1) : null;
        for (Rule r : KNOWN) {
            Matcher m = r.pattern().matcher(line);
            while (m.find()) {
                String secret = m.group(r.group());
                boolean password = r.id().equals("url-credentials") || r.id().equals("jdbc-password");
                // passwords in URLs may be short/simple, but references (${DB_PASS}, $PASS, {{...}}) are not secrets
                if (password && (secret.length() < 4 || secret.contains("$") || secret.contains("{") || secret.startsWith("<"))) continue;
                add(out, f, lineNo, m.start(r.group()) + 1, r.id(), r.label(), r.severity(), secret, line, assignedKey);
                known = true;
            }
        }
        if (known || !configFile) return;
        Matcher a = ASSIGNMENT.matcher(line);
        if (!a.matches() || line.trim().startsWith("#") || line.trim().startsWith("//")) return;
        String key = a.group(1);
        String value = unquote(stripComment(a.group(2)));
        if (!SENSITIVE_KEY.matcher(key).find() || NOT_A_SECRET_KEY.matcher(key).matches() || !Secrets.looksReal(value)) return;
        add(out, f, lineNo, line.indexOf(value) + 1, "generic-secret-assignment", "Hardcoded secret (" + key + ")",
                Severity.HIGH, value, line, key);
    }

    private void scanJava(JavaParser parser, SourceFile f, List<String> lines, Map<String, Finding> out) {
        ParseResult<CompilationUnit> parsed = parser.parse(String.join("\n", lines));
        if (parsed.getResult().isEmpty()) return;
        CompilationUnit cu = parsed.getResult().get();

        for (VariableDeclarator v : cu.findAll(VariableDeclarator.class)) {
            if (!SENSITIVE_KEY.matcher(v.getNameAsString()).find() || NOT_A_SECRET_KEY.matcher(v.getNameAsString()).matches()) continue;
            v.getInitializer().filter(StringLiteralExpr.class::isInstance).map(StringLiteralExpr.class::cast)
                    .filter(s -> Secrets.looksReal(s.asString()))
                    .ifPresent(s -> java(out, f, lines, s, "java-hardcoded-secret", "Hardcoded secret in constant " + v.getNameAsString(), s.asString(), v.getNameAsString()));
        }
        for (MethodCallExpr call : cu.findAll(MethodCallExpr.class)) {
            String name = call.getNameAsString();
            if (!(name.startsWith("set") || name.startsWith("with")) || !SENSITIVE_KEY.matcher(name).find()) continue;
            for (var arg : call.getArguments()) {
                if (arg instanceof StringLiteralExpr s && Secrets.looksReal(s.asString())) {
                    java(out, f, lines, s, "java-hardcoded-secret", "Hardcoded secret passed to " + name + "()", s.asString(), name);
                }
            }
        }
        for (AnnotationExpr ann : cu.findAll(AnnotationExpr.class)) {
            if (!ann.getNameAsString().endsWith("Value")) continue;
            Optional<StringLiteralExpr> lit = ann instanceof SingleMemberAnnotationExpr sm && sm.getMemberValue() instanceof StringLiteralExpr s
                    ? Optional.of(s)
                    : ann instanceof NormalAnnotationExpr na ? na.getPairs().stream().filter(p -> p.getNameAsString().equals("value"))
                    .map(p -> p.getValue()).filter(StringLiteralExpr.class::isInstance).map(StringLiteralExpr.class::cast).findFirst()
                    : Optional.empty();
            lit.ifPresent(s -> {
                Matcher m = Pattern.compile("^\\$\\{([^:}]+):([^}]*)}$").matcher(s.asString());
                if (m.matches() && SENSITIVE_KEY.matcher(m.group(1)).find() && Secrets.looksReal(m.group(2))) {
                    java(out, f, lines, s, "spring-value-default-secret", "Hardcoded default for " + m.group(1), m.group(2), m.group(1));
                }
            });
        }
    }

    private void java(Map<String, Finding> out, SourceFile f, List<String> lines, Node node, String rule, String label,
                      String secret, String key) {
        int line = node.getBegin().map(p -> p.line).orElse(1);
        int col = node.getBegin().map(p -> p.column).orElse(1);
        if (SourceFiles.ignored(lines, line)) return;
        add(out, f, line, col, rule, label, Severity.HIGH, secret, lines.get(line - 1), key);
    }

    private static void add(Map<String, Finding> out, SourceFile f, int line, int col, String rule, String label,
                            Severity severity, String secret, String lineText, String key) {
        String where = f.relative().toString().replace('\\', '/') + ":" + line;
        Severity sev = f.isTest() ? Severity.LOW : severity;
        Map<String, String> evidence = new LinkedHashMap<>();
        evidence.put("rule", rule);
        evidence.put("masked", Secrets.mask(secret));
        evidence.put("file", f.relative().toString().replace('\\', '/'));
        if (key != null) evidence.put("key", key);
        if (f.isTest()) evidence.put("note", "test file");
        String id = FindingType.SECRET + ":" + rule + ":" + f.relative().toString().replace('\\', '/') + ":" + Secrets.fingerprint(lineText, secret);
        String detail = "Move it to an environment variable or a secret manager (e.g. ${" + suggestEnv(key) + "}), and rotate it: "
                + "anything committed to git must be treated as leaked.";
        out.putIfAbsent(id, new Finding(id, FindingType.SECRET, sev, label + " in " + where, detail, Optional.empty(),
                Optional.of(new Location(f.relative(), line, col)), evidence));
    }

    private static String suggestEnv(String key) {
        if (key == null) return "SECRET";
        return key.replaceAll("[^A-Za-z0-9]+", "_").replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT);
    }

    private static String stripComment(String v) {
        int hash = v.indexOf(" #");
        return hash >= 0 ? v.substring(0, hash).trim() : v.trim();
    }

    private static String unquote(String v) {
        String s = v.trim();
        if (s.length() >= 2 && (s.startsWith("\"") && s.endsWith("\"") || s.startsWith("'") && s.endsWith("'"))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
