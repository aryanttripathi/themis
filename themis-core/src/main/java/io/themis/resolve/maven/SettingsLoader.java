package io.themis.resolve.maven;

import org.apache.maven.settings.Proxy;
import org.apache.maven.settings.Server;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.building.DefaultSettingsBuilderFactory;
import org.apache.maven.settings.building.DefaultSettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuildingException;
import org.apache.maven.settings.building.SettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsProblem;
import org.apache.maven.settings.crypto.DefaultSettingsDecrypter;
import org.apache.maven.settings.crypto.DefaultSettingsDecryptionRequest;
import org.apache.maven.settings.crypto.SettingsDecrypter;
import org.apache.maven.settings.crypto.SettingsDecryptionResult;
import org.sonatype.plexus.components.cipher.DefaultPlexusCipher;
import org.sonatype.plexus.components.sec.dispatcher.DefaultSecDispatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Loads settings.xml the way Maven does: global + user files merged and interpolated
 * ({@code ${user.home}}, {@code ${env.X}}), then server/proxy passwords decrypted with the
 * settings-security.xml of {@link ResolverOptions#settingsSecurity()}.
 * <p>
 * A password that cannot be decrypted is dropped (never sent still encrypted) and reported as a
 * warning that names only the server/proxy id; the scan goes on without those credentials.
 */
final class SettingsLoader {

    /** Effective settings plus non-fatal problems, worded for users. */
    record Loaded(Settings settings, List<String> warnings) {
        Loaded {
            warnings = List.copyOf(warnings);
        }
    }

    private SettingsLoader() {
    }

    static Loaded load(ResolverOptions options, Properties systemProperties) {
        Settings settings = build(options, systemProperties);
        List<String> warnings = new ArrayList<>();
        decrypt(settings, options, warnings);
        return new Loaded(settings, warnings);
    }

    private static Settings build(ResolverOptions options, Properties systemProperties) {
        SettingsBuildingRequest request = new DefaultSettingsBuildingRequest()
                .setUserSettingsFile(options.userSettings().toFile())
                .setSystemProperties(systemProperties);
        options.globalSettings().ifPresent(g -> request.setGlobalSettingsFile(g.toFile()));
        try {
            // Missing files are fine (empty settings); warnings are ignored, as Maven only logs them.
            return new DefaultSettingsBuilderFactory().newInstance().build(request).getEffectiveSettings();
        } catch (SettingsBuildingException e) {
            throw new IllegalStateException("Invalid Maven settings: " + e.getMessage(), e);
        }
    }

    /** Decrypts each server and proxy on its own, so one bad password only disables that entry's credentials. */
    private static void decrypt(Settings settings, ResolverOptions options, List<String> warnings) {
        SettingsDecrypter decrypter = new DefaultSettingsDecrypter(new DefaultSecDispatcher(
                new DefaultPlexusCipher(), Map.of(), options.settingsSecurity().toString()));

        List<Server> servers = new ArrayList<>();
        for (Server server : settings.getServers()) {
            SettingsDecryptionResult r = decrypter.decrypt(new DefaultSettingsDecryptionRequest(server));
            if (hasErrors(r)) {
                Server stripped = server.clone();
                stripped.setPassword(null);
                stripped.setPassphrase(null);
                servers.add(stripped);
                warnings.add(cannotDecrypt("server '" + server.getId() + "'", options));
            } else {
                servers.add(r.getServer());
            }
        }
        settings.setServers(servers);

        List<Proxy> proxies = new ArrayList<>();
        for (Proxy proxy : settings.getProxies()) {
            SettingsDecryptionResult r = decrypter.decrypt(new DefaultSettingsDecryptionRequest(proxy));
            if (hasErrors(r)) {
                Proxy stripped = proxy.clone();
                stripped.setPassword(null);
                proxies.add(stripped);
                warnings.add(cannotDecrypt("proxy '" + proxy.getId() + "'", options));
            } else {
                proxies.add(r.getProxy());
            }
        }
        settings.setProxies(proxies);
    }

    private static boolean hasErrors(SettingsDecryptionResult result) {
        return result.getProblems().stream().anyMatch(p -> p.getSeverity() != SettingsProblem.Severity.WARNING);
    }

    /** Deliberately our own wording: decrypter messages are not echoed so no secret material can leak. */
    private static String cannotDecrypt(String what, ResolverOptions options) {
        return "Cannot decrypt the password of " + what + " in Maven settings (master password in "
                + options.settingsSecurity() + "); resolving without its password";
    }
}
