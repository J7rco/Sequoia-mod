package org.sequoia.seq.managers;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;

/**
 * Resolves Minecraft usernames from packet-level chat components while
 * preserving hover/insertion metadata after Wynncraft formatting cleanup.
 */
final class PacketNameResolver {
    private static final Pattern USERNAME_PATTERN = Pattern.compile("[A-Za-z0-9_]{3,16}");

    private final String text;
    private final List<MetaChar> characters;

    private PacketNameResolver(String text, List<MetaChar> characters) {
        this.text = text;
        this.characters = characters;
    }

    static PacketNameResolver from(Component message) {
        List<MetaChar> characters = new ArrayList<>();
        if (message != null) {
            for (Component fragment : message.toFlatList()) {
                appendNormalizedFragment(characters, fragment);
            }
        }
        return new PacketNameResolver(toText(characters), List.copyOf(characters));
    }

    String text() {
        return text;
    }

    String resolveUsername(String displayedName, int startInclusive, int endExclusive) {
        String trimmed = displayedName == null ? "" : displayedName.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        NameMetadata metadata = metadataForRange(startInclusive, endExclusive);
        if (metadata.firstHoverRealName() != null) {
            return metadata.firstHoverRealName();
        }

        if (metadata.firstInsertionName() != null) {
            return metadata.firstInsertionName();
        }

        String displayedUsername = validUsername(trimmed);
        if (displayedUsername != null) {
            return displayedUsername;
        }

        return null;
    }

    String resolveMetadataUsername(int startInclusive, int endExclusive) {
        NameMetadata metadata = metadataForRange(startInclusive, endExclusive);
        if (metadata.firstHoverRealName() != null) {
            return metadata.firstHoverRealName();
        }
        return metadata.firstInsertionName();
    }

    private NameMetadata metadataForRange(int startInclusive, int endExclusive) {
        LinkedHashSet<String> hoverRealNames = new LinkedHashSet<>();
        LinkedHashSet<String> insertionNames = new LinkedHashSet<>();

        int cappedStart = Math.max(0, startInclusive);
        int cappedEnd = Math.min(endExclusive, characters.size());
        for (int index = cappedStart; index < cappedEnd; index++) {
            MetaChar character = characters.get(index);
            if (character.hoverRealName() != null) {
                hoverRealNames.add(character.hoverRealName());
            }
            if (character.insertionName() != null) {
                insertionNames.add(character.insertionName());
            }
        }

        return new NameMetadata(List.copyOf(hoverRealNames), List.copyOf(insertionNames));
    }

    private static void appendNormalizedFragment(List<MetaChar> target, Component fragment) {
        String text = extractFragmentText(fragment);
        if (text.isEmpty()) {
            return;
        }

        Style style = fragment.getStyle();
        String hoverRealName = validUsername(ChatManager.extractHoverRealUsername(style));
        String insertionName = validUsername(ChatManager.extractInsertionUsername(style));

        boolean previousWasSpace = !target.isEmpty() && target.getLast().value() == ' ';
        for (int index = 0; index < text.length();) {
            int codePoint = text.codePointAt(index);
            index += Character.charCount(codePoint);

            if (codePoint == '\u00A7') {
                if (index < text.length()) {
                    index += Character.charCount(text.codePointAt(index));
                }
                continue;
            }

            if (Character.isWhitespace(codePoint) || isIgnorableForParsing(codePoint)) {
                if (!previousWasSpace) {
                    target.add(new MetaChar(' ', hoverRealName, insertionName, style.isStrikethrough(), style.isItalic()));
                    previousWasSpace = true;
                }
                continue;
            }

            String value = new String(Character.toChars(codePoint));
            for (int charIndex = 0; charIndex < value.length(); charIndex++) {
                target.add(new MetaChar(
                        value.charAt(charIndex),
                        hoverRealName,
                        insertionName,
                        style.isStrikethrough(),
                        style.isItalic()));
            }
            previousWasSpace = false;
        }
    }

    private static String extractFragmentText(Component fragment) {
        if (fragment.getContents() instanceof PlainTextContents plainTextContents) {
            return plainTextContents.text();
        }
        return fragment.getString();
    }

    private static boolean isIgnorableForParsing(int codePoint) {
        return switch (Character.getType(codePoint)) {
            case Character.CONTROL,
                    Character.FORMAT,
                    Character.PRIVATE_USE,
                    Character.SURROGATE,
                    Character.UNASSIGNED -> true;
            default -> false;
        };
    }

    private static String validUsername(String candidate) {
        return candidate != null && USERNAME_PATTERN.matcher(candidate).matches() ? candidate : null;
    }

    private static String toText(List<MetaChar> characters) {
        StringBuilder builder = new StringBuilder(characters.size());
        for (MetaChar character : characters) {
            builder.append(character.value());
        }
        return builder.toString();
    }

    boolean hasStrikethrough(int startInclusive, int endExclusive) {
        int cappedStart = Math.max(0, startInclusive);
        int cappedEnd = Math.min(endExclusive, characters.size());
        for (int index = cappedStart; index < cappedEnd; index++) {
            if (characters.get(index).strikethrough()) {
                return true;
            }
        }
        return false;
    }

    boolean hasItalic(int startInclusive, int endExclusive) {
        int cappedStart = Math.max(0, startInclusive);
        int cappedEnd = Math.min(endExclusive, characters.size());
        for (int index = cappedStart; index < cappedEnd; index++) {
            if (characters.get(index).italic()) {
                return true;
            }
        }
        return false;
    }

    private record MetaChar(
            char value,
            String hoverRealName,
            String insertionName,
            boolean strikethrough,
            boolean italic) {}

    private record NameMetadata(List<String> hoverRealNames, List<String> insertionNames) {
        private String firstHoverRealName() {
            return hoverRealNames.isEmpty() ? null : hoverRealNames.get(0);
        }

        private String firstInsertionName() {
            return insertionNames.isEmpty() ? null : insertionNames.get(0);
        }
    }
}
