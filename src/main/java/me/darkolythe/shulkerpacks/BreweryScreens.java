/*
 * ShulkerPacks - open shulker boxes without placing them down
 * Licensed under the GNU General Public License v3.0 (see LICENSE).
 *
 * Added by NagiAsu, 2026-11-13 (1.8.0.5).
 */
package me.darkolythe.shulkerpacks;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Field;
import java.util.Collection;

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
 - for a barrel: this very barrel is in Brewery's list of barrels (a removed barrel is not);
   for a sealing table: it was opened for this player.
Anything else, including any reflection failure (e.g. a Brewery version that renamed these members), is
treated as a plugin GUI and blocks opening, as before. Only reads Brewery's state, never calls into it.
 */
final class BreweryScreens {

    private static final String PLUGIN = "Brewery";
    private static final String BARREL = "com.dre.brewery.Barrel";
    private static final String SEALER = "com.dre.brewery.BSealer";

    // looked up once per Brewery class (a reload of Brewery gives new classes)
    private static Class<?> barrelType;
    private static Field barrelList;
    private static Class<?> sealerType;
    private static Field sealerPlayer;
    private static boolean warned;

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
            return name.equals(BARREL) ? isRegisteredBarrel(type, holder) : openedFor(type, holder, player);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (!warned) {
                warned = true;
                Bukkit.getLogger().warning("[ShulkerPacks] Could not read Brewery's " + name + " (" + e
                        + "); boxes will not open over Brewery screens.");
            }
            return false;
        }
    }

    /*
    Brewery keeps every barrel it knows in a static list and drops a barrel from it when the barrel is removed.
     */
    private static boolean isRegisteredBarrel(Class<?> type, Object barrel) throws ReflectiveOperationException {
        if (type != barrelType) {
            barrelList = type.getField("barrels");
            barrelType = type;
        }
        Object list = barrelList.get(null);
        if (!(list instanceof Collection)) {
            return false;
        }
        for (Object known : (Collection<?>) list) {
            if (known == barrel) {
                return true;
            }
        }
        return false;
    }

    /*
    A sealing table screen is made for one player and remembers who.
     */
    private static boolean openedFor(Class<?> type, Object sealer, Player player) throws ReflectiveOperationException {
        if (type != sealerType) {
            Field field = type.getDeclaredField("player");
            field.setAccessible(true);
            sealerPlayer = field;
            sealerType = type;
        }
        Object opener = sealerPlayer.get(sealer);
        return opener instanceof Player && ((Player) opener).getUniqueId().equals(player.getUniqueId());
    }
}
