package org.sequoia.seq.client;

import com.collarmc.pounce.EventBus;
import com.collarmc.pounce.Preference;
import com.collarmc.pounce.Subscribe;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import java.util.Objects;
import java.util.UUID;
import lombok.Getter;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.sequoia.seq.config.ConfigManager;
import org.sequoia.seq.config.Setting;
import org.sequoia.seq.events.GameStartEvent;
import org.sequoia.seq.events.MinecraftFinishedLoading;
import org.sequoia.seq.command.SeqCommand;
import org.sequoia.seq.managers.AssetManager;
import org.sequoia.seq.managers.BombShareManager;
import org.sequoia.seq.managers.ChatManager;
import org.sequoia.seq.managers.FontManager;
import org.sequoia.seq.managers.GameManager;
import org.sequoia.seq.managers.GuildStorageTracker;
import org.sequoia.seq.managers.GuildWarTrackerHandle;
import org.sequoia.seq.managers.GuildWarTrackers;
import org.sequoia.seq.managers.PartyHealthCache;
import org.sequoia.seq.managers.PartyFinderManager;
import org.sequoia.seq.managers.WynnPartySyncManager;
import org.sequoia.seq.model.WynnClassType;
import org.sequoia.seq.network.ConnectionManager;
import org.sequoia.seq.network.WynncraftServerPolicy;
import org.sequoia.seq.network.auth.MinecraftAuthService;
import org.sequoia.seq.ui.SequoiaScreen;
import org.sequoia.seq.utils.WynnClassCache;
import org.sequoia.seq.update.UpdateManager;
import org.sequoia.seq.utils.rendering.nvg.NVGContext;
import org.slf4j.Logger;

public class SeqClient implements ClientModInitializer {
    private static final long MAIN_SCOPE_RECOVERY_INTERVAL_MS = 60_000L;

    public static final Logger LOGGER = LogUtils.getLogger();

    public static final Minecraft mc = Minecraft.getInstance();

    @Getter
    public static EventBus eventBus;

    @Getter
    public static FontManager fontManager;

    public static GameManager gameManager;
    public static AssetManager assetManager;

    @Getter
    public static ConfigManager configManager;

    @Getter
    public static PartyFinderManager partyFinderManager;

    @Getter
    public static MinecraftAuthService authService;

    public static ChatManager chatManager;

    @Getter
    public static BombShareManager bombShareManager;

    // ── Network config settings ──
    @Getter
    public static Setting.BooleanSetting autoConnectSetting;

    @Getter
    public static Setting.BooleanSetting showDiscordChatSetting;

    @Getter
    public static Setting.BooleanSetting raidAutoAnnounceSetting;

    @Getter
    public static Setting.BooleanSetting trackGuildWarsSetting;

    @Getter
    public static Setting.BooleanSetting checkUpdatesSetting;

    @Getter
    public static Setting.BooleanSetting trackGuildStorageSetting;

    @Getter
    public static Setting.IntSetting guildStorageEmeraldNotifyValueSetting;

    @Getter
    public static Setting.IntSetting guildStorageAspectNotifyValueSetting;

    @Getter
    public static Setting.BooleanSetting easterEggsSetting;

    @Getter
    public static Setting.BooleanSetting startupVideoSetting;

    @Getter
    public static Setting.BooleanSetting announceOpenPartiesSetting;

    @Getter
    public static Setting.IntSetting announceOpenPartiesIntervalMinutesSetting;

    @Getter
    public static Setting.BooleanSetting syncWynnPartySetting;

    @Getter
    public static Setting.BooleanSetting receiveBombShareRequestsSetting;

    @Getter
    public static WynnPartySyncManager wynnPartySyncManager;

    @Getter
    public static GuildWarTrackerHandle guildWarTracker;

    @Getter
    public static GuildStorageTracker guildStorageTracker;

    private static KeyMapping openScreenKey;
    private static KeyMapping shareBombsKey;
    private static WynnClassType lastBroadcastPartyClass;
    private static boolean wasInPartyFinder;
    private static WynncraftServerPolicy.Scope lastServerScope = WynncraftServerPolicy.Scope.BLOCKED;
    private static String lastServerHost;
    private static long lastProductionRecoveryAttemptAtMs;
    private static UUID lastSeenMinecraftProfileId;

    @Override
    public void onInitializeClient() {
        try {
            eventBus = new EventBus(mc::execute);
            eventBus.subscribe(this);
        } catch (Exception e) {
            LOGGER.warn("Event bus failed to initialize.");
        }
        fontManager = new FontManager();
        gameManager = new GameManager();
        partyFinderManager = new PartyFinderManager();
        wynnPartySyncManager = new WynnPartySyncManager();
        guildWarTracker = GuildWarTrackers.createIfAvailable();
        guildStorageTracker = GuildStorageTracker.getInstance();
        chatManager = new ChatManager();
        bombShareManager = new BombShareManager();
        configManager = new ConfigManager();
        configManager.load();
        configManager.migrateToken();
        authService = MinecraftAuthService.getInstance();
        SeqCommand.register();

        KeyMapping.Category category =
                KeyMapping.Category.register(Identifier.fromNamespaceAndPath("sequoia-mod", "controls"));

        openScreenKey = KeyBindingHelper.registerKeyBinding(
                new KeyMapping("key.sequoia-mod.open_settings", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, category));
        shareBombsKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.sequoia-mod.share_bombs", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, category));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openScreenKey.consumeClick()) {
                if (client.screen == null) {
                    openMainScreen();
                }
            }
            while (shareBombsKey.consumeClick()) {
                if (bombShareManager != null) {
                    bombShareManager.tryHotkeyShareLatestPendingPrompt();
                }
            }

            WynncraftServerPolicy.Scope serverScope = WynncraftServerPolicy.currentScope();
            String currentHost = WynncraftServerPolicy.currentNormalizedHost();
            WynncraftServerPolicy.Scope previousServerScope = lastServerScope;
            logServerScopeChange(serverScope, currentHost);
            if (serverScope == WynncraftServerPolicy.Scope.BLOCKED) {
                ConnectionManager.disconnectForBlockedServer();
                wasInPartyFinder = false;
                lastBroadcastPartyClass = null;
                if (wynnPartySyncManager != null) {
                    wynnPartySyncManager.reset();
                }
                if (guildWarTracker != null) {
                    guildWarTracker.reset();
                }
                if (guildStorageTracker != null) {
                    guildStorageTracker.reset();
                }
                return;
            }
            if (serverScope == WynncraftServerPolicy.Scope.UNKNOWN) {
                ConnectionManager.flushPendingOutbound();
                return;
            }

            if (handleMinecraftAccountChange()) {
                return;
            }

            maybeRecoverProductionConnection(serverScope, previousServerScope, currentHost);

            if (partyFinderManager != null) {
                partyFinderManager.tickOpenPartyAnnouncements();
            }
            if (wynnPartySyncManager != null) {
                wynnPartySyncManager.tick();
            }
            PartyHealthCache.tick();
            if (guildWarTracker != null) {
                guildWarTracker.tick();
            }
            if (guildStorageTracker != null) {
                guildStorageTracker.tick();
            }
            ConnectionManager.flushPendingOutbound();

            boolean inPartyFinder = partyFinderManager != null && partyFinderManager.isInParty();
            if (!inPartyFinder) {
                wasInPartyFinder = false;
                lastBroadcastPartyClass = null;
                return;
            }

            if (!ConnectionManager.isConnected()) {
                wasInPartyFinder = true;
                return;
            }

            WynnClassType currentClass = WynnClassCache.resolveLocalClassType();
            if (currentClass == null) {
                wasInPartyFinder = true;
                return;
            }

            if (!wasInPartyFinder || currentClass != lastBroadcastPartyClass) {
                ConnectionManager.getInstance().sendPartyClassUpdate(currentClass);
                lastBroadcastPartyClass = currentClass;
            }
            wasInPartyFinder = true;
        });
    }

    private static boolean handleMinecraftAccountChange() {
        UUID currentProfileId = currentMinecraftProfileId();
        if (currentProfileId == null) {
            return false;
        }
        if (lastSeenMinecraftProfileId == null) {
            lastSeenMinecraftProfileId = currentProfileId;
            return false;
        }
        if (lastSeenMinecraftProfileId.equals(currentProfileId)) {
            return false;
        }

        LOGGER.warn(
                "[Seq] Active Minecraft account changed {} -> {}; clearing connection state",
                lastSeenMinecraftProfileId,
                currentProfileId);
        lastSeenMinecraftProfileId = currentProfileId;
        ConnectionManager.resetForAccountChange();
        if (authService != null) {
            authService.clearSessionIfNotActiveProfile(currentProfileId);
        }
        wasInPartyFinder = false;
        lastBroadcastPartyClass = null;
        if (wynnPartySyncManager != null) {
            wynnPartySyncManager.reset();
        }
        if (guildWarTracker != null) {
            guildWarTracker.reset();
        }
        if (guildStorageTracker != null) {
            guildStorageTracker.reset();
        }
        return true;
    }

    private static UUID currentMinecraftProfileId() {
        if (mc == null || mc.getUser() == null) {
            return null;
        }
        return mc.getUser().getProfileId();
    }

    private static void logServerScopeChange(WynncraftServerPolicy.Scope serverScope, String currentHost) {
        if (serverScope == lastServerScope && Objects.equals(currentHost, lastServerHost)) {
            return;
        }
        LOGGER.info(
                "[Seq] Wynncraft scope changed {} -> {} host={} previousHost={}",
                lastServerScope,
                serverScope,
                currentHost,
                lastServerHost);
        lastServerScope = serverScope;
        lastServerHost = currentHost;
    }

    private static void maybeRecoverProductionConnection(
            WynncraftServerPolicy.Scope serverScope,
            WynncraftServerPolicy.Scope previousScope,
            String currentHost) {
        if (serverScope != WynncraftServerPolicy.Scope.MAIN || autoConnectSetting == null || !autoConnectSetting.getValue()) {
            return;
        }

        if (ConnectionManager.isConnected()) {
            lastProductionRecoveryAttemptAtMs = System.currentTimeMillis();
            return;
        }

        if (!ConnectionManager.canAutoConnectNow()) {
            return;
        }

        long now = System.currentTimeMillis();
        AutoConnectTrigger trigger = determineAutoConnectTrigger(
                true,
                serverScope,
                previousScope,
                true,
                now,
                lastProductionRecoveryAttemptAtMs,
                MAIN_SCOPE_RECOVERY_INTERVAL_MS);
        if (trigger == AutoConnectTrigger.NONE) {
            return;
        }

        lastProductionRecoveryAttemptAtMs = now;
        LOGGER.info(
                "[Seq] Triggering production reconnect reason={} host={} manualSuppressed={}",
                trigger.logName,
                currentHost,
                ConnectionManager.isAutoConnectSuppressedByManualDisconnect());
        ConnectionManager.getInstance().connect();
    }

    enum AutoConnectTrigger {
        NONE("none"),
        SCOPE_RECOVERY("scope_recovery"),
        PERIODIC_RECOVERY("periodic_recovery");

        private final String logName;

        AutoConnectTrigger(String logName) {
            this.logName = logName;
        }
    }

    static AutoConnectTrigger determineAutoConnectTrigger(
            boolean autoConnectEnabled,
            WynncraftServerPolicy.Scope currentScope,
            WynncraftServerPolicy.Scope previousScope,
            boolean canAutoConnectNow,
            long nowMs,
            long lastRecoveryAttemptAtMs,
            long recoveryIntervalMs) {
        if (!autoConnectEnabled || currentScope != WynncraftServerPolicy.Scope.MAIN || !canAutoConnectNow) {
            return AutoConnectTrigger.NONE;
        }
        if (previousScope != WynncraftServerPolicy.Scope.MAIN) {
            return AutoConnectTrigger.SCOPE_RECOVERY;
        }
        if (nowMs - lastRecoveryAttemptAtMs >= recoveryIntervalMs) {
            return AutoConnectTrigger.PERIODIC_RECOVERY;
        }
        return AutoConnectTrigger.NONE;
    }

    public static void openMainScreen() {
        mc.execute(() -> mc.setScreen(new SequoiaScreen()));
    }

    public static boolean isBombShareHotkeyDown() {
        return shareBombsKey != null && shareBombsKey.isDown();
    }

    @Subscribe(Preference.CALLER) // to stay in thread
    public void onMinecraftFinishedLoading(MinecraftFinishedLoading ignored) {
        // after minecraft done loading
        NVGContext.init();
        SeqClient.gameManager.loadFont();
        SeqClient.assetManager = new AssetManager();

        // Network settings
        autoConnectSetting = new Setting.BooleanSetting("auto_connect", "network", true);
        showDiscordChatSetting = new Setting.BooleanSetting("show_discord_bridge", "chat", true);
        raidAutoAnnounceSetting = new Setting.BooleanSetting("auto_announce", "raids", true);
        trackGuildWarsSetting = new Setting.BooleanSetting("track_guild_wars", "guild_wars", true);
        checkUpdatesSetting = new Setting.BooleanSetting("check_updates", "updates", true);
        trackGuildStorageSetting = new Setting.BooleanSetting("track_guild_storage", "guild_storage", true);
        guildStorageEmeraldNotifyValueSetting =
                new Setting.IntSetting("guild_storage_emerald_threshold_percent", "guild_storage", 100, 0, 100);
        guildStorageAspectNotifyValueSetting =
                new Setting.IntSetting("guild_storage_aspect_threshold_percent", "guild_storage", 100, 0, 100);
        easterEggsSetting = new Setting.BooleanSetting("enable_easter_eggs", "ui", true);
        startupVideoSetting = new Setting.BooleanSetting("startup_video", "ui", false);
        announceOpenPartiesSetting = new Setting.BooleanSetting("announce_open_parties", "party_finder", true);
        announceOpenPartiesIntervalMinutesSetting =
                new Setting.IntSetting("announce_open_parties_interval_minutes", "party_finder", 5, 1, 60);
        syncWynnPartySetting = new Setting.BooleanSetting("sync_with_wynn_party", "party_finder", true);
        receiveBombShareRequestsSetting = new Setting.BooleanSetting("receive_bomb_share_requests", "network", true);
        getConfigManager().register(autoConnectSetting);
        getConfigManager().register(showDiscordChatSetting);
        getConfigManager().register(raidAutoAnnounceSetting);
        getConfigManager().register(trackGuildWarsSetting);
        getConfigManager().register(checkUpdatesSetting);
        getConfigManager().register(trackGuildStorageSetting);
        getConfigManager().register(guildStorageEmeraldNotifyValueSetting);
        getConfigManager().register(guildStorageAspectNotifyValueSetting);
        getConfigManager().register(easterEggsSetting);
        getConfigManager().register(startupVideoSetting);
        getConfigManager().register(announceOpenPartiesSetting);
        getConfigManager().register(announceOpenPartiesIntervalMinutesSetting);
        getConfigManager().register(syncWynnPartySetting);
        getConfigManager().register(receiveBombShareRequestsSetting);
        getConfigManager().load(); // reload to pick up saved values for new settings

        // Auto-connect if enabled. The auth service will refresh or mint a backend token as needed.
        if (autoConnectSetting.getValue() && WynncraftServerPolicy.currentScope() == WynncraftServerPolicy.Scope.MAIN) {
            ConnectionManager.getInstance().connect();
        }
    }

    @Subscribe(Preference.CALLER)
    public void onGameStart(GameStartEvent ignored) {
        if (checkUpdatesSetting == null || checkUpdatesSetting.getValue()) {
            UpdateManager.getInstance().checkForUpdatesOnStartup();
        }
    }

    public static Identifier getFileLocation(String path) {
        return Identifier.fromNamespaceAndPath("seq", path);
    }
}
