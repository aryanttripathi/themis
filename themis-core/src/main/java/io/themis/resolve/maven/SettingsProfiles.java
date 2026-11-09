package io.themis.resolve.maven;

import org.apache.maven.model.Activation;
import org.apache.maven.model.ActivationFile;
import org.apache.maven.model.ActivationOS;
import org.apache.maven.model.ActivationProperty;
import org.apache.maven.model.Profile;
import org.apache.maven.model.Repository;
import org.apache.maven.model.RepositoryPolicy;
import org.apache.maven.settings.Settings;

import java.util.List;

/**
 * Converts settings.xml profiles into POM profiles for the model builder ("external profiles"),
 * like Maven's {@code SettingsUtils.convertFromSettingsProfile} (which lives in maven-core). The
 * model builder then evaluates their activation (activeByDefault, jdk, os, property, file, plus
 * {@code <activeProfiles>}) and injects their properties and repositories into every model it builds.
 */
final class SettingsProfiles {

    private SettingsProfiles() {
    }

    static List<Profile> toModelProfiles(Settings settings) {
        return settings.getProfiles().stream().map(SettingsProfiles::toModel).toList();
    }

    /** Repositories of profiles that are active by id or by {@code activeByDefault} (seed for parent lookups). */
    static List<org.apache.maven.settings.Repository> explicitlyActiveRepositories(Settings settings) {
        return settings.getProfiles().stream()
                .filter(p -> settings.getActiveProfiles().contains(p.getId())
                        || (p.getActivation() != null && p.getActivation().isActiveByDefault()))
                .flatMap(p -> p.getRepositories().stream())
                .toList();
    }

    private static Profile toModel(org.apache.maven.settings.Profile s) {
        Profile p = new Profile();
        p.setId(s.getId());
        p.setSource("settings.xml");
        if (s.getActivation() != null) {
            org.apache.maven.settings.Activation sa = s.getActivation();
            Activation a = new Activation();
            a.setActiveByDefault(sa.isActiveByDefault());
            a.setJdk(sa.getJdk());
            if (sa.getProperty() != null) {
                ActivationProperty ap = new ActivationProperty();
                ap.setName(sa.getProperty().getName());
                ap.setValue(sa.getProperty().getValue());
                a.setProperty(ap);
            }
            if (sa.getOs() != null) {
                ActivationOS os = new ActivationOS();
                os.setName(sa.getOs().getName());
                os.setFamily(sa.getOs().getFamily());
                os.setArch(sa.getOs().getArch());
                os.setVersion(sa.getOs().getVersion());
                a.setOs(os);
            }
            if (sa.getFile() != null) {
                ActivationFile f = new ActivationFile();
                f.setExists(sa.getFile().getExists());
                f.setMissing(sa.getFile().getMissing());
                a.setFile(f);
            }
            p.setActivation(a);
        }
        p.setProperties(s.getProperties());
        s.getRepositories().forEach(r -> p.addRepository(toModel(r)));
        s.getPluginRepositories().forEach(r -> p.addPluginRepository(toModel(r)));
        return p;
    }

    private static Repository toModel(org.apache.maven.settings.Repository s) {
        Repository r = new Repository();
        r.setId(s.getId());
        r.setName(s.getName());
        r.setUrl(s.getUrl());
        r.setLayout(s.getLayout());
        r.setReleases(toModel(s.getReleases()));
        r.setSnapshots(toModel(s.getSnapshots()));
        return r;
    }

    private static RepositoryPolicy toModel(org.apache.maven.settings.RepositoryPolicy s) {
        if (s == null) return null;
        RepositoryPolicy p = new RepositoryPolicy();
        p.setEnabled(s.isEnabled());
        p.setUpdatePolicy(s.getUpdatePolicy());
        p.setChecksumPolicy(s.getChecksumPolicy());
        return p;
    }
}
