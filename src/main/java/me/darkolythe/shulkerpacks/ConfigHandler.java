/*
 * ShulkerPacks - open shulker boxes without placing them down
 * Original work by Darkolythe (christopherwalkerml/ShulkerPacks), with contributions from Kerm and avixk.
 * Licensed under the GNU General Public License v3.0 (see LICENSE).
 *
 * Modified by NagiAsu, 2026-10-26 (1.8.0.0): canopeninchests, canopeninenderchest, canopeninbarrels,
 * canopeninvirtualinventory, blacklistedinventories and open-previous-inventory are no longer used (boxes only open
 * from the player's own inventory); shulker_open_cooldown defaults to 100 ms when missing, same as the bundled config.
 */
package me.darkolythe.shulkerpacks;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;

public class ConfigHandler {

    static void loadConfig(ShulkerPacks main) {
        main.reloadConfig();
        FileConfiguration config = main.getConfig();

        main.saveDefaultConfig();
        main.canopenininventory = config.getBoolean("canopenininventory", true);
        main.canplaceshulker = config.getBoolean("canplaceshulker", true);
        main.canopeninair = config.getBoolean("canopeninair", true);
        main.volume = (float) config.getDouble("shulkervolume", 1.0);
        main.pvp_timer_enabled = config.getBoolean("disable-in-combat", true);
        main.shulker_open_cooldown = config.getInt("shulker_open_cooldown", 100);
        if (config.getString("defaultname") != null) {
            main.defaultname = ChatColor.translateAlternateColorCodes('&', config.getString("defaultname"));
        }
        main.shiftclicktoopen = config.getBoolean("shiftclicktoopen");
    }
}