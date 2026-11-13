/*
 * ShulkerPacks - open shulker boxes without placing them down
 * Licensed under the GNU General Public License v3.0 (see LICENSE).
 *
 * Added by NagiAsu, 2026-11-13 (1.8.0.5).
 */
package me.darkolythe.shulkerpacks;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;

/*
The barrel and sealing table screens of the Brewery plugin (optional, looked up at run time by reflection, so
ShulkerPacks neither needs Brewery to build nor to run).

Brewery opens both as custom inventories (Bukkit#createInventory) with its own holder and no location, so the
"is there a real block" test in ShulkerListener#canOpenOver cannot see them. They hold real items like a chest
does, so a box from the player's own inventory may be opened over them too.

The class name alone is not trusted: any plugin can declare a class with the same name. A screen counts as
Brewery's only if
 - the holder class was loaded by the running Brewery plugin itself,
 - the screen is that holder's own inventory, and
 - for a barrel: Brewery still has this very barrel registered at its spigot block;
   for a sealing table: it was opened for this player.
Anything else, including any reflection failure (e.g. a Brewery version that renamed these members), is
treated as a plugin GUI and blocks opening, as before.
 */
final class BreweryScreens {

    private static final String PLUGIN = "Brewery";
    private static final String BARREL = "com.dre.brewery.Barrel";
    private static final String SEALER = "com.dre.brewery.BSealer";

    private BreweryScreens() {
    }

    static boolean isReal(Object holder, Inventory top, Player player) {
        if (!(holder instanceof InventoryHolder)) {
            return false;
        }
        Class<?> type = holder.getClass();
        String name = type.getName();
        if (!name.equals(BARREL) && !name.equals(SEALER)) {
            return false;
        }
        Plugin brewery = Bukkit.getPluginManager().getPlugin(PLUGIN);
        if (brewery == null || !brewery.isEnabled() || type.getClassLoader() != brewery.getClass().getClassLoader()) {
            return false;
        }
        try {
            Inventory own = ((InventoryHolder) holder).getInventory();
            if (own == null || !own.equals(top)) {
                return false;
            }
            return name.equals(BARREL) ? isRegisteredBarrel(type, holder, player) : openedFor(type, holder, player);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return false;
        }
    }

    /*
    Brewery#Barrel.get(spigot) returns the barrel Brewery has registered there; a removed barrel or one that was
    never registered gives null or another object.
     */
    private static boolean isRegisteredBarrel(Class<?> type, Object barrel, Player player) throws ReflectiveOperationException {
        Object spigot = type.getMethod("getSpigot").invoke(barrel);
        if (!(spigot instanceof Block)) {
            return false;
        }
        Block block = (Block) spigot;
        if (!block.getWorld().equals(player.getWorld())
                || !block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) {
            return false;
        }
        return type.getMethod("get", Block.class).invoke(null, block) == barrel;
    }

    /*
    A sealing table screen is made for one player and remembers who.
     */
    private static boolean openedFor(Class<?> type, Object sealer, Player player) throws ReflectiveOperationException {
        Field field = type.getDeclaredField("player");
        field.setAccessible(true);
        return field.get(sealer) == player;
    }
}
