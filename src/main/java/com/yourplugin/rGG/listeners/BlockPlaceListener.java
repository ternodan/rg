package com.yourplugin.rGG.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Location;
import org.bukkit.ChatColor;
import org.bukkit.block.Block;

import com.yourplugin.rGG.RGProtectPlugin;

import java.util.List;

public class BlockPlaceListener implements Listener {

    private final RGProtectPlugin plugin;

    public BlockPlaceListener(RGProtectPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        ItemStack item = event.getItemInHand();
        Block block = event.getBlockPlaced();

        if (!isProtectBlock(item)) {
            return;
        }

        String targetPlayer = getTargetPlayerFromItem(item);
        if (targetPlayer == null) {
            player.sendMessage(ChatColor.RED + "Ошибка: не удалось определить владельца!");
            event.setCancelled(true);
            return;
        }

        // Проверяем права на размещение
        if (!player.hasPermission("rgprotect.place") && !player.getName().equals(targetPlayer)) {
            player.sendMessage(ChatColor.RED + "Вы не можете размещать приваты для других игроков!");
            event.setCancelled(true);
            return;
        }

        Location location = block.getLocation();

        // Проверяем, можно ли создать регион в этом месте
        if (!plugin.getProtectRegionManager().canCreateRegion(location, targetPlayer)) {
            // Проверяем конкретную причину для правильного сообщения
            int maxRegions = plugin.getConfig().getInt("limits.max-regions-per-player", 5);
            int playerRegions = plugin.getProtectRegionManager().getPlayerRegionCount(location.getWorld(), targetPlayer);

            if (playerRegions >= maxRegions) {
                player.sendMessage(ChatColor.RED + "Достигнут лимит приватов! (" + playerRegions + "/" + maxRegions + ")");
            } else {
                player.sendMessage(ChatColor.RED + "Здесь нельзя создать приват!");
            }
            event.setCancelled(true);
            return;
        }

        // Создаем регион
        String regionName = plugin.getProtectRegionManager().createRegion(location, targetPlayer);
        if (regionName == null) {
            player.sendMessage(ChatColor.RED + "Ошибка при создании региона!");
            event.setCancelled(true);
            return;
        }

        // Создаем голограмму
        plugin.getHologramManager().createHologram(location.clone().add(0.5, 1.5, 0.5), targetPlayer, regionName);

        // НОВОЕ: Создаем таймер для региона если включено
        if (plugin.getConfig().getBoolean("region-timer.enabled", true)) {
            plugin.getRegionTimerManager().createRegionTimer(regionName, targetPlayer);

            int initialMinutes = plugin.getConfig().getInt("region-timer.initial-lifetime-minutes", 5);
            String timerMessage = plugin.getConfig().getString("messages.region-timer-created",
                    "&eВремя жизни региона: &f{time} минут. &7Не забудьте продлить!");
            timerMessage = timerMessage.replace("{time}", String.valueOf(initialMinutes));
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', timerMessage));
        }

        // Проверяем настройку подсветки по умолчанию
        boolean bordersEnabledByDefault = plugin.getConfig().getBoolean("region-creation.borders-enabled-by-default", true);

        // Устанавливаем состояние подсветки для нового региона
        plugin.getRegionMenuManager().setRegionBordersEnabled(regionName, bordersEnabledByDefault);

        if (plugin.getConfig().getBoolean("debug.log-border-creation", false)) {
            plugin.getLogger().info("DEBUG: Установлено состояние подсветки для нового региона " + regionName + ": " + bordersEnabledByDefault);
        }

        // Создаем физические границы ТОЛЬКО если подсветка включена
        if (bordersEnabledByDefault) {
            com.sk89q.worldguard.protection.regions.ProtectedRegion createdRegion = plugin.getProtectRegionManager().getRegionAt(location);
            if (createdRegion != null) {
                plugin.getVisualizationManager().showCreatedRegionVisualization(player, createdRegion);

                // Используем сообщения из конфига
                String createMessage = plugin.getConfig().getString("messages.region-created", "&aПриват успешно создан!");
                String borderMessage = plugin.getConfig().getString("messages.region-borders-created", "&7Границы региона отмечены &cкрасной шерстью&7 по верхнему периметру.");

                player.sendMessage(ChatColor.translateAlternateColorCodes('&', createMessage.replace("{player}", targetPlayer)));
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', borderMessage));

                // Информируем о возможности отключения
                String toggleHint = plugin.getConfig().getString("messages.borders-toggle-hint",
                        "&7Совет: Вы можете включить/выключить подсветку границ через меню региона (ПКМ по центральному блоку).");
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', toggleHint));
            } else {
                player.sendMessage(ChatColor.RED + "Ошибка: не удалось найти созданный регион!");
                event.setCancelled(true);
                return;
            }
        } else {
            // Подсветка выключена по умолчанию
            String createMessage = plugin.getConfig().getString("messages.region-created", "&aПриват успешно создан!");
            String noBordersMessage = plugin.getConfig().getString("messages.region-created-no-borders",
                    "&7Подсветка границ отключена. Вы можете включить её через меню региона.");

            player.sendMessage(ChatColor.translateAlternateColorCodes('&', createMessage.replace("{player}", targetPlayer)));
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', noBordersMessage));
        }

        // Логируем создание
        plugin.getLogger().info("Игрок " + player.getName() + " создал приват для " + targetPlayer + " в " +
                location.getWorld().getName() + " " + location.getBlockX() + " " +
                location.getBlockY() + " " + location.getBlockZ());

        if (bordersEnabledByDefault) {
            plugin.getLogger().info("Созданы границы из красной шерсти для региона " + regionName);
        } else {
            plugin.getLogger().info("Регион " + regionName + " создан без подсветки границ (выключено по умолчанию)");
        }

        if (plugin.getConfig().getBoolean("region-timer.enabled", true)) {
            plugin.getLogger().info("Создан таймер для региона " + regionName);
        }
    }

    private boolean isProtectBlock(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }

        ItemMeta meta = item.getItemMeta();
        if (!meta.hasLore()) {
            return false;
        }

        List<String> lore = meta.getLore();
        for (String line : lore) {
            if (line.startsWith(ChatColor.DARK_GRAY + "RGProtect:")) {
                return true;
            }
        }

        return false;
    }

    private String getTargetPlayerFromItem(ItemStack item) {
        if (!item.hasItemMeta()) {
            return null;
        }

        ItemMeta meta = item.getItemMeta();
        if (!meta.hasLore()) {
            return null;
        }

        List<String> lore = meta.getLore();
        for (String line : lore) {
            if (line.startsWith(ChatColor.DARK_GRAY + "RGProtect:")) {
                return line.substring((ChatColor.DARK_GRAY + "RGProtect:").length());
            }
        }

        return null;
    }
}