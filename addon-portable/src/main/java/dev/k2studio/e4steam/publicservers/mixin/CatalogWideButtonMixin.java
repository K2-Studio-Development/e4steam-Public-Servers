package dev.k2studio.e4steam.publicservers.mixin;

import dev.k2studio.e4steam.publicservers.client.PortableScrollGraphics;
import dev.k2studio.e4steam.publicservers.client.PublicServersScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Replaces only oversized catalog buttons; vanilla input handling remains unchanged. */
@Mixin(AbstractWidget.class)
public abstract class CatalogWideButtonMixin {
    @Inject(method="/^(render|method_25394|m_88315_|m_6305_)$/", at=@At("HEAD"), cancellable=true, require=0, remap=false)
    private void publicServers$wideButton(@Coerce Object graphics, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (Minecraft.getInstance().screen instanceof PublicServersScreen && (Object) this instanceof Button
                && PortableScrollGraphics.renderWideButton((Button) (Object) this, graphics, mouseX, mouseY)) ci.cancel();
    }
}
