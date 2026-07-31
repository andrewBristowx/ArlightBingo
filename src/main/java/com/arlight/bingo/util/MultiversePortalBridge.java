package com.arlight.bingo.util;

import org.bukkit.PortalType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Method;

/** Integración opcional y sin dependencia de compilación con MV-NetherPortals 5.x. */
public final class MultiversePortalBridge {
    private final JavaPlugin owner;

    public MultiversePortalBridge(JavaPlugin owner) {
        this.owner = owner;
    }

    public boolean link(WorldPoolManager.ArenaGroup group, boolean required) {
        Plugin mvnp = owner.getServer().getPluginManager().getPlugin("Multiverse-NetherPortals");
        if (mvnp == null || !mvnp.isEnabled()) {
            String message = "Multiverse-NetherPortals no está activo; se usarán los portales nativos de Bingo.";
            if (required) owner.getLogger().severe(message);
            else owner.getLogger().info(message);
            return !required;
        }
        try {
            Method add = mvnp.getClass().getMethod("addWorldLink", String.class, String.class, PortalType.class);
            Method get = mvnp.getClass().getMethod("getWorldLink", String.class, PortalType.class);
            Method save = mvnp.getClass().getMethod("saveMVNPConfig");
            add.invoke(mvnp, group.overworld(), group.nether(), PortalType.NETHER);
            add.invoke(mvnp, group.nether(), group.overworld(), PortalType.NETHER);
            add.invoke(mvnp, group.overworld(), group.end(), PortalType.ENDER);
            add.invoke(mvnp, group.end(), group.overworld(), PortalType.ENDER);
            save.invoke(mvnp);
            boolean verified = group.nether().equalsIgnoreCase(String.valueOf(get.invoke(mvnp, group.overworld(), PortalType.NETHER)))
                    && group.end().equalsIgnoreCase(String.valueOf(get.invoke(mvnp, group.overworld(), PortalType.ENDER)));
            if (!verified) owner.getLogger().warning("MV-NetherPortals no devolvió los enlaces esperados para " + group.overworld());
            return verified || !required;
        } catch (ReflectiveOperationException error) {
            owner.getLogger().warning("No se pudo configurar MV-NetherPortals 5.x: " + error.getMessage()
                    + ". Los portales nativos de Bingo seguirán disponibles.");
            return !required;
        }
    }
}
