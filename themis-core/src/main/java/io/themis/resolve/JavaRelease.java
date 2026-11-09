package io.themis.resolve;

import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Detects the Java release a project targets from its (effective) build properties. */
public final class JavaRelease {

    public static final int DEFAULT = 17;

    /** Properties checked in order; Spring Boot projects typically set java.version. */
    public static final List<String> PROPERTIES = List.of(
            "maven.compiler.release", "maven.compiler.target", "maven.compiler.source", "java.version");

    private static final Pattern LEADING_NUMBER = Pattern.compile("^(\\d+)(?:[.\\-+_].*)?$");

    private JavaRelease() {
    }

    /** First parsable value of {@link #PROPERTIES}, else {@link #DEFAULT}. */
    public static int detect(Properties props) {
        for (String name : PROPERTIES) {
            Optional<Integer> v = Optional.ofNullable(props.getProperty(name)).flatMap(JavaRelease::parse);
            if (v.isPresent()) return v.get();
        }
        return DEFAULT;
    }

    /** "17" -> 17, "1.8" -> 8, "21.0.2" -> 21, "${java.version}" -> empty. */
    public static Optional<Integer> parse(String raw) {
        String s = raw.trim();
        if (s.startsWith("1.")) s = s.substring(2);
        Matcher m = LEADING_NUMBER.matcher(s);
        return m.matches() ? Optional.of(Integer.parseInt(m.group(1))) : Optional.empty();
    }
}
