/*
 * ShulkerPacks - open shulker boxes without placing them down
 * Original work by Darkolythe (christopherwalkerml/ShulkerPacks), with contributions from Kerm and avixk.
 * Licensed under the GNU General Public License v3.0 (see LICENSE).
 *
 * Modified by NagiAsu, 2026-10-26 (1.8.0.0): open boxes are tracked by ShulkerListener (per-player sessions);
 * removed the old global maps and bStats; open boxes are saved when the plugin is disabled; PvP timer keyed by UUID.
 */
package me.darkolythe.shulkerpacks;

import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.logging.Level;

public final class ShulkerPacks extends JavaPlugin {

    ShulkerListener shulkerlistener;

    private static ShulkerPacks plugin;

    String prefix = ChatColor.WHITE.toString() + ChatColor.BOLD.toString() + "[" + ChatColor.BLUE.toString() + "ShulkerPacks" + ChatColor.WHITE.toString() + ChatColor.BOLD.toString() + "] ";

    private Map<UUID, Long> pvp_timer = new HashMap<>();
    String defaultname = ChatColor.BLUE + "Shulker Pack";
    boolean pvp_timer_enabled = false;
    boolean shiftclicktoopen = false;
    boolean canplaceshulker, canopenininventory, canopeninair;

    int shulker_open_cooldown;
    float volume;

    /*
    Sets up the plugin
     */
    @Override
    public void onEnable() {
        plugin = this;
        shulkerlistener = new ShulkerListener(this);

        getServer().getPluginManager().registerEvents(shulkerlistener, this);

        getCommand("shulkerpacks").setExecutor(new CommandReload());

        ConfigHandler.loadConfig(this);

        shulkerlistener.startChecker();

        getLogger().log(Level.INFO, (prefix + ChatColor.GREEN + "ShulkerPacks has been enabled!"));
    }

    /*
    Saves and closes every open shulker box
     */
    @Override
    public void onDisable() {
        if (shulkerlistener != null) {
            shulkerlistener.closeAll();
        }
        getLogger().log(Level.INFO, (prefix + ChatColor.RED + "ShulkerPacks has been disabled!"));
    }


    public static ShulkerPacks getInstance() {
        return plugin;
    }


    public boolean getPvpTimer(Player player) {
        Long last = pvp_timer.get(player.getUniqueId());
        if (last != null) {
            return System.currentTimeMillis() - last < 7000;
        }
        return false;
    }

    public void setPvpTimer(Player player) {
        if (pvp_timer_enabled) {
            pvp_timer.put(player.getUniqueId(), System.currentTimeMillis());
        }
    }

    public void clearPvpTimer(Player player) {
        pvp_timer.remove(player.getUniqueId());
    }
}