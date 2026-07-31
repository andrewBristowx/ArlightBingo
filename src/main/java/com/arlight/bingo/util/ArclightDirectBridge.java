package com.arlight.bingo.util;

import org.bukkit.Location;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Coloca estados de bloques NeoForge directamente en el ServerLevel de Arclight.
 * Evita ejecutar /setblock para cada fotograma de un cofre o cerradura, por lo
 * que desaparece el spam "El bloque ... ha cambiado" del chat y la consola.
 *
 * Todo se hace por reflexión para mantener ArlightBingo compilable únicamente
 * contra Paper API. Si Arclight cambia internamente, el llamador puede usar su
 * respaldo anterior sin que el plugin deje de cargar.
 */
public final class ArclightDirectBridge {
    private ArclightDirectBridge() { }

    public static boolean setBlockState(Location location, String stateText) {
        if (location == null || location.getWorld() == null || stateText == null || stateText.isBlank()) return false;
        try {
            Parsed parsed = Parsed.parse(stateText);
            Object serverLevel = location.getWorld().getClass().getMethod("getHandle").invoke(location.getWorld());

            Class<?> resourceLocationClass = Class.forName("net.minecraft.resources.ResourceLocation");
            Object resourceLocation = resourceLocationClass.getMethod("parse", String.class).invoke(null, parsed.id());

            Class<?> registriesClass = Class.forName("net.minecraft.core.registries.BuiltInRegistries");
            Field blockRegistryField = registriesClass.getField("BLOCK");
            Object blockRegistry = blockRegistryField.get(null);
            Object block = registryValue(blockRegistry, resourceLocationClass, resourceLocation);
            if (block == null) return false;

            Object state = block.getClass().getMethod("defaultBlockState").invoke(block);
            if (!parsed.properties().isEmpty()) state = applyProperties(state, parsed.properties());

            Class<?> blockPosClass = Class.forName("net.minecraft.core.BlockPos");
            Object pos = blockPosClass.getConstructor(int.class, int.class, int.class)
                    .newInstance(location.getBlockX(), location.getBlockY(), location.getBlockZ());

            Method setter = findSetBlockMethod(serverLevel.getClass(), blockPosClass);
            if (setter == null) return false;
            Object result = setter.invoke(serverLevel, pos, state, 3);
            return !(result instanceof Boolean value) || value;
        } catch (Throwable ignored) {
            return false;
        }
    }


    /**
     * Invoca un método público sin argumentos en la BlockEntity NeoForge existente.
     * Se usa para que los cofres de ArlightBosses conserven su inventario y ejecuten
     * su animación nativa, en lugar de reemplazar el bloque con /setblock.
     */
    public static boolean invokeBlockEntityMethod(Location location, String methodName) {
        if (location == null || location.getWorld() == null || methodName == null || methodName.isBlank()) return false;
        try {
            Object serverLevel = location.getWorld().getClass().getMethod("getHandle").invoke(location.getWorld());
            Class<?> blockPosClass = Class.forName("net.minecraft.core.BlockPos");
            Object pos = blockPosClass.getConstructor(int.class, int.class, int.class)
                    .newInstance(location.getBlockX(), location.getBlockY(), location.getBlockZ());
            Method getter = findCompatibleMethod(serverLevel.getClass(), "getBlockEntity", blockPosClass);
            if (getter == null) return false;
            Object blockEntity = getter.invoke(serverLevel, pos);
            if (blockEntity == null) return false;
            Method target = blockEntity.getClass().getMethod(methodName);
            target.invoke(blockEntity);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Actualiza solamente propiedades del estado que ya existe en el mundo. Al no
     * cambiar el tipo de bloque, Minecraft mantiene la misma BlockEntity y su NBT.
     */
    public static boolean updateExistingBlockProperties(Location location, Map<String, String> properties) {
        if (location == null || location.getWorld() == null || properties == null || properties.isEmpty()) return false;
        try {
            Object serverLevel = location.getWorld().getClass().getMethod("getHandle").invoke(location.getWorld());
            Class<?> blockPosClass = Class.forName("net.minecraft.core.BlockPos");
            Object pos = blockPosClass.getConstructor(int.class, int.class, int.class)
                    .newInstance(location.getBlockX(), location.getBlockY(), location.getBlockZ());
            Method getter = findCompatibleMethod(serverLevel.getClass(), "getBlockState", blockPosClass);
            if (getter == null) return false;
            Object current = getter.invoke(serverLevel, pos);
            Object updated = applyProperties(current, properties);
            Method setter = findSetBlockMethod(serverLevel.getClass(), blockPosClass);
            if (setter == null) return false;
            Object result = setter.invoke(serverLevel, pos, updated, 3);
            return !(result instanceof Boolean value) || value;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Method findCompatibleMethod(Class<?> owner, String name, Class<?> argumentType) {
        for (Method method : owner.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != 1) continue;
            Class<?> parameter = method.getParameterTypes()[0];
            if (parameter.isAssignableFrom(argumentType) || argumentType.isAssignableFrom(parameter)) return method;
        }
        return null;
    }

    private static Method findSetBlockMethod(Class<?> owner, Class<?> blockPosClass) {
        for (Method method : owner.getMethods()) {
            if (!method.getName().equals("setBlock") || method.getParameterCount() != 3) continue;
            Class<?>[] types = method.getParameterTypes();
            if ((types[0].isAssignableFrom(blockPosClass) || blockPosClass.isAssignableFrom(types[0]))
                    && (types[2] == int.class || types[2] == Integer.TYPE)) return method;
        }
        return null;
    }

    private static Object registryValue(Object registry, Class<?> resourceLocationClass, Object key) throws Exception {
        for (String name : new String[]{"getValue", "get"}) {
            try {
                Method method = registry.getClass().getMethod(name, resourceLocationClass);
                Object result = method.invoke(registry, key);
                if (result instanceof Optional<?> optional) return optional.orElse(null);
                if (result != null) return result;
            } catch (NoSuchMethodException ignored) { }
        }
        for (Method method : registry.getClass().getMethods()) {
            if (method.getParameterCount() != 1) continue;
            if (!method.getParameterTypes()[0].isAssignableFrom(resourceLocationClass)
                    && !resourceLocationClass.isAssignableFrom(method.getParameterTypes()[0])) continue;
            Object result = method.invoke(registry, key);
            if (result instanceof Optional<?> optional) return optional.orElse(null);
            if (result != null && !result.getClass().getName().contains("Holder")) return result;
        }
        return null;
    }

    private static Object applyProperties(Object state, Map<String, String> requested) throws Exception {
        Method getProperties = state.getClass().getMethod("getProperties");
        Iterable<?> properties = (Iterable<?>) getProperties.invoke(state);
        for (Object property : properties) {
            String name = String.valueOf(property.getClass().getMethod("getName").invoke(property));
            String requestedValue = requested.get(name);
            if (requestedValue == null) continue;
            Object value = property.getClass().getMethod("getValue", String.class).invoke(property, requestedValue);
            if (value instanceof Optional<?> optional) value = optional.orElse(null);
            if (!(value instanceof Comparable<?> comparable)) continue;
            Method setValue = null;
            for (Method method : state.getClass().getMethods()) {
                if (!method.getName().equals("setValue") || method.getParameterCount() != 2) continue;
                if (Modifier.isStatic(method.getModifiers())) continue;
                setValue = method;
                break;
            }
            if (setValue != null) state = setValue.invoke(state, property, comparable);
        }
        return state;
    }

    private record Parsed(String id, Map<String, String> properties) {
        static Parsed parse(String text) {
            String trimmed = text.trim();
            int bracket = trimmed.indexOf('[');
            if (bracket < 0) return new Parsed(trimmed, Map.of());
            String id = trimmed.substring(0, bracket).trim();
            int end = trimmed.lastIndexOf(']');
            String raw = end > bracket ? trimmed.substring(bracket + 1, end) : trimmed.substring(bracket + 1);
            Map<String, String> properties = new LinkedHashMap<>();
            for (String pair : raw.split(",")) {
                int equals = pair.indexOf('=');
                if (equals <= 0) continue;
                properties.put(pair.substring(0, equals).trim(), pair.substring(equals + 1).trim());
            }
            return new Parsed(id, properties);
        }
    }
}
