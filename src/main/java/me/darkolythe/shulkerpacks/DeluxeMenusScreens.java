/*
 * ShulkerPacks - open shulker boxes without placing them down
 * Licensed under the GNU General Public License v3.0 (see LICENSE).
 *
 * Added by NagiAsu, 2026-11-22 (1.8.0.6).
 */
package me.darkolythe.shulkerpacks;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;

/*
The menus of the DeluxeMenus plugin (optional, looked up at run time by reflection, so ShulkerPacks neither
needs DeluxeMenus to build nor to run).

DeluxeMenus opens every menu as a custom inventory (Bukkit#createInventory) with its own holder and no location,
so ShulkerListener#canOpenOver treats it as a plugin GUI. A box from the player's own inventory may be opened over
a DeluxeMenus menu as well: the menu is closed, the box opens, and closing the box returns to the game.
Items inside the menu (the upper half) never open, as with any other screen.

The menu title is not used. The class name alone is not trusted either: any plugin can declare a class with the
same name. A screen counts as a DeluxeMenus menu only if
 - the holder class is DeluxeMenus' menu holder and was loaded by the running DeluxeMenus plugin itself,
 - the screen is that holder's own inventory, and
 - the menu was opened for this player.
Anything else, including any reflection failure (e.g. a DeluxeMenus version that renamed these members), is
treated as a plugin GUI and blocks opening, as before. Only reads DeluxeMenus' state, never changes it.

Limit: every DeluxeMenus menu counts, whatever it is for; menus of other plugins still block opening.
 */
final class DeluxeMenusScreens {

    private static final String PLUGIN = "DeluxeMenus";
    private static final String HOLDER = "com.extendedclip.deluxemenus.menu.MenuHolder";

    // looked up once per DeluxeMenus class (a reload of DeluxeMenus gives a new class)
    private static Class<?> holderType;
    private static Method viewer;
    private static boolean warned;

    private DeluxeMenusScreens() {
    }

    static boolean isMenu(Object holder, Inventory top, Player player) {
        if (!(holder instanceof InventoryHolder)) {
            return false;
        }
        Class<?> type = holder.getClass();
        if (!type.getName().equals(HOLDER)) {
            return false;
        }
        Plugin plugin = Bukkit.getPluginManager().getPlugin(PLUGIN);
        if (plugin == null || !plugin.isEnabled() || type.getClassLoader() != plugin.getClass().getClassLoader()) {
            return false;
        }
        try {
            Inventory own = ((InventoryHolder) holder).getInventory();
            if (own == null || !own.equals(top)) {
                return false;
            }
            if (type != holderType) {
                viewer = type.getMethod("getViewer");
                holderType = type;
            }
            Object opener = viewer.invoke(holder);
            return opener instanceof Player && ((Player) opener).getUniqueId().equals(player.getUniqueId());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            if (!warned) {
                warned = true;
                Bukkit.getLogger().warning("[ShulkerPacks] Could not read DeluxeMenus' menu holder (" + e
                        + "); boxes will not open over DeluxeMenus menus.");
            }
            return false;
        }
    }
}
