/*
 * ShulkerPacks - open shulker boxes without placing them down
 * Original work by Darkolythe (christopherwalkerml/ShulkerPacks), with contributions from Kerm and avixk.
 * Licensed under the GNU General Public License v3.0 (see LICENSE).
 *
 * Modified by NagiAsu, 2026-10-26 (1.8.0.0):
 *  - Shulker boxes can only be opened from the player's own inventory screen (or from the hand, if enabled).
 *    Chests, ender chests, barrels, hoppers, dispensers, crafters, entity inventories and plugin GUIs are never used.
 *  - The opened box is tracked by its inventory slot plus a temporary marker stored on the item, instead of
 *    ItemStack.equals() and inventory title matching. The marker is removed when the box is closed.
 *  - Clicks that would move the opened box (pick up, number key, offhand swap, drop) are cancelled at LOWEST priority;
 *    opening runs at HIGH priority and ignores clicks other plugins already cancelled.
 *  - A per-tick check closes the box without saving if the item is no longer in the player's inventory.
 *  - The box is opened on the next tick, after re-checking the slot (avoids client inventory desync).
 *
 * Modified by NagiAsu, 2026-10-26 (1.8.0.1):
 *  - While a box opened by this plugin is showing, right clicking another box in the player's own inventory switches
 *    to it: the current box is saved and closed first, then the next one opens (upstream behaviour, lost in 1.8.0.0).
 *
 * Modified by NagiAsu, 2026-10-27 (1.8.0.2):
 *  - "Only from the player's own inventory" now means where the box is: a box in the player's own inventory
 *    (lower half of the screen) can be opened while a chest, barrel, ender chest or other vanilla container is open.
 *    Boxes inside the container (upper half) still never open. Plugin GUIs (custom or no holder) still block opening.
 *  - Closing the box returns to the game, like upstream with open-previous-inventory: false.
 */
package me.darkolythe.shulkerpacks;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.BlockState;
import org.bukkit.block.DoubleChest;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ShulkerListener implements Listener {

    private static final int OFFHAND_SLOT = 40;

    private final ShulkerPacks main;
    private final NamespacedKey markerKey;

    // one open box per player
    private final Map<UUID, Session> sessions = new HashMap<>();
    // players whose box will be opened on the next tick
    private final Set<UUID> pending = new HashSet<>();
    // cool-down
    private final Map<UUID, Long> lastOpen = new HashMap<>();

    static final class Session {
        final String id;
        int slot;
        final Inventory gui;

        Session(String id, int slot, Inventory gui) {
            this.id = id;
            this.slot = slot;
            this.gui = gui;
        }
    }

    public ShulkerListener(ShulkerPacks plugin) {
        this.main = plugin;
        this.markerKey = new NamespacedKey(plugin, "open");
    }

    static boolean isShulker(ItemStack item) {
        return item != null && item.getType().toString().contains("SHULKER_BOX");
    }

    static boolean isEmpty(ItemStack item) {
        return item == null || item.getType() == Material.AIR;
    }

    private String marker(ItemStack item) {
        if (!isShulker(item) || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(markerKey, PersistentDataType.STRING);
    }

    private boolean hasMarker(ItemStack item, String id) {
        return id.equals(marker(item));
    }

    /*
    True if the item is a box that is open right now. A marker left on a box that was taken away while open
    (e.g. by an admin) is stale: remove it so the box behaves normally again.
     */
    private boolean isOpenBox(ItemStack item) {
        String value = marker(item);
        if (value == null) {
            return false;
        }
        for (Session session : sessions.values()) {
            if (session.id.equals(value)) {
                return true;
            }
        }
        stripMarker(item);
        return false;
    }

    /*
    Which screens a box from the player's own inventory may be opened over: the player's own inventory screen,
    a box this plugin opened (switching), or a vanilla container (block, entity, ender chest).
    Plugin GUIs (custom holder, or no holder and no location) are never replaced.
     */
    private boolean canOpenOver(Inventory top, Session current) {
        if (top.getType() == InventoryType.CRAFTING || top.getType() == InventoryType.ENDER_CHEST) {
            return true;
        }
        if (current != null && top == current.gui) {
            return true;
        }
        Object holder = top.getHolder(false);
        if (holder instanceof BlockState || holder instanceof DoubleChest || holder instanceof Entity) {
            return true;
        }
        return holder == null && top.getLocation() != null;
    }

    /*
    True if the click is on a real slot of the player's own inventory holding this exact item
    (some views report special slots, e.g. a crafter's result, as player slots)
     */
    private static boolean isOwnSlot(InventoryClickEvent event, Player player) {
        if (event.getClickedInventory() == null || event.getClickedInventory() != player.getInventory()) {
            return false;
        }
        InventoryType.SlotType type = event.getSlotType();
        if (type != InventoryType.SlotType.CONTAINER && type != InventoryType.SlotType.QUICKBAR) {
            return false;
        }
        ItemStack inSlot = player.getInventory().getItem(event.getSlot());
        ItemStack clicked = event.getCurrentItem();
        return inSlot != null && clicked != null && inSlot.getAmount() == clicked.getAmount() && inSlot.isSimilar(clicked);
    }

    /*
    Opening: only boxes in the player's own inventory
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onOpenClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        if (sessions.containsKey(player.getUniqueId()) || pending.contains(player.getUniqueId())) {
            return;
        }
        // the box must be in the player's own inventory; the upper half may be a vanilla container, never a plugin GUI
        if (!canOpenOver(event.getView().getTopInventory(), null) || !isOwnSlot(event, player)) {
            return;
        }
        if (event.getClick() != (main.shiftclicktoopen ? ClickType.SHIFT_RIGHT : ClickType.RIGHT)) {
            return;
        }
        ItemStack item = event.getCurrentItem();
        if (!isShulker(item) || item.getAmount() != 1) {
            return;
        }
        if (!main.canopenininventory || !player.hasPermission("shulkerpacks.use")
                || !player.hasPermission("shulkerpacks.open_in_inventory")) {
            return;
        }
        // make sure player isn't holding an item on their cursor when opening a shulker box
        if (!isEmpty(event.getCursor())) {
            event.setCancelled(true);
            return;
        }
        if (!checkCooldown(player)) {
            return;
        }
        event.setCancelled(true);
        scheduleOpen(player, event.getSlot());
    }

    /*
    Opens the shulker if the air was clicked with one
     */
    @EventHandler
    public void onClickAir(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack item = event.getItem();
        if (!isShulker(item) || item.getAmount() != 1) {
            return;
        }
        if (!main.canopeninair || !player.hasPermission("shulkerpacks.use") || !player.hasPermission("shulkerpacks.open_in_air")) {
            return;
        }
        if (sessions.containsKey(player.getUniqueId()) || pending.contains(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        if (!checkCooldown(player)) {
            return;
        }
        event.setCancelled(true);
        int slot = event.getHand() == EquipmentSlot.OFF_HAND ? OFFHAND_SLOT : player.getInventory().getHeldItemSlot();
        scheduleOpen(player, slot);
    }

    private boolean checkCooldown(Player player) {
        Long last = lastOpen.get(player.getUniqueId());
        long now = System.currentTimeMillis();
        if (last != null && now - last < main.shulker_open_cooldown) {
            Bukkit.getLogger().warning(player.getName() + " is opening shulker boxes very quickly! They might be hacking!");
            Bukkit.broadcast(Component.text(player.getName() + " is opening shulker boxes very quickly! They might be hacking!"), "shulkerpacks.admin");
            return false;
        }
        lastOpen.put(player.getUniqueId(), now);
        return true;
    }

    private void scheduleOpen(Player player, int slot) {
        UUID uuid = player.getUniqueId();
        pending.add(uuid);
        Bukkit.getScheduler().runTask(main, () -> {
            pending.remove(uuid);
            if (!player.isOnline()) {
                return;
            }
            // re-check everything: one tick has passed
            // the screen must be the player's own inventory, or a box this plugin opened (switching to another box)
            Session current = sessions.get(uuid);
            Inventory top = player.getOpenInventory().getTopInventory();
            boolean switching = current != null && top == current.gui;
            if (current != null && !switching) {
                return;
            }
            if ((!switching && !canOpenOver(top, null)) || !isEmpty(player.getItemOnCursor())) {
                return;
            }
            if (switching && slot == current.slot) {
                return;
            }
            ItemStack item = player.getInventory().getItem(slot);
            if (!isShulker(item) || item.getAmount() != 1 || !(item.getItemMeta() instanceof BlockStateMeta)) {
                return;
            }
            if (main.getPvpTimer(player)) {
                player.sendMessage(main.prefix + ChatColor.RED + "You cannot open shulkerboxes in combat!");
                return;
            }
            if (switching) {
                // save the current box and remove its marker before the next one replaces the screen
                finish(player, current);
                if (hasMarker(item, current.id)) {
                    return;
                }
            }
            open(player, slot, item);
        });
    }

    private void open(Player player, int slot, ItemStack item) {
        String id = UUID.randomUUID().toString();
        ItemMeta marked = item.getItemMeta();
        marked.getPersistentDataContainer().set(markerKey, PersistentDataType.STRING, id);
        item.setItemMeta(marked);
        player.getInventory().setItem(slot, item);

        BlockStateMeta meta = (BlockStateMeta) item.getItemMeta();
        if (!(meta.getBlockState() instanceof ShulkerBox)) {
            removeMarker(player, slot, id);
            return;
        }
        ShulkerBox shulker = (ShulkerBox) meta.getBlockState();
        Component title = meta.hasDisplayName() ? meta.displayName()
                : LegacyComponentSerializer.legacySection().deserialize(main.defaultname);
        Inventory gui = Bukkit.createInventory(new ShulkerHolder(), InventoryType.SHULKER_BOX, title);
        gui.setContents(shulker.getInventory().getContents());

        Session session = new Session(id, slot, gui);
        sessions.put(player.getUniqueId(), session);
        player.openInventory(gui);
        if (player.getOpenInventory().getTopInventory() != gui) {
            // another plugin cancelled the open
            sessions.remove(player.getUniqueId());
            removeMarker(player, slot, id);
            return;
        }
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_SHULKER_BOX_OPEN, SoundCategory.PLAYERS, main.volume, 1);
    }

    /*
    Finds the opened box in the player's inventory (normally at session.slot)
     */
    private ItemStack findBox(Player player, Session session) {
        PlayerInventory inv = player.getInventory();
        ItemStack item = inv.getItem(session.slot);
        if (hasMarker(item, session.id)) {
            return item;
        }
        for (int i = 0; i < inv.getSize(); i++) {
            item = inv.getItem(i);
            if (hasMarker(item, session.id)) {
                session.slot = i;
                return item;
            }
        }
        return null;
    }

    /*
    Saves the gui contents into the opened box; false if the box is gone
     */
    private boolean save(Player player, Session session) {
        ItemStack item = findBox(player, session);
        if (item == null) {
            return false;
        }
        writeContents(item, session.gui);
        player.getInventory().setItem(session.slot, item);
        return true;
    }

    private static void writeContents(ItemStack item, Inventory gui) {
        BlockStateMeta meta = (BlockStateMeta) item.getItemMeta();
        ShulkerBox shulker = (ShulkerBox) meta.getBlockState();
        shulker.getInventory().setContents(gui.getContents());
        meta.setBlockState(shulker);
        item.setItemMeta(meta);
    }

    private void stripMarker(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().remove(markerKey);
        item.setItemMeta(meta);
    }

    private void removeMarker(Player player, int slot, String id) {
        ItemStack item = player.getInventory().getItem(slot);
        if (hasMarker(item, id)) {
            stripMarker(item);
            player.getInventory().setItem(slot, item);
        }
    }

    /*
    Saves the box, removes the marker and forgets the session
     */
    private void finish(Player player, Session session) {
        sessions.remove(player.getUniqueId());
        if (save(player, session)) {
            removeMarker(player, session.slot, session.id);
        } else {
            Bukkit.getLogger().warning("[ShulkerPacks] " + player.getName() + "'s open shulker box is no longer in their inventory; contents were not saved.");
        }
    }

    private void scheduleSave(Player player) {
        UUID uuid = player.getUniqueId();
        Bukkit.getScheduler().runTask(main, () -> {
            Session session = sessions.get(uuid);
            if (session != null && player.isOnline()) {
                save(player, session);
            }
        });
    }

    /*
    Protection: nothing may move the opened box while it is open
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onProtectClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (event.getView().getTopInventory() != session.gui) {
            event.setCancelled(true);
            player.closeInventory();
            return;
        }
        PlayerInventory inv = player.getInventory();
        boolean clickedTop = event.getClickedInventory() == session.gui;
        boolean clickedOwn = event.getClickedInventory() == inv;
        ClickType click = event.getClick();
        int hotbar = event.getHotbarButton();
        if ((clickedOwn && event.getSlot() == session.slot)
                || (click == ClickType.NUMBER_KEY && hotbar == session.slot)
                || (click == ClickType.SWAP_OFFHAND && session.slot == OFFHAND_SLOT)
                // no shulker boxes inside the open box
                || (clickedTop && isShulker(event.getCursor()))
                || (clickedTop && click == ClickType.NUMBER_KEY && hotbar >= 0 && isShulker(inv.getItem(hotbar)))
                || (clickedTop && click == ClickType.SWAP_OFFHAND && isShulker(inv.getItemInOffHand()))
                || (clickedOwn && event.isShiftClick() && isShulker(event.getCurrentItem()))) {
            event.setCancelled(true);
            return;
        }
        // right click another box in the player's own inventory: switch to it
        if (clickedOwn && isOwnSlot(event, player) && click == (main.shiftclicktoopen ? ClickType.SHIFT_RIGHT : ClickType.RIGHT)
                && isShulker(event.getCurrentItem()) && event.getCurrentItem().getAmount() == 1
                && main.canopenininventory && player.hasPermission("shulkerpacks.use")
                && player.hasPermission("shulkerpacks.open_in_inventory")) {
            if (!isEmpty(event.getCursor()) || pending.contains(player.getUniqueId())) {
                event.setCancelled(true);
                return;
            }
            if (checkCooldown(player)) {
                event.setCancelled(true);
                scheduleOpen(player, event.getSlot());
                return;
            }
        }
        scheduleSave(player);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onProtectDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        Session session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        if (isShulker(event.getOldCursor())) {
            for (int raw : event.getRawSlots()) {
                if (raw < session.gui.getSize()) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
        scheduleSave(player);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) {
        ItemStack dropped = event.getItemDrop().getItemStack();
        boolean open = isOpenBox(dropped);
        event.getItemDrop().setItemStack(dropped);
        if (open) {
            event.setCancelled(true);
            Bukkit.broadcast(Component.text(event.getPlayer().getName() + " tried to drop an open shulker box, they might be trying to dupe."), "shulkerpacks.admin");
            Bukkit.getLogger().warning(event.getPlayer().getName() + " tried to drop an open shulker box, they might be trying to dupe.");
        }
    }

    /*
    Saves the shulker if its open, then removes the current open shulker from the player data
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getPlayer();
        Session session = sessions.get(player.getUniqueId());
        if (session == null || event.getInventory() != session.gui) {
            return;
        }
        finish(player, session);
        player.getWorld().playSound(player.getLocation(), Sound.BLOCK_SHULKER_BOX_CLOSE, SoundCategory.PLAYERS, main.volume, 1);
        Bukkit.getScheduler().runTask(main, player::updateInventory);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null) {
            finish(event.getPlayer(), session);
        }
        pending.remove(event.getPlayer().getUniqueId());
        lastOpen.remove(event.getPlayer().getUniqueId());
        main.clearPvpTimer(event.getPlayer());
    }

    /*
    Drops were already copied from the inventory: put the contents into the dropped (or kept) box and remove the marker
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Session session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        List<ItemStack> all = new ArrayList<>(event.getDrops());
        all.addAll(event.getItemsToKeep());
        for (ItemStack item : all) {
            if (hasMarker(item, session.id)) {
                writeContents(item, session.gui);
                stripMarker(item);
            }
        }
        ItemStack item = findBox(player, session);
        if (item != null) {
            writeContents(item, session.gui);
            stripMarker(item);
            player.getInventory().setItem(session.slot, item);
        }
        session.gui.clear();
        player.closeInventory();
    }

    @EventHandler
    public void onShulkerPlace(BlockPlaceEvent event) {
        if (!isShulker(event.getItemInHand())) {
            return;
        }
        if (!main.canplaceshulker || isOpenBox(event.getItemInHand())) {
            event.setCancelled(true);
            return;
        }
        if (marker(event.getItemInHand()) == null) {
            return;
        }
        // stale marker (the box was taken away while open): place the box without it
        ItemStack hand = event.getItemInHand();
        stripMarker(hand);
        event.getPlayer().getInventory().setItem(event.getHand(), hand);
        event.setCancelled(true);
    }

    @EventHandler
    public void onPlayerHit(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player && event.getEntity() instanceof Player) {
            main.setPvpTimer((Player) event.getDamager());
            main.setPvpTimer((Player) event.getEntity());
        }
    }

    @EventHandler
    public void onPlayerShoot(ProjectileHitEvent event) {
        if (event.getHitEntity() instanceof Player && event.getEntity().getShooter() instanceof Player) {
            main.setPvpTimer((Player) event.getEntity().getShooter());
            main.setPvpTimer((Player) event.getHitEntity());
        }
    }

    /*
    Every tick: close a box whose item left the player's inventory (do not save into nothing)
     */
    void startChecker() {
        Bukkit.getScheduler().runTaskTimer(main, () -> {
            for (UUID uuid : new ArrayList<>(sessions.keySet())) {
                Session session = sessions.get(uuid);
                Player player = Bukkit.getPlayer(uuid);
                if (session == null) {
                    continue;
                }
                if (player == null) {
                    sessions.remove(uuid);
                    continue;
                }
                if (player.getOpenInventory().getTopInventory() != session.gui) {
                    finish(player, session);
                } else if (findBox(player, session) == null) {
                    sessions.remove(uuid);
                    session.gui.clear();
                    player.closeInventory();
                    Bukkit.getLogger().warning("[ShulkerPacks] " + player.getName() + "'s open shulker box left their inventory; closed without saving.");
                }
            }
        }, 1L, 1L);
    }

    /*
    Plugin disable: save and close everything
     */
    void closeAll() {
        for (UUID uuid : new ArrayList<>(sessions.keySet())) {
            Session session = sessions.get(uuid);
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && session != null) {
                finish(player, session);
                player.closeInventory();
            }
        }
        sessions.clear();
    }
}
