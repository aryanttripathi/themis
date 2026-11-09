package io.themis.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CoordinateTest {

    @Test
    void plainJarPrintsAsGav() {
        Coordinate c = new Coordinate("g", "a", "1.0");
        assertThat(c.extension()).isEqualTo("jar");
        assertThat(c.classifier()).isEmpty();
        assertThat(c).hasToString("g:a:1.0");
        assertThat(Coordinate.parse("g:a:1.0")).isEqualTo(c);
    }

    @Test
    void classifierAndNonJarExtensionsAppearOnlyWhenNonDefault() {
        Coordinate classified = new Coordinate("io.netty", "epoll", "4.1", "jar", "linux-x86_64");
        Coordinate zip = new Coordinate("g", "dist", "2", "zip", "");

        assertThat(classified).hasToString("io.netty:epoll:jar:linux-x86_64:4.1");
        assertThat(zip).hasToString("g:dist:zip:2");
        assertThat(Coordinate.parse(classified.toString())).isEqualTo(classified);
        assertThat(Coordinate.parse(zip.toString())).isEqualTo(zip);
        assertThat(classified.ga()).isEqualTo("io.netty:epoll");
        assertThat(classified.artifactKey()).isEqualTo("io.netty:epoll:jar:linux-x86_64");
        assertThat(classified).isNotEqualTo(new Coordinate("io.netty", "epoll", "4.1", "jar", "linux-aarch_64"));
    }

    @Test
    void rejectsMalformedInput() {
        assertThatThrownBy(() -> Coordinate.parse("g:a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Coordinate.parse("a:b:c:d:e:f")).isInstanceOf(IllegalArgumentException.class);
    }
}
