package de.melanx.simplebackups.compat;

import de.melanx.simplebackups.SimpleBackups;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.InvocationTargetException;

public class Mc2DiscordCompat {

    public static void announce(Component text) {
        try {
            Class<?> manager = Class.forName("fr.denisd3d.mc2discord.core.MessageManager");
            manager.getMethod("sendInfoMessage", String.class, String.class)
                    .invoke(null, SimpleBackups.MODID, text.getString());
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            SimpleBackups.LOGGER.warn("Cannot send backup message to mc2discord", e);
        }
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("mc2discord");
    }
}
