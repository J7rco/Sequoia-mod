package org.sequoia.seq.managers;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.sequoia.seq.accessors.NotificationAccessor;
import org.sequoia.seq.client.SeqClient;
import org.sequoia.seq.model.Listing;
import org.sequoia.seq.network.ConnectionManager;
import org.sequoia.seq.utils.PacketTextNormalizer;

public class WynnPartySyncManager {

    private static final Pattern PARTY_CREATED_PATTERN =
            Pattern.compile("^You have successfully created a party\\.$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARTY_JOINED_PATTERN = Pattern.compile(
            "^(.+?) has joined your party, say hello!$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARTY_LEFT_PATTERN =
            Pattern.compile("^(.+?) has left the party!$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARTY_KICKED_PATTERN =
            Pattern.compile("^(.+?) has been kicked from the party!$", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCAL_PARTY_LEFT_PATTERN =
            Pattern.compile("^You have left the party\\.?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARTY_LEADER_PATTERN =
            Pattern.compile("^(.+?) is now the Party Leader!$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARTY_DISBANDED_PATTERN =
            Pattern.compile("^Your party has been disbanded\\.?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARTY_MEMBERS_PATTERN =
            Pattern.compile("^Party members:\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern MC_USERNAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{3,16}$");
    private static final Duration DUPLICATE_WINDOW = Duration.ofMillis(750);
    private static final Duration HEARTBEAT_RESEND_INTERVAL = Duration.ofSeconds(60);
    private static final String OPEN_CREATE_UI_COMMAND = "/seq party create-ui";

    private final ObservedWynnPartyState observedState = new ObservedWynnPartyState();
    private String lastSentSnapshotKey;
    private Instant lastSentSnapshotAt = Instant.EPOCH;
    private String lastEventKey;
    private Instant lastEventAt = Instant.EPOCH;

    public void onSystemChat(Component message) {
        String normalized = PacketTextNormalizer.normalizeForParsing(message == null ? null : message.getString());
        if (normalized.isBlank()) {
            return;
        }
        if (isDuplicateEvent(normalized)) {
            SeqClient.LOGGER.debug("[WynnPartySync] Ignored duplicate event raw='{}'", normalized);
            return;
        }

        if (PARTY_CREATED_PATTERN.matcher(normalized).matches()) {
            SeqClient.LOGGER.info("[WynnPartySync] Detected Wynn party creation");
            handlePartyCreated();
            return;
        }

        Matcher joinedMatcher = PARTY_JOINED_PATTERN.matcher(normalized);
        if (joinedMatcher.matches()) {
            String observedUsername = resolveObservedUsername(message, joinedMatcher.group(1));
            SeqClient.LOGGER.info(
                    "[WynnPartySync] Detected Wynn party join displayed='{}' resolved='{}'",
                    joinedMatcher.group(1),
                    observedUsername);
            handleMemberJoined(observedUsername);
            return;
        }

        Matcher leftMatcher = PARTY_LEFT_PATTERN.matcher(normalized);
        if (leftMatcher.matches()) {
            String observedUsername = resolveObservedUsername(message, leftMatcher.group(1));
            SeqClient.LOGGER.info(
                    "[WynnPartySync] Detected Wynn party leave displayed='{}' resolved='{}'",
                    leftMatcher.group(1),
                    observedUsername);
            handleMemberLeft(observedUsername);
            return;
        }

        Matcher kickedMatcher = PARTY_KICKED_PATTERN.matcher(normalized);
        if (kickedMatcher.matches()) {
            String observedUsername = resolveObservedUsername(message, kickedMatcher.group(1));
            SeqClient.LOGGER.info(
                    "[WynnPartySync] Detected Wynn party kick displayed='{}' resolved='{}'",
                    kickedMatcher.group(1),
                    observedUsername);
            handleMemberKicked(observedUsername);
            return;
        }

        if (LOCAL_PARTY_LEFT_PATTERN.matcher(normalized).matches()) {
            SeqClient.LOGGER.info("[WynnPartySync] Detected local Wynn party leave");
            handleLocalPartyLeft();
            return;
        }

        Matcher leaderMatcher = PARTY_LEADER_PATTERN.matcher(normalized);
        if (leaderMatcher.matches()) {
            String observedUsername = resolveObservedUsername(message, leaderMatcher.group(1));
            SeqClient.LOGGER.info(
                    "[WynnPartySync] Detected Wynn party leader change displayed='{}' resolved='{}'",
                    leaderMatcher.group(1),
                    observedUsername);
            handleLeaderChanged(observedUsername);
            return;
        }

        Matcher membersMatcher = PARTY_MEMBERS_PATTERN.matcher(normalized);
        if (membersMatcher.matches()) {
            List<String> snapshotMembers = parseAuthoritativeMembersSnapshot(membersMatcher.group(1));
            if (!snapshotMembers.isEmpty()) {
                SeqClient.LOGGER.info(
                        "[WynnPartySync] Detected authoritative party members snapshot members={}",
                        snapshotMembers);
                handleAuthoritativeMembersSnapshot(snapshotMembers);
                return;
            }
            SeqClient.LOGGER.debug("[WynnPartySync] Ignoring invalid party members snapshot raw='{}'", normalized);
        }

        if (PARTY_DISBANDED_PATTERN.matcher(normalized).matches()) {
            SeqClient.LOGGER.info("[WynnPartySync] Detected Wynn party disband");
            handlePartyDisbanded();
        }
    }

    public void tick() {
        if (!ConnectionManager.isConnected()) {
            lastSentSnapshotKey = null;
            lastSentSnapshotAt = Instant.EPOCH;
            return;
        }
        if (SeqClient.getSyncWynnPartySetting() == null || !SeqClient.getSyncWynnPartySetting().getValue()) {
            return;
        }

        Listing currentListing =
                SeqClient.getPartyFinderManager() != null ? SeqClient.getPartyFinderManager().getCurrentListing() : null;
        if (currentListing == null) {
            SeqClient.LOGGER.debug(
                    "[WynnPartySync] Skipping snapshot send: no active Sequoia listing active={} leader={} members={}",
                    observedState.active,
                    observedState.leaderUsername,
                    observedState.memberUsernames);
            return;
        }
        if (!observedState.initialized) {
            SeqClient.LOGGER.debug(
                    "[WynnPartySync] Skipping snapshot send: observed party state is still unknown for listingId={}",
                    currentListing.id());
            return;
        }

        Instant now = Instant.now();
        String snapshotKey = buildSnapshotKey(currentListing.id());
        boolean heartbeatDue = Duration.between(lastSentSnapshotAt, now).compareTo(HEARTBEAT_RESEND_INTERVAL) >= 0;
        if (snapshotKey.equals(lastSentSnapshotKey) && !heartbeatDue) {
            return;
        }

        boolean sent = ConnectionManager.getInstance()
                .sendPartySyncSnapshot(
                        observedState.active,
                        observedState.leaderUsername,
                        List.copyOf(observedState.memberUsernames));
        if (sent) {
            SeqClient.LOGGER.info(
                    "[WynnPartySync] Sent snapshot listingId={} active={} leader={} members={}",
                    currentListing.id(),
                    observedState.active,
                    observedState.leaderUsername,
                    observedState.memberUsernames);
            lastSentSnapshotKey = snapshotKey;
            lastSentSnapshotAt = now;
        }
    }

    public void reset() {
        observedState.reset();
        lastSentSnapshotKey = null;
        lastSentSnapshotAt = Instant.EPOCH;
        lastEventKey = null;
        lastEventAt = Instant.EPOCH;
    }

    private void handlePartyCreated() {
        observedState.reset();
        observedState.initialized = true;
        observedState.active = true;
        String localUsername = getLocalUsername();
        if (localUsername != null) {
            observedState.memberUsernames.add(localUsername);
            observedState.leaderUsername = localUsername;
        }
        logObservedState("created");
        maybeShowCreatePrompt();
    }

    private void handleMemberJoined(String username) {
        if (!observedState.initialized) {
            SeqClient.LOGGER.debug("[WynnPartySync] Ignoring join event before party creation");
            return;
        }
        if (username == null) {
            SeqClient.LOGGER.warn("[WynnPartySync] Ignoring join event because username could not be resolved");
            return;
        }
        observedState.active = true;
        observedState.memberUsernames.add(username);
        logObservedState("join");
    }

    private void handleMemberLeft(String username) {
        if (!observedState.initialized) {
            SeqClient.LOGGER.debug("[WynnPartySync] Ignoring leave event before party creation");
            return;
        }
        if (username == null) {
            SeqClient.LOGGER.warn("[WynnPartySync] Ignoring leave event because username could not be resolved");
            return;
        }
        observedState.memberUsernames.removeIf(existing -> existing.equalsIgnoreCase(username));
        if (observedState.leaderUsername != null && observedState.leaderUsername.equalsIgnoreCase(username)) {
            observedState.leaderUsername = null;
        }
        if (observedState.memberUsernames.isEmpty()) {
            observedState.active = false;
        }
        sendExplicitRemoval(username, "left");
        logObservedState("leave");
    }

    private void handleMemberKicked(String username) {
        if (!observedState.initialized) {
            SeqClient.LOGGER.debug("[WynnPartySync] Ignoring kick event before party creation");
            return;
        }
        if (username == null) {
            SeqClient.LOGGER.warn("[WynnPartySync] Ignoring kick event because username could not be resolved");
            return;
        }
        observedState.memberUsernames.removeIf(existing -> existing.equalsIgnoreCase(username));
        if (observedState.leaderUsername != null && observedState.leaderUsername.equalsIgnoreCase(username)) {
            observedState.leaderUsername = null;
        }
        if (observedState.memberUsernames.isEmpty()) {
            observedState.active = false;
        }
        sendExplicitRemoval(username, "kicked");
        logObservedState("kick");
    }

    private void handleLeaderChanged(String username) {
        if (!observedState.initialized) {
            SeqClient.LOGGER.debug("[WynnPartySync] Ignoring leader event before party creation");
            return;
        }
        if (username == null) {
            SeqClient.LOGGER.warn("[WynnPartySync] Ignoring leader event because username could not be resolved");
            return;
        }
        observedState.active = true;
        observedState.memberUsernames.add(username);
        observedState.leaderUsername = username;
        logObservedState("leader");
    }

    private void handleAuthoritativeMembersSnapshot(List<String> usernames) {
        observedState.initialized = true;
        observedState.active = true;

        String preservedLeader = findMatchingUsername(usernames, observedState.leaderUsername);
        observedState.memberUsernames.clear();
        observedState.memberUsernames.addAll(usernames);
        observedState.leaderUsername = preservedLeader != null ? preservedLeader : usernames.get(0);
        logObservedState("members_snapshot");
    }

    private void handlePartyDisbanded() {
        if (!observedState.initialized) {
            SeqClient.LOGGER.debug("[WynnPartySync] Ignoring disband event before party creation");
            return;
        }
        observedState.initialized = true;
        observedState.active = false;
        observedState.leaderUsername = null;
        observedState.memberUsernames.clear();
        logObservedState("disbanded");
    }

    private void handleLocalPartyLeft() {
        handlePartyDisbanded();
    }

    private void maybeShowCreatePrompt() {
        if (SeqClient.getSyncWynnPartySetting() == null || !SeqClient.getSyncWynnPartySetting().getValue()) {
            return;
        }
        if (observedState.createPromptShown) {
            return;
        }
        if (SeqClient.getPartyFinderManager() != null
                && SeqClient.getPartyFinderManager().getCurrentListing() != null) {
            return;
        }

        observedState.createPromptShown = true;
        SeqClient.mc.execute(() -> {
            LocalPlayer player = SeqClient.mc.player;
            if (player == null) {
                return;
            }

            MutableComponent prompt = NotificationAccessor.prefixComponent()
                    .append(Component.literal("Create a Sequoia party for this Wynn party?")
                            .withStyle(ChatFormatting.GRAY));
            MutableComponent action = Component.empty().append(NotificationAccessor.wynnPill(
                    "yes",
                    ChatFormatting.GREEN,
                    ChatFormatting.WHITE,
                    new ClickEvent.RunCommand(OPEN_CREATE_UI_COMMAND)));

            player.displayClientMessage(prompt, false);
            player.displayClientMessage(action, false);
        });
    }

    private String buildSnapshotKey(long listingId) {
        String leader = observedState.leaderUsername == null ? "" : observedState.leaderUsername.toLowerCase(Locale.ROOT);
        List<String> members = new ArrayList<>();
        for (String username : observedState.memberUsernames) {
            members.add(username.toLowerCase(Locale.ROOT));
        }
        return listingId + "|" + observedState.active + "|" + leader + "|" + String.join(",", members);
    }

    private boolean isDuplicateEvent(String normalizedMessage) {
        Instant now = Instant.now();
        if (normalizedMessage.equals(lastEventKey)
                && Duration.between(lastEventAt, now).compareTo(DUPLICATE_WINDOW) < 0) {
            return true;
        }
        lastEventKey = normalizedMessage;
        lastEventAt = now;
        return false;
    }

    private String resolveObservedUsername(Component message, String displayedName) {
        String trimmed = displayedName == null ? null : displayedName.trim();
        if (trimmed == null || trimmed.isBlank()) {
            return null;
        }

        String resolved = ChatManager.resolvePacketUsername(message, trimmed);
        if (resolved != null && MC_USERNAME_PATTERN.matcher(resolved).matches()) {
            NicknameResolverCache.remember(trimmed, resolved);
            return resolved;
        }

        if (MC_USERNAME_PATTERN.matcher(trimmed).matches()) {
            NicknameResolverCache.remember(trimmed, trimmed);
            return trimmed;
        }
        SeqClient.LOGGER.warn(
                "[WynnPartySync] Could not resolve a valid username from displayed='{}'",
                displayedName);
        return null;
    }

    private List<String> parseAuthoritativeMembersSnapshot(String rawMembersTail) {
        if (rawMembersTail == null || rawMembersTail.isBlank()) {
            return List.of();
        }

        String membersTail = rawMembersTail;
        int inviteMarkerIndex = membersTail.toLowerCase(Locale.ROOT).indexOf("click to invite:");
        if (inviteMarkerIndex >= 0) {
            membersTail = membersTail.substring(0, inviteMarkerIndex);
        }
        membersTail = membersTail.trim();
        if (membersTail.isBlank()) {
            return List.of();
        }

        String[] tokens = membersTail.split("\\s*,\\s*(?:and\\s+)?|\\s+and\\s+");
        List<String> usernames = new ArrayList<>();
        for (String token : tokens) {
            String username = token == null ? "" : token.trim();
            if (MC_USERNAME_PATTERN.matcher(username).matches()) {
                usernames.add(username);
            }
        }
        return usernames;
    }

    private String findMatchingUsername(List<String> usernames, String expectedUsername) {
        if (expectedUsername == null || expectedUsername.isBlank()) {
            return null;
        }
        for (String username : usernames) {
            if (username.equalsIgnoreCase(expectedUsername)) {
                return username;
            }
        }
        return null;
    }

    private String getLocalUsername() {
        String localUsername = null;
        if (SeqClient.mc != null && SeqClient.mc.player != null && SeqClient.mc.player.getName() != null) {
            localUsername = SeqClient.mc.player.getName().getString();
        }
        if (localUsername == null || localUsername.isBlank()) {
            localUsername = SeqClient.getConfigManager() != null
                    ? SeqClient.getConfigManager().getMinecraftUsername()
                    : null;
        }
        if (localUsername == null || !MC_USERNAME_PATTERN.matcher(localUsername).matches()) {
            return null;
        }
        return localUsername;
    }

    private void logObservedState(String reason) {
        SeqClient.LOGGER.info(
                "[WynnPartySync] State after {} active={} leader={} members={}",
                reason,
                observedState.active,
                observedState.leaderUsername,
                observedState.memberUsernames);
    }

    private void sendExplicitRemoval(String username, String reason) {
        if (SeqClient.getSyncWynnPartySetting() == null || !SeqClient.getSyncWynnPartySetting().getValue()) {
            return;
        }
        Listing currentListing =
                SeqClient.getPartyFinderManager() != null ? SeqClient.getPartyFinderManager().getCurrentListing() : null;
        if (currentListing == null) {
            SeqClient.LOGGER.debug(
                    "[WynnPartySync] Skipping explicit removal send: no active Sequoia listing username={} reason={}",
                    username,
                    reason);
            return;
        }
        ConnectionManager.getInstance().sendPartySyncMemberRemoved(username, reason);
    }

    static final class ObservedWynnPartyState {
        private boolean initialized;
        private boolean active;
        private String leaderUsername;
        private final Set<String> memberUsernames = new LinkedHashSet<>();
        private boolean createPromptShown;

        private void reset() {
            initialized = false;
            active = false;
            leaderUsername = null;
            memberUsernames.clear();
            createPromptShown = false;
        }

        @Override
        public int hashCode() {
            return Objects.hash(initialized, active, leaderUsername, memberUsernames, createPromptShown);
        }
    }
}
