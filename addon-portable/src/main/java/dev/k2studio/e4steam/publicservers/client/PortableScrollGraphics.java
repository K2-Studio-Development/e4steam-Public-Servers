package dev.k2studio.e4steam.publicservers.client;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;

/** The scrollbar uses only solid rectangles across PoseStack and GuiGraphics. */
public final class PortableScrollGraphics {
    private static final java.util.Map<Button, int[]> BOUNDS = new java.util.WeakHashMap<>();
    private PortableScrollGraphics() { }
    public static void registerButton(Button button, int x, int y, int width, int height) {
        BOUNDS.put(button, new int[]{x,y,width,height});
    }
    public static boolean renderWideButton(Button button, Object graphics, int mouseX, int mouseY) {
        int[] bounds = BOUNDS.get(button);
        if (bounds == null || bounds[2] <= 400 || !button.visible) return false;
        int x=bounds[0], y=bounds[1], w=bounds[2], h=bounds[3];
        boolean hover = mouseX >= x && mouseX < x+w && mouseY >= y && mouseY < y+h;
        fill(graphics, x,y,x+w,y+h, button.active && hover ? 0xff789baf : 0xff465969);
        fill(graphics, x+1,y+1,x+w-1,y+h-1, button.active ? hover ? 0xff3a5366 : 0xff303e4c : 0xff26313d);
        net.minecraft.client.gui.Font font = Minecraft.getInstance().font;
        String text = button.getMessage().getString();
        while (!text.isEmpty() && font.width(text) > w-16) text = text.substring(0,text.offsetByCodePoints(text.length(),-1));
        centeredText(graphics,font,PortableComponents.literal(text),x+w/2,y+(h-8)/2,button.active ? 0xffedf5fa : 0xff8b9aa7);
        return true;
    }
    public static void centeredText(Object graphics, Object font, Object text, int x, int y, int color) {
        for (Class<?> start : new Class<?>[]{graphics.getClass(), Screen.class}) {
            for (Class<?> type = start; type != null; type = type.getSuperclass()) {
                for (Method method : type.getDeclaredMethods()) {
                    if (!java.util.Set.of("drawCenteredString", "method_27534", "m_280653_", "m_93215_").contains(method.getName())) continue;
                    boolean legacy = Modifier.isStatic(method.getModifiers());
                    Object[] args = legacy ? new Object[]{graphics,font,text,x,y,color | 0xff000000} : new Object[]{font,text,x,y,color | 0xff000000};
                    Class<?>[] parameters = method.getParameterTypes();
                    if (parameters.length != args.length) continue;
                    boolean match = true;
                    for (int i=0; i<parameters.length; i++) if (!(parameters[i].isInstance(args[i]) || parameters[i] == int.class && args[i] instanceof Integer)) match = false;
                    if (!match) continue;
                    try { method.setAccessible(true); method.invoke(legacy ? null : graphics, args); return; }
                    catch (ReflectiveOperationException failure) { throw new IllegalStateException("Catalog text rendering failed", failure); }
                }
            }
        }
    }
    public static void fill(Object graphics, int x1, int y1, int x2, int y2, int color) {
        for (Class<?> start : new Class<?>[]{graphics.getClass(), Screen.class}) {
            for (Class<?> type = start; type != null; type = type.getSuperclass()) {
                for (Method method : type.getDeclaredMethods()) {
                    if (!java.util.Set.of("fill", "method_25294", "m_280509_", "m_93172_").contains(method.getName())) continue;
                    boolean legacy = Modifier.isStatic(method.getModifiers());
                    Class<?>[] parameters = method.getParameterTypes();
                    int offset = legacy ? 1 : 0;
                    if (parameters.length != 5 + offset || legacy && !parameters[0].isInstance(graphics)) continue;
                    boolean match = true;
                    for (int i = offset; i < parameters.length; i++) if (parameters[i] != int.class) match = false;
                    if (!match) continue;
                    try {
                        method.setAccessible(true);
                        Object[] args = legacy ? new Object[]{graphics,x1,y1,x2,y2,color} : new Object[]{x1,y1,x2,y2,color};
                        method.invoke(legacy ? null : graphics, args);
                        return;
                    } catch (ReflectiveOperationException failure) {
                        throw new IllegalStateException("Scrollbar rendering failed", failure);
                    }
                }
            }
        }
    }
}
