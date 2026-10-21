package de.melanx.simplebackups.compat;

import de.melanx.simplebackups.config.CommonConfig;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.InvocationTargetException;

public class CherishedWorldsCompat {

    public static boolean isFavorite(String worldName) {
        if (!CommonConfig.onlyFavorites()) {
            return true;
        }
        try {
            Class<?> favorites = Class.forName("com.illusivesoulworks.cherishedworlds.client.favorites.FavoritesList");
            return (boolean) favorites.getMethod("contains", String.class).invoke(null, worldName);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Cannot query Cherished Worlds favorites", e);
        }
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("cherishedworlds");
    }
}
