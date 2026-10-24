package dev.k2studio.e4steam.publicservers.client;

import dev.k2studio.e4steam.publicservers.common.PublicJoinCoordinator;
import dev.k2studio.e4steam.publicservers.common.PublicServersRuntime;
import dev.k2studio.e4steam.publicservers.common.PublicServersSnapshot;
import dev.k2studio.e4steam.publicservers.contract.PublicServerType;
import link.e4steam.api.Registration;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;
import java.util.Optional;

/** Thin client-only adapter around the loader-neutral runtime. */
public final class PublicServersClientBridge {
    private static final ConcurrentHashMap<Class<?>, Object> GUI_PIPELINES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Class<?>, Method> SPRITE_METHODS = new ConcurrentHashMap<>();
    private static final PublicServersSnapshot NOT_CONFIGURED = PublicServersSnapshot.initial(
            PublicServersSnapshot.State.NOT_CONFIGURED,
            Instant.EPOCH,
            "e4steam_public_servers.state.not_configured");

    private PublicServersClientBridge() {
    }

    /** Fabric lifecycle hook; runtime ownership remains with e4steam addon lifecycle. */
    public static void start() {
        // Deliberately lazy: the client-started event supplies the creator name.
    }

    public static void setCreatorName(String creatorName) {
        PublicServersRuntime.setLocalCreatorName(creatorName);
    }

    /**
     * Minecraft 26.1 uses Minecraft.setScreen while 26.3 moved this method to
     * Gui. Resolve it at runtime so one addon artifact works across both APIs.
     */
    public static void setScreen(Minecraft minecraft, Screen screen) {
        Method setter = findScreenSetter(minecraft.gui.getClass());
        Object owner = minecraft.gui;
        if (setter == null) {
            setter = findScreenSetter(minecraft.getClass());
            owner = minecraft;
        }
        if (setter == null) throw new IllegalStateException("Minecraft screen setter is unavailable");
        try {
            setter.invoke(owner, screen);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot change Minecraft screen", failure);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("Cannot change Minecraft screen", cause);
        }
    }

    /**
     * RenderPipelines moved from Blaze3D's pipeline class to RenderPearl in
     * Minecraft 26.3. Resolve both the constant and overload at runtime to keep
     * the shared 26.1–26.3 catalog binary-compatible.
     */
    public static void blitSprite(Object graphics, Identifier sprite,
                                  int x, int y, int width, int height) {
        Class<?> graphicsType = graphics.getClass();
        Object pipeline = GUI_PIPELINES.computeIfAbsent(graphicsType,
                PublicServersClientBridge::loadGuiPipeline);
        Method blit = SPRITE_METHODS.computeIfAbsent(graphicsType,
                type -> findSpriteMethod(type, pipeline, sprite));
        try {
            blit.invoke(graphics, pipeline, sprite, x, y, width, height);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot render Public Servers sprite", failure);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new IllegalStateException("Cannot render Public Servers sprite", cause);
        }
    }

    private static Object loadGuiPipeline(Class<?> graphicsType) {
        try {
            Class<?> pipelines = Class.forName("net.minecraft.client.renderer.RenderPipelines",
                    true, graphicsType.getClassLoader());
            Field field = pipelines.getField("GUI_TEXTURED");
            return field.get(null);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Minecraft GUI texture pipeline is unavailable", failure);
        }
    }

    private static Method findSpriteMethod(Class<?> graphicsType, Object pipeline, Identifier sprite) {
        for (Method method : graphicsType.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (!method.getName().equals("blitSprite") || parameters.length != 6
                    || !parameters[0].isInstance(pipeline) || !parameters[1].isInstance(sprite)) continue;
            boolean integerCoordinates = true;
            for (int i = 2; i < parameters.length; i++) integerCoordinates &= parameters[i] == int.class;
            if (integerCoordinates) return method;
        }
        throw new IllegalStateException("Minecraft GUI sprite rendering method is unavailable");
    }

    private static Method findScreenSetter(Class<?> type) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals("setScreen") && method.getParameterCount() == 1
                    && Screen.class.isAssignableFrom(method.getParameterTypes()[0])) return method;
        }
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals("setScreen") && method.getParameterCount() == 1
                    && Screen.class.isAssignableFrom(method.getParameterTypes()[0])) {
                method.setAccessible(true);
                return method;
            }
        }
        return null;
    }

    /** Fabric shutdown hook; e4steam closes the runtime's ResourceScope. */
    public static void stop() {
        // Screen registrations close themselves; no second executor exists here.
    }

    public static PublicServersSnapshot snapshot() {
        return PublicServersRuntime.current()
                .map(PublicServersRuntime::clientSnapshot)
                .orElse(NOT_CONFIGURED);
    }

    public static void refresh() {
        PublicServersRuntime.current().ifPresent(PublicServersRuntime::refresh);
    }

    public static void search(String value) {
        PublicServersRuntime.current().ifPresent(runtime -> runtime.search(value));
    }

    public static void type(PublicServerType value) {
        PublicServersRuntime.current().ifPresent(runtime -> runtime.type(value));
    }

    public static void compatibleOnly(boolean value) {
        PublicServersRuntime.current().ifPresent(runtime -> runtime.compatibleOnly(value));
    }

    public static void hasSlotsOnly(boolean value) {
        PublicServersRuntime.current().ifPresent(runtime -> runtime.hasSlotsOnly(value));
    }

    public static void select(String listingId) {
        PublicServersRuntime.current().ifPresent(runtime -> runtime.select(listingId));
    }

    public static void join(String listingId) {
        PublicServersRuntime.current().ifPresent(runtime -> runtime.join(listingId));
    }

    public static void cancelJoin() {
        PublicServersRuntime.current().ifPresent(PublicServersRuntime::cancelJoin);
    }

    public static Optional<PublicJoinCoordinator.Snapshot> joinSnapshot() {
        return PublicServersRuntime.current().map(PublicServersRuntime::joinSnapshot);
    }

    public static AutoCloseable listen(Runnable listener) {
        Optional<PublicServersRuntime> runtime = PublicServersRuntime.current();
        if (runtime.isEmpty()) return () -> { };
        Registration registration = runtime.orElseThrow().addListener(listener);
        return registration::close;
    }
}
