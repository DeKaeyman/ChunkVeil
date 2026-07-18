package com.dekaeyman.chunkveil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class VeilConfigMigratorTest {
    @Test
    void addsNewDefaultsWithoutOverwritingExistingValues() {
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("config-version", 1);
        defaults.set("worlds.world.hide-air", false);
        defaults.set("packet-protection.cancel-particles", true);
        defaults.set("packet-protection.sanitize-light", true);

        YamlConfiguration existing = new YamlConfiguration();
        existing.set("worlds.world.hide-air", true);

        List<String> added = VeilConfigMigrator.addMissingDefaults(existing, defaults);

        assertTrue(existing.getBoolean("worlds.world.hide-air"));
        assertTrue(existing.getBoolean("packet-protection.cancel-particles"));
        assertTrue(existing.getBoolean("packet-protection.sanitize-light"));
        assertTrue(added.contains("packet-protection.cancel-particles"));
        assertFalse(added.contains("worlds.world.hide-air"));
    }

    @Test
    void keepsUnknownCustomSettingsAndWorlds() {
        YamlConfiguration defaults = new YamlConfiguration();
        defaults.set("worlds.world.enabled", true);

        YamlConfiguration existing = new YamlConfiguration();
        existing.set("worlds.custom_world.enabled", false);
        existing.set("third-party-extension.value", 42);

        VeilConfigMigrator.addMissingDefaults(existing, defaults);

        assertFalse(existing.getBoolean("worlds.custom_world.enabled"));
        assertEquals(42, existing.getInt("third-party-extension.value"));
        assertTrue(existing.getBoolean("worlds.world.enabled"));
    }
}
