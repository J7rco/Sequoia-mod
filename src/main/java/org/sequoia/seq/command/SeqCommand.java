package org.sequoia.seq.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.SharedSuggestionProvider;
import org.sequoia.seq.accessors.NotificationAccessor;
import org.sequoia.seq.client.SeqClient;
import org.sequoia.seq.config.ConfigManager;
import org.sequoia.seq.managers.BombShareManager;
import org.sequoia.seq.managers.PartyHealthCache;
import org.sequoia.seq.managers.PartyFinderManager;
import org.sequoia.seq.managers.PartyListing;
import org.sequoia.seq.managers.WynnPartyScoreboardReader;
import org.sequoia.seq.model.Activity;
import org.sequoia.seq.model.Listing;
import org.sequoia.seq.model.PartyRole;
import org.sequoia.seq.network.ConnectionManager;
import org.sequoia.seq.ui.PartyFinderScreen;
import org.sequoia.seq.utils.PlayerNameCache;

public class SeqCommand {

        private static final List<String> ROLE_SUGGESTIONS = List.of("dps", "healer", "tank");

        public static void register() {
                ClientCommandRegistrationCallback.EVENT.register(SeqCommand::registerCommands);
        }

        private static void registerCommands(
                        CommandDispatcher<FabricClientCommandSource> dispatcher,
                        CommandBuildContext registryAccess) {
                var root = ClientCommandManager.literal("seq")
                                .executes(ctx -> {
                                        SeqClient.openMainScreen();
                                        return 1;
                                })
                                .then(ClientCommandManager.literal("connect")
                                                .executes(ctx -> {
                                                        ConnectionManager.getInstance().connectManually();
                                                        return 1;
                                                }))
                                .then(ClientCommandManager.literal("link")
                                                .executes(ctx -> {
                                                        ConnectionManager.getInstance().linkManually();
                                                        return 1;
                                                }))
                                .then(ClientCommandManager.literal("disconnect")
                                                .executes(ctx -> {
                                                        ConnectionManager.getInstance().disconnectManually();
                                                        return 1;
                                                }))
                                .then(ClientCommandManager.literal("connected")
                                                .executes(ctx -> {
                                                        if (!ConnectionManager.isConnected()) {
                                                                sendFeedback(
                                                                                ctx.getSource(),
                                                                                "Not connected. Use /seq connect first.");
                                                                return 0;
                                                        }
                                                        ConnectionManager.getInstance().requestConnectedUsers(users -> {
                                                                if (users.isEmpty()) {
                                                                        sendFeedback(ctx.getSource(), "No users connected.");
                                                                        return;
                                                                }

                                                                sendFeedback(
                                                                                ctx.getSource(),
                                                                                "Connected users (" + users.size() + "):");
                                                                for (String user : users) {
                                                                        sendFeedback(ctx.getSource(), "• " + user);
                                                                }
                                                        });
                                                        return 1;
                                                }))
                                .then(ClientCommandManager.literal("status")
                                                .executes(ctx -> {
                                                        boolean connected = ConnectionManager.isConnected();
                                                        String token = SeqClient.getConfigManager().getToken();
                                                        boolean hasToken = token != null && !token.isBlank();
                                                        String uptime = ConnectionManager.getInstance()
                                                                        .getUptimeString();
                                                        sendFeedback(
                                                                        ctx.getSource(),
                                                                        "Connected: " + connected
                                                                                        + " | Token: "
                                                                                        + (hasToken ? "present"
                                                                                                        : "none")
                                                                                        + (uptime != null
                                                                                                        ? " | Uptime: " + uptime
                                                                                                        : ""));
                                                        return 1;
                                                }))
                                .then(ClientCommandManager.literal("logout")
                                                .executes(ctx -> {
                                                        ConnectionManager.getInstance().disconnect();
                                                        SeqClient.getConfigManager().clearToken();
                                                        sendFeedback(ctx.getSource(), "Logged out and token cleared.");
                                                        return 1;
                                                }))
                                .then(buildIgnoreCommand())
                                .then(buildUnignoreCommand())
                                .then(buildBombCommand())
                                .then(buildPartyCommand("party"))
                                .then(buildPartyCommand("p"));

                dispatcher.register(root);
        }

        private static LiteralArgumentBuilder<FabricClientCommandSource> buildPartyCommand(String literalName) {
                return ClientCommandManager.literal(literalName)
                                .executes(ctx -> openPartyScreen())
                                .then(ClientCommandManager.literal("create-ui")
                                                .executes(ctx -> openPartyCreateScreen()))
                                .then(ClientCommandManager.literal("list")
                                                .executes(SeqCommand::runPartyList))
                                .then(ClientCommandManager.literal("status")
                                                 .executes(SeqCommand::runPartyStatus))
                                .then(ClientCommandManager.literal("hp")
                                                 .executes(SeqCommand::runPartyHp))
                                .then(ClientCommandManager.literal("hp-debug")
                                                 .executes(SeqCommand::runPartyHpDebug))
                                .then(ClientCommandManager.literal("hp-render-debug")
                                                .executes(SeqCommand::runPartyHpRenderDebug))
                                .then(ClientCommandManager.literal("create")
                                                 .then(ClientCommandManager.argument(
                                                                "activities",
                                                                StringArgumentType.greedyString())
                                                                .suggests(SeqCommand::suggestActivities)
                                                                .executes(SeqCommand::runPartyCreate)))
                                .then(ClientCommandManager.literal("update")
                                                .then(ClientCommandManager.argument(
                                                                "activities",
                                                                StringArgumentType.greedyString())
                                                                .suggests(SeqCommand::suggestActivities)
                                                                .executes(SeqCommand::runPartyUpdate)))
                                .then(buildPartyJoinCommand())
                                .then(ClientCommandManager.literal("deny")
                                                .then(ClientCommandManager.argument(
                                                                "listingId",
                                                                LongArgumentType.longArg(1))
                                                                .executes(SeqCommand::runPartyDeny)))
                                .then(ClientCommandManager.literal("leave")
                                                .executes(ctx -> relayCommandResult(
                                                                ctx,
                                                                SeqClient.getPartyFinderManager()
                                                                                .leavePartyFromCommand())))
                                .then(ClientCommandManager.literal("invite")
                                                .then(ClientCommandManager.argument(
                                                                "username",
                                                                StringArgumentType.word())
                                                                .executes(SeqCommand::runPartyInvite)))
                                .then(ClientCommandManager.literal("reserve")
                                                .then(ClientCommandManager.argument(
                                                                "count",
                                                                IntegerArgumentType.integer(0))
                                                                .executes(SeqCommand::runPartyReserve)))
                                .then(ClientCommandManager.literal("open")
                                                .executes(ctx -> relayCommandResult(
                                                                ctx,
                                                                SeqClient.getPartyFinderManager()
                                                                                .reopenPartyFromCommand())))
                                .then(ClientCommandManager.literal("close")
                                                .executes(ctx -> relayCommandResult(
                                                                ctx,
                                                                SeqClient.getPartyFinderManager()
                                                                                .closePartyFromCommand())))
                                .then(ClientCommandManager.literal("disband")
                                                .executes(ctx -> relayCommandResult(
                                                                ctx,
                                                                SeqClient.getPartyFinderManager()
                                                                                .disbandPartyFromCommand())))
                                .then(ClientCommandManager.literal("role")
                                                .then(ClientCommandManager.argument(
                                                                "role",
                                                                StringArgumentType.word())
                                                                .suggests(SeqCommand::suggestRoles)
                                                                .executes(SeqCommand::runPartyRole)))
                                .then(ClientCommandManager.literal("kick")
                                                .then(ClientCommandManager.argument(
                                                                "username",
                                                                StringArgumentType.word())
                                                                .executes(SeqCommand::runPartyKick)))
                                .then(ClientCommandManager.literal("promote")
                                                .then(ClientCommandManager.argument(
                                                                "username",
                                                                StringArgumentType.word())
                                                                .executes(SeqCommand::runPartyPromote)))
                                .then(ClientCommandManager.literal("invite-all")
                                                .executes(ctx -> relayCommandResult(
                                                                ctx,
                                                                SeqClient.getPartyFinderManager()
                                                                                .inviteAllCurrentMembersFromCommand())));
        }

        private static LiteralArgumentBuilder<FabricClientCommandSource> buildIgnoreCommand() {
                return ClientCommandManager.literal("ignore")
                                .executes(SeqCommand::runIgnoredBridgeUsersList)
                                .then(ClientCommandManager.argument(
                                                "username",
                                                StringArgumentType.word())
                                                .executes(SeqCommand::runIgnoreBridgeUser));
        }

        private static LiteralArgumentBuilder<FabricClientCommandSource> buildUnignoreCommand() {
                return ClientCommandManager.literal("unignore")
                                .executes(SeqCommand::runIgnoredBridgeUsersList)
                                .then(ClientCommandManager.argument(
                                                "username",
                                                StringArgumentType.word())
                                                .suggests(SeqCommand::suggestIgnoredBridgeUsers)
                                                .executes(SeqCommand::runUnignoreBridgeUser));
        }

        private static LiteralArgumentBuilder<FabricClientCommandSource> buildBombCommand() {
                return ClientCommandManager.literal("bomb")
                                .then(ClientCommandManager.literal("_share")
                                                .then(ClientCommandManager.argument(
                                                                "requestId",
                                                                StringArgumentType.word())
                                                                .executes(ctx -> SeqClient.getBombShareManager()
                                                                                .sharePrompt(StringArgumentType
                                                                                                .getString(
                                                                                                                ctx,
                                                                                                                "requestId")))))
                                .then(ClientCommandManager.literal("_mute-requests")
                                                .executes(ctx -> SeqClient.getBombShareManager().muteRequests()))
                                .then(ClientCommandManager.argument(
                                                "selectors",
                                                StringArgumentType.greedyString())
                                                .suggests(SeqCommand::suggestBombSelectors)
                                                .executes(ctx -> SeqClient.getBombShareManager()
                                                                .requestBombShare(StringArgumentType.getString(
                                                                                ctx,
                                                                                "selectors"))));
        }

        private static LiteralArgumentBuilder<FabricClientCommandSource> buildPartyJoinCommand() {
                return ClientCommandManager.literal("join")
                                .then(ClientCommandManager.argument(
                                                "listingId",
                                                LongArgumentType.longArg(1))
                                                .executes(ctx -> runPartyJoin(ctx, PartyRole.DPS, null))
                                                .then(ClientCommandManager.literal("token")
                                                                .then(ClientCommandManager.argument(
                                                                                "inviteToken",
                                                                                StringArgumentType.string())
                                                                                .executes(SeqCommand::runPartyJoinWithToken)))
                                                .then(ClientCommandManager.argument(
                                                                "role",
                                                                StringArgumentType.word())
                                                                .suggests(SeqCommand::suggestRoles)
                                                                .executes(SeqCommand::runPartyJoinWithRole)
                                                                .then(ClientCommandManager.literal("token")
                                                                                .then(ClientCommandManager.argument(
                                                                                                "inviteToken",
                                                                                                StringArgumentType
                                                                                                                .string())
                                                                                                .executes(SeqCommand::runPartyJoinWithRoleAndToken)))));
        }

        private static int openPartyScreen() {
                SeqClient.mc.execute(() -> SeqClient.mc.setScreen(new PartyFinderScreen(SeqClient.mc.screen)));
                return 1;
        }

        private static CompletableFuture<Suggestions> suggestBombSelectors(
                        CommandContext<FabricClientCommandSource> ctx,
                        SuggestionsBuilder builder) {
                for (String suggestion : BombShareManager.suggestionsFor(builder.getRemaining())) {
                        builder.suggest(suggestion);
                }
                return builder.buildFuture();
        }

        private static int openPartyCreateScreen() {
                SeqClient.mc.execute(() -> SeqClient.mc.setScreen(new PartyFinderScreen(SeqClient.mc.screen, true)));
                return 1;
        }

        private static int runPartyList(CommandContext<FabricClientCommandSource> ctx) {
                FabricClientCommandSource source = ctx.getSource();
                PartyFinderManager manager = SeqClient.getPartyFinderManager();
                manager.refreshListingsForCommand().whenComplete((result, error) -> {
                        if (error != null) {
                                sendFeedback(source, "Unexpected error while loading party listings.");
                                return;
                        }
                        if (!result.success()) {
                                sendFeedback(source, result.message());
                                return;
                        }

                        List<Listing> listings = result.data();
                        if (listings == null || listings.isEmpty()) {
                                sendFeedback(source, "No Sequoia party listings found.");
                                return;
                        }

                        sendFeedback(source, "Party listings (" + listings.size() + "):");
                        Listing currentListing = manager.getCurrentListing();
                        for (Listing listing : listings) {
                                boolean isCurrent = currentListing != null && currentListing.id() == listing.id();
                                sendFeedback(source, formatListingSummary(listing, isCurrent));
                        }
                });
                return 1;
        }

        private static int runPartyStatus(CommandContext<FabricClientCommandSource> ctx) {
                FabricClientCommandSource source = ctx.getSource();
                PartyFinderManager manager = SeqClient.getPartyFinderManager();
                manager.refreshListingsForCommand().whenComplete((result, error) -> {
                        if (error != null) {
                                sendFeedback(source, "Unexpected error while loading party status.");
                                return;
                        }
                        if (!result.success()) {
                                sendFeedback(source, result.message());
                                return;
                        }

                        Listing currentListing = manager.getCurrentListing();
                        if (currentListing == null) {
                                sendFeedback(source, "You are not currently in a Sequoia party.");
                                return;
                        }

                        sendFeedback(source, "Current party: " + formatListingSummary(currentListing, true));
                        sendFeedback(
                                        source,
                                        manager.isPartyLeader()
                                                        ? "You are the party leader."
                                                        : "You are a party member.");
                });
                return 1;
        }

        private static int runPartyHp(CommandContext<FabricClientCommandSource> ctx) {
                List<WynnPartyScoreboardReader.PartyHealth> members = WynnPartyScoreboardReader.readPartyHealth();
                if (members.isEmpty()) {
                        sendFeedback(ctx.getSource(), "No Wynn party HP found. Try /seq p hp-debug to inspect sidebar text.");
                        return 0;
                }

                sendFeedback(ctx.getSource(), "Wynn party HP from scoreboard:");
                for (WynnPartyScoreboardReader.PartyHealth member : members) {
                        String username = member.username() != null ? member.username() : "unknown username";
                        sendFeedback(
                                        ctx.getSource(),
                                        member.nickname()
                                                        + " -> "
                                                        + username
                                                        + " | HP: "
                                                        + member.hp()
                                                        + " | Level: "
                                                        + member.level()
                                                        + " | Online: "
                                                        + member.online()
                                                        + " | Alive: "
                                                        + member.alive());
                }
                return 1;
        }

        private static int runPartyHpDebug(CommandContext<FabricClientCommandSource> ctx) {
                List<String> lines = WynnPartyScoreboardReader.readSidebarLines();
                if (lines.isEmpty()) {
                        sendFeedback(ctx.getSource(), "No sidebar scoreboard lines found.");
                        return 0;
                }

                sendFeedback(ctx.getSource(), "Sidebar lines read by Sequoia:");
                for (int i = 0; i < lines.size(); i++) {
                        sendFeedback(ctx.getSource(), (i + 1) + ": " + lines.get(i));
                }
                return 1;
        }

        private static int runPartyHpRenderDebug(CommandContext<FabricClientCommandSource> ctx) {
                List<PartyHealthCache.VisiblePlayerHealthDebug> players = PartyHealthCache.visiblePlayerDebug();
                if (players.isEmpty()) {
                        sendFeedback(ctx.getSource(), "No visible players found for HP render debug.");
                        return 0;
                }

                sendFeedback(ctx.getSource(), "Visible player HP render debug:");
                for (PartyHealthCache.VisiblePlayerHealthDebug player : players) {
                        String health = player.health()
                                        .map(value -> value.nickname()
                                                        + " -> "
                                                        + value.username()
                                                        + " "
                                                        + value.hp()
                                                        + "/"
                                                        + value.maxHp())
                                        .orElse("no cached party HP");
                        sendFeedback(
                                        ctx.getSource(),
                                        player.name()
                                                        + " | profile="
                                                        + player.profileName()
                                                        + " | scoreboard="
                                                        + player.scoreboardName()
                                                        + " | custom="
                                                        + player.customName()
                                                        + " | percent="
                                                        + player.percent()
                                                        + " | "
                                                        + health);
                }
                return 1;
        }

        private static int runPartyCreate(CommandContext<FabricClientCommandSource> ctx) {
                List<String> activities = parseActivitiesInput(
                                StringArgumentType.getString(ctx, "activities"));
                if (activities.isEmpty()) {
                        sendFeedback(ctx.getSource(), "Provide at least one activity.");
                        return 0;
                }
                return relayCommandResult(
                                ctx,
                                SeqClient.getPartyFinderManager().createPartyFromCommand(activities));
        }

        private static int runPartyUpdate(CommandContext<FabricClientCommandSource> ctx) {
                List<String> activities = parseActivitiesInput(
                                StringArgumentType.getString(ctx, "activities"));
                if (activities.isEmpty()) {
                        sendFeedback(ctx.getSource(), "Provide at least one activity.");
                        return 0;
                }
                return relayCommandResult(
                                ctx,
                                SeqClient.getPartyFinderManager().updatePartyFromCommand(activities));
        }

        private static int runPartyJoin(
                        CommandContext<FabricClientCommandSource> ctx,
                        PartyRole role,
                        String inviteToken) {
                long listingId = LongArgumentType.getLong(ctx, "listingId");
                return relayCommandResult(
                                ctx,
                                SeqClient.getPartyFinderManager().joinPartyFromCommand(listingId, role, inviteToken));
        }

        private static int runPartyJoinWithRole(CommandContext<FabricClientCommandSource> ctx) {
                PartyRole role = parseRole(StringArgumentType.getString(ctx, "role"));
                if (role == null) {
                        sendFeedback(ctx.getSource(), "Role must be one of: DPS, Healer, Tank.");
                        return 0;
                }
                return runPartyJoin(ctx, role, null);
        }

        private static int runPartyJoinWithToken(CommandContext<FabricClientCommandSource> ctx) {
                return runPartyJoin(
                                ctx,
                                PartyRole.DPS,
                                StringArgumentType.getString(ctx, "inviteToken"));
        }

        private static int runPartyJoinWithRoleAndToken(CommandContext<FabricClientCommandSource> ctx) {
                PartyRole role = parseRole(StringArgumentType.getString(ctx, "role"));
                if (role == null) {
                        sendFeedback(ctx.getSource(), "Role must be one of: DPS, Healer, Tank.");
                        return 0;
                }
                return runPartyJoin(ctx, role, StringArgumentType.getString(ctx, "inviteToken"));
        }

        private static int runPartyDeny(CommandContext<FabricClientCommandSource> ctx) {
                long listingId = LongArgumentType.getLong(ctx, "listingId");
                sendFeedback(ctx.getSource(), "Dismissed party invite for #" + listingId + ".");
                return 1;
        }

        private static int runIgnoreBridgeUser(CommandContext<FabricClientCommandSource> ctx) {
                String username = StringArgumentType.getString(ctx, "username");
                if (!ConfigManager.isValidBridgeUsername(username)) {
                        sendFeedback(
                                        ctx.getSource(),
                                        "IGN must be a Minecraft username: 3-16 letters, numbers, or underscores.");
                        return 0;
                }

                if (SeqClient.getConfigManager().addIgnoredBridgeUser(username)) {
                        sendFeedback(ctx.getSource(), "Ignoring Discord bridge messages from " + username.trim() + ".");
                } else {
                        sendFeedback(
                                        ctx.getSource(),
                                        "Already ignoring Discord bridge messages from " + username.trim() + ".");
                }
                return 1;
        }

        private static int runUnignoreBridgeUser(CommandContext<FabricClientCommandSource> ctx) {
                String username = StringArgumentType.getString(ctx, "username");
                if (!ConfigManager.isValidBridgeUsername(username)) {
                        sendFeedback(
                                        ctx.getSource(),
                                        "IGN must be a Minecraft username: 3-16 letters, numbers, or underscores.");
                        return 0;
                }

                if (SeqClient.getConfigManager().removeIgnoredBridgeUser(username)) {
                        sendFeedback(
                                        ctx.getSource(),
                                        "Discord bridge messages from " + username.trim() + " are visible again.");
                } else {
                        sendFeedback(
                                        ctx.getSource(),
                                        "Discord bridge messages from " + username.trim() + " were not ignored.");
                }
                return 1;
        }

        private static int runIgnoredBridgeUsersList(CommandContext<FabricClientCommandSource> ctx) {
                List<String> ignoredUsers = SeqClient.getConfigManager().ignoredBridgeUsers();
                if (ignoredUsers.isEmpty()) {
                        sendFeedback(ctx.getSource(), "No Discord bridge users are ignored.");
                        return 1;
                }

                sendFeedback(ctx.getSource(), "Ignored Discord bridge users: " + String.join(", ", ignoredUsers));
                return 1;
        }

        private static int runPartyInvite(CommandContext<FabricClientCommandSource> ctx) {
                String username = StringArgumentType.getString(ctx, "username");
                return relayCommandResult(
                                ctx,
                                SeqClient.getPartyFinderManager().createInviteFromCommand(username));
        }

        private static int runPartyReserve(CommandContext<FabricClientCommandSource> ctx) {
                int count = IntegerArgumentType.getInteger(ctx, "count");
                return relayCommandResult(
                                ctx,
                                SeqClient.getPartyFinderManager().setReservedSlotTargetFromCommand(count));
        }

        private static int runPartyRole(CommandContext<FabricClientCommandSource> ctx) {
                PartyRole role = parseRole(StringArgumentType.getString(ctx, "role"));
                if (role == null) {
                        sendFeedback(ctx.getSource(), "Role must be one of: DPS, Healer, Tank.");
                        return 0;
                }
                return relayCommandResult(
                                ctx,
                                SeqClient.getPartyFinderManager().changeRoleFromCommand(role));
        }

        private static int runPartyKick(CommandContext<FabricClientCommandSource> ctx) {
                String username = StringArgumentType.getString(ctx, "username");
                return relayCommandResult(
                                ctx,
                                SeqClient.getPartyFinderManager().kickMemberFromCommand(username));
        }

        private static int runPartyPromote(CommandContext<FabricClientCommandSource> ctx) {
                String username = StringArgumentType.getString(ctx, "username");
                return relayCommandResult(
                                ctx,
                                SeqClient.getPartyFinderManager().promoteMemberFromCommand(username));
        }

        private static <T> int relayCommandResult(
                        CommandContext<FabricClientCommandSource> ctx,
                        CompletableFuture<PartyFinderManager.CommandResult<T>> future) {
                FabricClientCommandSource source = ctx.getSource();
                future.whenComplete((result, error) -> {
                        if (error != null) {
                                sendFeedback(source, "Unexpected command failure.");
                                return;
                        }
                        if (result != null && result.message() != null && !result.message().isBlank()) {
                                sendFeedback(source, result.message());
                        }
                });
                return 1;
        }

        private static CompletableFuture<Suggestions> suggestRoles(
                        CommandContext<FabricClientCommandSource> ctx,
                        SuggestionsBuilder builder) {
                return SharedSuggestionProvider.suggest(ROLE_SUGGESTIONS, builder);
        }

        private static CompletableFuture<Suggestions> suggestActivities(
                        CommandContext<FabricClientCommandSource> ctx,
                        SuggestionsBuilder builder) {
                String remaining = builder.getRemaining();
                int lastCommaIndex = remaining.lastIndexOf(',');
                int segmentStart = lastCommaIndex >= 0 ? lastCommaIndex + 1 : 0;
                while (segmentStart < remaining.length() && Character.isWhitespace(remaining.charAt(segmentStart))) {
                        segmentStart++;
                }

                String segment = remaining.substring(segmentStart);
                if (!segment.isEmpty() && (segment.charAt(0) == '"' || segment.charAt(0) == '\'')) {
                        segment = segment.substring(1);
                }

                SuggestionsBuilder segmentBuilder = builder.createOffset(builder.getStart() + segmentStart);
                String loweredSegment = segment.toLowerCase(Locale.ROOT);
                List<String> matches = PartyListing.activityCommandAliases()
                                .stream()
                                .filter(alias -> alias.toLowerCase(Locale.ROOT).startsWith(loweredSegment))
                                .toList();
                return SharedSuggestionProvider.suggest(matches, segmentBuilder);
        }

        private static CompletableFuture<Suggestions> suggestIgnoredBridgeUsers(
                        CommandContext<FabricClientCommandSource> ctx,
                        SuggestionsBuilder builder) {
                return SharedSuggestionProvider.suggest(SeqClient.getConfigManager().ignoredBridgeUsers(), builder);
        }

        private static List<String> parseActivitiesInput(String rawActivities) {
                List<String> activities = new ArrayList<>();
                if (rawActivities == null || rawActivities.isBlank()) {
                        return activities;
                }

                StringBuilder current = new StringBuilder();
                char activeQuote = 0;
                for (int i = 0; i < rawActivities.length(); i++) {
                        char ch = rawActivities.charAt(i);
                        if ((ch == '"' || ch == '\'') && (activeQuote == 0 || activeQuote == ch)) {
                                activeQuote = activeQuote == 0 ? ch : 0;
                                continue;
                        }
                        if (ch == ',' && activeQuote == 0) {
                                addActivityToken(activities, current.toString());
                                current.setLength(0);
                                continue;
                        }
                        current.append(ch);
                }
                addActivityToken(activities, current.toString());
                return activities;
        }

        private static void addActivityToken(List<String> activities, String rawToken) {
                if (rawToken == null) {
                        return;
                }
                String normalized = rawToken.trim();
                if (normalized.length() >= 2
                                && ((normalized.startsWith("\"") && normalized.endsWith("\""))
                                                || (normalized.startsWith("'") && normalized.endsWith("'")))) {
                        normalized = normalized.substring(1, normalized.length() - 1).trim();
                }
                if (!normalized.isEmpty()) {
                        activities.add(normalized);
                }
        }

        private static PartyRole parseRole(String rawRole) {
                        if (rawRole == null) {
                                return null;
                        }
                        return switch (rawRole.trim().toLowerCase(Locale.ROOT)) {
                                case "dps" -> PartyRole.DPS;
                                case "healer" -> PartyRole.HEALER;
                                case "tank" -> PartyRole.TANK;
                                default -> null;
                        };
        }

        private static String formatListingSummary(Listing listing, boolean isCurrent) {
                String prefix = isCurrent ? "* " : "• ";
                String activities = listing.resolvedActivities()
                                .stream()
                                .map(Activity::name)
                                .map(PartyListing::backendNameToDisplayName)
                                .reduce((left, right) -> left + ", " + right)
                                .orElse("Unknown Activity");
                String leaderName = PlayerNameCache.resolve(listing.leaderUUID());
                boolean leaderResolvable = leaderName != null
                                && !leaderName.isBlank()
                                && !"Loading...".equalsIgnoreCase(leaderName)
                                && !"Unknown".equalsIgnoreCase(leaderName);
                String leaderSegment = leaderResolvable ? " | Leader: " + leaderName : "";

                return prefix + "#" + listing.id()
                                + " | "
                                + activities
                                + " | "
                                + formatEnumLabel(listing.mode().name())
                                + " | "
                                + listing.occupiedSlotCount()
                                + "/"
                                + listing.maxPartySize()
                                + " | "
                                + formatEnumLabel(listing.status().name())
                                + leaderSegment;
        }

        private static String formatEnumLabel(String raw) {
                if (raw == null || raw.isBlank()) {
                        return "";
                }
                String lower = raw.toLowerCase(Locale.ROOT);
                return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
        }

        private static void sendFeedback(FabricClientCommandSource source, String message) {
                SeqClient.mc.execute(() -> source.sendFeedback(NotificationAccessor.prefixed(message)));
        }

}
