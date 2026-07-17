package com.dekaeyman.chunkveil.api;

import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired on the main thread whenever ChunkVeil's runtime protection changes
 * state: when it starts protecting, when an admin disables it, and when it
 * fails closed after a critical packet incompatibility.
 *
 * <p>Monitoring and alerting plugins can listen to this event to distinguish
 * "plugin loaded" from "server actually protected".
 */
public final class VeilProtectionStatusEvent extends Event {
    public enum Cause {
        /** Runtime protection started (server start, /chunkveil enable, or reload). */
        ENABLED,
        /** An admin ran /chunkveil disable. */
        DISABLED_BY_ADMIN,
        /** A critical packet incompatibility forced a fail-closed shutdown. */
        FAILED_CLOSED
    }

    private static final HandlerList HANDLERS = new HandlerList();

    private final boolean protectionActive;
    private final Cause cause;
    private final String reason;

    public VeilProtectionStatusEvent(boolean protectionActive, Cause cause, String reason) {
        this.protectionActive = protectionActive;
        this.cause = cause;
        this.reason = reason == null ? "" : reason;
    }

    /** Whether runtime protection is active after this state change. */
    public boolean protectionActive() {
        return protectionActive;
    }

    public Cause cause() {
        return cause;
    }

    /** Human-readable reason; empty when protection was enabled normally. */
    public String reason() {
        return reason;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
