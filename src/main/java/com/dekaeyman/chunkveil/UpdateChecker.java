package com.dekaeyman.chunkveil;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Checks a hosted version manifest for newer ChunkVeil releases that declare
 * compatibility with this server's Minecraft version.
 *
 * The checker only reads version metadata over HTTPS. It never downloads or
 * installs anything, runs entirely off the main thread, and never touches the
 * veil protection runtime: any failure here is logged and swallowed.
 */
final class UpdateChecker {
    private static final String DEFAULT_MANIFEST_URL = "https://raw.githubusercontent.com/DeKaeyman/ChunkVeil/main/update.json";
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(10);
    private static final long FIRST_CHECK_DELAY_TICKS = 20L * 15L;

    private final ChunkVeilPlugin plugin;
    private final boolean enabled;
    private final boolean notifyInGame;
    private final boolean includePrereleases;
    private final String manifestUrl;
    private final long intervalTicks;
    private final HttpClient httpClient;
    private final Set<UUID> notifiedPlayers = ConcurrentHashMap.newKeySet();
    private volatile UpdateInfo availableUpdate;
    private volatile String lastLoggedVersion;
    private BukkitTask checkTask;

    private UpdateChecker(ChunkVeilPlugin plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("update-checker.enabled", true);
        this.notifyInGame = plugin.getConfig().getBoolean("update-checker.notify-in-game", true);
        this.includePrereleases = plugin.getConfig().getBoolean("update-checker.include-prereleases", false);
        this.manifestUrl = plugin.getConfig().getString("update-checker.manifest-url", DEFAULT_MANIFEST_URL);
        this.intervalTicks = 20L * 3600L * Math.max(1L, plugin.getConfig().getLong("update-checker.interval-hours", 6L));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(HTTP_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    static UpdateChecker start(ChunkVeilPlugin plugin) {
        UpdateChecker checker = new UpdateChecker(plugin);
        if (checker.enabled) {
            checker.checkTask = Bukkit.getScheduler().runTaskTimerAsynchronously(
                    plugin,
                    () -> checker.performCheck(true),
                    FIRST_CHECK_DELAY_TICKS,
                    checker.intervalTicks
            );
        }
        return checker;
    }

    void stop() {
        if (checkTask != null) {
            checkTask.cancel();
            checkTask = null;
        }
    }

    boolean enabled() {
        return enabled;
    }

    UpdateInfo availableUpdate() {
        return availableUpdate;
    }

    /**
     * Runs a check now for a command sender and reports the outcome to them.
     */
    void checkNow(CommandSender sender) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            CheckOutcome outcome = performCheck(false);
            if (!plugin.isEnabled()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> deliverOutcome(sender, outcome));
        });
    }

    /**
     * Sends the update notice to a joining player, at most once per version.
     */
    void notifyPlayer(Player player) {
        UpdateInfo update = availableUpdate;
        if (!enabled || !notifyInGame || update == null) {
            return;
        }
        if (!player.hasPermission("chunkveil.update") && !player.hasPermission("chunkveil.admin")) {
            return;
        }
        if (!notifiedPlayers.add(player.getUniqueId())) {
            return;
        }
        sendUpdateMessages(player, update);
    }

    private void deliverOutcome(CommandSender sender, CheckOutcome outcome) {
        VeilLang lang = plugin.lang();
        if (outcome.error() != null) {
            sender.sendMessage(lang.message("commands.update.failed", Map.of("reason", outcome.error())));
            return;
        }
        if (outcome.update() == null) {
            sender.sendMessage(lang.message("commands.update.up-to-date", Map.of(
                    "version", pluginVersion(),
                    "mc", Bukkit.getMinecraftVersion()
            )));
            return;
        }
        if (sender instanceof Player player) {
            notifiedPlayers.add(player.getUniqueId());
        }
        sendUpdateMessages(sender, outcome.update());
    }

    private void sendUpdateMessages(CommandSender recipient, UpdateInfo update) {
        VeilLang lang = plugin.lang();
        recipient.sendMessage(lang.message("commands.update.available", Map.of(
                "latest", update.version(),
                "current", pluginVersion(),
                "mc", Bukkit.getMinecraftVersion()
        )));
        if (update.notes() != null && !update.notes().isBlank()) {
            recipient.sendMessage(lang.message("commands.update.notes", Map.of("notes", update.notes())));
        }

        Component link = LegacyComponentSerializer.legacySection()
                .deserialize(lang.message("commands.update.link", Map.of("url", update.download())))
                .clickEvent(ClickEvent.openUrl(update.download()))
                .hoverEvent(HoverEvent.showText(
                        LegacyComponentSerializer.legacySection().deserialize(lang.raw("commands.update.link-hover"))
                ));
        recipient.sendMessage(link);
    }

    private CheckOutcome performCheck(boolean broadcastNewUpdate) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(manifestUrl))
                    .timeout(HTTP_TIMEOUT)
                    .header("User-Agent", "ChunkVeil/" + pluginVersion() + " update-checker")
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                return CheckOutcome.failure("manifest request returned HTTP " + response.statusCode());
            }

            UpdateInfo update = selectUpdate(parseManifest(response.body()));
            applyCheckResult(update, broadcastNewUpdate);
            return CheckOutcome.success(update);
        } catch (Exception exception) {
            String reason = exception.getClass().getSimpleName()
                    + (exception.getMessage() == null || exception.getMessage().isBlank() ? "" : ": " + exception.getMessage());
            plugin.getLogger().fine("Update check failed: " + reason);
            return CheckOutcome.failure(reason);
        }
    }

    private void applyCheckResult(UpdateInfo update, boolean broadcastNewUpdate) {
        UpdateInfo previous = availableUpdate;
        availableUpdate = update;
        if (update == null) {
            return;
        }
        boolean newVersion = previous == null || !previous.version().equals(update.version());
        if (newVersion) {
            notifiedPlayers.clear();
        }

        if (!update.version().equals(lastLoggedVersion)) {
            lastLoggedVersion = update.version();
            String severity = update.severity() == null ? "recommended" : update.severity();
            plugin.getLogger().info("A new ChunkVeil version is available: " + update.version()
                    + " (current: " + pluginVersion() + ", severity: " + severity + ")."
                    + " Compatible with Minecraft " + Bukkit.getMinecraftVersion() + "."
                    + " Download: " + update.download());
        }

        if (newVersion && broadcastNewUpdate && notifyInGame && plugin.isEnabled()) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                UpdateInfo current = availableUpdate;
                if (current == null) {
                    return;
                }
                for (Player player : Bukkit.getOnlinePlayers()) {
                    notifyPlayer(player);
                }
            });
        }
    }

    /**
     * Picks the highest manifest release that is newer than the running plugin
     * version and declares a Minecraft range containing this server's version.
     * Releases without declared Minecraft ranges are never offered.
     */
    private UpdateInfo selectUpdate(List<ManifestRelease> releases) {
        String currentMc = Bukkit.getMinecraftVersion();
        String currentVersion = pluginVersion();
        ManifestRelease best = null;
        for (ManifestRelease release : releases) {
            if (release.version() == null || release.version().isBlank() || release.download() == null || release.download().isBlank()) {
                continue;
            }
            if (release.prerelease() && !includePrereleases) {
                continue;
            }
            if (release.mcRanges().isEmpty() || release.mcRanges().stream().noneMatch(range -> isWithinRange(currentMc, range))) {
                continue;
            }
            if (compareVersions(release.version(), currentVersion) <= 0) {
                continue;
            }
            if (best == null || compareVersions(release.version(), best.version()) > 0) {
                best = release;
            }
        }

        if (best == null) {
            return null;
        }
        return new UpdateInfo(stripVersionPrefix(best.version()), best.download(), best.severity(), best.notes());
    }

    private List<ManifestRelease> parseManifest(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonArray releaseArray = root.getAsJsonArray("releases");
        if (releaseArray == null) {
            return List.of();
        }

        List<ManifestRelease> releases = new ArrayList<>(releaseArray.size());
        for (JsonElement element : releaseArray) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject release = element.getAsJsonObject();
            String version = stringOrNull(release, "version");
            boolean prerelease = release.has("prerelease") && release.get("prerelease").isJsonPrimitive()
                    ? release.get("prerelease").getAsBoolean()
                    : version != null && version.contains("-");
            releases.add(new ManifestRelease(
                    version,
                    stringOrNull(release, "download"),
                    stringOrNull(release, "severity"),
                    prerelease,
                    stringOrNull(release, "notes"),
                    parseMcRanges(release.getAsJsonArray("mc"))
            ));
        }
        return releases;
    }

    private List<McRange> parseMcRanges(JsonArray ranges) {
        if (ranges == null) {
            return List.of();
        }
        List<McRange> parsed = new ArrayList<>(ranges.size());
        for (JsonElement element : ranges) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject range = element.getAsJsonObject();
            parsed.add(new McRange(stringOrNull(range, "min"), stringOrNull(range, "max")));
        }
        return parsed;
    }

    private String stringOrNull(JsonObject object, String key) {
        JsonElement element = object.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private String pluginVersion() {
        return plugin.getDescription().getVersion();
    }

    /**
     * Checks whether a Minecraft version falls in a [min, max] range. The max
     * bound is prefix-inclusive: {@code max: "1.21"} covers every 1.21.x patch
     * because the current version is truncated to the bound's segment count
     * before comparing.
     */
    static boolean isWithinRange(String current, McRange range) {
        if (range.min() != null && !range.min().isBlank() && compareVersions(current, range.min()) < 0) {
            return false;
        }
        if (range.max() != null && !range.max().isBlank()) {
            String truncated = truncateToSegments(current, segmentCount(range.max()));
            return compareVersions(truncated, range.max()) <= 0;
        }
        return true;
    }

    /**
     * Compares two version strings by numeric dot segments, e.g. "0.10.0" is
     * newer than "0.3.0" and "26.1" is newer than "1.21.11". A release is newer
     * than a prerelease of the same base ("0.4.0" is newer than "0.4.0-rc.1").
     */
    static int compareVersions(String left, String right) {
        String[] leftParts = stripVersionPrefix(left).split("-", 2);
        String[] rightParts = stripVersionPrefix(right).split("-", 2);
        int comparison = compareNumericSegments(leftParts[0], rightParts[0]);
        if (comparison != 0) {
            return comparison;
        }

        boolean leftRelease = leftParts.length == 1;
        boolean rightRelease = rightParts.length == 1;
        if (leftRelease && rightRelease) {
            return 0;
        }
        if (leftRelease) {
            return 1;
        }
        if (rightRelease) {
            return -1;
        }
        return leftParts[1].compareToIgnoreCase(rightParts[1]);
    }

    private static int compareNumericSegments(String left, String right) {
        String[] leftSegments = left.split("\\.");
        String[] rightSegments = right.split("\\.");
        int length = Math.max(leftSegments.length, rightSegments.length);
        for (int i = 0; i < length; i++) {
            int leftValue = i < leftSegments.length ? numericValue(leftSegments[i]) : 0;
            int rightValue = i < rightSegments.length ? numericValue(rightSegments[i]) : 0;
            if (leftValue != rightValue) {
                return Integer.compare(leftValue, rightValue);
            }
        }
        return 0;
    }

    private static int numericValue(String segment) {
        int end = 0;
        while (end < segment.length() && Character.isDigit(segment.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(segment.substring(0, end));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private static String stripVersionPrefix(String version) {
        String trimmed = version.trim();
        return trimmed.toLowerCase(Locale.ROOT).startsWith("v") ? trimmed.substring(1) : trimmed;
    }

    private static int segmentCount(String version) {
        return stripVersionPrefix(version).split("-", 2)[0].split("\\.").length;
    }

    private static String truncateToSegments(String version, int segments) {
        String[] parts = stripVersionPrefix(version).split("-", 2)[0].split("\\.");
        if (parts.length <= segments) {
            return String.join(".", parts);
        }
        StringBuilder truncated = new StringBuilder(parts[0]);
        for (int i = 1; i < segments; i++) {
            truncated.append('.').append(parts[i]);
        }
        return truncated.toString();
    }

    record UpdateInfo(String version, String download, String severity, String notes) {
    }

    record ManifestRelease(String version, String download, String severity, boolean prerelease, String notes, List<McRange> mcRanges) {
    }

    record McRange(String min, String max) {
    }

    private record CheckOutcome(UpdateInfo update, String error) {
        static CheckOutcome success(UpdateInfo update) {
            return new CheckOutcome(update, null);
        }

        static CheckOutcome failure(String error) {
            return new CheckOutcome(null, error);
        }
    }
}
