package io.themis.config;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.UnrecognizedPropertyException;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.themis.license.SpdxNormalizer;
import io.themis.model.LicenseCategory;
import io.themis.model.Severity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Reads {@code .themis.yml} (docs/LLD.md section 12). Every key is checked: an unknown key, a
 * value of the wrong type or an unknown severity/category is a {@link ConfigException} naming the
 * key path, the file and the line, so typos never silently weaken a policy.
 * <pre>
 * javaRelease: 17
 * analyzers: [licenses, javaversion]
 * failOn:
 *   severity: HIGH
 * licenses:
 *   severity: {NETWORK_COPYLEFT: CRITICAL, UNKNOWN: NONE}   # merged over the defaults
 *   overrides: {"com.example:legacy-lib": "Apache-2.0"}     # g:a or g:a:v -> SPDX
 *   allowArtifacts: ["org.mariadb.jdbc:mariadb-java-client"]
 * network:
 *   offline: false
 *   cacheTtlHours: 24
 * </pre>
 */
public final class ConfigLoader {

    public static final String FILE_NAME = ".themis.yml";

    private static final YAMLMapper MAPPER = YAMLMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();
    private static final Pattern ARTIFACT_KEY = Pattern.compile("[^:\\s]+:[^:\\s]+(:[^:\\s]+)?");

    // File shape, bound field by field. (Plain classes rather than records: with creator-based
    // binding Jackson buffers properties and reports an unknown key at the end of its object,
    // which gives the wrong line number.)
    static final class FileDto {
        public Integer javaRelease;
        public List<String> analyzers;
        public FailOnDto failOn;
        public LicensesDto licenses;
        public NetworkDto network;
    }

    static final class FailOnDto {
        public String severity;
    }

    static final class LicensesDto {
        public Map<String, String> severity;
        public Map<String, String> overrides;
        public List<String> allowArtifacts;
    }

    static final class NetworkDto {
        public Boolean offline;
        public Integer cacheTtlHours;
    }

    private ConfigLoader() {
    }

    /**
     * The explicit file if given (it must exist), else {@code projectDir/.themis.yml} if present.
     */
    public static Optional<ConfigFile> load(Path projectDir, Optional<Path> explicit) {
        return load(projectDir, explicit, Set.of());
    }

    /** As {@link #load(Path, Optional)}, also checking {@code analyzers} against the known names. */
    public static Optional<ConfigFile> load(Path projectDir, Optional<Path> explicit, Set<String> knownAnalyzers) {
        if (explicit.isPresent()) {
            if (!Files.isRegularFile(explicit.get())) throw new ConfigException("Config file not found: " + explicit.get());
            return Optional.of(read(explicit.get(), knownAnalyzers));
        }
        Path file = projectDir.resolve(FILE_NAME);
        return Files.isRegularFile(file) ? Optional.of(read(file, knownAnalyzers)) : Optional.empty();
    }

    public static ConfigFile read(Path file) {
        return read(file, Set.of());
    }

    /** @param knownAnalyzers valid {@code analyzers} entries; empty = not checked */
    public static ConfigFile read(Path file, Set<String> knownAnalyzers) {
        String text;
        try {
            text = Files.readString(file);
        } catch (IOException e) {
            throw new ConfigException("Cannot read config file " + file + ": " + e.getMessage(), e);
        }
        if (text.isBlank()) return ConfigFile.empty(file);
        FileDto dto;
        try {
            dto = MAPPER.readValue(text, FileDto.class);
        } catch (UnrecognizedPropertyException e) {
            String known = e.getKnownPropertyIds() == null ? "" : e.getKnownPropertyIds().stream()
                    .map(String::valueOf).collect(Collectors.toCollection(TreeSet::new)).toString();
            throw new ConfigException("Unknown key '" + path(e) + "' in " + file + where(e.getLocation())
                    + (known.isEmpty() ? "" : "; known keys here: " + known), e);
        } catch (JsonMappingException e) {
            throw new ConfigException("Invalid value for '" + path(e) + "' in " + file + where(e.getLocation())
                    + ": " + e.getOriginalMessage(), e);
        } catch (JsonProcessingException e) {
            throw new ConfigException("Cannot parse " + file + where(e.getLocation()) + ": " + e.getOriginalMessage(), e);
        }
        return dto == null ? ConfigFile.empty(file) : validate(file, dto, knownAnalyzers);
    }

    private static ConfigFile validate(Path file, FileDto dto, Set<String> knownAnalyzers) {
        Optional<Set<String>> analyzers = Optional.ofNullable(dto.analyzers).map(LinkedHashSet::new);
        if (analyzers.isPresent() && !knownAnalyzers.isEmpty()) {
            for (String a : analyzers.get()) {
                if (!knownAnalyzers.contains(a)) {
                    throw new ConfigException(file + ": analyzers: unknown analyzer '" + a + "' (known: "
                            + new TreeSet<>(knownAnalyzers) + ")");
                }
            }
        }
        Optional<Severity> failOn = Optional.ofNullable(dto.failOn).map(f -> f.severity)
                .map(s -> severity(file, "failOn.severity", s, false).orElseThrow());

        Map<LicenseCategory, Severity> severities = new EnumMap<>(LicenseCategory.class);
        Set<LicenseCategory> silenced = EnumSet.noneOf(LicenseCategory.class);
        Map<String, String> overrides = new LinkedHashMap<>();
        Set<String> allow = new LinkedHashSet<>();
        LicensesDto licenses = dto.licenses;
        if (licenses != null) {
            if (licenses.severity != null) {
                licenses.severity.forEach((category, value) -> {
                    LicenseCategory cat = category(file, category);
                    severity(file, "licenses.severity." + category, value, true)
                            .ifPresentOrElse(s -> severities.put(cat, s), () -> silenced.add(cat));
                });
            }
            if (licenses.overrides != null) {
                licenses.overrides.forEach((artifact, expression) -> {
                    if (!ARTIFACT_KEY.matcher(artifact).matches()) {
                        throw new ConfigException(file + ": licenses.overrides: key '" + artifact
                                + "' must be groupId:artifactId or groupId:artifactId:version");
                    }
                    String spdx = SpdxNormalizer.bundled().normalize(expression, null)
                            .map(SpdxNormalizer.Normalized::spdx)
                            .orElseThrow(() -> new ConfigException(file + ": licenses.overrides[\"" + artifact + "\"]: '"
                                    + expression + "' is not a recognised SPDX expression (use an SPDX id such as "
                                    + "Apache-2.0, an expression such as 'MIT OR Apache-2.0', or LicenseRef-<name>)"));
                    overrides.put(artifact, spdx);
                });
            }
            if (licenses.allowArtifacts != null) {
                for (String ga : licenses.allowArtifacts) {
                    if (ga == null || !ga.matches("[^:\\s]+:[^:\\s]+")) {
                        throw new ConfigException(file + ": licenses.allowArtifacts: '" + ga + "' must be groupId:artifactId");
                    }
                    allow.add(ga);
                }
            }
        }
        NetworkDto network = dto.network;
        Optional<Boolean> offline = Optional.ofNullable(network).map(n -> n.offline);
        OptionalInt ttl = network == null || network.cacheTtlHours == null
                ? OptionalInt.empty() : OptionalInt.of(network.cacheTtlHours);
        if (ttl.isPresent() && ttl.getAsInt() < 0) {
            throw new ConfigException(file + ": network.cacheTtlHours must not be negative");
        }
        return new ConfigFile(file, dto.javaRelease == null ? OptionalInt.empty() : OptionalInt.of(dto.javaRelease),
                analyzers, failOn, severities, silenced, overrides, allow, offline, ttl);
    }

    /** Case-insensitive (locale-independent); {@code NONE} = empty, only where allowed. */
    private static Optional<Severity> severity(Path file, String key, String value, boolean noneAllowed) {
        String v = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (noneAllowed && v.equals("NONE")) return Optional.empty();
        try {
            return Optional.of(Severity.valueOf(v));
        } catch (IllegalArgumentException e) {
            throw new ConfigException(file + ": " + key + ": '" + value + "' is not a severity (expected one of "
                    + Arrays.toString(Severity.values()) + (noneAllowed ? " or NONE" : "") + ")");
        }
    }

    private static LicenseCategory category(Path file, String value) {
        try {
            return LicenseCategory.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ConfigException(file + ": licenses.severity: unknown category '" + value + "' (expected one of "
                    + Arrays.toString(LicenseCategory.values()) + ")");
        }
    }

    private static String path(JsonMappingException e) {
        return e.getPath().stream()
                .map(r -> r.getFieldName() != null ? r.getFieldName() : "[" + r.getIndex() + "]")
                .collect(Collectors.joining(".")).replace(".[", "[");
    }

    private static String where(JsonLocation location) {
        return location == null || location.getLineNr() < 1 ? ""
                : " (line " + location.getLineNr() + ", column " + location.getColumnNr() + ")";
    }
}
