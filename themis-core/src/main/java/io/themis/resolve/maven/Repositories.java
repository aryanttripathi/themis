package io.themis.resolve.maven;

import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;

/** Converts POM and settings.xml repository declarations into Maven Resolver repositories. */
final class Repositories {

    /** The repository every POM inherits from the super POM. */
    static final RemoteRepository CENTRAL =
            new RemoteRepository.Builder("central", "default", "https://repo.maven.apache.org/maven2")
                    .setSnapshotPolicy(policy(false, null, null))
                    .build();

    private Repositories() {
    }

    static RemoteRepository toRemote(org.apache.maven.model.Repository r) {
        return remote(r.getId(), r.getLayout(), r.getUrl(),
                r.getReleases() == null ? null : policy(r.getReleases().isEnabled(), r.getReleases().getUpdatePolicy(),
                        r.getReleases().getChecksumPolicy()),
                r.getSnapshots() == null ? null : policy(r.getSnapshots().isEnabled(), r.getSnapshots().getUpdatePolicy(),
                        r.getSnapshots().getChecksumPolicy()));
    }

    static RemoteRepository toRemote(org.apache.maven.settings.Repository r) {
        return remote(r.getId(), r.getLayout(), r.getUrl(),
                r.getReleases() == null ? null : policy(r.getReleases().isEnabled(), r.getReleases().getUpdatePolicy(),
                        r.getReleases().getChecksumPolicy()),
                r.getSnapshots() == null ? null : policy(r.getSnapshots().isEnabled(), r.getSnapshots().getUpdatePolicy(),
                        r.getSnapshots().getChecksumPolicy()));
    }

    /** The two declaration types (POM and settings) differ only in class, so both funnel through here. */
    private static RemoteRepository remote(String id, String layout, String url,
                                           RepositoryPolicy releases, RepositoryPolicy snapshots) {
        return new RemoteRepository.Builder(id, layout == null || layout.isBlank() ? "default" : layout, url)
                .setReleasePolicy(releases == null ? policy(true, null, null) : releases)
                .setSnapshotPolicy(snapshots == null ? policy(true, null, null) : snapshots)
                .build();
    }

    /** Unset policies get Maven's defaults: check daily, warn on checksum mismatch. */
    private static RepositoryPolicy policy(boolean enabled, String updatePolicy, String checksumPolicy) {
        return new RepositoryPolicy(enabled,
                updatePolicy == null || updatePolicy.isBlank() ? RepositoryPolicy.UPDATE_POLICY_DAILY : updatePolicy,
                checksumPolicy == null || checksumPolicy.isBlank() ? RepositoryPolicy.CHECKSUM_POLICY_WARN : checksumPolicy);
    }
}
