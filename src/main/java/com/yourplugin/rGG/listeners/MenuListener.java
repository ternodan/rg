package com.yourplugin.rGG.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.entity.Player;
import org.bukkit.ChatColor;

import com.yourplugin.rGG.RGProtectPlugin;

public class MenuListener implements Listener {

    private final RGProtectPlugin plugin;

    public MenuListener(RGProtectPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();

        // Проверяем, открыто ли у игрока меню региона
        if (!plugin.getRegionMenuManager().hasOpenMenu(player)) {
            return;
        }

        // Проверяем, что это наше меню
        String menuTitle = ChatColor.translateAlternateColorCodes('&',
                plugin.getConfig().getString("menu.title", "&6&lМеню региона"));

        if (!event.getView().getTitle().equals(menuTitle)) {
            return;
        }

        // Отменяем все клики в нашем меню
        event.setCancelled(true);

        // Проверяем клик по верхнему инвентарю (нашему меню)
        if (event.getClickedInventory() == null ||
                !event.getClickedInventory().equals(event.getView().getTopInventory())) {
            return;
        }

        // Обрабатываем клик
        plugin.getRegionMenuManager().handleMenuClick(player, event.getSlot(), event.getCurrentItem());
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getPlayer();

        // Проверяем, было ли открыто наше меню
        if (!plugin.getRegionMenuManager().hasOpenMenu(player)) {
            return;
        }

        // Проверяем, что это наше меню
        String menuTitle = ChatColor.translateAlternateColorCodes('&',
                plugin.getConfig().getString("menu.title", "&6&lМеню региона"));

        if (!event.getView().getTitle().equals(menuTitle)) {
            return;
        }

        // НОВОЕ: Проверяем, было ли ожидание подтверждения удаления
        if (plugin.getRegionMenuManager().hasPendingDeletion(player)) {
            plugin.getRegionMenuManager().clearPendingDeletion(player);

            String cancelMessage = plugin.getConfig().getString("messages.region-deletion-cancelled",
                    "&7Удаление региона отменено.");
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', cancelMessage));

            if (plugin.getConfig().getBoolean("debug.log-menu-actions", true)) {
                plugin.getLogger().info("DEBUG MENU: Игрок " + player.getName() + " отменил удаление региона, закрыв меню");
            }
        }

        // Убираем игрока из списка открытых меню
        plugin.getRegionMenuManager().closeMenuForPlayer(player);
    }
}