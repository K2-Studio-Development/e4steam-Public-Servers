package dev.k2studio.e4steam.publicservers.fabric;

import dev.k2studio.e4steam.publicservers.client.PublicServersClientBridge;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;

/** Fabric client bootstrap shared by the Java 16 and Java 17 variants. */
public final class PortableFabricPublicServersClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        PublicServersClientBridge.start();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getUser() != null) {
            PublicServersClientBridge.setCreatorName(minecraft.getUser().getName());
        }
    }
}
