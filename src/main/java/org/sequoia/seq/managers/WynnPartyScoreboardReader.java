package org.sequoia.seq.managers;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;

import org.sequoia.seq.utils.PacketTextNormalizer;

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
     * Group 1 = HP number
     * Group 2 = player name
     * Group 3 = level
     */
    private static final Pattern PARTY_LINE =
            Pattern.compile("^[-\\s]*\\D*(\\d+)\\D+(.+?)\\s+\\[(\\d+)]$");

    private WynnPartyScoreboardReader() {}

    public record PartyHealth(String name, int hp, int level) {}

    public static List<String> readSidebarLines() {
        Minecraft mc = Minecraft.getInstance();

        // No world loaded, so there is no scoreboard to read.
        if (mc.level == null) {
            return List.of();
        }

        // This is the client-side scoreboard sent by the server.
        Scoreboard scoreboard = mc.level.getScoreboard();

        // Wynncraft displays the visible sidebar using the SIDEBAR display slot.
        Objective sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);

        // If the sidebar is hidden or not loaded yet, there is nothing to parse.
        if (sidebar == null) {
            return List.of();
        }

        // Get all score rows shown for this sidebar objective.
        List<PlayerScoreEntry> entries = new ArrayList<>(scoreboard.listPlayerScores(sidebar));

        /*
         * Scoreboards are ordered by score value.
         * Higher scores usually appear higher on the sidebar.
         */
        entries.sort(Comparator.comparingInt(PlayerScoreEntry::value).reversed());

        List<String> lines = new ArrayList<>();
        for (PlayerScoreEntry entry : entries) {
            /*
             * entry.display() is the formatted text the server wants shown.
             * If it is null, fall back to entry.owner().
             */
            Component display = entry.display();
            String rawLine = display != null ? display.getString() : entry.owner();

            /*
             * Wynncraft scoreboard text can contain formatting/private-use symbols.
             * This helper cleans it into easier text for parsing.
             */
            lines.add(PacketTextNormalizer.normalizeForParsing(rawLine));
        }

        return lines;
    }

    public static List<PartyHealth> readPartyHealth() {
        List<PartyHealth> partyHealth = new ArrayList<>();

        /*
         * We only want lines inside:
         *
         * Party:
         * - [hp] name [level]
         * - [hp] name [level]
         *
         * Raid:
         *
         * So this flag becomes true after "Party:" and false after "Raid:".
         */
        boolean inPartySection = false;

        for (String line : readSidebarLines()) {
            // Start reading party member lines after the Party header.
            if (line.startsWith("Party:")) {
                inPartySection = true;
                continue;
            }

            // Stop reading party member lines once the next scoreboard section starts.
            if (line.startsWith("Raid:")) {
                inPartySection = false;
                continue;
            }

            // Ignore normal scoreboard lines until we are inside the Party section.
            if (!inPartySection) {
                continue;
            }

            // Try to parse a party member line.
            Matcher matcher = PARTY_LINE.matcher(line);

            // If the line is not a party HP line, ignore it.
            if (!matcher.matches()) {
                continue;
            }

            int hp = Integer.parseInt(matcher.group(1));
            String name = matcher.group(2).trim();
            int level = Integer.parseInt(matcher.group(3));

            partyHealth.add(new PartyHealth(name, hp, level));
        }

        return partyHealth;
    }

    public static String extractRedText(Component component) {
        if (component == null) {
            return "";
        }

        /*
         * This gets Minecraft's normal RED text color.
         * The HP numbers in your screenshot are red.
         */
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
