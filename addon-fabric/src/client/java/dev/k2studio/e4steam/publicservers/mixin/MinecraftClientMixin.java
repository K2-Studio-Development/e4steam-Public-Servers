package dev.k2studio.e4steam.publicservers.mixin;

import dev.k2studio.e4steam.publicservers.client.PublicServersClientBridge;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Captures the local profile name on every loader without a loader event dependency. */
@Mixin(Minecraft.class)
public abstract class MinecraftClientMixin {
    @Inject(method = "run", at = @At("HEAD"), require = 1)
    private void e4steamPublicServers$captureCreatorName(CallbackInfo callback) {
        Minecraft minecraft = (Minecraft) (Object) this;
        if (minecraft.getUser() != null) {
            PublicServersClientBridge.setCreatorName(minecraft.getUser().getName());
        }
    }
}
