package dev.k2studio.e4steam.publicservers.client;

import dev.k2studio.e4steam.publicservers.common.Compatibility;
import dev.k2studio.e4steam.publicservers.common.PublicJoinCoordinator;
import dev.k2studio.e4steam.publicservers.common.PublicServersSnapshot;
import dev.k2studio.e4steam.publicservers.contract.ListingMetadata;
import dev.k2studio.e4steam.publicservers.contract.PublicServerEntry;
import dev.k2studio.e4steam.publicservers.contract.PublicServerType;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.LoadingDotsWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Native Minecraft 26.2 catalog for player worlds and dedicated servers. */
public final class PublicServersScreen extends Screen {
    private int refreshTicks;
    private static final int PANEL_MAX_WIDTH = 620;
    private static final int PANEL_MAX_HEIGHT = 360;
    /** Keeps the three-pixel overflow of the inner frame inside the outer frame. */
    private static final int PANEL_BORDER = 11;
    private static final int TAB_HEIGHT = 20;
    private static final int TAB_GAP = 7;
    private static final int ROW_HEIGHT = 42;
    private static final int SCROLLBAR_WIDTH = 6;
    private static final int MIN_THUMB_HEIGHT = 24;
    private static final int DETAILS_WIDTH = 210;
    private static final int COLOR_LIST = 0xff202020;
    private static final int COLOR_LIST_ALT = 0xff252525;
    private static final int COLOR_LIST_HOVER = 0xff353535;
    private static final int COLOR_LIST_SELECTED = 0xff454545;
    private static final int COLOR_MUTED = 0xffa0a0a0;
    private static final Identifier WORLD_ICON = Identifier.fromNamespaceAndPath(
            "minecraft", "world_list/marked_join");
    private static final Identifier DEDICATED_ICON = Identifier.fromNamespaceAndPath(
            "minecraft", "server_list/join");
    private static final Identifier SCROLLER = Identifier.fromNamespaceAndPath(
            "minecraft", "widget/scroller");
    private static final Identifier SCROLLER_BACKGROUND = Identifier.fromNamespaceAndPath(
            "minecraft", "widget/scroller_background");

    private final Screen parent;
    private PublicServersSnapshot snapshot = PublicServersClientBridge.snapshot();
    private AutoCloseable listener = () -> { };
    private boolean open;
    private boolean compatibleOnly;
    private boolean freeSlotsOnly;
    private boolean draggingScrollbar;
    private boolean joinClickPending;
    private PublicServerType typeFilter;
    private String searchText = "";
    private int scrollIndex;
    private int panelLeft;
    private int panelTop;
    private int panelWidth;
    private int panelHeight;
    private int contentLeft;
    private int contentTop;
    private int contentWidth;
    private int listLeft;
    private int listTop;
    private int listWidth;
    private int listHeight;
    private int detailLeft;
    private int detailWidth;
    private int visibleRows;
    private int footerButtonsY;
    private EditBox searchBox;
    private Checkbox compatibleCheckbox;
    private Checkbox freeSlotsCheckbox;
    private Button refreshButton;
    private Button connectButton;
    private Button backButton;
    private LoadingDotsWidget loadingDots;

    public PublicServersScreen(Screen parent) {
        super(Component.translatable("e4steam_public_servers.screen.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        open = true;
        calculateLayout();
        addTabs();

        int refreshWidth = Math.min(80, Math.max(64, contentWidth / 4));
        int searchWidth = Math.max(96, contentWidth - refreshWidth - 6);
        searchBox = addRenderableWidget(new EditBox(
                font, contentLeft, contentTop, searchWidth, 20,
                Component.translatable("e4steam_public_servers.search")));
        searchBox.setHint(Component.translatable("e4steam_public_servers.search"));
        searchBox.setMaxLength(64);
        searchBox.setValue(searchText);
        searchBox.setResponder(value -> {
            searchText = value;
            scrollIndex = 0;
            PublicServersClientBridge.search(value);
        });

        refreshButton = addRenderableWidget(Button.builder(
                        Component.translatable("e4steam_public_servers.refresh"),
                        ignored -> PublicServersClientBridge.refresh())
                .bounds(contentLeft + searchWidth + 6, contentTop, refreshWidth, 20)
                .tooltip(Tooltip.create(Component.translatable(
                        "e4steam_public_servers.refresh.tooltip")))
                .build());

        boolean filtersShareRow = contentWidth >= 430;
        int compatibleY = contentTop + 25;
        int compatibleWidth = filtersShareRow ? contentWidth / 2 - 4 : contentWidth;
        compatibleCheckbox = addRenderableWidget(Checkbox.builder(
                        Component.translatable("e4steam_public_servers.filter.compatible"), font)
                .pos(contentLeft, compatibleY)
                .selected(compatibleOnly)
                .maxWidth(compatibleWidth)
                .onValueChange((checkbox, selected) -> {
                    compatibleOnly = selected;
                    scrollIndex = 0;
                    PublicServersClientBridge.compatibleOnly(selected);
                })
                .build());

        int freeX = filtersShareRow ? contentLeft + contentWidth / 2 + 4 : contentLeft;
        int freeY = filtersShareRow ? compatibleY : compatibleY + 22;
        freeSlotsCheckbox = addRenderableWidget(Checkbox.builder(
                        Component.translatable("e4steam_public_servers.filter.free_slots"), font)
                .pos(freeX, freeY)
                .selected(freeSlotsOnly)
                .maxWidth(filtersShareRow ? contentWidth / 2 - 4 : contentWidth)
                .onValueChange((checkbox, selected) -> {
                    freeSlotsOnly = selected;
                    scrollIndex = 0;
                    PublicServersClientBridge.hasSlotsOnly(selected);
                })
                .build());

        loadingDots = addRenderableWidget(new LoadingDotsWidget(
                font, Component.translatable("e4steam_public_servers.state.loading")));

        int actionWidth = Math.min(150, (contentWidth - 8) / 2);
        int actionsLeft = contentLeft + (contentWidth - actionWidth * 2 - 8) / 2;
        connectButton = addRenderableWidget(Button.builder(
                        Component.translatable("e4steam_public_servers.connect"),
                        ignored -> joinSelected())
                .bounds(actionsLeft, footerButtonsY, actionWidth, 20)
                .build());
        backButton = addRenderableWidget(Button.builder(
                        Component.translatable("e4steam_public_servers.back"),
                        ignored -> onClose())
                .bounds(actionsLeft + actionWidth + 8, footerButtonsY, actionWidth, 20)
                .build());

        listener = PublicServersClientBridge.listen(() -> {
            Minecraft client = Minecraft.getInstance();
            client.execute(() -> {
                if (open) syncSnapshot();
            });
        });
        PublicServersClientBridge.search(searchText);
        PublicServersClientBridge.type(typeFilter);
        PublicServersClientBridge.compatibleOnly(compatibleOnly);
        PublicServersClientBridge.hasSlotsOnly(freeSlotsOnly);
        syncSnapshot();
        PublicServersClientBridge.refresh();
    }

    private void addTabs() {
        int tabY = panelTop - TAB_HEIGHT - TAB_GAP;
        int firstWidth = contentWidth / 3;
        int secondWidth = contentWidth / 3;
        int thirdWidth = contentWidth - firstWidth - secondWidth;
        addRenderableWidget(new PublicServersTabButton(
                contentLeft, tabY, firstWidth,
                Component.translatable("e4steam_public_servers.filter.all"),
                () -> selectType(null), () -> typeFilter == null));
        addRenderableWidget(new PublicServersTabButton(
                contentLeft + firstWidth, tabY, secondWidth,
                Component.translatable("e4steam_public_servers.filter.worlds"),
                () -> selectType(PublicServerType.INTEGRATED_WORLD),
                () -> typeFilter == PublicServerType.INTEGRATED_WORLD));
        addRenderableWidget(new PublicServersTabButton(
                contentLeft + firstWidth + secondWidth, tabY, thirdWidth,
                Component.translatable("e4steam_public_servers.filter.dedicated"),
                () -> selectType(PublicServerType.DEDICATED_SERVER),
                () -> typeFilter == PublicServerType.DEDICATED_SERVER));
    }

    @Override
    protected void repositionElements() {
        clearWidgets();
        safeClose(listener);
        listener = () -> { };
        init();
    }

    @Override
    public void tick() {
        syncSnapshot();
        if (++refreshTicks >= 200) {
            refreshTicks = 0;
            if (!PublicServersClientBridge.joinSnapshot().map(PublicServersScreen::pending).orElse(false) && snapshot.state() != PublicServersSnapshot.State.LOADING) {
                PublicServersClientBridge.refresh();
            }
        }
    }

    @Override
    public void removed() {
        open = false;
        draggingScrollbar = false;
        safeClose(listener);
        listener = () -> { };
    }

    @Override
    public void onClose() {
        PublicServersClientBridge.setScreen(minecraft, parent);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double mouseX = event.x();
        double mouseY = event.y();
        if (overScrollbar(mouseX, mouseY)) {
            draggingScrollbar = true;
            scrollFromMouse(mouseY);
            return true;
        }
        if (inside(mouseX, mouseY, listLeft, listTop,
                listWidth - SCROLLBAR_WIDTH, listHeight)) {
            int row = (int) ((mouseY - listTop) / ROW_HEIGHT);
            int index = scrollIndex + row;
            List<PublicServersSnapshot.Item> items = snapshot.items();
            if (row < visibleRows && index >= 0 && index < items.size()) {
                PublicServersSnapshot.Item item = items.get(index);
                PublicServersClientBridge.select(item.entry().listingId());
                if (doubleClick && item.compatibility().joinable()) {
                    joinSelected();
                }
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingScrollbar) {
            scrollFromMouse(event.y());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingScrollbar) {
            draggingScrollbar = false;
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(
            double mouseX,
            double mouseY,
            double horizontalAmount,
            double verticalAmount
    ) {
        if (inside(mouseX, mouseY, listLeft, listTop, listWidth, listHeight)
                && verticalAmount != 0.0) {
            int next = scrollIndex + (verticalAmount > 0.0 ? -1 : 1);
            scrollIndex = Math.max(0, Math.min(next, maxScrollIndex()));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public void extractBackground(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            float partialTick
    ) {
        if (parent != null) {
            parent.extractBackground(graphics, mouseX, mouseY, partialTick);
            graphics.nextStratum();
        } else {
            super.extractBackground(graphics, mouseX, mouseY, partialTick);
        }
        graphics.fill(panelLeft, panelTop, panelLeft + panelWidth, panelTop + panelHeight,
                0xe6121b26);
        outline(graphics, panelLeft, panelTop, panelWidth, panelHeight, 0xff34495c);
        graphics.fill(panelLeft + 1, panelTop + 1, panelLeft + panelWidth - 1, panelTop + 4,
                0xff55cbb9);
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            float partialTick
    ) {
        renderCatalog(graphics, mouseX, mouseY);
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    private void renderCatalog(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        graphics.fill(listLeft - 3, listTop - 3, listLeft + listWidth + 3,
                listTop + listHeight + 3, 0xff34495c);
        graphics.fill(listLeft, listTop, listLeft + listWidth, listTop + listHeight, COLOR_LIST);
        graphics.fill(listLeft, listTop, listLeft + listWidth, listTop + 2, 0xff55cbb9);

        if (snapshot.items().isEmpty()) {
            if (!isLoading()) renderEmptyState(graphics);
        } else {
            renderRows(graphics, mouseX, mouseY);
        }
        renderScrollbar(graphics);
        if (detailWidth > 0) renderDetails(graphics);
        renderFooterStatus(graphics);
    }

    private void renderEmptyState(GuiGraphicsExtractor graphics) {
        Component message = stateMessage();
        if (listHeight >= 80 && listWidth >= 170) {
            int imageY = listTop + Math.max(6, (listHeight - 24) / 2);
            graphics.centeredText(
                    font,
                    Component.literal(ellipsize(message.getString(), listWidth - 16)),
                    listLeft + listWidth / 2,
                    imageY,
                    COLOR_MUTED
            );
            return;
        }
        List<String> lines = wrap(message.getString(), listWidth - 16, 2);
        int y = listTop + listHeight / 2 - lines.size() * 6;
        for (String line : lines) {
            graphics.centeredText(
                    font,
                    Component.literal(line),
                    listLeft + listWidth / 2,
                    y,
                    COLOR_MUTED
            );
            y += 12;
        }
    }

    private void renderRows(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        List<PublicServersSnapshot.Item> items = snapshot.items();
        for (int row = 0; row < visibleRows && scrollIndex + row < items.size(); row++) {
            PublicServersSnapshot.Item item = items.get(scrollIndex + row);
            PublicServerEntry entry = item.entry();
            ListingMetadata metadata = entry.metadata();
            int y = listTop + row * ROW_HEIGHT;
            boolean selected = entry.listingId().equals(snapshot.selectedListingId());
            boolean hovered = inside(
                    mouseX,
                    mouseY,
                    listLeft,
                    y,
                    listWidth - SCROLLBAR_WIDTH,
                    ROW_HEIGHT
            );
            int background = selected
                    ? COLOR_LIST_SELECTED
                    : hovered ? COLOR_LIST_HOVER : (row & 1) == 0 ? COLOR_LIST_ALT : COLOR_LIST;
            int rowRight = listLeft + listWidth - SCROLLBAR_WIDTH;
            graphics.fill(listLeft, y, rowRight, y + ROW_HEIGHT, background);
            graphics.fill(listLeft, y + ROW_HEIGHT - 1, rowRight, y + ROW_HEIGHT, 0xff111111);
            int accent = compatibilityColor(item.compatibility());
            graphics.fill(listLeft + 1, y + 3, listLeft + 4, y + ROW_HEIGHT - 3, accent);
            if (selected) outline(graphics, listLeft, y, rowRight - listLeft, ROW_HEIGHT, 0xffffffff);

            Identifier icon = entry.type() == PublicServerType.INTEGRATED_WORLD
                    ? WORLD_ICON : DEDICATED_ICON;
            graphics.fill(listLeft + 8, y + 8, listLeft + 34, y + 34, 0xff101010);
            PublicServersClientBridge.blitSprite(
                    graphics,
                    icon,
                    listLeft + 11,
                    y + 11,
                    20,
                    20
            );

            int textLeft = listLeft + 40;
            int textRight = rowRight - 6;
            Component fullStatus = compatibilityText(item.compatibility());
            int maximumStatusWidth = Math.max(42, (textRight - textLeft) / 2);
            Component status = Component.literal(ellipsize(
                    fullStatus.getString(),
                    Math.max(1, maximumStatusWidth - 8)));
            int statusWidth = Math.min(font.width(status) + 8, maximumStatusWidth);
            int statusX = textRight - statusWidth;
            graphics.fill(statusX, y + 27, textRight, y + 39, 0xaa101010);
            graphics.text(font, status, statusX + 4, y + 29, accent, false);

            graphics.text(
                    font,
                    Component.literal(ellipsize(metadata.name(), textRight - textLeft)),
                    textLeft,
                    y + 5,
                    0xffffffff,
                    false
            );
            Component type = Component.translatable(entry.type() == PublicServerType.INTEGRATED_WORLD
                    ? "e4steam_public_servers.type.integrated"
                    : "e4steam_public_servers.type.dedicated");
            String middle = type.getString() + " · " + metadata.minecraftVersion()
                    + " · " + metadata.loader();
            graphics.text(
                    font,
                    Component.literal(ellipsize(middle, textRight - textLeft)),
                    textLeft,
                    y + 17,
                    COLOR_MUTED,
                    false
            );
            Component population = Component.translatable(
                    "e4steam_public_servers.players",
                    metadata.players(),
                    metadata.capacity()
            );
            graphics.text(
                    font,
                    population,
                    textLeft,
                    y + 29,
                    metadata.players() < metadata.capacity() ? 0xff80ff80 : 0xffff8080,
                    false
            );
        }
    }

    private void renderDetails(GuiGraphicsExtractor graphics) {
        graphics.fill(detailLeft - 3, listTop - 3, detailLeft + detailWidth + 3,
                listTop + listHeight + 3, 0xff34495c);
        graphics.fill(detailLeft, listTop, detailLeft + detailWidth, listTop + listHeight, 0xff292929);
        graphics.fill(detailLeft, listTop, detailLeft + detailWidth, listTop + 2, 0xff55cbb9);
        PublicServersSnapshot.Item item = snapshot.selected();
        if (item == null) {
            List<String> lines = wrap(
                    Component.translatable("e4steam_public_servers.selection.none").getString(),
                    detailWidth - 18,
                    3
            );
            int y = listTop + listHeight / 2 - lines.size() * 6;
            for (String line : lines) {
                graphics.centeredText(
                        font,
                        Component.literal(line),
                        detailLeft + detailWidth / 2,
                        y,
                        COLOR_MUTED
                );
                y += 12;
            }
            return;
        }

        ListingMetadata metadata = item.entry().metadata();
        int x = detailLeft + 9;
        int maxWidth = detailWidth - 18;
        int y = listTop + 8;
        Identifier icon = item.entry().type() == PublicServerType.INTEGRATED_WORLD
                ? WORLD_ICON : DEDICATED_ICON;
        graphics.fill(x, y, x + 28, y + 28, 0xff101010);
        PublicServersClientBridge.blitSprite(graphics, icon, x + 4, y + 4, 20, 20);
        int titleX = x + 35;
        graphics.text(
                font,
                Component.literal(ellipsize(metadata.name(), maxWidth - 35)),
                titleX,
                y + 2,
                0xffffffff,
                false
        );
        graphics.text(
                font,
                Component.translatable(item.entry().type() == PublicServerType.INTEGRATED_WORLD
                        ? "e4steam_public_servers.type.integrated"
                        : "e4steam_public_servers.type.dedicated"),
                titleX,
                y + 16,
                COLOR_MUTED,
                false
        );
        y += 35;
        graphics.fill(x, y, x + maxWidth, y + 2, 0xff435365);
        y += 8;
        for (String line : wrap(metadata.description(), maxWidth, 3)) {
            graphics.text(font, Component.literal(line), x, y, 0xffc0c0c0, false);
            y += 11;
        }
        y += 4;
        graphics.text(font, Component.translatable(
                "e4steam_public_servers.version",
                metadata.minecraftVersion(),
                metadata.loader()), x, y, COLOR_MUTED, false);
        y += 12;
        graphics.text(font, Component.translatable(
                "e4steam_public_servers.e4steam_version",
                metadata.e4steamVersion()), x, y, COLOR_MUTED, false);
        y += 12;
        graphics.text(font, Component.translatable(
                "e4steam_public_servers.players",
                metadata.players(),
                metadata.capacity()), x, y, COLOR_MUTED, false);
        y += 12;
        graphics.text(font, Component.translatable(
                "e4steam_public_servers.ping"), x, y, 0xff808080, false);
        y += 16;
        graphics.text(
                font,
                compatibilityText(item.compatibility()).copy().withStyle(ChatFormatting.BOLD),
                x,
                y,
                compatibilityColor(item.compatibility()),
                false
        );
        y += 14;
        if (!metadata.tags().isEmpty() && y < listTop + listHeight - 10) {
            graphics.text(
                    font,
                    Component.literal(ellipsize(String.join(" · ", metadata.tags()), maxWidth)),
                    x,
                    y,
                    0xff80a0ff,
                    false
            );
        }
    }

    private void renderScrollbar(GuiGraphicsExtractor graphics) {
        int count = snapshot.items().size();
        if (count <= visibleRows) return;
        int trackX = listLeft + listWidth - SCROLLBAR_WIDTH;
        int thumbHeight = thumbHeight(count);
        int travel = listHeight - thumbHeight;
        int thumbY = listTop + (maxScrollIndex() == 0
                ? 0 : travel * scrollIndex / maxScrollIndex());
        PublicServersClientBridge.blitSprite(
                graphics,
                SCROLLER_BACKGROUND,
                trackX,
                listTop,
                SCROLLBAR_WIDTH,
                listHeight
        );
        PublicServersClientBridge.blitSprite(
                graphics,
                SCROLLER,
                trackX,
                thumbY,
                SCROLLBAR_WIDTH,
                thumbHeight
        );
    }

    private void renderFooterStatus(GuiGraphicsExtractor graphics) {
        Optional<PublicJoinCoordinator.Snapshot> joinOperation = PublicServersClientBridge.joinSnapshot();
        String footerKey = joinOperation.map(operation -> joinStateKey(operation.state()))
                .filter(key -> !key.isEmpty())
                .orElseGet(this::snapshotFooterKey);
        if (footerKey.isEmpty()) return;
        boolean failedJoin = joinOperation
                .map(operation -> operation.state() == PublicJoinCoordinator.State.FAILED)
                .orElse(false);
        graphics.centeredText(
                font,
                Component.literal(ellipsize(
                        Component.translatable(footerKey).getString(),
                        contentWidth)),
                panelLeft + panelWidth / 2,
                footerButtonsY - 12,
                failedJoin ? 0xffff8080 : COLOR_MUTED
        );
    }

    private void syncSnapshot() {
        snapshot = PublicServersClientBridge.snapshot();
        scrollIndex = Math.min(scrollIndex, maxScrollIndex());
        boolean loading = isLoading();
        loadingDots.visible = loading;
        loadingDots.setRectangle(
                listLeft + (listWidth - loadingDots.getWidth()) / 2,
                listTop + listHeight / 2 - loadingDots.getHeight() / 2,
                loadingDots.getWidth(),
                loadingDots.getHeight()
        );
        Optional<PublicJoinCoordinator.Snapshot> operation = PublicServersClientBridge.joinSnapshot();
        boolean joining = operation.map(PublicServersScreen::pending).orElse(false);
        joinClickPending = joining;
        PublicServersSnapshot.Item selected = snapshot.selected();
        connectButton.active = joining
                || selected != null && selected.compatibility().joinable();
        connectButton.setMessage(Component.translatable(joining
                ? "e4steam_public_servers.cancel"
                : "e4steam_public_servers.connect"));
        refreshButton.active = !loading;
    }

    private void joinSelected() {
        if (joinClickPending) {
            PublicServersClientBridge.cancelJoin();
            return;
        }
        PublicServersSnapshot.Item selected = snapshot.selected();
        if (selected == null || !selected.compatibility().joinable()) return;
        joinClickPending = true;
        connectButton.active = false;
        PublicServersClientBridge.join(selected.entry().listingId());
    }

    private void selectType(PublicServerType type) {
        if (typeFilter == type) return;
        typeFilter = type;
        scrollIndex = 0;
        PublicServersClientBridge.type(type);
    }

    private Component stateMessage() {
        if (!snapshot.messageKey().isEmpty()) {
            return Component.translatable(snapshot.messageKey());
        }
        return Component.translatable(switch (snapshot.state()) {
            case NOT_CONFIGURED -> "e4steam_public_servers.state.not_configured";
            case LOADING -> "e4steam_public_servers.state.loading";
            case EMPTY, READY -> "e4steam_public_servers.state.empty";
            case CACHED -> "e4steam_public_servers.state.cached";
            case RATE_LIMITED -> "e4steam_public_servers.state.rate_limited";
            case UNAVAILABLE, CLOSED -> "e4steam_public_servers.state.registry_unavailable";
        });
    }

    private static Component compatibilityText(Compatibility compatibility) {
        String detail = compatibility.detail();
        return switch (compatibility.reason()) {
            case COMPATIBLE -> Component.translatable("e4steam_public_servers.compatible");
            case FULL -> Component.translatable("e4steam_public_servers.full");
            case STALE -> Component.translatable("e4steam_public_servers.state.stale");
            case MINECRAFT_VERSION -> Component.translatable(
                    "e4steam_public_servers.incompatible.minecraft", detail);
            case LOADER -> Component.translatable(
                    "e4steam_public_servers.incompatible.loader", detail);
            case MISSING_REQUIRED_ADDON, INCOMPATIBLE_REQUIRED_ADDON -> Component.translatable(
                    "e4steam_public_servers.incompatible.addon", detail);
            case E4STEAM_VERSION, CORE_PROTOCOL, DIRECTORY_PROTOCOL -> Component.translatable(
                    "e4steam_public_servers.incompatible.protocol");
        };
    }

    private void calculateLayout() {
        panelWidth = Math.min(PANEL_MAX_WIDTH, Math.max(220, width - 20));
        panelWidth = Math.min(panelWidth, Math.max(1, width - 4));
        panelHeight = Math.min(PANEL_MAX_HEIGHT, Math.max(152, height - 68));
        panelHeight = Math.min(panelHeight, Math.max(1, height - TAB_HEIGHT - TAB_GAP - 10));
        panelLeft = (width - panelWidth) / 2;
        panelTop = Math.max(TAB_HEIGHT + TAB_GAP + 4, (height - panelHeight) / 2);
        if (panelTop + panelHeight > height - 6) {
            panelTop = Math.max(TAB_HEIGHT + TAB_GAP + 2, height - panelHeight - 6);
        }
        contentLeft = panelLeft + PANEL_BORDER;
        contentTop = panelTop + PANEL_BORDER;
        contentWidth = panelWidth - PANEL_BORDER * 2;
        footerButtonsY = panelTop + panelHeight - 28;

        int controlsHeight = contentWidth >= 430 ? 49 : 71;
        listTop = contentTop + controlsHeight;
        listLeft = contentLeft;
        listHeight = Math.max(ROW_HEIGHT, footerButtonsY - listTop - 18);
        boolean showDetails = contentWidth >= 500;
        detailWidth = showDetails ? Math.min(DETAILS_WIDTH, contentWidth / 3) : 0;
        int detailGap = showDetails ? 8 : 0;
        listWidth = contentWidth - detailGap - detailWidth;
        detailLeft = listLeft + listWidth + detailGap;
        visibleRows = Math.max(1, listHeight / ROW_HEIGHT);
    }

    private boolean isLoading() {
        return snapshot.state() == PublicServersSnapshot.State.LOADING;
    }

    private int maxScrollIndex() {
        return Math.max(0, snapshot.items().size() - visibleRows);
    }

    private boolean overScrollbar(double mouseX, double mouseY) {
        return snapshot.items().size() > visibleRows && inside(
                mouseX,
                mouseY,
                listLeft + listWidth - SCROLLBAR_WIDTH,
                listTop,
                SCROLLBAR_WIDTH,
                listHeight
        );
    }

    private void scrollFromMouse(double mouseY) {
        int count = snapshot.items().size();
        if (count <= visibleRows) return;
        int thumb = thumbHeight(count);
        int travel = listHeight - thumb;
        if (travel <= 0) return;
        double relative = mouseY - listTop - thumb / 2.0;
        double fraction = Math.max(0.0, Math.min(1.0, relative / travel));
        scrollIndex = (int) Math.round(fraction * maxScrollIndex());
    }

    private int thumbHeight(int entryCount) {
        int contentHeight = entryCount * ROW_HEIGHT;
        int maximum = Math.max(8, listHeight - 8);
        int minimum = Math.min(MIN_THUMB_HEIGHT, maximum);
        return Math.max(minimum, Math.min(maximum, listHeight * listHeight / contentHeight));
    }

    private String ellipsize(String value, int maximumWidth) {
        if (value == null || maximumWidth <= 0) return "";
        if (font.width(value) <= maximumWidth) return value;
        String suffix = "...";
        int allowed = Math.max(0, maximumWidth - font.width(suffix));
        return font.plainSubstrByWidth(value, allowed) + suffix;
    }

    private List<String> wrap(String value, int maximumWidth, int maximumLines) {
        if (value == null || value.isBlank()) return List.of();
        List<String> result = new ArrayList<>();
        String remaining = value.trim();
        while (!remaining.isEmpty() && result.size() < maximumLines) {
            String line = font.plainSubstrByWidth(remaining, maximumWidth);
            if (line.isEmpty()) break;
            int split = line.length();
            if (split < remaining.length()) {
                int whitespace = line.lastIndexOf(' ');
                if (whitespace > 0) split = whitespace;
            }
            String part = remaining.substring(0, split).trim();
            remaining = remaining.substring(split).trim();
            if (result.size() == maximumLines - 1 && !remaining.isEmpty()) {
                part = ellipsize(part + " " + remaining, maximumWidth);
                remaining = "";
            }
            result.add(part);
        }
        return result;
    }

    private static boolean pending(PublicJoinCoordinator.Snapshot operation) {
        return switch (operation.state()) {
            case RESOLVING, JOINING_STEAM_TARGET, AUTHENTICATING, CONNECTING_MINECRAFT -> true;
            case IDLE, ACTIVE, CANCELLED, STALE, FULL, INCOMPATIBLE, DENIED,
                    TARGET_LOST, REGISTRY_UNAVAILABLE, FAILED, CLOSED -> false;
        };
    }

    private static String joinStateKey(PublicJoinCoordinator.State state) {
        return switch (state) {
            case IDLE, ACTIVE, CLOSED -> "";
            case RESOLVING -> "e4steam_public_servers.state.resolving";
            case JOINING_STEAM_TARGET -> "e4steam_public_servers.state.joining";
            case AUTHENTICATING -> "e4steam_public_servers.state.authenticating";
            case CONNECTING_MINECRAFT -> "e4steam_public_servers.state.connecting";
            case CANCELLED -> "e4steam_public_servers.state.cancelled";
            case STALE -> "e4steam_public_servers.state.stale";
            case FULL -> "e4steam_public_servers.full";
            case INCOMPATIBLE -> "e4steam_public_servers.incompatible";
            case DENIED -> "e4steam_public_servers.state.denied";
            case TARGET_LOST -> "e4steam_public_servers.state.target_lost";
            case REGISTRY_UNAVAILABLE -> "e4steam_public_servers.state.registry_unavailable";
            case FAILED -> "e4steam_public_servers.state.failed";
        };
    }

    private String snapshotFooterKey() {
        return switch (snapshot.state()) {
            case CACHED -> "e4steam_public_servers.state.cached";
            case RATE_LIMITED -> "e4steam_public_servers.state.rate_limited";
            default -> "";
        };
    }

    private static boolean inside(
            double x,
            double y,
            int left,
            int top,
            int width,
            int height
    ) {
        return x >= left && x < left + width && y >= top && y < top + height;
    }

    private static void outline(
            GuiGraphicsExtractor graphics,
            int x,
            int y,
            int width,
            int height,
            int color
    ) {
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y, x + 1, y + height, color);
        graphics.fill(x + width - 1, y, x + width, y + height, color);
    }

    private static int compatibilityColor(Compatibility compatibility) {
        return switch (compatibility.reason()) {
            case COMPATIBLE -> 0xff62d26f;
            case FULL -> 0xffffb347;
            case STALE -> 0xff8a8a8a;
            default -> 0xffff6b6b;
        };
    }

    private static void safeClose(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Registration close is idempotent; there is no user-facing recovery here.
        }
    }
}
