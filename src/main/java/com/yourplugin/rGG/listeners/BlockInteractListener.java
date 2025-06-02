package com.yourplugin.rGG.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.entity.Player;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.ChatColor;

import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.yourplugin.rGG.RGProtectPlugin;

public class BlockInteractListener implements Listener {

    private final RGProtectPlugin plugin;

    public BlockInteractListener(RGProtectPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Проверяем, что игрок кликнул по блоку (левой или правой кнопкой)
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.LEFT_CLICK_BLOCK) {
            return;
        }

        Player player = event.getPlayer();
        Block block = event.getClickedBlock();

        if (block == null) {
            return;
        }

        Location location = block.getLocation();

        // Проверяем, есть ли регион в этом месте
        ProtectedRegion region = plugin.getProtectRegionManager().getRegionAt(location);

        if (region == null) {
            return; // Нет региона - обычное взаимодействие
        }

        // Проверяем, является ли это центральным блоком региона
        if (isCenterBlock(location, region)) {
            // Проверяем права доступа к региону
            if (!canPlayerAccessRegion(player, region)) {
                player.sendMessage(ChatColor.RED + "У вас нет доступа к этому региону!");
                event.setCancelled(true);
                return;
            }

            // Открываем меню региона
            plugin.getRegionMenuManager().openRegionMenu(player, region);

            // Отменяем стандартное взаимодействие с блоком
            event.setCancelled(true);

            // Добавляем отладочную информацию
            if (plugin.getConfig().getBoolean("debug.enabled", true)) {
                plugin.getLogger().info("DEBUG INTERACT: Игрок " + player.getName() + " открыл меню региона " + region.getId());
            }
        }
    }

    /**
     * Проверяет, может ли игрок получить доступ к региону
     */
    private boolean canPlayerAccessRegion(Player player, ProtectedRegion region) {
        // Владелец может всегда
        if (region.getOwners().contains(player.getUniqueId()) ||
                region.getOwners().contains(player.getName())) {
            return true;
        }

        // Члены региона могут (если добавлены)
        if (region.getMembers().contains(player.getUniqueId()) ||
                region.getMembers().contains(player.getName())) {
            return true;
        }

        // Админы могут всегда
        return player.hasPermission("rgprotect.admin");
    }

    /**
     * ИСПРАВЛЕННЫЙ метод проверки центрального блока
     * Теперь правильно вычисляет центр для расширенных регионов
     */
    private boolean isCenterBlock(Location blockLocation, ProtectedRegion region) {
        // Получаем границы региона
        int regionMinX = region.getMinimumPoint().x();
        int regionMaxX = region.getMaximumPoint().x();
        int regionMinY = region.getMinimumPoint().y();
        int regionMaxY = region.getMaximumPoint().y();
        int regionMinZ = region.getMinimumPoint().z();
        int regionMaxZ = region.getMaximumPoint().z();

        // ИСПРАВЛЕНИЕ: Правильно вычисляем центр для любого размера региона
        // Центр - это середина между min и max (с учетом четных/нечетных размеров)
        int centerX = (regionMinX + regionMaxX) / 2;
        int centerY = (regionMinY + regionMaxY) / 2;
        int centerZ = (regionMinZ + regionMaxZ) / 2;

        // Добавляем отладочную информацию
        if (plugin.getConfig().getBoolean("debug.enabled", true)) {
            plugin.getLogger().info("DEBUG INTERACT: Проверка центра для региона " + region.getId());
            plugin.getLogger().info("DEBUG INTERACT: Клик по блоку: " + blockLocation.getBlockX() + "," + blockLocation.getBlockY() + "," + blockLocation.getBlockZ());
            plugin.getLogger().info("DEBUG INTERACT: Границы региона: min(" + regionMinX + "," + regionMinY + "," + regionMinZ + ") max(" + regionMaxX + "," + regionMaxY + "," + regionMaxZ + ")");
            plugin.getLogger().info("DEBUG INTERACT: Вычисленный центр: " + centerX + "," + centerY + "," + centerZ);

            int currentSizeX = regionMaxX - regionMinX + 1;
            int currentSizeY = regionMaxY - regionMinY + 1;
            int currentSizeZ = regionMaxZ - regionMinZ + 1;
            plugin.getLogger().info("DEBUG INTERACT: Размер региона: " + currentSizeX + "x" + currentSizeY + "x" + currentSizeZ);
        }

        boolean isCenter = blockLocation.getBlockX() == centerX &&
                blockLocation.getBlockY() == centerY &&
                blockLocation.getBlockZ() == centerZ;

        if (plugin.getConfig().getBoolean("debug.enabled", true)) {
            plugin.getLogger().info("DEBUG INTERACT: Это центральный блок? " + isCenter);
        }

        return isCenter;
    }
}