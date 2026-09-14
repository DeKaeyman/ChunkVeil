# ChunkVeil 1.1.0-rc.4 — One bad packet never takes down a running server

ChunkVeil 1.1.0-rc.4 changes how runtime packet failures are handled, motivated by a Canvas 26.2 field report: after five hours and 2.5 million perfectly protected chunk packets, a single malformed chunk packet — produced by one of three simultaneously installed anti-xray plugins — tripped the quarantine and kicked a full server.

With the new runtime failure tolerance (`security.runtime-failure-tolerance`, default 5 failures per 10 minutes), a packet that cannot be protected is dropped instead of sent, so nothing unprotected ever leaves the server; the affected chunk is immediately re-requested, so the viewer receives it properly protected moments later; and protection keeps running. Only repeated failures within the window apply the configured `runtime-trip-action`, because that pattern means something is systematically wrong rather than one bad packet from a neighbor plugin. The strict preset keeps trip-on-first-failure, and startup behavior is unchanged: boot-time incompatibilities still stop the server under the strict startup policy.

Failure logs now carry real diagnostics — the exact parse position and buffer size for unreadable chunk data — and name plugins with actual evidence of modifying chunk packets (registered MAP_CHUNK listeners, known rewriters such as Orebfuscator) as the likely cause. Conflicts are named only when a failure has actually happened; there are no speculative startup warnings. `/chunkveil verify` reports tolerated failures in the current window as a warning while confirming protection is active, and packet-path health recovers after a successful rewrite instead of showing FAILED forever.

rc.4 also closes a fail-open gap: after a legacy multi-block-change rewrite failure, later multi-block packets were skipped unrewritten while protection appeared active. Every failing packet is now cancelled and counted instead.

## Short marketplace update text

**Title:** ChunkVeil 1.1.0-rc.4 — Runtime Failure Tolerance

**Description:** A single bad packet (usually from another packet-rewriting plugin) no longer quarantines a running server: it is dropped safely, the chunk is re-requested, and protection keeps running. Quarantine now requires repeated failures, and failure logs name the likely conflicting plugin with real diagnostics. Strongly recommended for all release-candidate testers.
