package dev.k2studio.e4steam.publicservers.client;

import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.Consumer;

/** Narrow reflection boundary for Button and Tooltip API changes since 1.17. */
public final class PortableMinecraftUi {
    private PortableMinecraftUi() {
    }

    public static Button button(Component message, Consumer<Button> onPress,
                         int x, int y, int width, int height) {
        Button button = tryBuilder(message, onPress, x, y, width, height);
        if (button == null) {
            button = tryConstructor(message, onPress, x, y, width, height);
        }
        if (button == null) {
            throw new IllegalStateException("No compatible Minecraft Button factory was found");
        }
        PortableScrollGraphics.registerButton(button, x, y, width, height);
        return button;
    }

    public static void tooltip(Button button, Component message) {
        for (Method setter : button.getClass().getMethods()) {
            if (setter.getParameterCount() != 1 || Modifier.isStatic(setter.getModifiers())) {
                continue;
            }
            Object tooltip = createTooltip(setter.getParameterTypes()[0], message);
            if (tooltip == null) {
                continue;
            }
            try {
                setter.invoke(button, tooltip);
                return;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Tooltips are optional on Minecraft versions that predate them.
            }
        }
    }

    private static Button tryBuilder(Component message, Consumer<Button> onPress,
                                     int x, int y, int width, int height) {
        for (Method factory : Button.class.getDeclaredMethods()) {
            if (!Modifier.isStatic(factory.getModifiers()) || factory.getParameterCount() != 2) {
                continue;
            }
            Class<?>[] parameters = factory.getParameterTypes();
            if (!Component.class.isAssignableFrom(parameters[0]) || !parameters[1].isInterface()) {
                continue;
            }
            try {
                factory.setAccessible(true);
                Object builder = factory.invoke(null, message, callback(parameters[1], onPress, message));
                if (builder == null || builder instanceof Button) {
                    continue;
                }
                Method bounds = Arrays.stream(builder.getClass().getMethods())
                        .filter(method -> method.getParameterCount() == 4)
                        .filter(method -> Arrays.stream(method.getParameterTypes())
                                .allMatch(type -> type == int.class))
                        .findFirst().orElse(null);
                Method build = Arrays.stream(builder.getClass().getMethods())
                        .filter(method -> method.getParameterCount() == 0)
                        .filter(method -> Button.class.isAssignableFrom(method.getReturnType()))
                        .findFirst().orElse(null);
                if (bounds != null && build != null) {
                    bounds.invoke(builder, x, y, width, height);
                    return (Button) build.invoke(builder);
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Fall through to the pre-builder constructor.
            }
        }
        return null;
    }

    private static Button tryConstructor(Component message, Consumer<Button> onPress,
                                         int x, int y, int width, int height) {
        Constructor<?>[] constructors = Button.class.getDeclaredConstructors();
        Arrays.sort(constructors, Comparator.comparingInt(Constructor::getParameterCount));
        for (Constructor<?> constructor : constructors) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length < 6
                    || parameters[0] != int.class || parameters[1] != int.class
                    || parameters[2] != int.class || parameters[3] != int.class
                    || !Component.class.isAssignableFrom(parameters[4])
                    || !parameters[5].isInterface()) {
                continue;
            }
            Object[] values = new Object[parameters.length];
            values[0] = x;
            values[1] = y;
            values[2] = width;
            values[3] = height;
            values[4] = message;
            values[5] = callback(parameters[5], onPress, message);
            for (int index = 6; index < parameters.length; index++) {
                values[index] = defaultValue(parameters[index], message);
            }
            try {
                constructor.setAccessible(true);
                return (Button) constructor.newInstance(values);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Try another legacy constructor shape.
            }
        }
        return null;
    }

    private static Object createTooltip(Class<?> type, Component message) {
        if (type.isPrimitive() || type == String.class) {
            return null;
        }
        for (Method factory : type.getDeclaredMethods()) {
            if (!Modifier.isStatic(factory.getModifiers())
                    || factory.getParameterCount() != 1
                    || !type.isAssignableFrom(factory.getReturnType())
                    || !Component.class.isAssignableFrom(factory.getParameterTypes()[0])) {
                continue;
            }
            try {
                factory.setAccessible(true);
                return factory.invoke(null, message);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Try another factory.
            }
        }
        return null;
    }

    private static Object callback(Class<?> callbackType, Consumer<Button> onPress,
                                   Component message) {
        return Proxy.newProxyInstance(callbackType.getClassLoader(), new Class<?>[]{callbackType},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        if (method.getName().equals("toString")) return "e4steam Public Servers button";
                        if (method.getName().equals("hashCode")) return System.identityHashCode(proxy);
                        if (method.getName().equals("equals")) {
                            return proxy == (arguments == null ? null : arguments[0]);
                        }
                        return null;
                    }
                    if (arguments != null) {
                        for (Object argument : arguments) {
                            if (argument instanceof Button) {
                                onPress.accept((Button) argument);
                                break;
                            }
                        }
                    }
                    return defaultValue(method.getReturnType(), message);
                });
    }

    private static Object defaultValue(Class<?> type, Component message) {
        if (!type.isPrimitive()) {
            if (type.isAssignableFrom(message.getClass()) || type.isAssignableFrom(Component.class)) {
                return message;
            }
            if (type.isInterface()) {
                return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                        (proxy, method, arguments) -> defaultValue(method.getReturnType(), message));
            }
            return null;
        }
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0F;
        if (type == double.class) return 0D;
        return null;
    }
}
