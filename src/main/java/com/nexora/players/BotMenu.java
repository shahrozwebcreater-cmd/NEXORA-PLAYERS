package com.nexora.players;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public final class BotMenu implements Listener {
    private static final String TITLE = ChatColor.DARK_AQUA + "NEXORA AI | FightBots";
    private final NexoraPlayers plugin;

    public BotMenu(NexoraPlayers plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void open(Player player) {
        refresh(player);
    }

    private void refresh(Player player) {
        List<String> bots = plugin.getFightBotBridge().activeBotNames();
        Inventory inv = Bukkit.createInventory(null, 54, TITLE);
        for (int i = 0; i < Math.min(45, bots.size()); i++) {
            String name = bots.get(i);
            boolean enabled = plugin.getFightBotBridge().isSelected(name);
            ItemStack item = new ItemStack(enabled ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE);
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName((enabled ? ChatColor.GREEN : ChatColor.RED) + name);
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "FightBot detected automatically");
            lore.add(ChatColor.GRAY + "AI chat: " + (enabled ? ChatColor.GREEN + "ENABLED" : ChatColor.RED + "DISABLED"));
            lore.add(ChatColor.YELLOW + "Click to toggle");
            meta.setLore(lore);
            item.setItemMeta(meta);
            inv.setItem(i, item);
        }
        ItemStack refresh = new ItemStack(Material.SUNFLOWER);
        ItemMeta refreshMeta = refresh.getItemMeta();
        refreshMeta.setDisplayName(ChatColor.YELLOW + "Refresh bot list");
        refreshMeta.setLore(List.of(ChatColor.GRAY + "Re-detect active FightBot bots"));
        refresh.setItemMeta(refreshMeta);
        inv.setItem(49, refresh);
        ItemStack info = new ItemStack(Material.BOOK);
        ItemMeta infoMeta = info.getItemMeta();
        infoMeta.setDisplayName(ChatColor.AQUA + "Detected: " + bots.size());
        infoMeta.setLore(List.of(ChatColor.GRAY + "Click a bot to enable/disable AI chat.",
                ChatColor.GRAY + "Names are refreshed whenever this menu opens."));
        info.setItemMeta(infoMeta);
        inv.setItem(53, info);
        player.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!TITLE.equals(event.getView().getTitle())) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54) return;
        if (slot == 49) {
            refresh(player);
            return;
        }
        if (slot >= 45) return;
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta() || clicked.getItemMeta().getDisplayName() == null) return;
        String name = ChatColor.stripColor(clicked.getItemMeta().getDisplayName());
        if (!plugin.getFightBotBridge().activeBotNames().contains(name)) {
            player.sendMessage(ChatColor.RED + "That bot is no longer active. Refreshing list...");
            refresh(player);
            return;
        }
        boolean enabled = plugin.getFightBotBridge().toggleSelected(name);
        player.sendMessage(ChatColor.AQUA + "[NEXORA AI] " + ChatColor.WHITE + name
                + ChatColor.GRAY + " AI chat " + (enabled ? ChatColor.GREEN + "enabled" : ChatColor.RED + "disabled"));
        refresh(player);
    }
}
