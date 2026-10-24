package dev.k2studio.e4steam.publicservers.client;

import net.minecraft.network.chat.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Component factories that do not link Java 16 builds to post-1.18 helpers. */
public final class PortableComponents {
    private static final String[] TRANSLATABLE_COMPONENTS = {
            "net.minecraft.network.chat.TranslatableComponent",
            "net.minecraft.class_2588"
    };
    private static final String[] TEXT_COMPONENTS = {
            "net.minecraft.network.chat.TextComponent",
            "net.minecraft.class_2585"
    };

    private PortableComponents() {
    }

    public static Component translatable(String key, Object... arguments) {
        Component modern = invokeFactory("translatable", key, arguments);
        if (modern != null) {
            return modern;
        }
        return construct(TRANSLATABLE_COMPONENTS, key, arguments);
    }

    public static Component literal(String value) {
        Component modern = invokeFactory("literal", value);
        if (modern != null) {
            return modern;
        }
        return construct(TEXT_COMPONENTS, value);
    }

    private static Component invokeFactory(String name, Object... arguments) {
        for (Method method : Component.class.getMethods()) {
            String[] names = name.equals("literal") ? new String[]{"literal", "method_43470", "m_237113_"} : new String[]{"translatable", "method_43469", "m_237110_"};
            if (!java.util.Arrays.asList(names).contains(method.getName()) || !Modifier.isStatic(method.getModifiers())
                    || !Component.class.isAssignableFrom(method.getReturnType())) {
                continue;
            }
            try {
                Class<?>[] parameters = method.getParameterTypes();
                if (name.equals("translatable") && parameters.length == 2
                        && parameters[0] == String.class && parameters[1].isArray()) {
                    return (Component) method.invoke(null, arguments[0], arguments[1]);
                }
                if (name.equals("literal") && parameters.length == 1
                        && parameters[0] == String.class) {
                    return (Component) method.invoke(null, arguments[0]);
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // Try the legacy concrete component class.
            }
        }
        return null;
    }

    private static Component construct(String[] classNames, Object... arguments) {
        ReflectiveOperationException lastFailure = null;
        for (String className : classNames) {
            try {
                Class<?> type = Class.forName(className, false, Component.class.getClassLoader());
                for (Constructor<?> constructor : type.getDeclaredConstructors()) {
                    Class<?>[] parameters = constructor.getParameterTypes();
                    if (arguments.length == 1
                            && parameters.length == 1
                            && parameters[0] == String.class) {
                        constructor.setAccessible(true);
                        return (Component) constructor.newInstance(arguments[0]);
                    }
                    if (arguments.length == 2
                            && parameters.length == 2
                            && parameters[0] == String.class
                            && parameters[1].isArray()) {
                        constructor.setAccessible(true);
                        return (Component) constructor.newInstance(arguments[0], arguments[1]);
                    }
                }
            } catch (ReflectiveOperationException failure) {
                lastFailure = failure;
            } catch (RuntimeException failure) {
                throw new IllegalStateException("No compatible Minecraft Component factory was found", failure);
            }
        }
        throw new IllegalStateException("No compatible Minecraft Component constructor was found",
                lastFailure);
    }
}
