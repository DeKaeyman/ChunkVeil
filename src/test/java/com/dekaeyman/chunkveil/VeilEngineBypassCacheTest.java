package com.dekaeyman.chunkveil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

class VeilEngineBypassCacheTest {
    private final AtomicInteger permissionLookups = new AtomicInteger();
    private final AtomicBoolean hasBypass = new AtomicBoolean();
    private final AtomicBoolean online = new AtomicBoolean(true);
    private final UUID playerId = UUID.randomUUID();

    private Player player() {
        return (Player) Proxy.newProxyInstance(Player.class.getClassLoader(), new Class<?>[]{Player.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> {
                        permissionLookups.incrementAndGet();
                        yield VeilEngine.BYPASS_PERMISSION.equals(args[0]) && hasBypass.get();
                    }
                    case "getUniqueId" -> playerId;
                    case "isOnline" -> online.get();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    @Test
    void packetPathLooksUpThePermissionOnce() {
        VeilEngine engine = new VeilEngine(null, null, null);
        Player player = player();
        hasBypass.set(true);
        for (int i = 0; i < 10_000; i++) {
            assertTrue(engine.isBypassed(player));
        }
        assertEquals(1, permissionLookups.get(), "per-block/per-packet checks must hit the cache");
    }

    @Test
    void lateLookupAfterQuitIsNotCached() {
        VeilEngine engine = new VeilEngine(null, null, null);
        Player player = player();
        online.set(false);
        engine.isBypassed(player);
        engine.isBypassed(player);
        assertEquals(2, permissionLookups.get(), "an offline player's entry would never be removed");
    }

    @Test
    void entryGoesStaleAfterAboutOneSecond() {
        long checked = 10_000L;
        assertFalse(VeilEngine.bypassEntryStale(checked, checked));
        assertFalse(VeilEngine.bypassEntryStale(checked, checked + VeilEngine.BYPASS_REFRESH_MILLIS - 1));
        assertTrue(VeilEngine.bypassEntryStale(checked, checked + VeilEngine.BYPASS_REFRESH_MILLIS));
        assertTrue(VeilEngine.bypassEntryStale(checked, checked - 5), "clock stepped back");
    }
}
