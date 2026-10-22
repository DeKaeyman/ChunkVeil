import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;

/**
 * Summarises CPU samples (jdk.ExecutionSample) of a server JFR recording,
 * with a focus on ChunkVeil. Run with Java 21+ as a single-file program:
 *
 *   java stress/tools/JfrHotspots.java recording.jfr [fromEpochMillis toEpochMillis] > hotspots.json
 *
 * Output (JSON):
 *  - samples per thread group (tick thread = Paper main thread or Folia region
 *    threads, netty = network threads, other)
 *  - how many of those contain a ChunkVeil frame, and how many contain a
 *    ProtocolLib frame without ChunkVeil (listener overhead outside our code)
 *  - ChunkVeil "entry points" (outermost ChunkVeil frame: which feature)
 *  - ChunkVeil hot methods (innermost ChunkVeil frame: inclusive time)
 *  - leaf methods inside ChunkVeil samples (where the CPU actually is)
 *  - the overall hottest leaf methods on tick threads (context)
 */
public final class JfrHotspots {
    private static final String CHUNKVEIL = "com.dekaeyman.chunkveil.";
    private static final String PROTOCOLLIB = "com.comphenix.protocol.";
    // The stress driver plugin shares the package prefix; it is not ChunkVeil.
    private static final String STRESS_DRIVER = "com.dekaeyman.chunkveil.stress.";

    private JfrHotspots() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.err.println("usage: java JfrHotspots.java <recording.jfr> [fromEpochMillis toEpochMillis]");
            System.exit(2);
        }
        Path file = Path.of(args[0]);
        if (args.length >= 3 && args[1].equals("--callers")) {
            printCallers(file, args[2], args.length >= 4 ? Integer.parseInt(args[3]) : 8);
            return;
        }
        long from = args.length >= 3 ? Long.parseLong(args[1]) : Long.MIN_VALUE;
        long to = args.length >= 3 ? Long.parseLong(args[2]) : Long.MAX_VALUE;

        Map<String, long[]> groups = new HashMap<>(); // group -> {samples, chunkveil, protocolLibOnly}
        Map<String, Long> entryPoints = new HashMap<>();
        Map<String, Long> innermost = new HashMap<>();
        Map<String, Long> cvLeaves = new HashMap<>();
        Map<String, Long> tickLeaves = new HashMap<>();
        Map<String, Long> cvByGroupEntry = new HashMap<>();
        long total = 0;
        long first = Long.MAX_VALUE;
        long last = Long.MIN_VALUE;

        try (RecordingFile recording = new RecordingFile(file)) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                if (!event.getEventType().getName().equals("jdk.ExecutionSample")) {
                    continue;
                }
                Instant start = event.getStartTime();
                long millis = start.toEpochMilli();
                if (millis < from || millis > to) {
                    continue;
                }
                RecordedStackTrace stack = event.getStackTrace();
                if (stack == null) {
                    continue;
                }
                first = Math.min(first, millis);
                last = Math.max(last, millis);
                total++;
                RecordedThread thread = event.getThread("sampledThread");
                String group = group(thread == null ? "" : thread.getJavaName());
                long[] counters = groups.computeIfAbsent(group, ignored -> new long[3]);
                counters[0]++;

                List<RecordedFrame> frames = stack.getFrames();
                String leaf = frames.isEmpty() ? "?" : name(frames.get(0));
                String innermostCv = null;
                String outermostCv = null;
                boolean protocolLib = false;
                for (RecordedFrame frame : frames) {
                    if (!frame.isJavaFrame()) {
                        continue;
                    }
                    String type = frame.getMethod().getType().getName();
                    if (type.startsWith(CHUNKVEIL) && !type.startsWith(STRESS_DRIVER)) {
                        String name = name(frame);
                        if (innermostCv == null) {
                            innermostCv = name;
                        }
                        outermostCv = name;
                    } else if (type.startsWith(PROTOCOLLIB)) {
                        protocolLib = true;
                    }
                }
                if (group.equals("tick")) {
                    tickLeaves.merge(leaf, 1L, Long::sum);
                }
                if (innermostCv != null) {
                    counters[1]++;
                    entryPoints.merge(outermostCv, 1L, Long::sum);
                    innermost.merge(innermostCv, 1L, Long::sum);
                    cvLeaves.merge(leaf, 1L, Long::sum);
                    cvByGroupEntry.merge(group + " | " + outermostCv, 1L, Long::sum);
                } else if (protocolLib) {
                    counters[2]++;
                }
            }
        }

        StringBuilder json = new StringBuilder();
        json.append("{\n  \"file\": \"").append(escape(file.toString())).append("\",\n");
        json.append("  \"samples\": ").append(total).append(",\n");
        json.append("  \"firstMillis\": ").append(first == Long.MAX_VALUE ? 0 : first).append(",\n");
        json.append("  \"lastMillis\": ").append(last == Long.MIN_VALUE ? 0 : last).append(",\n");
        json.append("  \"groups\": {");
        boolean firstEntry = true;
        for (Map.Entry<String, long[]> entry : groups.entrySet()) {
            json.append(firstEntry ? "\n" : ",\n");
            firstEntry = false;
            long[] c = entry.getValue();
            json.append(String.format(Locale.ROOT,
                    "    \"%s\": {\"samples\": %d, \"chunkveil\": %d, \"protocolLibOutsideChunkVeil\": %d}",
                    entry.getKey(), c[0], c[1], c[2]));
        }
        json.append("\n  },\n");
        appendTop(json, "chunkveilEntryPoints", entryPoints, 40, false);
        appendTop(json, "chunkveilEntryPointsByThreadGroup", cvByGroupEntry, 40, false);
        appendTop(json, "chunkveilInnermostMethods", innermost, 40, false);
        appendTop(json, "leafMethodsInChunkveilSamples", cvLeaves, 40, false);
        appendTop(json, "leafMethodsOnTickThreads", tickLeaves, 40, true);
        json.append("}\n");
        System.out.print(json);
    }

    /**
     * {@code java JfrHotspots.java rec.jfr --callers <leaf substring> [depth]}:
     * the most common call stacks (top {@code depth} frames) of samples whose
     * leaf method contains the substring. Answers "who calls this hot method".
     */
    private static void printCallers(Path file, String leafPart, int depth) throws IOException {
        Map<String, Long> stacks = new HashMap<>();
        long matched = 0;
        try (RecordingFile recording = new RecordingFile(file)) {
            while (recording.hasMoreEvents()) {
                RecordedEvent event = recording.readEvent();
                if (!event.getEventType().getName().equals("jdk.ExecutionSample") || event.getStackTrace() == null) {
                    continue;
                }
                List<RecordedFrame> frames = event.getStackTrace().getFrames();
                if (frames.isEmpty() || !name(frames.get(0)).contains(leafPart)) {
                    continue;
                }
                matched++;
                StringBuilder key = new StringBuilder();
                for (int i = 0; i < Math.min(depth, frames.size()); i++) {
                    key.append(i == 0 ? "" : "\n      <- ").append(name(frames.get(i)));
                }
                stacks.merge(key.toString(), 1L, Long::sum);
            }
        }
        List<Map.Entry<String, Long>> entries = new ArrayList<>(stacks.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        System.out.println(matched + " samples with leaf containing '" + leafPart + "'");
        for (int i = 0; i < Math.min(12, entries.size()); i++) {
            System.out.println(String.format(Locale.ROOT, "%7d  %s", entries.get(i).getValue(), entries.get(i).getKey()));
        }
    }

    private static String group(String threadName) {
        if (threadName.equals("Server thread") || threadName.startsWith("Region Scheduler Thread")
                || threadName.startsWith("Folia Region")) {
            return "tick";
        }
        String lower = threadName.toLowerCase(Locale.ROOT);
        if (lower.contains("netty") || lower.contains("epoll") || lower.contains("nio")) {
            return "netty";
        }
        if (lower.contains("chunk") || lower.contains("worldgen") || lower.contains("c2me")
                || lower.contains("moonrise") || lower.contains("io worker")) {
            return "chunk-system";
        }
        return "other";
    }

    private static String name(RecordedFrame frame) {
        if (!frame.isJavaFrame()) {
            return "[native]";
        }
        return frame.getMethod().getType().getName() + "." + frame.getMethod().getName();
    }

    private static void appendTop(StringBuilder json, String key, Map<String, Long> counts, int limit, boolean last) {
        List<Map.Entry<String, Long>> entries = new ArrayList<>(counts.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        json.append("  \"").append(key).append("\": [");
        for (int i = 0; i < Math.min(limit, entries.size()); i++) {
            json.append(i == 0 ? "\n" : ",\n");
            json.append("    {\"name\": \"").append(escape(entries.get(i).getKey())).append("\", \"samples\": ")
                    .append(entries.get(i).getValue()).append('}');
        }
        json.append(entries.isEmpty() ? "]" : "\n  ]").append(last ? "\n" : ",\n");
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
