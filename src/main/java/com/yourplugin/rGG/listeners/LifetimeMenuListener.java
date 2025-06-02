package com.yourplugin.rGG.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.entity.Player;
import org.bukkit.ChatColor;

import com.yourplugin.rGG.RGProtectPlugin;

public class LifetimeMenuListener implements Listener {

    private final RGProtectPlugin plugin;

    public LifetimeMenuListener(RGProtectPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();

        // Проверяем, открыто ли у игрока меню времени жизни
        if (!plugin.getRegionLifetimeMenu().hasOpenMenu(player)) {
            return;
        }

        // Проверяем, что это наше меню
        String menuTitle = ChatColor.translateAlternateColorCodes('&',
                plugin.getConfig().getString("lifetime-menu.title", "&5&lПродление времени жизни"));

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
        plugin.getRegionLifetimeMenu().handleMenuClick(player, event.getSlot(), event.getCurrentItem());
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getPlayer();

        // Проверяем, было ли открыто наше меню
        if (!plugin.getRegionLifetimeMenu().hasOpenMenu(player)) {
            return;
        }

        // Проверяем, что это наше меню
        String menuTitle = ChatColor.translateAlternateColorCodes('&',
                plugin.getConfig().getString("lifetime-menu.title", "&5&lПродление времени жизни"));

        if (!event.getView().getTitle().equals(menuTitle)) {
            return;
        }

        // Убираем игрока из списка открытых меню
        plugin.getRegionLifetimeMenu().closeMenuForPlayer(player);
    }
}