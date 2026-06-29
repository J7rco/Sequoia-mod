package org.sequoia.seq.managers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;

public final class WynnPartyScoreboardReader {
    /*
     * Matches lines like:
     * - [9980] enter hig [106]
     * - [[12526]] i love [120]
     * - [[||12526||]] i love [120]
     *
     * Wynncraft can leave custom formatting/separator characters around the HP
     * number, so the HP section intentionally allows non-digit noise around it.
     *
     * The parser below intentionally does not use one full-line regex because
     * Wynncraft can inject custom separator glyphs around the HP number.
     */
    private static final Pattern FIRST_NUMBER = Pattern.compile("(\\d+)");
    private static final Pattern BRACKETED_NUMBER = Pattern.compile("\\[(\\d+)]");

    private WynnPartyScoreboardReader() {}

    public record PartyHealth(String nickname, String username, int hp, int level, boolean online, boolean alive) {}

    private record SidebarLine(String text, PacketNameResolver resolver) {}

    public static List<String> readSidebarLines() {
        List<String> lines = new ArrayList<>();
        for (SidebarLine line : readSidebarLineComponents()) {
            lines.add(line.text());
        }

        return lines;
    }

    private static List<SidebarLine> readSidebarLineComponents() {
        Minecraft mc = Minecraft.getInstance();

        if (mc.level == null) {
            return List.of();
        }

        Scoreboard scoreboard = mc.level.getScoreboard();
        Objective sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);

        if (sidebar == null) {
            return List.of();
        }

        List<PlayerScoreEntry> entries = new ArrayList<>(scoreboard.listPlayerScores(sidebar));
        entries.sort(Comparator.comparingInt(PlayerScoreEntry::value).reversed());

        List<SidebarLine> lines = new ArrayList<>();
        for (PlayerScoreEntry entry : entries) {
            Component display = entry.display();
            Component lineComponent = display != null ? display : Component.literal(entry.owner());
            PacketNameResolver resolver = PacketNameResolver.from(lineComponent);
            lines.add(new SidebarLine(resolver.text(), resolver));
        }

        return lines;
    }

    public static List<PartyHealth> readPartyHealth() {
        List<PartyHealth> partyHealth = new ArrayList<>();
        boolean inPartySection = false;

        for (SidebarLine sidebarLine : readSidebarLineComponents()) {
            String line = sidebarLine.text();
            String trimmedLine = line.trim();

            if (isHeaderLine(trimmedLine)) {
                inPartySection = isPartyHeaderLine(trimmedLine);
                continue;
            }

            int trimOffset = line.indexOf(trimmedLine);
            ParsedPartyLine parsed = parsePartyLine(trimmedLine, inPartySection);
            if (parsed == null) {
                continue;
            }

            boolean alive = parsed.online()
                    && !sidebarLine.resolver().hasStrikethrough(
                            trimOffset + parsed.nicknameStart(),
                            trimOffset + parsed.nicknameEnd());
            boolean nicknameStyle = sidebarLine.resolver().hasItalic(
                    trimOffset + parsed.nicknameStart(),
                    trimOffset + parsed.nicknameEnd());

            String username = sidebarLine.resolver().resolveMetadataUsername(
                    trimOffset + parsed.nicknameStart(),
                    trimOffset + parsed.nicknameEnd());
            if (username != null) {
                org.sequoia.seq.client.SeqClient.LOGGER.debug(
                        "[WynnPartyScoreboard] Metadata resolved nickname='{}' username='{}' line='{}'",
                        parsed.nickname(),
                        username,
                        line);
                NicknameResolverCache.remember(parsed.nickname(), username);
            } else {
                org.sequoia.seq.client.SeqClient.LOGGER.debug(
                        "[WynnPartyScoreboard] Metadata missing nickname='{}' line='{}'; trying nickname cache",
                        parsed.nickname(),
                        line);
                username = NicknameResolverCache.resolveUsername(parsed.nickname());
                if (username == null && !nicknameStyle) {
                    username = resolveVisiblePlayerUsername(parsed.nickname());
                    if (username != null) {
                        org.sequoia.seq.client.SeqClient.LOGGER.debug(
                                "[WynnPartyScoreboard] Visible player resolved non-nick name='{}' username='{}' line='{}'",
                                parsed.nickname(),
                                username,
                                line);
                        NicknameResolverCache.remember(parsed.nickname(), username);
                    } else {
                        username = parsed.nickname();
                    }
                }
            }

            if (username != null && !nicknameStyle) {
                String fullVisibleUsername = resolveVisiblePlayerUsername(username);
                if (fullVisibleUsername == null) {
                    fullVisibleUsername = resolveVisiblePlayerUsername(parsed.nickname());
                }
                if (fullVisibleUsername != null && !fullVisibleUsername.equals(username)) {
                    org.sequoia.seq.client.SeqClient.LOGGER.debug(
                            "[WynnPartyScoreboard] Canonicalized non-nick name='{}' username='{}' fullUsername='{}' line='{}'",
                            parsed.nickname(),
                            username,
                            fullVisibleUsername,
                            line);
                    username = fullVisibleUsername;
                    NicknameResolverCache.remember(parsed.nickname(), username);
                }
            }

            org.sequoia.seq.client.SeqClient.LOGGER.debug(
                    "[WynnPartyScoreboard] Parsed nickname='{}' username='{}' hp={} level={} online={} alive={} italic={} line='{}'",
                    parsed.nickname(),
                    username,
                    parsed.hp(),
                    parsed.level(),
                    parsed.online(),
                    alive,
                    nicknameStyle,
                    line);

            partyHealth.add(new PartyHealth(
                    parsed.nickname(),
                    username,
                    parsed.hp(),
                    parsed.level(),
                    parsed.online(),
                    alive));
        }

        return partyHealth;
    }

    private static boolean isHeaderLine(String line) {
        return line.contains("Party:") || line.contains("Raid:");
    }

    private static boolean isPartyHeaderLine(String line) {
        return line.contains("Party:");
    }

    private static ParsedPartyLine parsePartyLine(String line, boolean allowOfflineLine) {
        if (line == null || line.isBlank()) {
            return null;
        }

        ParsedPartyLine onlineLine = parseOnlinePartyLine(line);
        if (onlineLine != null) {
            return onlineLine;
        }

        return allowOfflineLine ? parseOfflinePartyLine(line) : null;
    }

    private static ParsedPartyLine parseOnlinePartyLine(String line) {
        Matcher hpMatcher = FIRST_NUMBER.matcher(line);
        if (!hpMatcher.find()) {
            return null;
        }

        BracketedNumber level = lastBracketedNumber(line);
        if (level == null) {
            return null;
        }

        int nicknameStart = hpMatcher.end();
        while (nicknameStart < level.start() && !isNicknameCharacter(line.charAt(nicknameStart))) {
            nicknameStart++;
        }

        int nicknameEnd = level.start();
        while (nicknameEnd > nicknameStart && !isNicknameCharacter(line.charAt(nicknameEnd - 1))) {
            nicknameEnd--;
        }

        if (nicknameStart >= nicknameEnd) {
            return null;
        }

        String nickname = line.substring(nicknameStart, nicknameEnd).trim();
        if (nickname.isBlank()) {
            return null;
        }

        return new ParsedPartyLine(
                Integer.parseInt(hpMatcher.group(1)),
                nickname,
                nicknameStart,
                nicknameEnd,
                level.value(),
                true);
    }

    private static ParsedPartyLine parseOfflinePartyLine(String line) {
        if (!line.startsWith("-")) {
            return null;
        }

        if (lastBracketedNumber(line) != null) {
            return null;
        }

        int nicknameStart = 1;
        while (nicknameStart < line.length() && !isNicknameCharacter(line.charAt(nicknameStart))) {
            nicknameStart++;
        }

        int nicknameEnd = line.length();
        while (nicknameEnd > nicknameStart && !isNicknameCharacter(line.charAt(nicknameEnd - 1))) {
            nicknameEnd--;
        }

        if (nicknameStart >= nicknameEnd) {
            return null;
        }

        String nickname = line.substring(nicknameStart, nicknameEnd).trim();
        if (nickname.isBlank()) {
            return null;
        }

        return new ParsedPartyLine(0, nickname, nicknameStart, nicknameEnd, 0, false);
    }

    private static BracketedNumber lastBracketedNumber(String line) {
        Matcher matcher = BRACKETED_NUMBER.matcher(line);
        BracketedNumber lastMatch = null;
        while (matcher.find()) {
            lastMatch = new BracketedNumber(Integer.parseInt(matcher.group(1)), matcher.start(), matcher.end());
        }
        return lastMatch;
    }

    private static boolean isNicknameCharacter(char value) {
        return Character.isLetterOrDigit(value) || value == '_' || Character.isWhitespace(value);
    }

    private static String resolveVisiblePlayerUsername(String nickname) {
        String key = nickname == null ? "" : nickname.trim().toLowerCase(Locale.ROOT);
        if (key.length() < 3) {
            return null;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }

        String uniquePrefixMatch = null;
        for (AbstractClientPlayer player : mc.level.players()) {
            String username = player.getName().getString();
            if (username.isBlank()) {
                continue;
            }

            String usernameKey = username.toLowerCase(Locale.ROOT);
            if (usernameKey.equals(key)) {
                return username;
            }

            if (usernameKey.startsWith(key)) {
                if (uniquePrefixMatch != null && !uniquePrefixMatch.equalsIgnoreCase(username)) {
                    return null;
                }
                uniquePrefixMatch = username;
            }
        }

        return uniquePrefixMatch;
    }

    private record ParsedPartyLine(
            int hp,
            String nickname,
            int nicknameStart,
            int nicknameEnd,
            int level,
            boolean online) {}

    private record BracketedNumber(int value, int start, int end) {}

    public static String extractRedText(Component component) {
        if (component == null) {
            return "";
        }

        TextColor red = TextColor.fromLegacyFormat(ChatFormatting.RED);

        StringBuilder redText = new StringBuilder();

        /*
         * Components are made of smaller styled pieces.
         * Example:
         *
         * "-" is gray
         * "[9980]" is red
         * "enter hig" is white
         * "[106]" is gray
         *
         * toFlatList() lets us inspect each styled piece.
         */
        for (Component fragment : component.toFlatList()) {
            TextColor fragmentColor = fragment.getStyle().getColor();

            /*
             * Use .equals() for object comparison.
             *
             * This is safer than:
             * fragmentColor.equals(red)
             *
             * because fragmentColor can be null.
             */
            if (red.equals(fragmentColor)) {
                redText.append(fragment.getString());
            }
        }

        return redText.toString();
    }
}
