package com.mapsyncer.client;

import com.mapsyncer.client.voxy.VoxySyncClient;
import com.mapsyncer.network.PacketHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public class MapSyncerScreen extends Screen {
    private static final int PANEL_WIDTH = 600;
    private static final int PANEL_HEIGHT = 450;
    private static final int COMPLETE_VISIBLE_MS = 2000;
    private static final int ADMIN_POLL_MS = 2000;
    private static final Duration TOOLTIP_DELAY = Duration.ofMillis(250);
    private static final int COLOR_PANEL = 0xE2161A22;
    private static final int COLOR_PANEL_LIGHT = 0x4428323C;
    private static final int COLOR_BORDER = 0xFF4C8BA8;
    private static final int COLOR_ACCENT = 0xFF5BC0EB;
    private static final int COLOR_TEXT = 0xFFE8F0F6;
    private static final int COLOR_MUTED = 0xFF9BA8B5;
    private static final int COLOR_SUBTLE = 0xFFB9C3CC;
    private static final int COLOR_SUCCESS = 0xFF8CE99A;
    private static final int COLOR_WARN = 0xFFFFC857;
    private static final int COLOR_ERROR = 0xFFFF6B6B;

    private Tab activeTab = Tab.SYNC;
    private final List<HelpArea> frameHelpAreas = new ArrayList<>();
    private Button syncCurrentButton;
    private Button syncAllButton;
    private Button radiusSyncButton;
    private Button radiusValueButton;
    private Button publicWaypointsButton;
    private Button voxyButton;
    private Button autoSyncButton;
    private Button hudButton;
    private Button delayValueButton;
    private Button chatIntervalValueButton;
    private Button incrementalButton;
    private Button forceButton;
    private Button radiusEnabledButton;
    private Button radiusModeButton;
    private Button radiusMaxValueButton;
    private Button radiusSaveButton;
    private Button scanLocalWaypointsButton;
    private LocalWaypointList localWaypointList;
    private EditBox dimensionBox;
    private int radiusSyncBlocks = 1000;
    private int adminRadiusMaxBlocks = 3000;
    private String adminRadiusMode = "PLAYER_POSITION";
    private long lastAdminPoll;
    private long lastWaypointListUpdate;

    public MapSyncerScreen() {
        super(Component.translatable("mapsyncer.gui.title"));
    }

    @Override
    protected void init() {
        if (activeTab == Tab.ADMIN && !isOwner()) {
            activeTab = Tab.SYNC;
        }
        rebuildGui();
    }

    @Override
    public void tick() {
        if (activeTab == Tab.ADMIN && !isOwner()) {
            activeTab = Tab.SYNC;
            rebuildGui();
            return;
        }
        if (activeTab == Tab.ADMIN && isOwner()) {
            long now = System.currentTimeMillis();
            if (now - lastAdminPoll > ADMIN_POLL_MS) {
                AdminStatusClientState.requestNow();
                lastAdminPoll = now;
            }
        }
        updateDynamicButtons();
        refreshLocalWaypointList(false);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        drawShell(graphics);
        switch (activeTab) {
            case SYNC -> drawSyncTab(graphics);
            case ADMIN -> drawAdminTab(graphics);
            case SETTINGS -> drawSettingsTab(graphics);
        }
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        renderTooltipAreas(graphics, mouseX, mouseY);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private void rebuildGui() {
        clearWidgets();
        frameHelpAreas.clear();
        int panelLeft = panelLeft();
        int panelTop = panelTop();
        int x = contentLeft();
        int y = panelTop + 30;
        int gap = 8;
        int tabCount = isOwner() ? 3 : 2;
        int tabWidth = Math.min(108, Math.max(72, (contentWidth() - gap * (tabCount - 1)) / tabCount));

        addTabButton(Tab.SYNC, x, y, tabWidth);
        x += tabWidth + gap;
        if (isOwner()) {
            addTabButton(Tab.ADMIN, x, y, tabWidth);
            x += tabWidth + gap;
        }
        addTabButton(Tab.SETTINGS, x, y, tabWidth);

        switch (activeTab) {
            case SYNC -> buildSyncWidgets(panelLeft, panelTop);
            case ADMIN -> buildAdminWidgets(panelLeft, panelTop);
            case SETTINGS -> buildSettingsWidgets(panelLeft, panelTop);
        }
        if (activeTab == Tab.SYNC && MapPacketReceiver.isServerInstalled()) {
            VoxySyncClient.requestCapability();
        }
        updateDynamicButtons();
        updateHelpAreas();
    }

    private void addTabButton(Tab tab, int x, int y, int width) {
        Button button = Button.builder(tab.title(), b -> {
            activeTab = tab;
            rebuildGui();
            if (activeTab == Tab.ADMIN) {
                AdminStatusClientState.requestNow();
                lastAdminPoll = System.currentTimeMillis();
            }
        }).bounds(x, y, width, 20).build();
        button.active = activeTab != tab;
        button.setTooltip(Tooltip.create(Component.translatable(tab.tooltipKey())));
        button.setTooltipDelay(TOOLTIP_DELAY);
        addRenderableWidget(button);
    }

    private void buildSyncWidgets(int panelLeft, int panelTop) {
        int x = contentLeft();
        int y = syncButtonY();
        int gap = 10;
        int buttonWidth = Math.max(1, (contentWidth() - gap) / 2);
        syncCurrentButton = addRenderableWidget(Button.builder(
                Component.translatable("mapsyncer.gui.sync.current"),
                b -> MapSyncerCommand.sendSyncRequest(Minecraft.getInstance(), currentDimensionId(), false)
        ).bounds(x, y, buttonWidth, 22).build());
        syncCurrentButton.setTooltip(tooltip("mapsyncer.gui.help.sync.current"));
        syncCurrentButton.setTooltipDelay(TOOLTIP_DELAY);
        syncAllButton = addRenderableWidget(Button.builder(
                Component.translatable("mapsyncer.gui.sync.all"),
                b -> MapSyncerCommand.sendSyncRequest(Minecraft.getInstance(), "all", true)
        ).bounds(x + buttonWidth + gap, y, buttonWidth, 22).build());
        syncAllButton.setTooltip(tooltip("mapsyncer.gui.help.sync.all"));
        syncAllButton.setTooltipDelay(TOOLTIP_DELAY);

        int radiusY = y + 28;
        int stepWidth = 32;
        int valueWidth = Math.min(96, Math.max(72, buttonWidth / 2));
        int radiusButtonWidth = contentWidth() - stepWidth * 2 - valueWidth - gap * 3;
        addRenderableWidget(Button.builder(Component.literal("-"), b -> {
            radiusSyncBlocks = Math.max(128, radiusSyncBlocks - 128);
            updateSettingsButtonMessages();
        }).bounds(x, radiusY, stepWidth, 22).build());
        attachLastWidgetTooltip("mapsyncer.gui.help.sync.radius_minus");
        radiusValueButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
        }).bounds(x + stepWidth + gap, radiusY, valueWidth, 22).build());
        radiusValueButton.active = false;
        radiusValueButton.setTooltip(tooltip("mapsyncer.gui.help.sync.radius_value"));
        radiusValueButton.setTooltipDelay(TOOLTIP_DELAY);
        addRenderableWidget(Button.builder(Component.literal("+"), b -> {
            radiusSyncBlocks = Math.min(100_000, radiusSyncBlocks + 128);
            updateSettingsButtonMessages();
        }).bounds(x + stepWidth + gap + valueWidth + gap, radiusY, stepWidth, 22).build());
        attachLastWidgetTooltip("mapsyncer.gui.help.sync.radius_plus");
        radiusSyncButton = addRenderableWidget(Button.builder(
                Component.translatable("mapsyncer.gui.sync.radius"),
                b -> MapSyncerCommand.sendRadiusSyncRequest(Minecraft.getInstance(), radiusSyncBlocks)
        ).bounds(x + stepWidth * 2 + valueWidth + gap * 3, radiusY,
                Math.max(96, radiusButtonWidth), 22).build());
        radiusSyncButton.setTooltip(tooltip("mapsyncer.gui.help.sync.radius_action"));
        radiusSyncButton.setTooltipDelay(TOOLTIP_DELAY);
        publicWaypointsButton = addRenderableWidget(Button.builder(
                Component.translatable("mapsyncer.gui.waypoints.sync"),
                b -> PublicWaypointReceiver.requestManualSync()
        ).bounds(x, y + 56, contentWidth(), 22).build());
        publicWaypointsButton.setTooltip(tooltip("mapsyncer.gui.help.sync.waypoints"));
        publicWaypointsButton.setTooltipDelay(TOOLTIP_DELAY);
        voxyButton = addRenderableWidget(Button.builder(
                Component.translatable("mapsyncer.gui.voxy.sync_current"),
                b -> VoxySyncClient.startCurrentDimensionSync(Minecraft.getInstance())
        ).bounds(x, y + 84, contentWidth(), 22).build());
        voxyButton.setTooltip(tooltip("mapsyncer.gui.help.sync.voxy"));
        voxyButton.setTooltipDelay(TOOLTIP_DELAY);
    }

    private void buildAdminWidgets(int panelLeft, int panelTop) {
        int x = contentLeft();
        int y = panelTop + 94;
        int contentWidth = contentWidth();
        int gap = 8;

        dimensionBox = addRenderableWidget(new EditBox(font, x, y, Math.min(238, contentWidth), 20,
                Component.translatable("mapsyncer.gui.admin.dimension")));
        dimensionBox.setMaxLength(128);
        dimensionBox.setValue(currentDimensionId());
        dimensionBox.setTooltip(tooltip("mapsyncer.gui.help.admin.dimension"));
        dimensionBox.setTooltipDelay(TOOLTIP_DELAY);

        if (contentWidth >= 470) {
            int buttonWidth = Math.max(1, (contentWidth - gap * 2) / 3);
            incrementalButton = addRenderableWidget(Button.builder(
                    Component.translatable("mapsyncer.gui.admin.incremental"),
                    b -> {
                        sendServerCommand("mapsyncer incremental run");
                        AdminStatusClientState.requestNow();
                        lastAdminPoll = System.currentTimeMillis();
                    }
            ).bounds(x, y + 34, buttonWidth, 22).build());
            incrementalButton.setTooltip(tooltip("mapsyncer.gui.help.admin.incremental"));
            incrementalButton.setTooltipDelay(TOOLTIP_DELAY);

            forceButton = addRenderableWidget(Button.builder(
                    Component.translatable("mapsyncer.gui.admin.force"),
                    b -> {
                        String dimension = sanitizeDimensionInput(dimensionBox.getValue());
                        sendServerCommand("mapsyncer generate " + dimension + " force");
                        AdminStatusClientState.requestNow();
                        lastAdminPoll = System.currentTimeMillis();
                    }
            ).bounds(x + buttonWidth + gap, y + 34, buttonWidth, 22).build());
            forceButton.setTooltip(tooltip("mapsyncer.gui.help.admin.force"));
            forceButton.setTooltipDelay(TOOLTIP_DELAY);

            addRenderableWidget(Button.builder(
                    Component.translatable("mapsyncer.gui.admin.refresh"),
                    b -> {
                        AdminStatusClientState.requestNow();
                        lastAdminPoll = System.currentTimeMillis();
                    }
            ).bounds(x + (buttonWidth + gap) * 2, y + 34, buttonWidth, 22).build());
            attachLastWidgetTooltip("mapsyncer.gui.help.admin.refresh");
        } else {
            int buttonWidth = Math.max(1, (contentWidth - gap) / 2);
            incrementalButton = addRenderableWidget(Button.builder(
                Component.translatable("mapsyncer.gui.admin.incremental"),
                b -> {
                    sendServerCommand("mapsyncer incremental run");
                    AdminStatusClientState.requestNow();
                    lastAdminPoll = System.currentTimeMillis();
                }
            ).bounds(x, y + 34, buttonWidth, 22).build());
            incrementalButton.setTooltip(tooltip("mapsyncer.gui.help.admin.incremental"));
            incrementalButton.setTooltipDelay(TOOLTIP_DELAY);

            forceButton = addRenderableWidget(Button.builder(
                Component.translatable("mapsyncer.gui.admin.force"),
                b -> {
                    String dimension = sanitizeDimensionInput(dimensionBox.getValue());
                    sendServerCommand("mapsyncer generate " + dimension + " force");
                    AdminStatusClientState.requestNow();
                    lastAdminPoll = System.currentTimeMillis();
                }
            ).bounds(x + buttonWidth + gap, y + 34, buttonWidth, 22).build());
            forceButton.setTooltip(tooltip("mapsyncer.gui.help.admin.force"));
            forceButton.setTooltipDelay(TOOLTIP_DELAY);

            addRenderableWidget(Button.builder(
                Component.translatable("mapsyncer.gui.admin.refresh"),
                b -> {
                    AdminStatusClientState.requestNow();
                    lastAdminPoll = System.currentTimeMillis();
                }
            ).bounds(x, y + 62, buttonWidth, 22).build());
            attachLastWidgetTooltip("mapsyncer.gui.help.admin.refresh");
        }

        int settingsY = y + (contentWidth >= 470 ? 68 : 92);
        radiusEnabledButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            PacketHandler.AdminStatusPayload status = AdminStatusClientState.getLastStatus();
            if (status != null) {
                AdminStatusClientState.setDraftRadiusEnabled(!status.radiusSyncEnabled());
            }
            updateDynamicButtons();
        }).bounds(x, settingsY, 94, 20).build());
        radiusEnabledButton.setTooltip(tooltip("mapsyncer.gui.help.admin.radius_enabled"));
        radiusEnabledButton.setTooltipDelay(TOOLTIP_DELAY);
        radiusModeButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            adminRadiusMode = switch (adminRadiusMode) {
                case "PLAYER_POSITION" -> "WORLD_SPAWN";
                case "WORLD_SPAWN" -> "FIXED";
                default -> "PLAYER_POSITION";
            };
            AdminStatusClientState.setDraftRadiusMode(adminRadiusMode);
            updateDynamicButtons();
        }).bounds(x + 102, settingsY, 132, 20).build());
        radiusModeButton.setTooltip(tooltip("mapsyncer.gui.help.admin.radius_mode"));
        radiusModeButton.setTooltipDelay(TOOLTIP_DELAY);
        addRenderableWidget(Button.builder(Component.literal("-"), b -> {
            adminRadiusMaxBlocks = Math.max(128, adminRadiusMaxBlocks - 128);
            AdminStatusClientState.setDraftMaxRadius(adminRadiusMaxBlocks);
            updateDynamicButtons();
        }).bounds(x + 242, settingsY, 28, 20).build());
        attachLastWidgetTooltip("mapsyncer.gui.help.admin.radius_max_minus");
        radiusMaxValueButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
        }).bounds(x + 274, settingsY, 74, 20).build());
        radiusMaxValueButton.active = false;
        radiusMaxValueButton.setTooltip(tooltip("mapsyncer.gui.help.admin.radius_max_value"));
        radiusMaxValueButton.setTooltipDelay(TOOLTIP_DELAY);
        addRenderableWidget(Button.builder(Component.literal("+"), b -> {
            adminRadiusMaxBlocks = Math.min(100_000, adminRadiusMaxBlocks + 128);
            AdminStatusClientState.setDraftMaxRadius(adminRadiusMaxBlocks);
            updateDynamicButtons();
        }).bounds(x + 352, settingsY, 28, 20).build());
        attachLastWidgetTooltip("mapsyncer.gui.help.admin.radius_max_plus");
        radiusSaveButton = addRenderableWidget(Button.builder(
                Component.translatable("mapsyncer.gui.admin.save_radius"),
                b -> AdminStatusClientState.sendSettingsUpdate()
        ).bounds(x + 388, settingsY, Math.max(96, contentWidth - 388), 20).build());
        radiusSaveButton.setTooltip(tooltip("mapsyncer.gui.help.admin.save_radius"));
        radiusSaveButton.setTooltipDelay(TOOLTIP_DELAY);

        int importY = adminImportY();
        scanLocalWaypointsButton = addRenderableWidget(Button.builder(
                Component.translatable("mapsyncer.gui.admin.waypoints.import.scan"),
                b -> LocalXaeroWaypointScanner.scanAsync()
        ).bounds(x, importY, Math.min(168, contentWidth), 20).build());
        scanLocalWaypointsButton.setTooltip(tooltip("mapsyncer.gui.help.admin.waypoints_import_scan"));
        scanLocalWaypointsButton.setTooltipDelay(TOOLTIP_DELAY);

        localWaypointList = new LocalWaypointList(minecraft, x, adminWaypointListY(), contentWidth,
                adminWaypointListHeight());
        addRenderableWidget(localWaypointList);
        refreshLocalWaypointList(true);

        AdminStatusClientState.requestNow();
        lastAdminPoll = System.currentTimeMillis();
    }

    private void buildSettingsWidgets(int panelLeft, int panelTop) {
        int x = contentLeft();
        int y = settingsStartY();
        int controlWidth = Math.min(130, Math.max(104, contentWidth() / 3));
        int controlX = contentRight() - controlWidth;
        int stepperWidth = Math.min(130, Math.max(122, controlWidth));
        int stepX = contentRight() - stepperWidth;
        int valueWidth = stepperWidth - 76;

        autoSyncButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            ClientConfig.VALUES.autoSyncOnJoin = !ClientConfig.VALUES.autoSyncOnJoin;
            ClientConfig.save();
            updateSettingsButtonMessages();
        }).bounds(controlX, y, controlWidth, 20).build());
        autoSyncButton.setTooltip(tooltip("mapsyncer.gui.help.settings.auto_sync"));
        autoSyncButton.setTooltipDelay(TOOLTIP_DELAY);

        hudButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
            ClientConfig.VALUES.showSyncHud = !ClientConfig.VALUES.showSyncHud;
            ClientConfig.save();
            updateSettingsButtonMessages();
        }).bounds(controlX, y + settingsRowGap(), controlWidth, 20).build());
        hudButton.setTooltip(tooltip("mapsyncer.gui.help.settings.hud"));
        hudButton.setTooltipDelay(TOOLTIP_DELAY);

        addRenderableWidget(Button.builder(Component.literal("-"), b -> {
            ClientConfig.VALUES.autoSyncDelaySeconds = Math.max(1, ClientConfig.VALUES.autoSyncDelaySeconds - 1);
            ClientConfig.save();
            updateSettingsButtonMessages();
        }).bounds(stepX, y + settingsRowGap() * 2, 32, 20).build());
        attachLastWidgetTooltip("mapsyncer.gui.help.settings.delay_minus");
        delayValueButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
        }).bounds(stepX + 38, y + settingsRowGap() * 2, valueWidth, 20).build());
        delayValueButton.active = false;
        delayValueButton.setTooltip(tooltip("mapsyncer.gui.help.settings.delay_value"));
        delayValueButton.setTooltipDelay(TOOLTIP_DELAY);
        addRenderableWidget(Button.builder(Component.literal("+"), b -> {
            ClientConfig.VALUES.autoSyncDelaySeconds = Math.min(60, ClientConfig.VALUES.autoSyncDelaySeconds + 1);
            ClientConfig.save();
            updateSettingsButtonMessages();
        }).bounds(stepX + 44 + valueWidth, y + settingsRowGap() * 2, 32, 20).build());
        attachLastWidgetTooltip("mapsyncer.gui.help.settings.delay_plus");

        addRenderableWidget(Button.builder(Component.literal("-"), b -> {
            ClientConfig.VALUES.syncProgressChatIntervalPercent = Math.max(0,
                    ClientConfig.VALUES.syncProgressChatIntervalPercent - 5);
            ClientConfig.save();
            updateSettingsButtonMessages();
        }).bounds(stepX, y + settingsRowGap() * 3, 32, 20).build());
        attachLastWidgetTooltip("mapsyncer.gui.help.settings.chat_minus");
        chatIntervalValueButton = addRenderableWidget(Button.builder(Component.empty(), b -> {
        }).bounds(stepX + 38, y + settingsRowGap() * 3, valueWidth, 20).build());
        chatIntervalValueButton.active = false;
        chatIntervalValueButton.setTooltip(tooltip("mapsyncer.gui.help.settings.chat_value"));
        chatIntervalValueButton.setTooltipDelay(TOOLTIP_DELAY);
        addRenderableWidget(Button.builder(Component.literal("+"), b -> {
            ClientConfig.VALUES.syncProgressChatIntervalPercent = Math.min(100,
                    ClientConfig.VALUES.syncProgressChatIntervalPercent + 5);
            ClientConfig.save();
            updateSettingsButtonMessages();
        }).bounds(stepX + 44 + valueWidth, y + settingsRowGap() * 3, 32, 20).build());
        attachLastWidgetTooltip("mapsyncer.gui.help.settings.chat_plus");

        updateSettingsButtonMessages();
    }

    private void drawShell(GuiGraphicsExtractor graphics) {
        int left = panelLeft();
        int top = panelTop();
        graphics.fill(left, top, left + panelWidth(), top + panelHeight(), COLOR_PANEL);
        graphics.outline(left, top, panelWidth(), panelHeight(), COLOR_BORDER);
        graphics.fill(left + 1, top + 1, left + panelWidth() - 1, top + 26, 0x66212A34);
        graphics.text(font, title, contentLeft(), top + 10, 0xFFFFFFFF, false);
        drawRoleBadge(graphics, left + panelWidth() - panelMargin() - roleBadgeWidth(), top + 8);
        graphics.fill(left + 12, top + 58, left + panelWidth() - 12, top + 59, 0x664C8BA8);
    }

    private void drawSyncTab(GuiGraphicsExtractor graphics) {
        int x = contentLeft();
        int y = syncTextY();
        drawSection(graphics, x, y - 18, contentWidth(), 48,
                Component.translatable("mapsyncer.gui.section.connection"));
        boolean installed = MapPacketReceiver.isServerInstalled();
        Component serverStatus = installed
                ? Component.translatable("mapsyncer.gui.sync.server_installed", MapPacketReceiver.getServerVersion())
                : Component.translatable("mapsyncer.gui.sync.server_missing");
        graphics.text(font, Component.translatable("mapsyncer.gui.sync.dimension", currentDimensionId()), x + 8, y, COLOR_TEXT, false);
        graphics.text(font, serverStatus, x + 8, y + 16, installed ? COLOR_SUCCESS : COLOR_WARN, false);

        drawSection(graphics, x, syncButtonY() - 18, contentWidth(), 118,
                Component.translatable("mapsyncer.gui.section.map_sync"));
        drawSection(graphics, x, syncVoxyY() - 34, contentWidth(), Math.max(76, panelTop() + panelHeight() - syncVoxyY() + 24),
                Component.translatable("mapsyncer.gui.section.extras"));

        int barY = syncBarY();
        int barWidth = contentWidth();
        int percent = visibleSyncPercent();
        graphics.text(font, Component.translatable("mapsyncer.gui.sync.progress",
                SyncProgressTracker.getProcessed(), SyncProgressTracker.getTotal(), percent), x + 8, barY - 12, COLOR_TEXT, false);
        graphics.fill(x, barY, x + barWidth, barY + 8, 0xFF2E3740);
        graphics.fill(x, barY, x + (barWidth * percent) / 100, barY + 8, COLOR_ACCENT);
        graphics.outline(x, barY, barWidth, 8, 0xFF6B7785);
        if (!SyncProgressTracker.getStatus().isBlank()) {
            drawSingleLine(graphics, Component.literal(SyncProgressTracker.getStatus()), x + 8, barY + 14,
                    contentWidth() - 16, COLOR_SUBTLE);
        }

        int voxyY = syncVoxyY();
        PublicWaypointClientState.Status waypointStatus = PublicWaypointClientState.getStatus();
        Component waypointText = Component.translatable("mapsyncer.gui.waypoints.status",
                Component.translatable("mapsyncer.gui.waypoints.status." + waypointStatus.name().toLowerCase()).getString(),
                PublicWaypointClientState.getCount());
        drawSingleLine(graphics, waypointText, x + 8, voxyY - 18, contentWidth() - 16, COLOR_SUBTLE);

        String voxyReason = VoxySyncClient.getUnavailableReason(minecraft);
        Component voxyReasonText = switch (voxyReason) {
            case "" -> Component.translatable("mapsyncer.gui.voxy.reason.enabled");
            case "not_installed", "not_enabled", "server_disabled", "no_connection", "syncing",
                    "busy", "unknown", "region_dir_missing", "dimension_changed", "interrupted",
                    "failed", "completed", "client_io_failed", "import_busy", "import_failed",
                    "sending" -> Component.translatable("mapsyncer.gui.voxy.reason." + voxyReason);
            default -> Component.literal(voxyReason);
        };
        Component voxyStatus = Component.translatable("mapsyncer.gui.voxy.status", voxyReasonText.getString());
        drawSingleLine(graphics, voxyStatus, x + 8, voxyY, contentWidth() - 16, voxyReason.isBlank() ? COLOR_SUCCESS : COLOR_SUBTLE);

        int voxyPercent = visibleVoxyPercent();
        graphics.text(font, Component.translatable("mapsyncer.gui.voxy.progress",
                VoxySyncClient.getProcessedRegions(), VoxySyncClient.getTotalRegions(), voxyPercent),
                x + 8, voxyY + 14, COLOR_TEXT, false);
        graphics.fill(x, voxyY + 28, x + barWidth, voxyY + 36, 0xFF2E3740);
        graphics.fill(x, voxyY + 28, x + (barWidth * voxyPercent) / 100, voxyY + 36, 0xFFB089FF);
        graphics.outline(x, voxyY + 28, barWidth, 8, 0xFF6B7785);

        String voxyText = VoxySyncClient.getDisplayStatus();
        if (!voxyText.isBlank() && voxyY + 52 <= panelTop() + panelHeight() - 4) {
            drawSingleLine(graphics, Component.literal(voxyText), x + 8, voxyY + 42, contentWidth() - 16, COLOR_SUBTLE);
        }

        int noteY = voxyY + 58;
        if (panelHeight() >= 270 && noteY + 18 <= panelTop() + panelHeight() - 8) {
            graphics.textWithWordWrap(font, Component.translatable("mapsyncer.gui.voxy.note"),
                    x + 8, noteY, contentWidth() - 16, COLOR_MUTED);
        }
    }

    private void drawAdminTab(GuiGraphicsExtractor graphics) {
        int x = contentLeft();
        int y = panelTop() + 72;
        drawSection(graphics, x, y - 8, contentWidth(), 82,
                Component.translatable("mapsyncer.gui.section.admin_actions"));
        graphics.text(font, Component.translatable("mapsyncer.gui.admin.dimension_label"), x + 8, y + 10, COLOR_TEXT, false);

        int settingsY = panelTop() + 94 + (contentWidth() >= 470 ? 68 : 92);
        drawSection(graphics, x, settingsY - 18, contentWidth(), 44,
                Component.translatable("mapsyncer.gui.section.radius_policy"));
        graphics.textWithWordWrap(font, Component.translatable("mapsyncer.gui.admin.warning"), x + 8,
                settingsY + 30, contentWidth() - 16, COLOR_WARN);

        int importY = adminImportY();
        drawSection(graphics, x, importY - 18, contentWidth(), adminWaypointListHeight() + 54,
                Component.translatable("mapsyncer.gui.section.waypoint_import"));
        drawSingleLine(graphics, waypointImportStatusText(), x + 184, importY + 5,
                Math.max(40, contentWidth() - 192), waypointImportStatusColor());

        int statusY = adminStatusY();
        drawSection(graphics, x, statusY - 18, contentWidth(), panelTop() + panelHeight() - statusY - 8,
                Component.translatable("mapsyncer.gui.section.server_status"));
        String error = AdminStatusClientState.getLastError();
        PacketHandler.AdminStatusPayload status = AdminStatusClientState.getLastStatus();
        if (!error.isBlank()) {
            graphics.text(font, Component.translatable("mapsyncer.gui.admin.status_" + error), x + 8, statusY, COLOR_ERROR, false);
            return;
        }
        if (status == null) {
            graphics.text(font, Component.translatable("mapsyncer.gui.admin.status_waiting"), x + 8, statusY, COLOR_SUBTLE, false);
            return;
        }
        if (!status.allowed()) {
            graphics.text(font, Component.translatable("mapsyncer.gui.admin.no_permission"), x + 8, statusY, COLOR_ERROR, false);
            return;
        }

        int percent = status.total() > 0 ? Math.min(100, status.processed() * 100 / status.total()) : 0;
        drawSingleLine(graphics, Component.translatable("mapsyncer.gui.admin.status_line",
                status.running() ? Component.translatable("mapsyncer.gui.admin.running") : Component.translatable("mapsyncer.gui.admin.idle"),
                status.processed(), status.total(), percent, status.status()), x + 8, statusY, contentWidth() - 16, COLOR_TEXT);
        String speedLimit = status.syncSpeedLimitKBps() <= 0
                ? Component.translatable("mapsyncer.gui.admin.unlimited").getString()
                : status.syncSpeedLimitKBps() + " KB/s";
        int columnWidth = Math.max(120, (contentWidth() - 24) / 2);
        int rightColumnX = x + 16 + columnWidth;
        drawSingleLine(graphics, Component.translatable("mapsyncer.gui.admin.dirty", status.dirtyCount()), x + 8, statusY + 16, columnWidth, COLOR_SUBTLE);
        drawSingleLine(graphics, Component.translatable("mapsyncer.gui.admin.cache",
                status.cacheDimensionCount(), status.cacheRegionCount(), status.cacheSizeBytes() / (1024.0 * 1024.0)), x + 8, statusY + 30, columnWidth, COLOR_SUBTLE);
        drawSingleLine(graphics, Component.translatable("mapsyncer.gui.admin.limit", speedLimit), rightColumnX, statusY + 16, columnWidth, COLOR_SUBTLE);
        drawSingleLine(graphics, Component.translatable("mapsyncer.gui.admin.incremental_status", status.incrementalStatus()), rightColumnX, statusY + 30, columnWidth, COLOR_SUBTLE);
        drawSingleLine(graphics, Component.translatable("mapsyncer.gui.admin.radius_status",
                status.radiusSyncEnabled() ? Component.translatable("mapsyncer.gui.settings.on").getString()
                        : Component.translatable("mapsyncer.gui.settings.off").getString(),
                status.maxRadiusSyncBlocks(), status.radiusSyncCenterMode()), x + 8, statusY + 48, contentWidth() - 16, COLOR_SUBTLE);
        String waypointHash = status.publicWaypointsHash().isBlank()
                ? "-"
                : status.publicWaypointsHash().substring(0, Math.min(8, status.publicWaypointsHash().length()));
        drawSingleLine(graphics, Component.translatable("mapsyncer.gui.admin.waypoints",
                status.publicWaypointsEnabled() ? Component.translatable("mapsyncer.gui.settings.on").getString()
                        : Component.translatable("mapsyncer.gui.settings.off").getString(),
                status.publicWaypointsGroup(), status.publicWaypointsCount(), waypointHash),
                x + 8, statusY + 62, contentWidth() - 16, COLOR_SUBTLE);
    }

    private void refreshLocalWaypointList(boolean force) {
        if (localWaypointList == null) {
            return;
        }
        long updatedAt = PublicWaypointImportClientState.getUpdatedAtMillis();
        if (!force && updatedAt == lastWaypointListUpdate) {
            return;
        }
        localWaypointList.setWaypoints(PublicWaypointImportClientState.getWaypoints());
        lastWaypointListUpdate = updatedAt;
    }

    private Component waypointImportStatusText() {
        PublicWaypointImportClientState.Status status = PublicWaypointImportClientState.getStatus();
        return switch (status) {
            case READY -> Component.translatable("mapsyncer.gui.admin.waypoints.import.status.ready",
                    PublicWaypointImportClientState.getWaypoints().size(),
                    PublicWaypointImportClientState.getSkippedCount());
            case FAILED -> Component.translatable("mapsyncer.gui.admin.waypoints.import.status.failed",
                    PublicWaypointImportClientState.getDetail());
            case ADDING -> Component.translatable("mapsyncer.gui.admin.waypoints.import.status.adding",
                    PublicWaypointImportClientState.getDetail());
            case ADDED -> Component.translatable("mapsyncer.gui.admin.waypoints.import.status.added",
                    PublicWaypointImportClientState.getDetail());
            case UPDATED -> Component.translatable("mapsyncer.gui.admin.waypoints.import.status.updated",
                    PublicWaypointImportClientState.getDetail());
            default -> Component.translatable("mapsyncer.gui.admin.waypoints.import.status."
                    + status.name().toLowerCase());
        };
    }

    private int waypointImportStatusColor() {
        return switch (PublicWaypointImportClientState.getStatus()) {
            case READY, ADDED, UPDATED -> COLOR_SUCCESS;
            case NO_FILE, EMPTY, PERMISSION_DENIED -> COLOR_WARN;
            case FAILED -> COLOR_ERROR;
            default -> COLOR_SUBTLE;
        };
    }

    private void submitLocalWaypoint(PacketHandler.PublicWaypoint waypoint) {
        if (waypoint == null || !ClientPlayNetworking.canSend(PacketHandler.PublicWaypointAddPayload.TYPE)) {
            PublicWaypointImportClientState.handleAddResult(
                    new PacketHandler.PublicWaypointAddResultPayload("failed", waypoint == null ? "" : waypoint.name()));
            return;
        }
        PublicWaypointImportClientState.setAdding(waypoint);
        ClientPlayNetworking.send(new PacketHandler.PublicWaypointAddPayload(waypoint));
    }

    private void drawSettingsTab(GuiGraphicsExtractor graphics) {
        int x = contentLeft();
        int y = settingsStartY();
        drawSection(graphics, x, y - 20, contentWidth(), panelTop() + panelHeight() - y - 8,
                Component.translatable("mapsyncer.gui.section.client_settings"));
        int textWidth = Math.max(118, contentRight() - x - Math.min(130, Math.max(104, contentWidth() / 3)) - 12);
        int rowGap = settingsRowGap();
        drawSetting(graphics, "mapsyncer.gui.settings.auto_sync", "mapsyncer.gui.settings.auto_sync.desc", x + 8, y, textWidth);
        drawSetting(graphics, "mapsyncer.gui.settings.hud", "mapsyncer.gui.settings.hud.desc", x + 8, y + rowGap, textWidth);
        drawSetting(graphics, "mapsyncer.gui.settings.delay", "mapsyncer.gui.settings.delay.desc", x + 8, y + rowGap * 2, textWidth);
        drawSetting(graphics, "mapsyncer.gui.settings.chat", "mapsyncer.gui.settings.chat.desc", x + 8, y + rowGap * 3, textWidth);
        int noteY = y + rowGap * 4 + 4;
        if (noteY + 24 < panelTop() + panelHeight()) {
            graphics.textWithWordWrap(font, Component.translatable("mapsyncer.gui.settings.server_limit_note"),
                    x + 8, noteY, contentWidth() - 16, COLOR_SUBTLE);
        }
    }

    private void drawSetting(GuiGraphicsExtractor graphics, String titleKey, String descKey, int x, int y, int textWidth) {
        graphics.text(font, Component.translatable(titleKey), x, y + 2, COLOR_TEXT, false);
        graphics.textWithWordWrap(font, Component.translatable(descKey), x, y + 16, textWidth, COLOR_MUTED);
    }

    private void drawSingleLine(GuiGraphicsExtractor graphics, Component text, int x, int y, int maxWidth, int color) {
        String value = text.getString();
        if (font.width(value) <= maxWidth) {
            graphics.text(font, text, x, y, color, false);
            return;
        }
        String ellipsis = "...";
        int limit = Math.max(1, maxWidth - font.width(ellipsis));
        String trimmed = font.plainSubstrByWidth(value, limit) + ellipsis;
        graphics.text(font, Component.literal(trimmed), x, y, color, false);
    }

    private void drawRoleBadge(GuiGraphicsExtractor graphics, int x, int y) {
        boolean owner = isOwner();
        Component label = Component.translatable(owner ? "mapsyncer.gui.role.op" : "mapsyncer.gui.role.player");
        int width = roleBadgeWidth();
        graphics.fill(x, y, x + width, y + 18, owner ? 0xFF2E4A36 : 0xFF2B3742);
        graphics.outline(x, y, width, 18, owner ? COLOR_SUCCESS : COLOR_BORDER);
        graphics.text(font, label, x + 6, y + 5, 0xFFFFFFFF, false);
    }

    private int roleBadgeWidth() {
        return isOwner() ? 92 : 78;
    }

    private void drawSection(GuiGraphicsExtractor graphics, int x, int y, int width, int height, Component title) {
        graphics.text(font, title, x, y + 4, COLOR_TEXT, false);
        graphics.fill(x, y + 14, x + width, y + 15, 0x55365260);
    }

    private Tooltip tooltip(String key) {
        return Tooltip.create(Component.translatable(key));
    }

    private void attachLastWidgetTooltip(String key) {
        List<? extends AbstractWidget> widgets = children().stream()
                .filter(AbstractWidget.class::isInstance)
                .map(AbstractWidget.class::cast)
                .toList();
        if (widgets.isEmpty()) {
            return;
        }
        AbstractWidget widget = widgets.get(widgets.size() - 1);
        widget.setTooltip(tooltip(key));
        widget.setTooltipDelay(TOOLTIP_DELAY);
    }

    private void updateHelpAreas() {
        frameHelpAreas.clear();
        switch (activeTab) {
            case SYNC -> {
                frameHelpAreas.add(new HelpArea(leftRoleBadgeX(), panelTop() + 8, roleBadgeWidth(), 18,
                        Component.translatable("mapsyncer.gui.help.role")));
                frameHelpAreas.add(new HelpArea(contentLeft(), syncTextY() - 20, contentWidth(), 38,
                        Component.translatable("mapsyncer.gui.help.sync.connection")));
                frameHelpAreas.add(new HelpArea(contentLeft(), syncButtonY() - 18, contentWidth(), 118,
                        Component.translatable("mapsyncer.gui.help.sync.map_section")));
                frameHelpAreas.add(new HelpArea(contentLeft(), syncVoxyY() - 34, contentWidth(),
                        Math.max(76, panelTop() + panelHeight() - syncVoxyY() + 24),
                        Component.translatable("mapsyncer.gui.help.sync.extras_section")));
            }
            case ADMIN -> {
                int importY = adminImportY();
                int statusY = adminStatusY();
                frameHelpAreas.add(new HelpArea(contentLeft(), panelTop() + 64, contentWidth(), 90,
                        Component.translatable("mapsyncer.gui.help.admin.actions_section")));
                frameHelpAreas.add(new HelpArea(contentLeft(), panelTop() + 94 + (contentWidth() >= 470 ? 68 : 92) - 18, contentWidth(), 44,
                        Component.translatable("mapsyncer.gui.help.admin.radius_section")));
                frameHelpAreas.add(new HelpArea(contentLeft(), importY - 18, contentWidth(),
                        adminWaypointListHeight() + 54,
                        Component.translatable("mapsyncer.gui.help.admin.waypoints_import_section")));
                frameHelpAreas.add(new HelpArea(contentLeft(), statusY - 18, contentWidth(),
                        panelTop() + panelHeight() - statusY - 8,
                        Component.translatable("mapsyncer.gui.help.admin.status_section")));
                frameHelpAreas.add(new HelpArea(leftRoleBadgeX(), panelTop() + 8, roleBadgeWidth(),
                        18, Component.translatable("mapsyncer.gui.help.role")));
            }
            case SETTINGS -> frameHelpAreas.add(new HelpArea(contentLeft(), settingsStartY() - 20, contentWidth(),
                    panelTop() + panelHeight() - settingsStartY() - 8,
                    Component.translatable("mapsyncer.gui.help.settings.section")));
        }
    }

    private void renderTooltipAreas(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        for (var child : children()) {
            if (child instanceof AbstractWidget widget && widget.isMouseOver(mouseX, mouseY)) {
                return;
            }
        }
        for (HelpArea area : frameHelpAreas) {
            if (area.contains(mouseX, mouseY)) {
                graphics.setTooltipForNextFrame(font, area.tooltip(), mouseX, mouseY);
                break;
            }
        }
    }

    private int leftRoleBadgeX() {
        return panelLeft() + panelWidth() - panelMargin() - roleBadgeWidth();
    }

    private record HelpArea(int x, int y, int width, int height, Component tooltip) {
        boolean contains(int mouseX, int mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }
    }

    private void updateDynamicButtons() {
        boolean canSync = minecraft != null && minecraft.player != null && minecraft.level != null
                && MapPacketReceiver.isServerInstalled() && !MapPacketReceiver.isSyncInProgress();
        if (syncCurrentButton != null) {
            syncCurrentButton.active = canSync;
        }
        if (syncAllButton != null) {
            syncAllButton.active = canSync;
        }
        if (radiusSyncButton != null) {
            radiusSyncButton.active = canSync && ClientPlayNetworking.canSend(PacketHandler.RadiusSyncRequestPayload.TYPE);
        }
        if (publicWaypointsButton != null) {
            publicWaypointsButton.active = minecraft != null && minecraft.player != null
                    && ClientPlayNetworking.canSend(PacketHandler.PublicWaypointsRequestPayload.TYPE);
        }
        if (voxyButton != null) {
            if (MapPacketReceiver.isServerInstalled()) {
                VoxySyncClient.requestCapability();
            }
            voxyButton.active = VoxySyncClient.canStart(minecraft);
        }
        boolean canAdminAction = isOwner() && minecraft != null && minecraft.player != null;
        if (incrementalButton != null) {
            incrementalButton.active = canAdminAction;
        }
        if (forceButton != null) {
            forceButton.active = canAdminAction && dimensionBox != null && !sanitizeDimensionInput(dimensionBox.getValue()).isBlank();
        }
        if (radiusSaveButton != null) {
            radiusSaveButton.active = canAdminAction && ClientPlayNetworking.canSend(PacketHandler.AdminSettingsUpdatePayload.TYPE);
        }
        if (scanLocalWaypointsButton != null) {
            scanLocalWaypointsButton.active = canAdminAction
                    && PublicWaypointImportClientState.getStatus() != PublicWaypointImportClientState.Status.SCANNING;
        }
        updateSettingsButtonMessages();
        updateHelpAreas();
    }

    private void updateSettingsButtonMessages() {
        if (autoSyncButton != null) {
            autoSyncButton.setMessage(toggleLabel(ClientConfig.VALUES.autoSyncOnJoin));
        }
        if (hudButton != null) {
            hudButton.setMessage(toggleLabel(ClientConfig.VALUES.showSyncHud));
        }
        if (delayValueButton != null) {
            delayValueButton.setMessage(Component.translatable("mapsyncer.gui.settings.seconds",
                    ClientConfig.VALUES.autoSyncDelaySeconds));
        }
        if (chatIntervalValueButton != null) {
            chatIntervalValueButton.setMessage(Component.translatable("mapsyncer.gui.settings.percent",
                    ClientConfig.VALUES.syncProgressChatIntervalPercent));
        }
        if (radiusValueButton != null) {
            radiusValueButton.setMessage(Component.translatable("mapsyncer.gui.sync.radius_value", radiusSyncBlocks));
        }
        PacketHandler.AdminStatusPayload status = AdminStatusClientState.getLastStatus();
        if (status != null && !AdminStatusClientState.hasDraft()) {
            adminRadiusMaxBlocks = status.maxRadiusSyncBlocks();
            adminRadiusMode = status.radiusSyncCenterMode();
        } else {
            adminRadiusMaxBlocks = AdminStatusClientState.getDraftMaxRadius(adminRadiusMaxBlocks);
            adminRadiusMode = AdminStatusClientState.getDraftRadiusMode(adminRadiusMode);
        }
        if (radiusEnabledButton != null) {
            boolean enabled = AdminStatusClientState.getDraftRadiusEnabled(status != null && status.radiusSyncEnabled());
            radiusEnabledButton.setMessage(toggleLabel(enabled));
        }
        if (radiusModeButton != null) {
            radiusModeButton.setMessage(Component.translatable("mapsyncer.gui.admin.radius_mode." + adminRadiusMode));
        }
        if (radiusMaxValueButton != null) {
            radiusMaxValueButton.setMessage(Component.literal(String.valueOf(adminRadiusMaxBlocks)));
        }
    }

    private Component toggleLabel(boolean enabled) {
        return Component.translatable(enabled ? "mapsyncer.gui.settings.on" : "mapsyncer.gui.settings.off");
    }

    private int visibleSyncPercent() {
        if (SyncProgressTracker.isTracking()) {
            return SyncProgressTracker.getPercent();
        }
        long completedAt = SyncProgressTracker.getCompletedAt();
        if (completedAt > 0 && System.currentTimeMillis() - completedAt <= COMPLETE_VISIBLE_MS) {
            return 100;
        }
        return 0;
    }

    private int visibleVoxyPercent() {
        if (VoxySyncClient.isSyncing()) {
            return VoxySyncClient.getPercent();
        }
        long completedAt = VoxySyncClient.getCompletedAt();
        if (completedAt > 0 && System.currentTimeMillis() - completedAt <= COMPLETE_VISIBLE_MS) {
            return 100;
        }
        return 0;
    }

    private boolean isOwner() {
        return minecraft != null && minecraft.player != null
                && Commands.LEVEL_OWNERS.check(minecraft.player.permissions());
    }

    private String currentDimensionId() {
        return MapSyncerCommand.currentDimensionId(Minecraft.getInstance());
    }

    private String sanitizeDimensionInput(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value.isBlank() ? currentDimensionId() : value.replace(" ", "");
    }

    private void sendServerCommand(String command) {
        // Straight to the server: these are server subcommands, and routing them through
        // the client dispatcher would re-enter this mod's own /mapsyncer handler.
        MapSyncerCommand.sendToServer(minecraft, command);
    }

    private int panelWidth() {
        return Math.min(PANEL_WIDTH, Math.max(1, width - 32));
    }

    private int panelHeight() {
        return Math.min(PANEL_HEIGHT, Math.max(1, height - 32));
    }

    private int panelLeft() {
        return (width - panelWidth()) / 2;
    }

    private int panelTop() {
        return Math.max(16, (height - panelHeight()) / 2);
    }

    private int panelMargin() {
        int target = panelWidth() < 430 ? 20 : 28;
        return Math.max(1, Math.min(target, panelWidth() / 8));
    }

    private int contentLeft() {
        return panelLeft() + panelMargin();
    }

    private int contentRight() {
        return panelLeft() + panelWidth() - panelMargin();
    }

    private int contentWidth() {
        return contentRight() - contentLeft();
    }

    private int settingsStartY() {
        return panelTop() + (panelHeight() < 250 ? 72 : 88);
    }

    private int settingsRowGap() {
        return panelHeight() < 250 ? 40 : 48;
    }

    private int syncTextY() {
        return panelTop() + (panelHeight() < 270 ? 66 : 72);
    }

    private int syncButtonY() {
        return panelTop() + (panelHeight() < 270 ? 94 : 104);
    }

    private int syncBarY() {
        return syncButtonY() + (panelHeight() < 270 ? 110 : 116);
    }

    private int syncVoxyY() {
        return syncBarY() + (panelHeight() < 270 ? 34 : 32);
    }

    private int adminImportY() {
        return panelTop() + 190;
    }

    private int adminWaypointListY() {
        return adminImportY() + 26;
    }

    private int adminWaypointListHeight() {
        return Math.max(72, Math.min(122, adminStatusY() - adminWaypointListY() - 24));
    }

    private int adminStatusY() {
        return panelTop() + panelHeight() - 92;
    }

    private final class LocalWaypointList extends ContainerObjectSelectionList<LocalWaypointList.Entry> {
        private List<LocalXaeroWaypointScanner.LocalWaypoint> currentWaypoints = List.of();

        LocalWaypointList(Minecraft minecraft, int x, int y, int width, int height) {
            super(minecraft, width, height, y, 28);
            setX(x);
        }

        void setWaypoints(List<LocalXaeroWaypointScanner.LocalWaypoint> waypoints) {
            List<LocalXaeroWaypointScanner.LocalWaypoint> safeWaypoints = List.copyOf(waypoints == null ? List.of() : waypoints);
            if (safeWaypoints.equals(currentWaypoints)) {
                return;
            }
            currentWaypoints = safeWaypoints;
            replaceEntries(safeWaypoints.stream().map(Entry::new).toList());
        }

        @Override
        public int getRowWidth() {
            return Math.max(1, getWidth() - 18);
        }

        @Override
        protected int scrollBarX() {
            return getX() + getWidth() - 8;
        }

        @Override
        protected void extractListBackground(GuiGraphicsExtractor graphics) {
            graphics.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), 0x66212A34);
            graphics.outline(getX(), getY(), getWidth(), getHeight(), 0x55365260);
        }

        @Override
        protected void extractListSeparators(GuiGraphicsExtractor graphics) {
        }

        @Override
        public void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
            super.extractWidgetRenderState(graphics, mouseX, mouseY, partialTick);
            if (currentWaypoints.isEmpty()) {
                Component text = switch (PublicWaypointImportClientState.getStatus()) {
                    case SCANNING -> Component.translatable("mapsyncer.gui.admin.waypoints.import.status.scanning");
                    case NO_FILE -> Component.translatable("mapsyncer.gui.admin.waypoints.import.status.no_file");
                    case EMPTY -> Component.translatable("mapsyncer.gui.admin.waypoints.import.status.empty");
                    case FAILED -> Component.translatable("mapsyncer.gui.admin.waypoints.import.status.failed",
                            PublicWaypointImportClientState.getDetail());
                    default -> Component.translatable("mapsyncer.gui.admin.waypoints.import.empty_hint");
                };
                drawSingleLine(graphics, text, getX() + 8, getY() + 10, getWidth() - 16, COLOR_MUTED);
            }
        }

        private final class Entry extends ContainerObjectSelectionList.Entry<Entry> {
            private final LocalXaeroWaypointScanner.LocalWaypoint waypoint;
            private final Button addButton;

            Entry(LocalXaeroWaypointScanner.LocalWaypoint waypoint) {
                this.waypoint = waypoint;
                this.addButton = Button.builder(Component.translatable("mapsyncer.gui.admin.waypoints.import.add"),
                        b -> submitLocalWaypoint(this.waypoint.toPublicWaypoint())).bounds(0, 0, 56, 20).build();
                this.addButton.setTooltip(tooltip("mapsyncer.gui.help.admin.waypoints_import_add"));
                this.addButton.setTooltipDelay(TOOLTIP_DELAY);
            }

            @Override
            public List<? extends GuiEventListener> children() {
                return List.of(addButton);
            }

            @Override
            public List<? extends NarratableEntry> narratables() {
                return List.of(addButton);
            }

            @Override
            public void extractContent(GuiGraphicsExtractor graphics, int mouseX, int mouseY,
                                       boolean hovered, float partialTick) {
                int buttonWidth = 56;
                int buttonX = getContentRight() - buttonWidth - 6;
                int buttonY = getContentY() + 4;
                addButton.setRectangle(buttonX, buttonY, buttonWidth, 20);
                PacketHandler.PublicWaypoint publicWaypoint = waypoint.toPublicWaypoint();
                addButton.active = isOwner()
                        && ClientPlayNetworking.canSend(PacketHandler.PublicWaypointAddPayload.TYPE)
                        && !PublicWaypointImportClientState.isPending(publicWaypoint);
                addButton.setMessage(PublicWaypointImportClientState.isPending(publicWaypoint)
                        ? Component.translatable("mapsyncer.gui.admin.waypoints.import.adding")
                        : Component.translatable("mapsyncer.gui.admin.waypoints.import.add"));

                int textX = getContentX() + 6;
                int textWidth = Math.max(40, getContentWidth() - buttonWidth - 24);
                drawSingleLine(graphics, Component.literal(waypoint.name()), textX, getContentY() + 4,
                        textWidth, COLOR_TEXT);
                drawSingleLine(graphics, Component.translatable("mapsyncer.gui.admin.waypoints.import.row",
                                waypoint.dimension(), waypoint.x(), waypoint.y(), waypoint.z()),
                        textX, getContentY() + 16, textWidth, COLOR_SUBTLE);
            }

            @Override
            public boolean mouseClicked(MouseButtonEvent event, boolean doubled) {
                LocalWaypointList.this.setSelected(this);
                if (super.mouseClicked(event, doubled)) {
                    return true;
                }
                submitLocalWaypoint(waypoint.toPublicWaypoint());
                return true;
            }

            @Override
            public void visitWidgets(Consumer<AbstractWidget> consumer) {
                consumer.accept(addButton);
            }
        }
    }

    private enum Tab {
        SYNC("mapsyncer.gui.tab.sync", "mapsyncer.gui.help.tab.sync"),
        ADMIN("mapsyncer.gui.tab.admin", "mapsyncer.gui.help.tab.admin"),
        SETTINGS("mapsyncer.gui.tab.settings", "mapsyncer.gui.help.tab.settings");

        private final String key;
        private final String tooltipKey;

        Tab(String key, String tooltipKey) {
            this.key = key;
            this.tooltipKey = tooltipKey;
        }

        Component title() {
            return Component.translatable(key);
        }

        String tooltipKey() {
            return tooltipKey;
        }
    }
}
