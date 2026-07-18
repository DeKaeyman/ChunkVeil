package com.dekaeyman.chunkveil;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import org.junit.jupiter.api.Test;

class ReleaseMetadataTest {
    @Test void loadsReleaseVerifiedVersionsFromBundledCanonicalMetadata() {
        assertEquals(Set.of("1.21.8", "1.21.11", "26.1.2", "26.2"),
                ReleaseMetadata.releaseVerifiedMinecraftVersions());
    }
}
