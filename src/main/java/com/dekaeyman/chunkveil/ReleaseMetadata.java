package com.dekaeyman.chunkveil;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Runtime view of the canonical build/release metadata bundled in the plugin jar. */
final class ReleaseMetadata {
    private static final Pattern VERIFIED_COMBINATION = Pattern.compile(
            "\\\"paperVersion\\\":\\\"([^\\\"]+)\\\"[^}]*\\\"releaseVerified\\\":\\\"([^\\\"]+)\\\"");

    private ReleaseMetadata() {
    }

    static Set<String> releaseVerifiedMinecraftVersions() {
        try (InputStream input = ReleaseMetadata.class.getResourceAsStream("/release-metadata.json")) {
            if (input == null) {
                return Set.of();
            }
            String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            Set<String> versions = new HashSet<>();
            Matcher matcher = VERIFIED_COMBINATION.matcher(json);
            while (matcher.find()) {
                versions.add(matcher.group(1));
            }
            return Set.copyOf(versions);
        } catch (IOException exception) {
            return Set.of();
        }
    }
}
