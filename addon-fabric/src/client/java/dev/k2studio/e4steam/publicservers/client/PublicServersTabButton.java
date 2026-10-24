package dev.k2studio.e4steam.publicservers.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.WidgetSprites;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import java.util.function.BooleanSupplier;

/** Minecraft 26.2 Friends-style tab used by the public server catalog. */
final class PublicServersTabButton extends Button {
    private static final WidgetSprites SPRITES = new WidgetSprites(
            Identifier.withDefaultNamespace("widget/button"),
            Identifier.withDefaultNamespace("widget/button_disabled"),
            Identifier.withDefaultNamespace("widget/button_highlighted"),
            Identifier.withDefaultNamespace("widget/button_highlighted")
    );

    private final BooleanSupplier selected;

    PublicServersTabButton(
            int x,
            int y,
            int width,
            Component message,
            Runnable onPress,
            BooleanSupplier selected
    ) {
        super(x, y, width, 20, message, ignored -> onPress.run(), DEFAULT_NARRATION);
        this.selected = selected;
    }

    @Override
    protected void extractContents(
            GuiGraphicsExtractor graphics,
            int mouseX,
            int mouseY,
            float partialTick
    ) {
        boolean isSelected = selected.getAsBoolean();
        PublicServersClientBridge.blitSprite(
                graphics,
                SPRITES.get(isSelected, isHoveredOrFocused()),
                getX(), getY(), getWidth(), getHeight()
        );
        int color = active ? 0xffffffff : 0xffa0a0a0;
        int textY = getY() + (isSelected ? 6 : 7);
        Component label = fittedLabel();
        graphics.centeredText(
                Minecraft.getInstance().font,
                label,
                getX() + getWidth() / 2,
                textY,
                color
        );
        if (isSelected) {
            int underlineWidth = Math.min(
                    Minecraft.getInstance().font.width(label),
                    getWidth() - 6
            );
            int underlineX = getX() + (getWidth() - underlineWidth) / 2;
            graphics.fill(
                    underlineX,
                    getY() + getHeight() - 2,
                    underlineX + underlineWidth,
                    getY() + getHeight() - 1,
                    color
            );
        }
    }

    private Component fittedLabel() {
        var font = Minecraft.getInstance().font;
        String value = getMessage().getString();
        int available = Math.max(1, getWidth() - 8);
        if (font.width(value) <= available) return getMessage();
        String suffix = "...";
        int textWidth = Math.max(0, available - font.width(suffix));
        return Component.literal(font.plainSubstrByWidth(value, textWidth) + suffix);
    }
}
