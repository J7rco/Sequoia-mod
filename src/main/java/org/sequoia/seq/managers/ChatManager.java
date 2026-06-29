package org.sequoia.seq.managers;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import org.sequoia.seq.accessors.NotificationAccessor;
import org.sequoia.seq.client.SeqClient;
import org.sequoia.seq.events.DiscordChatEvent;
import org.sequoia.seq.integrations.WynntilsGuildRankAccess;
import org.sequoia.seq.integrations.WynntilsItemPreviewAccess;
import org.sequoia.seq.model.ChatItemPreview;
import org.sequoia.seq.network.ConnectionManager;
import org.sequoia.seq.utils.PacketTextNormalizer;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.time.Duration;
import java.time.Instant;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.sequoia.seq.client.SeqClient.mc;

/**
 * Bridges Wynncraft guild chat ↔ Discord via the Sequoia backend.
 * <p>
 * Outgoing: intercepts Wynncraft guild chat, resolves nicknames to real
 * usernames
 * via component insertion tags, and sends to backend.
 * Incoming: listens for Discord chat WS messages, displays in MC chat.
 */
public class ChatManager {

    /**
     * Aqua text color (0x55FFFF / §b) used by Wynncraft for guild chat messages.
     * Adapted from Wynntils' RecipientType.GUILD foreground pattern
     * {@code ^§b(...).*$}.
     * Other message types use different colors: DMs use §#ddcc99ff, party uses §e,
     * shout uses §#bd45ffff, territory/battle info uses §c, etc.
     */
    private static final int GUILD_CHAT_COLOR = 0x55FFFF;
    private static final String BACKEND_GUILD_NAME = "Sequoia";
    // Nicknames may contain spaces (e.g. "Emanant Force"), so allow spaces in the
    // display-name capture group. DOTALL so the message group captures across \n.
    // Packet-level normalization strips the icon/banner glyph spam before matching.
    private static final Pattern CHAT_PATTERN = Pattern.compile(
            "^(?:<\\d+>\\s*)?([a-zA-Z0-9_][a-zA-Z0-9_ ]*[a-zA-Z0-9_]|[a-zA-Z0-9_]{3,16})\\s*:\\s*(.*)$",
            Pattern.DOTALL);
    private static final Pattern HOVER_REAL_NAME_PATTERN = Pattern.compile(
            // Wynntils format: "<nick>'s real name is <username>"
            // Also accepts nicknames ending in 's' where Wynncraft uses "<nick>' real name
            // is <username>"
            // Legacy format: "Real Username: <username>"
            "(?:'(?:s)? real name is\\s+|Real Username:\\s*)([a-zA-Z0-9_]{3,16})",
            Pattern.CASE_INSENSITIVE);
    private static final Duration OUTGOING_DEDUPE_WINDOW = Duration.ofMillis(750);
    private static volatile String lastOutgoingKey;
    private static volatile Instant lastOutgoingAt = Instant.EPOCH;

    private static final Pattern WYNNCRAFT_WELCOME_PATTERN = Pattern.compile("Welcome to Wynncraft!",
            Pattern.CASE_INSENSITIVE);

    private static boolean firstConnect = true;

    public ChatManager() {
        registerIncomingHook();
    }

    // ── Outgoing: Wynncraft guild → backend → Discord ──

    /**
     * Called from {@link org.sequoia.seq.mixins.ClientPacketListenerMixin} at the
     * packet level, before Wynntils or Fabric's message API can cancel/reformat
     * the message. This ensures multiline guild messages are never missed.
     */
    public static void onSystemChat(Component message) {
        // Bumliotech goon parser to auto goon.
        if (SeqClient.getAutoConnectSetting().getValue() && isWynncraftWelcomeMessage(message) && mc.player != null
                && !ConnectionManager.getInstance().isOpen() && firstConnect) {
            firstConnect = !firstConnect;
            mc.execute(() -> mc.player.connection.sendCommand("seq connect"));
        }

        observeNicknameMapping(message);

        // Guild chat uses aqua color (§b / 0x55FFFF) per Wynntils' RecipientType.GUILD.
        // This cleanly rejects DMs, party, shout, territory, and other message types
        // that share the \uDAFF\uDFFC icon prefix but use different colors.
        if (!hasLeadingGuildChatColor(message))
            return;

        if (!ConnectionManager.isConnected())
            return;

        if (!shouldRelayForLocalGuild())
            return;

        ParsedMessage parsed = parseGuildMessage(message);
        if (parsed == null)
            return;

        if (isDuplicateOutgoing(parsed.username(), parsed.message())) {
            SeqClient.LOGGER.debug(
                    "[GuildChat] Duplicate outgoing guild chat ignored username='{}' content='{}'",
                    parsed.username(),
                    parsed.message());
            return;
        }

            SeqClient.LOGGER.info(
                    "[GuildChat] Forwarding parsed guild chat username='{}' nickname='{}' content='{}' avatar='{}'",
                parsed.username(),
                parsed.nickname(),
                parsed.message(),
                parsed.avatarUrl());

        ConnectionManager.getInstance().sendGuildChat(
                parsed.username(),
                parsed.nickname(),
                parsed.message(),
                parsed.avatarUrl(),
                parsed.itemPreviews());
    }

    private static void observeNicknameMapping(Component message) {
        String cleaned = PacketTextNormalizer.normalizeForParsing(message == null ? null : message.getString());
        Matcher matcher = CHAT_PATTERN.matcher(cleaned);
        if (!matcher.find()) {
            return;
        }

        String displayedName = matcher.group(1).trim();
        String realUsername = findRealUsername(message, displayedName);
        if (realUsername == null || !realUsername.matches("[a-zA-Z0-9_]{3,16}")) {
            SeqClient.LOGGER.info(
                    "[NicknameResolver] Chat observe no real username displayed='{}' real='{}' cleaned='{}'",
                    displayedName,
                    realUsername,
                    cleaned);
            return;
        }

        SeqClient.LOGGER.info(
                "[NicknameResolver] Chat observe displayed='{}' real='{}' cleaned='{}'",
                displayedName,
                realUsername,
                cleaned);
        NicknameResolverCache.remember(displayedName, realUsername);
    }

    private static boolean shouldRelayForLocalGuild() {
        WynntilsGuildRankAccess.GuildMembership membership =
                WynntilsGuildRankAccess.guildMembership(BACKEND_GUILD_NAME);
        boolean shouldRelay = shouldRelayForGuild(membership);
        if (!shouldRelay) {
            SeqClient.LOGGER.debug(
                    "[GuildChat] Dropping guild chat relay: local Wynntils guild='{}' expected='{}'",
                    membership == null ? null : membership.currentGuildName(),
                    BACKEND_GUILD_NAME);
        }
        return shouldRelay;
    }

    static boolean shouldRelayForGuild(WynntilsGuildRankAccess.GuildMembership membership) {
        return membership != null && membership.available() && membership.inExpectedGuild();
    }

    static boolean hasLeadingGuildChatColor(Component message) {
        if (message == null) {
            return false;
        }

        var rootColor = message.getStyle().getColor();
        if (rootColor != null) {
            return rootColor.getValue() == GUILD_CHAT_COLOR;
        }

        Optional<Boolean> leadingColorIsGuild = message.visit((style, text) -> {
            if (text == null || text.isBlank()) {
                return Optional.empty();
            }
            var color = style.getColor();
            return Optional.of(color != null && color.getValue() == GUILD_CHAT_COLOR);
        }, Style.EMPTY);
        return leadingColorIsGuild.orElse(false);
    }

    /**
     * Extracts real username and message content from Wynncraft guild chat.
     * 
     * <p>
     * Wynncraft sends nicknames as the visible text, but puts the real username
     * in the Style's insertion field (used for shift-click @mentions). We look for
     * the component with a valid username insertion to identify the speaker.
     */
    static ParsedMessage parseGuildMessage(Component message) {
        String cleaned = PacketTextNormalizer.normalizeForParsing(message == null ? null : message.getString());
        Matcher matcher = CHAT_PATTERN.matcher(cleaned);

        if (!matcher.find())
            return null;

        String displayedName = matcher.group(1).trim();
        String content = matcher.group(2).trim();
        WynntilsItemPreviewAccess.Result itemPreviewResult =
                WynntilsItemPreviewAccess.extract(extractRawContent(message.getString()));
        String previewCleanedContent = PacketTextNormalizer.normalizeForParsing(itemPreviewResult.message());
        if (!previewCleanedContent.isBlank()) {
            content = previewCleanedContent;
        }

        if (content.isEmpty())
            return null;

        // Search the component tree for the real username. Prefer candidates that
        // differ from the displayed nickname (e.g. nicked/decorated names).
        String realUsername = findRealUsername(message, displayedName);
        String avatarUsername = resolveAvatarUsername(displayedName, realUsername);
        if (avatarUsername == null) {
            SeqClient.LOGGER.warn(
                    "[GuildChat] Dropping guild chat: no avatar-safe username displayed='{}' real='{}'",
                    displayedName,
                    realUsername);
            return null;
        }

        if (!avatarUsername.equals(displayedName) && realUsername == null) {
            SeqClient.LOGGER.debug(
                    "[GuildChat] Using normalized fallback username='{}' from displayed='{}'",
                    avatarUsername,
                    displayedName);
        }

        String avatarUrl = "https://mc-heads.net/avatar/"
                + URLEncoder.encode(avatarUsername, StandardCharsets.UTF_8).replace("+", "%20")
                + "/128";
        String nickname = deriveNickname(displayedName, avatarUsername);
        NicknameResolverCache.remember(displayedName, avatarUsername);
        return new ParsedMessage(avatarUsername, nickname, content, avatarUrl, itemPreviewResult.previews());
    }

    private static String extractRawContent(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return "";
        }
        int colonIndex = rawText.indexOf(':');
        if (colonIndex < 0 || colonIndex == rawText.length() - 1) {
            return "";
        }
        return rawText.substring(colonIndex + 1).trim();
    }

    static boolean isWynncraftWelcomeMessage(Component message) {
        String normalized = PacketTextNormalizer.normalizeForParsing(message == null ? null : message.getString());
        if (normalized.isEmpty()) {
            return false;
        }

        Matcher welcomeMatcher = WYNNCRAFT_WELCOME_PATTERN.matcher(normalized);
        return welcomeMatcher.find() && normalized.contains("play.wynncraft.com");
    }

    private static String deriveNickname(String displayedName, String actualUsername) {
        if (displayedName == null) {
            return null;
        }
        String trimmed = displayedName.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (actualUsername != null && trimmed.equalsIgnoreCase(actualUsername)) {
            return null;
        }
        return trimmed;
    }

    private static String resolveAvatarUsername(String displayedName, String realUsername) {
        if (realUsername != null && realUsername.matches("[a-zA-Z0-9_]{3,16}")) {
            return realUsername;
        }
        if (displayedName != null && displayedName.matches("[a-zA-Z0-9_]{3,16}")) {
            return displayedName;
        }

        String normalized = displayedName == null ? "" : displayedName.replaceAll("[^a-zA-Z0-9_]", "");
        if (normalized.matches("[a-zA-Z0-9_]{3,16}")) {
            return normalized;
        }

        return null;
    }

    private static boolean isDuplicateOutgoing(String username, String message) {
        String key = username + "\u0000" + message;
        Instant now = Instant.now();

        synchronized (ChatManager.class) {
            if (key.equals(lastOutgoingKey)
                    && Duration.between(lastOutgoingAt, now).compareTo(OUTGOING_DEDUPE_WINDOW) < 0) {
                return true;
            }
            lastOutgoingKey = key;
            lastOutgoingAt = now;
            return false;
        }
    }

    private static String findRealUsername(Component component, String displayedName) {
        List<String> usernames = extractRealUsernames(component);
        if (usernames.isEmpty()) {
            return null;
        }

        if (displayedName == null || displayedName.isBlank()) {
            return usernames.get(0);
        }

        String displayedTrimmed = displayedName.trim();
        String displayedLower = displayedTrimmed.toLowerCase();

        String bestPrefixExpansion = null;
        for (String candidate : usernames) {
            if (candidate == null) {
                continue;
            }
            String candidateLower = candidate.toLowerCase();
            if (candidateLower.equals(displayedLower)) {
                continue;
            }
            if (candidateLower.startsWith(displayedLower)
                    && candidate.length() > displayedTrimmed.length()
                    && (bestPrefixExpansion == null || candidate.length() > bestPrefixExpansion.length())) {
                bestPrefixExpansion = candidate;
            }
        }
        if (bestPrefixExpansion != null) {
            return bestPrefixExpansion;
        }

        for (String candidate : usernames) {
            if (candidate != null && !candidate.equalsIgnoreCase(displayedTrimmed)) {
                return candidate;
            }
        }

        return usernames.get(0);
    }

    static String resolvePacketUsername(Component component, String displayedName) {
        String realUsername = findRealUsername(component, displayedName);
        if (realUsername != null && realUsername.matches("[a-zA-Z0-9_]{3,16}")) {
            return realUsername;
        }
        if (displayedName != null && displayedName.matches("[a-zA-Z0-9_]{3,16}")) {
            return displayedName;
        }
        return null;
    }

    public static List<String> extractRealUsernames(Component component) {
        if (component == null) {
            return List.of();
        }

        Set<String> usernames = new LinkedHashSet<>();
        collectHoverRealUsernames(component, usernames);
        collectInsertionUsernames(component, usernames);
        return List.copyOf(new ArrayList<>(usernames));
    }

    public static List<String> extractHoverRealUsernames(Component component) {
        if (component == null) {
            return List.of();
        }

        Set<String> usernames = new LinkedHashSet<>();
        collectHoverRealUsernames(component, usernames);
        return List.copyOf(new ArrayList<>(usernames));
    }

    public static List<String> extractInsertionUsernames(Component component) {
        if (component == null) {
            return List.of();
        }

        Set<String> usernames = new LinkedHashSet<>();
        collectInsertionUsernames(component, usernames);
        return List.copyOf(new ArrayList<>(usernames));
    }

    private static void collectHoverRealUsernames(Component component, Set<String> usernames) {
        if (component == null) {
            return;
        }

        String hoverUsername = extractHoverRealUsername(component.getStyle());
        if (hoverUsername != null) {
            usernames.add(hoverUsername);
        }

        for (Component sibling : component.getSiblings()) {
            collectHoverRealUsernames(sibling, usernames);
        }
    }

    private static void collectInsertionUsernames(Component component, Set<String> usernames) {
        if (component == null) {
            return;
        }

        String insertionUsername = extractInsertionUsername(component.getStyle());
        if (insertionUsername != null) {
            usernames.add(insertionUsername);
        }

        for (Component sibling : component.getSiblings()) {
            collectInsertionUsernames(sibling, usernames);
        }
    }

    static String extractHoverRealUsername(Style style) {
        if (style == null) {
            return null;
        }

        HoverEvent hoverEvent = style.getHoverEvent();
        if (!(hoverEvent instanceof HoverEvent.ShowText showTextEvent)) {
            return null;
        }

        Component hoverComponent = showTextEvent.value();
        if (hoverComponent == null) {
            return null;
        }

        String hoverText = hoverComponent.getString();
        Matcher matcher = HOVER_REAL_NAME_PATTERN.matcher(hoverText);
        return matcher.find() ? matcher.group(1) : null;
    }

    static String extractInsertionUsername(Style style) {
        if (style == null) {
            return null;
        }

        String insertion = style.getInsertion();
        if (insertion == null || !insertion.matches("[a-zA-Z0-9_]{3,16}")) {
            return null;
        }
        return insertion;
    }

    // ── Incoming: Discord → backend → WS → MC chat ──

    private void registerIncomingHook() {
        ConnectionManager.onDiscordChat(msg -> {
            if (!SeqClient.getShowDiscordChatSetting().getValue())
                return;

            mc.execute(() -> {
                if (mc.player != null) {
                    MutableComponent formatted = NotificationAccessor.prefixComponent()
                            .append(Component.literal(msg.username()).withStyle(ChatFormatting.WHITE))
                            .append(Component.literal(": ").withStyle(ChatFormatting.GRAY))
                            .append(Component.literal(msg.message()).withStyle(ChatFormatting.WHITE));
                    mc.player.displayClientMessage(formatted, false);
                }

                if (SeqClient.getEventBus() != null) {
                    SeqClient.getEventBus().dispatch(new DiscordChatEvent(msg.username(), msg.message()));
                }
            });
        });
    }

    record ParsedMessage(
            String username, String nickname, String message, String avatarUrl, List<ChatItemPreview> itemPreviews) {
    }
}
