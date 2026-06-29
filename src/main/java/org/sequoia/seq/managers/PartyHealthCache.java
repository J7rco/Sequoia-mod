package org.sequoia.seq.managers;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;

public class PartyHealthCache {

    private static final long HIGHER_MAX_PROMOTION_DELAY_MS = 4_000;
    private static final long LOWER_MAX_PROMOTION_DELAY_MS = 20_000;
    private static final long REFRESH_INTERVAL_MS = 250;

    /** Latest party HP snapshot, keyed by the real player's Minecraft UUID. */
    private static final Map<UUID, CachedPartyHealth> healthByUuid = new ConcurrentHashMap<>();

    /** Tracks trusted max HP separately from short-lived ability spikes and damage. */
    private static final Map<UUID, HealthMaxState> maxHealthByUuid = new ConcurrentHashMap<>();
    private static long lastRefreshMs;

    public record CachedPartyHealth(
            String nickname,
            String username,
            UUID uuid,
            int hp,
            int level,
            int maxHp) {}

    public record VisiblePlayerHealthDebug(
            String name,
            String profileName,
            String scoreboardName,
            String customName,
            UUID uuid,
            Optional<CachedPartyHealth> health,
            float percent) {}

    public static void tick() {
        lastRefreshMs = System.currentTimeMillis();

        List<WynnPartyScoreboardReader.PartyHealth> scoreboardHealth = WynnPartyScoreboardReader.readPartyHealth();
        Map<String, UUID> visiblePlayerUuids = visiblePlayerUuidsByUsername();
        Set<UUID> currentPartyUuids = new HashSet<>();

        for (WynnPartyScoreboardReader.PartyHealth member : scoreboardHealth) {
            if (member.username() == null || member.username().isBlank()) {
                continue;
            }

            UUID uuid = resolveVisiblePlayerUuid(visiblePlayerUuids, member.username());
            if (uuid == null) {
                uuid = resolveVisiblePlayerUuid(visiblePlayerUuids, member.nickname());
            }
            if (uuid == null) {
                continue;
            }
            currentPartyUuids.add(uuid);

            if (!member.online() || !member.alive()) {
                healthByUuid.remove(uuid);
                maxHealthByUuid.remove(uuid);
                continue;
            }

            int maxHp = maxHealth(uuid, member.hp());
            healthByUuid.put(uuid, new CachedPartyHealth(
                    member.nickname(),
                    member.username(),
                    uuid,
                    member.hp(),
                    member.level(),
                    maxHp));
        }

        healthByUuid.keySet().retainAll(currentPartyUuids);
        maxHealthByUuid.keySet().retainAll(currentPartyUuids);
    }

    public static Optional<CachedPartyHealth> get(UUID uuid) {
        refreshIfStale();
        if (uuid == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(healthByUuid.get(uuid));
    }

    public static float healthPercent(UUID uuid) {
        refreshIfStale();
        CachedPartyHealth health = uuid != null ? healthByUuid.get(uuid) : null;
        if (health == null || health.maxHp() <= 0) {
            return -1f;
        }
        return Math.max(0f, Math.min(1f, health.hp() / (float) health.maxHp()));
    }

    public static List<VisiblePlayerHealthDebug> visiblePlayerDebug() {
        tick();

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return List.of();
        }

        List<VisiblePlayerHealthDebug> result = new java.util.ArrayList<>();
        for (AbstractClientPlayer player : mc.level.players()) {
            Optional<CachedPartyHealth> health = Optional.ofNullable(healthByUuid.get(player.getUUID()));
            result.add(new VisiblePlayerHealthDebug(
                    player.getName().getString(),
                    player.getGameProfile().name(),
                    player.getScoreboardName(),
                    player.getCustomName() == null ? null : player.getCustomName().getString(),
                    player.getUUID(),
                    health,
                    healthPercent(player.getUUID())));
        }
        return result;
    }

    private static void refreshIfStale() {
        long now = System.currentTimeMillis();
        if (now - lastRefreshMs >= REFRESH_INTERVAL_MS) {
            tick();
        }
    }

    /**
     * Estimates max HP per UUID without trusting short-lived Wynn ability spikes.
     *
     * The scoreboard gives current HP, not true max HP. If current HP jumps above
     * the trusted max, we first treat it as a candidate. Only if that candidate is
     * still present after a delay do we promote it to the trusted max.
     *
     * Gear can also lower max HP. Since current HP and damaged HP look identical
     * from the scoreboard, downward changes use a longer stability window before
     * replacing the trusted max.
     */
    private static int maxHealth(UUID uuid, int currentHp) {
        if (uuid == null) {
            return currentHp;
        }

        long now = System.currentTimeMillis();
        HealthMaxState state = maxHealthByUuid.computeIfAbsent(
                uuid,
                ignored -> new HealthMaxState(currentHp, currentHp, now, currentHp, now, currentHp));

        if (currentHp == state.stableMaxHp) {
            state.higherCandidateMaxHp = state.stableMaxHp;
            state.higherCandidateSinceMs = now;
            state.lowerCandidateMaxHp = state.stableMaxHp;
            state.lowerCandidateSinceMs = now;
            state.lastObservedHp = currentHp;
            return state.stableMaxHp;
        }

        if (currentHp > state.stableMaxHp) {
            state.lowerCandidateMaxHp = state.stableMaxHp;
            state.lowerCandidateSinceMs = now;
            state.lastObservedHp = currentHp;

            if (currentHp != state.higherCandidateMaxHp) {
                state.higherCandidateMaxHp = currentHp;
                state.higherCandidateSinceMs = now;
                return state.stableMaxHp;
            }

            if (now - state.higherCandidateSinceMs >= HIGHER_MAX_PROMOTION_DELAY_MS) {
                state.stableMaxHp = currentHp;
                state.lowerCandidateMaxHp = currentHp;
                state.lowerCandidateSinceMs = now;
            }

            return state.stableMaxHp;
        }

        state.higherCandidateMaxHp = state.stableMaxHp;
        state.higherCandidateSinceMs = now;
        if (currentHp != state.lastObservedHp || currentHp != state.lowerCandidateMaxHp) {
            state.lowerCandidateMaxHp = currentHp;
            state.lowerCandidateSinceMs = now;
            state.lastObservedHp = currentHp;
            return state.stableMaxHp;
        }

        if (now - state.lowerCandidateSinceMs >= LOWER_MAX_PROMOTION_DELAY_MS) {
            state.stableMaxHp = currentHp;
            state.higherCandidateMaxHp = currentHp;
            state.higherCandidateSinceMs = now;
        }

        state.lastObservedHp = currentHp;
        return state.stableMaxHp;
    }

    private static final class HealthMaxState {
        private int stableMaxHp;
        private int higherCandidateMaxHp;
        private long higherCandidateSinceMs;
        private int lowerCandidateMaxHp;
        private long lowerCandidateSinceMs;
        private int lastObservedHp;

        private HealthMaxState(
                int stableMaxHp,
                int higherCandidateMaxHp,
                long higherCandidateSinceMs,
                int lowerCandidateMaxHp,
                long lowerCandidateSinceMs,
                int lastObservedHp) {
            this.stableMaxHp = stableMaxHp;
            this.higherCandidateMaxHp = higherCandidateMaxHp;
            this.higherCandidateSinceMs = higherCandidateSinceMs;
            this.lowerCandidateMaxHp = lowerCandidateMaxHp;
            this.lowerCandidateSinceMs = lowerCandidateSinceMs;
            this.lastObservedHp = lastObservedHp;
        }
    }

    private static Map<String, UUID> visiblePlayerUuidsByUsername() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return Map.of();
        }

        Map<String, UUID> result = new HashMap<>();
        for (AbstractClientPlayer player : mc.level.players()) {
            rememberPlayerName(result, player.getName(), player.getUUID());
            rememberPlayerName(result, Component.literal(player.getGameProfile().name()), player.getUUID());
            rememberPlayerName(result, Component.literal(player.getScoreboardName()), player.getUUID());
            rememberPlayerName(result, player.getCustomName(), player.getUUID());
        }

        return result;
    }

    private static void rememberPlayerName(Map<String, UUID> result, Component name, UUID uuid) {
        if (name == null || uuid == null) {
            return;
        }

        String normalized = normalizeName(name.getString());
        if (!normalized.isBlank()) {
            result.put(normalized, uuid);
        }
    }

    private static UUID resolveVisiblePlayerUuid(Map<String, UUID> visiblePlayerUuids, String username) {
        String key = normalizeName(username);
        if (key.isBlank()) {
            return null;
        }

        UUID exactMatch = visiblePlayerUuids.get(key);
        if (exactMatch != null) {
            return exactMatch;
        }

        UUID prefixMatch = null;
        for (Map.Entry<String, UUID> entry : visiblePlayerUuids.entrySet()) {
            if (!entry.getKey().startsWith(key)) {
                continue;
            }
            if (prefixMatch != null && !prefixMatch.equals(entry.getValue())) {
                return null;
            }
            prefixMatch = entry.getValue();
        }

        return prefixMatch;
    }

    private static String normalizeName(String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
