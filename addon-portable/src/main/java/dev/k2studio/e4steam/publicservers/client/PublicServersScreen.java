package dev.k2studio.e4steam.publicservers.client;

import dev.k2studio.e4steam.publicservers.common.PublicJoinCoordinator;
import dev.k2studio.e4steam.publicservers.common.PublicServersSnapshot;
import dev.k2studio.e4steam.publicservers.contract.ListingMetadata;
import dev.k2studio.e4steam.publicservers.contract.PublicServerEntry;
import dev.k2studio.e4steam.publicservers.contract.PublicServerType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Portable native-widget catalog used by Minecraft 1.17-1.21.x.
 *
 * <p>It deliberately leaves custom rendering to the 26.x implementation: this
 * screen only links stable Screen, EditBox and Button APIs, so old release JARs
 * never contain references to GuiGraphicsExtractor or other new GUI classes.</p>
 */
public final class PublicServersScreen extends Screen {
    private int refreshTicks;
    private final Screen parent;
    private final List<Button> rowButtons = new ArrayList<>();
    private PublicServersSnapshot snapshot = PublicServersClientBridge.snapshot();
    private AutoCloseable listener = () -> { };
    private boolean open;
    private boolean compatibleOnly;
    private boolean freeSlotsOnly;
    private PublicServerType typeFilter;
    private String searchText = "";
    private int scrollIndex;
    private int visibleRows;
    private int listLeft, listRight, listTop, listHeight;
    private boolean draggingScrollbar;
    private double scrollbarGrab;
    private EditBox searchBox;
    private Button allButton;
    private Button worldsButton;
    private Button dedicatedButton;
    private Button compatibleButton;
    private Button slotsButton;
    private Button previousButton;
    private Button nextButton;
    private Button connectButton;
    private Component statusLabel;

    public PublicServersScreen(Screen parent) {
        super(PortableComponents.translatable("e4steam_public_servers.screen.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        open = true;
        closeListener();
        rowButtons.clear();
        PublicServersClientBridge.setCreatorName(minecraft.getUser().getName());

        int contentWidth = Math.max(220, width - 32);
        int left = (width - contentWidth) / 2;
        int smallGap = 4;
        int refreshWidth = Math.min(88, Math.max(68, contentWidth / 5));
        int searchWidth = contentWidth - refreshWidth - smallGap;

        searchBox = addRenderableWidget(new EditBox(font, left, 32, searchWidth, 20,
                PortableComponents.translatable("e4steam_public_servers.search")));
        searchBox.setMaxLength(64);
        searchBox.setValue(searchText);
        searchBox.setSuggestion(PortableComponents.translatable(
                "e4steam_public_servers.search").getString());
        searchBox.setResponder(value -> {
            searchText = value;
            scrollIndex = 0;
            PublicServersClientBridge.search(value);
        });

        Button refresh = addButton(PortableMinecraftUi.button(
                PortableComponents.translatable("e4steam_public_servers.refresh"),
                ignored -> PublicServersClientBridge.refresh(),
                left + searchWidth + smallGap, 32, refreshWidth, 20));
        PortableMinecraftUi.tooltip(refresh, PortableComponents.translatable(
                "e4steam_public_servers.refresh.tooltip"));

        int tabWidth = contentWidth / 3;
        allButton = addButton(PortableMinecraftUi.button(PortableComponents.translatable(
                        "e4steam_public_servers.filter.all"), ignored -> selectType(null),
                left, 56, tabWidth, 20));
        worldsButton = addButton(PortableMinecraftUi.button(PortableComponents.translatable(
                        "e4steam_public_servers.filter.worlds"),
                ignored -> selectType(PublicServerType.INTEGRATED_WORLD),
                left + tabWidth, 56, tabWidth, 20));
        dedicatedButton = addButton(PortableMinecraftUi.button(PortableComponents.translatable(
                        "e4steam_public_servers.filter.dedicated"),
                ignored -> selectType(PublicServerType.DEDICATED_SERVER),
                left + tabWidth * 2, 56, contentWidth - tabWidth * 2, 20));

        int filterWidth = (contentWidth - smallGap) / 2;
        compatibleButton = addButton(PortableMinecraftUi.button(PortableComponents.literal(""),
                ignored -> {
                    compatibleOnly = !compatibleOnly;
                    scrollIndex = 0;
                    PublicServersClientBridge.compatibleOnly(compatibleOnly);
                    syncSnapshot();
                }, left, 80, filterWidth, 20));
        slotsButton = addButton(PortableMinecraftUi.button(PortableComponents.literal(""),
                ignored -> {
                    freeSlotsOnly = !freeSlotsOnly;
                    scrollIndex = 0;
                    PublicServersClientBridge.hasSlotsOnly(freeSlotsOnly);
                    syncSnapshot();
                }, left + filterWidth + smallGap, 80,
                contentWidth - filterWidth - smallGap, 20));

        int footerTop = Math.max(130, height - 48);
        listTop = 104;
        listLeft = left;
        listRight = left + contentWidth;
        draggingScrollbar = false;
        visibleRows = Math.max(1, (footerTop - listTop - 4) / 22);
        listHeight = visibleRows * 22 - 2;
        for (int row = 0; row < visibleRows; row++) {
            final int rowOffset = row;
            Button button = addButton(PortableMinecraftUi.button(PortableComponents.literal(""),
                    ignored -> selectVisibleRow(rowOffset), left, listTop + row * 22,
                    contentWidth - 10, 20));
            rowButtons.add(button);
        }

        int navWidth = Math.min(70, Math.max(46, contentWidth / 7));
        int connectWidth = Math.min(160, Math.max(100, contentWidth / 3));
        int backWidth = Math.min(100, Math.max(70, contentWidth / 5));
        previousButton = addButton(PortableMinecraftUi.button(PortableComponents.literal("<"),
                ignored -> scroll(-visibleRows), left, footerTop, navWidth, 20));
        nextButton = addButton(PortableMinecraftUi.button(PortableComponents.literal(">"),
                ignored -> scroll(visibleRows), left + navWidth + smallGap, footerTop,
                navWidth, 20));
        connectButton = addButton(PortableMinecraftUi.button(PortableComponents.translatable(
                        "e4steam_public_servers.connect"), ignored -> joinSelected(),
                left + (contentWidth - connectWidth) / 2, footerTop, connectWidth, 20));
        addButton(PortableMinecraftUi.button(PortableComponents.translatable(
                        "e4steam_public_servers.back"), ignored -> onClose(),
                left + contentWidth - backWidth, footerTop, backWidth, 20));
        statusLabel = PortableComponents.literal("");

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
        closeListener();
        super.removed();
    }

    @Override
    public void onClose() {
        minecraft.setScreen(parent);
    }

    private Button addButton(Button button) {
        return addRenderableWidget(button);
    }

    private void selectType(PublicServerType type) {
        if (typeFilter == type) return;
        typeFilter = type;
        scrollIndex = 0;
        PublicServersClientBridge.type(type);
        syncSnapshot();
    }

    private void selectVisibleRow(int row) {
        int index = scrollIndex + row;
        if (index < 0 || index >= snapshot.items().size()) return;
        PublicServerEntry entry = snapshot.items().get(index).entry();
        PublicServersClientBridge.select(entry.listingId());
        syncSnapshot();
    }

    private void scroll(int amount) {
        scrollIndex = Math.max(0, Math.min(maxScrollIndex(), scrollIndex + amount));
        syncSnapshot();
    }

    public void renderScrollbar(Object graphics) {
        PortableScrollGraphics.centeredText(graphics, font, title, width / 2, 14, 0xffeef5fa);
        if (statusLabel != null) PortableScrollGraphics.centeredText(graphics, font, statusLabel, width / 2, height - 18, 0xff9eb6c8);
        if (snapshot.items().isEmpty() && statusLabel != null) {
            PortableScrollGraphics.centeredText(graphics, font, statusLabel, width / 2, listTop + listHeight / 2 - 4, 0xff9eb6c8);
        }
        if (maxScrollIndex() == 0) return;
        int thumb = dev.k2studio.e4steam.publicservers.common.CatalogScroll.thumbHeight(snapshot.items().size(), visibleRows, listHeight);
        int top = listTop + dev.k2studio.e4steam.publicservers.common.CatalogScroll.thumbTop(scrollIndex, snapshot.items().size(), visibleRows, listHeight);
        PortableScrollGraphics.fill(graphics, listRight - 7, listTop, listRight, listTop + listHeight, 0xff101820);
        PortableScrollGraphics.fill(graphics, listRight - 6, top, listRight - 1, top + thumb, draggingScrollbar ? 0xff7de1c7 : 0xff54b5a7);
    }

    public void renderCatalogBackground(Object graphics) {
        PortableScrollGraphics.fill(graphics, 0, 0, width, height, 0xff0b131d);
        PortableScrollGraphics.fill(graphics, listLeft - 10, 4, listRight + 10, height - 4, 0xff16212d);
        PortableScrollGraphics.fill(graphics, listLeft - 10, 4, listRight + 10, 6, 0xff54cfbc);
        PortableScrollGraphics.fill(graphics, listLeft - 2, listTop - 2, listRight + 2, listTop + listHeight + 2, 0xff304456);
        PortableScrollGraphics.fill(graphics, listLeft, listTop, listRight, listTop + listHeight, 0xff101923);
    }

    public boolean scrollbarPress(double x, double y, int button, int action) {
        if (button != 0) return false;
        if (action == 0) { boolean wasDragging = draggingScrollbar; draggingScrollbar = false; return wasDragging; }
        if (action != 1 || maxScrollIndex() == 0 || x < listRight - 9 || x >= listRight || y < listTop || y >= listTop + listHeight) return false;
        int thumb = dev.k2studio.e4steam.publicservers.common.CatalogScroll.thumbHeight(snapshot.items().size(), visibleRows, listHeight);
        int top = listTop + dev.k2studio.e4steam.publicservers.common.CatalogScroll.thumbTop(scrollIndex, snapshot.items().size(), visibleRows, listHeight);
        scrollbarGrab = y >= top && y < top + thumb ? y - top : thumb / 2.0;
        draggingScrollbar = true;
        scrollbarMove(y);
        return true;
    }

    public void scrollbarMove(double y) {
        if (!draggingScrollbar) return;
        scrollIndex = dev.k2studio.e4steam.publicservers.common.CatalogScroll.indexAt(y - listTop - scrollbarGrab, snapshot.items().size(), visibleRows, listHeight);
        syncSnapshot();
    }

    public boolean scrollbarWheel(double x, double y, double amount) {
        if (amount == 0 || maxScrollIndex() == 0 || x < listLeft || x >= listRight || y < listTop || y >= listTop + listHeight) return false;
        scroll(amount > 0 ? -1 : 1);
        return true;
    }

    private void joinSelected() {
        Optional<PublicJoinCoordinator.Snapshot> operation =
                PublicServersClientBridge.joinSnapshot();
        if (operation.map(PublicServersScreen::pending).orElse(false)) {
            PublicServersClientBridge.cancelJoin();
            return;
        }
        PublicServersSnapshot.Item selected = snapshot.selected();
        if (selected != null && selected.compatibility().joinable()) {
            PublicServersClientBridge.join(selected.entry().listingId());
        }
    }

    private void syncSnapshot() {
        snapshot = PublicServersClientBridge.snapshot();
        scrollIndex = Math.min(scrollIndex, maxScrollIndex());
        allButton.active = typeFilter != null;
        worldsButton.active = typeFilter != PublicServerType.INTEGRATED_WORLD;
        dedicatedButton.active = typeFilter != PublicServerType.DEDICATED_SERVER;
        compatibleButton.setMessage(toggleMessage(compatibleOnly,
                "e4steam_public_servers.filter.compatible"));
        slotsButton.setMessage(toggleMessage(freeSlotsOnly,
                "e4steam_public_servers.filter.free_slots"));

        for (int row = 0; row < rowButtons.size(); row++) {
            Button button = rowButtons.get(row);
            int index = scrollIndex + row;
            if (index >= snapshot.items().size()) {
                button.visible = false;
                continue;
            }
            PublicServersSnapshot.Item item = snapshot.items().get(index);
            button.visible = true;
            button.active = true;
            button.setMessage(rowMessage(item));
            PortableMinecraftUi.tooltip(button, rowTooltip(item));
        }

        previousButton.active = scrollIndex > 0;
        nextButton.active = scrollIndex < maxScrollIndex();
        Optional<PublicJoinCoordinator.Snapshot> operation =
                PublicServersClientBridge.joinSnapshot();
        boolean joining = operation.map(PublicServersScreen::pending).orElse(false);
        PublicServersSnapshot.Item selected = snapshot.selected();
        connectButton.active = joining
                || selected != null && selected.compatibility().joinable();
        connectButton.setMessage(PortableComponents.translatable(joining
                ? "e4steam_public_servers.cancel" : "e4steam_public_servers.connect"));
        statusLabel = statusMessage(operation);
    }

    private Component rowMessage(PublicServersSnapshot.Item item) {
        ListingMetadata metadata = item.entry().metadata();
        String marker = item.entry().listingId().equals(snapshot.selectedListingId()) ? "> " : "";
        String type = PortableComponents.translatable(item.entry().type()
                == PublicServerType.INTEGRATED_WORLD
                ? "e4steam_public_servers.type.integrated"
                : "e4steam_public_servers.type.dedicated").getString();
        return PortableComponents.literal(marker + metadata.name() + "  |  " + type + "  |  "
                + metadata.minecraftVersion() + " " + metadata.loader() + "  |  "
                + metadata.players() + "/" + metadata.capacity());
    }

    private Component rowTooltip(PublicServersSnapshot.Item item) {
        ListingMetadata metadata = item.entry().metadata();
        String compatibility = item.compatibility().compatible()
                ? PortableComponents.translatable("e4steam_public_servers.compatible").getString()
                : PortableComponents.translatable("e4steam_public_servers.incompatible").getString();
        return PortableComponents.literal(metadata.description() + "\n" + compatibility
                + " · e4steam " + metadata.e4steamVersion());
    }

    private Component statusMessage(Optional<PublicJoinCoordinator.Snapshot> operation) {
        if (operation.isPresent()) {
            String key = joinStateKey(operation.get().state());
            if (!key.isEmpty()) return PortableComponents.translatable(key);
        }
        if (snapshot.items().isEmpty()) {
            String key = snapshot.messageKey().isEmpty()
                    ? stateKey(snapshot.state()) : snapshot.messageKey();
            return PortableComponents.translatable(key);
        }
        PublicServersSnapshot.Item selected = snapshot.selected();
        if (selected != null) {
            return selected.compatibility().compatible()
                    ? PortableComponents.translatable("e4steam_public_servers.compatible")
                    : PortableComponents.translatable("e4steam_public_servers.incompatible");
        }
        return PortableComponents.translatable("e4steam_public_servers.selection.none");
    }

    private static Component toggleMessage(boolean selected, String key) {
        return PortableComponents.literal((selected ? "[x] " : "[ ] ")
                + PortableComponents.translatable(key).getString());
    }

    private int maxScrollIndex() {
        return Math.max(0, snapshot.items().size() - visibleRows);
    }

    private void closeListener() {
        try {
            listener.close();
        } catch (Exception ignored) {
            // Addon registrations are idempotent.
        }
        listener = () -> { };
    }

    private static boolean pending(PublicJoinCoordinator.Snapshot operation) {
        switch (operation.state()) {
            case RESOLVING:
            case JOINING_STEAM_TARGET:
            case AUTHENTICATING:
            case CONNECTING_MINECRAFT:
                return true;
            default:
                return false;
        }
    }

    private static String joinStateKey(PublicJoinCoordinator.State state) {
        switch (state) {
            case RESOLVING: return "e4steam_public_servers.state.resolving";
            case JOINING_STEAM_TARGET: return "e4steam_public_servers.state.joining";
            case AUTHENTICATING: return "e4steam_public_servers.state.authenticating";
            case CONNECTING_MINECRAFT: return "e4steam_public_servers.state.connecting";
            case CANCELLED: return "e4steam_public_servers.state.cancelled";
            case STALE: return "e4steam_public_servers.state.stale";
            case FULL: return "e4steam_public_servers.full";
            case INCOMPATIBLE: return "e4steam_public_servers.incompatible";
            case DENIED: return "e4steam_public_servers.state.denied";
            case TARGET_LOST: return "e4steam_public_servers.state.target_lost";
            case REGISTRY_UNAVAILABLE: return "e4steam_public_servers.state.registry_unavailable";
            case FAILED: return "e4steam_public_servers.state.failed";
            default: return "";
        }
    }

    private static String stateKey(PublicServersSnapshot.State state) {
        switch (state) {
            case NOT_CONFIGURED: return "e4steam_public_servers.state.not_configured";
            case LOADING: return "e4steam_public_servers.state.loading";
            case CACHED: return "e4steam_public_servers.state.cached";
            case RATE_LIMITED: return "e4steam_public_servers.state.rate_limited";
            case UNAVAILABLE:
            case CLOSED:
                return "e4steam_public_servers.state.registry_unavailable";
            default:
                return "e4steam_public_servers.state.empty";
        }
    }
}
