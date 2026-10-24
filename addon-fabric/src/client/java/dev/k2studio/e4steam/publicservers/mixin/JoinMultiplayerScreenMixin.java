package dev.k2studio.e4steam.publicservers.mixin;

import dev.k2studio.e4steam.publicservers.client.PublicServersScreen;
import dev.k2studio.e4steam.publicservers.client.PublicServersClientBridge;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.multiplayer.ServerSelectionList;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds the optional catalog entry without replacing Minecraft's server list. */
@Mixin(JoinMultiplayerScreen.class)
public abstract class JoinMultiplayerScreenMixin extends Screen {
    @Unique
    private static final int e4steamPublicServers$BUTTON_WIDTH = 120;

    @Shadow
    protected ServerSelectionList serverSelectionList;

    @Unique
    private Button e4steamPublicServers$button;

    protected JoinMultiplayerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"), require = 1)
    private void e4steamPublicServers$addCatalogButton(CallbackInfo ci) {
        e4steamPublicServers$button = addRenderableWidget(Button.builder(
                        Component.translatable("e4steam_public_servers.button.open"),
                        ignored -> PublicServersClientBridge.setScreen(minecraft,
                                new PublicServersScreen((Screen) (Object) this)))
                .bounds(0, 0, e4steamPublicServers$buttonWidth(), 20)
                .tooltip(Tooltip.create(Component.translatable(
                        "e4steam_public_servers.button.open.tooltip")))
                .build());
        e4steamPublicServers$positionCatalogButton();
    }

    @Inject(method = "repositionElements", at = @At("TAIL"), require = 1)
    private void e4steamPublicServers$repositionCatalogButton(CallbackInfo ci) {
        e4steamPublicServers$positionCatalogButton();
    }

    @Unique
    private int e4steamPublicServers$buttonWidth() {
        return Math.min(e4steamPublicServers$BUTTON_WIDTH, Math.max(84, width / 3));
    }

    @Unique
    private void e4steamPublicServers$positionCatalogButton() {
        if (e4steamPublicServers$button == null) {
            return;
        }
        int buttonWidth = e4steamPublicServers$buttonWidth();
        e4steamPublicServers$button.setWidth(buttonWidth);
        if (width >= 420) {
            e4steamPublicServers$button.setX(width - buttonWidth - 6);
            e4steamPublicServers$button.setY(6);
            return;
        }

        // On narrow GUI scales reserve a small row below the title instead of
        // covering either the title or Minecraft's server entries.
        e4steamPublicServers$button.setX((width - buttonWidth) / 2);
        e4steamPublicServers$button.setY(34);
        if (serverSelectionList != null) {
            int listTop = 58;
            int listHeight = Math.max(36, height - listTop - 60);
            serverSelectionList.updateSizeAndPosition(width, listHeight, listTop);
        }
    }
}
