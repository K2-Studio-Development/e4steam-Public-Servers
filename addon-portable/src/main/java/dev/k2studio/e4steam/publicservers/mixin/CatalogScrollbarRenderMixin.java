package dev.k2studio.e4steam.publicservers.mixin;

import dev.k2studio.e4steam.publicservers.client.PublicServersScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Screen.class)
public abstract class CatalogScrollbarRenderMixin {
    @Inject(method="/^(render|method_25394|m_88315_|m_6305_)$/", at=@At("HEAD"), require=0, remap=false)
    private void publicServers$background(@Coerce Object graphics, int x, int y, float delta, CallbackInfo ci) {
        if ((Object) this instanceof PublicServersScreen) ((PublicServersScreen) (Object) this).renderCatalogBackground(graphics);
    }
    @Inject(method="/^(render|method_25394|m_88315_|m_6305_)$/", at=@At("TAIL"), require=0, remap=false)
    private void publicServers$scrollbar(@Coerce Object graphics, int x, int y, float delta, CallbackInfo ci) {
        if ((Object) this instanceof PublicServersScreen) ((PublicServersScreen) (Object) this).renderScrollbar(graphics);
    }
}
