package dev.k2studio.e4steam.publicservers.mixin;

import dev.k2studio.e4steam.publicservers.client.PublicServersScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Native mouse callbacks avoid the changing Screen mouse-event types in newer releases. */
@Mixin(MouseHandler.class)
public abstract class CatalogScrollbarInputMixin {
    @Inject(method="onPress", at=@At("HEAD"), cancellable=true, require=0)
    private void publicServers$press(long window, int button, int action, int modifiers, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (!(client.screen instanceof PublicServersScreen)) return;
        PublicServersScreen screen = (PublicServersScreen) client.screen;
        double x = client.mouseHandler.xpos() * screen.width / client.getWindow().getScreenWidth();
        double y = client.mouseHandler.ypos() * screen.height / client.getWindow().getScreenHeight();
        if (screen.scrollbarPress(x, y, button, action)) ci.cancel();
    }

    @Inject(method="onMove", at=@At("TAIL"), require=0)
    private void publicServers$move(long window, double x, double y, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (client.screen instanceof PublicServersScreen) {
            PublicServersScreen screen = (PublicServersScreen) client.screen;
            screen.scrollbarMove(y * screen.height / client.getWindow().getScreenHeight());
        }
    }

    @Inject(method="onScroll", at=@At("HEAD"), cancellable=true, require=0)
    private void publicServers$wheel(long window, double horizontal, double vertical, CallbackInfo ci) {
        Minecraft client = Minecraft.getInstance();
        if (!(client.screen instanceof PublicServersScreen)) return;
        PublicServersScreen screen = (PublicServersScreen) client.screen;
        double x = client.mouseHandler.xpos() * screen.width / client.getWindow().getScreenWidth();
        double y = client.mouseHandler.ypos() * screen.height / client.getWindow().getScreenHeight();
        if (screen.scrollbarWheel(x, y, vertical)) ci.cancel();
    }
}
