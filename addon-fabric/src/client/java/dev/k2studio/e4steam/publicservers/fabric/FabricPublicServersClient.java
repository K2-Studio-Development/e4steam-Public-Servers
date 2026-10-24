package dev.k2studio.e4steam.publicservers.fabric;

import dev.k2studio.e4steam.publicservers.client.PublicServersClientBridge;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;

/** Fabric-only lifecycle glue; all catalog state remains loader-neutral. */
public final class FabricPublicServersClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        PublicServersClientBridge.start();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getUser() != null) {
            PublicServersClientBridge.setCreatorName(minecraft.getUser().getName());
        }
    }
}
