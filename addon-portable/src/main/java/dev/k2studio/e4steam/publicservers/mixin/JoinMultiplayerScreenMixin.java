package dev.k2studio.e4steam.publicservers.mixin;

import dev.k2studio.e4steam.publicservers.client.PortableComponents;
import dev.k2studio.e4steam.publicservers.client.PortableMinecraftUi;
import dev.k2studio.e4steam.publicservers.client.PublicServersClientBridge;
import dev.k2studio.e4steam.publicservers.client.PublicServersScreen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds the catalog button through the stable multiplayer-screen init hook. */
@Mixin(JoinMultiplayerScreen.class)
public abstract class JoinMultiplayerScreenMixin extends Screen {
    protected JoinMultiplayerScreenMixin(Component title) {
        super(title);
    }

    @Inject(method = "init", at = @At("TAIL"), require = 1)
    private void e4steamPublicServers$addCatalogButton(CallbackInfo callback) {
        PublicServersClientBridge.setCreatorName(minecraft.getUser().getName());
        int buttonWidth = Math.min(120, Math.max(84, width / 3));
        int x = width >= 420 ? width - buttonWidth - 6 : (width - buttonWidth) / 2;
        int y = width >= 420 ? 6 : 34;
        Button button = PortableMinecraftUi.button(
                PortableComponents.translatable("e4steam_public_servers.button.open"),
                ignored -> minecraft.setScreen(new PublicServersScreen((Screen) (Object) this)),
                x, y, buttonWidth, 20);
        PortableMinecraftUi.tooltip(button, PortableComponents.translatable(
                "e4steam_public_servers.button.open.tooltip"));
        addRenderableWidget(button);
    }
}
