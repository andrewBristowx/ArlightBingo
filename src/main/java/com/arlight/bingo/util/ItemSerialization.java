package com.arlight.bingo.util;

import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

/**
 * Convierte ItemStack (y arreglos de ItemStack, con huecos null incluidos) hacia/desde
 * texto Base64 para poder guardarlos en gamedata.yml. Se usa para persistir el inventario
 * original de los jugadores mientras estan jugando una partida de Bingo, de forma que
 * sobreviva a un reinicio del servidor o a una desconexion a mitad de partida.
 */
public final class ItemSerialization {

    private ItemSerialization() {
    }

    public static String encodeItems(ItemStack[] items) {
        try (ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
             BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(byteStream)) {
            dataOutput.writeInt(items.length);
            for (ItemStack item : items) {
                dataOutput.writeObject(item);
            }
            dataOutput.flush();
            return Base64.getEncoder().encodeToString(byteStream.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo serializar un inventario de Bingo.", e);
        }
    }

    public static ItemStack[] decodeItems(String encoded) throws IOException {
        if (encoded == null || encoded.isEmpty()) return new ItemStack[0];
        byte[] bytes = Base64.getDecoder().decode(encoded);
        try (ByteArrayInputStream byteStream = new ByteArrayInputStream(bytes);
             BukkitObjectInputStream dataInput = new BukkitObjectInputStream(byteStream)) {
            int length = dataInput.readInt();
            ItemStack[] items = new ItemStack[length];
            for (int i = 0; i < length; i++) {
                items[i] = (ItemStack) dataInput.readObject();
            }
            return items;
        } catch (ClassNotFoundException e) {
            throw new IOException("Datos de inventario corruptos en gamedata.yml.", e);
        }
    }

    public static String encodeItem(ItemStack item) {
        return encodeItems(new ItemStack[]{item});
    }

    public static ItemStack decodeItem(String encoded) throws IOException {
        ItemStack[] items = decodeItems(encoded);
        return items.length > 0 ? items[0] : null;
    }
}
