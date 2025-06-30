package com.yourplugin.rGG.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Location;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.block.Block;

import com.yourplugin.rGG.RGProtectPlugin;

import java.util.List;
import java.util.UUID;

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

        // УЛУЧШЕННАЯ проверка с детальной диагностикой
        if (!plugin.getProtectRegionManager().canCreateRegion(location, targetPlayer)) {
            handleRegionCreationFailure(player, location, targetPlayer);
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

        // ИСПРАВЛЕНИЕ: Создаем голограмму ПОСЛЕ успешного создания региона
        Location hologramLocation = location.clone().add(0.5, 1.5, 0.5);
        plugin.getHologramManager().createHologram(hologramLocation, targetPlayer, regionName);

        // Сохраняем информацию о голограмме для восстановления после перезагрузки
        plugin.getHologramManager().saveHologramData(regionName, hologramLocation, targetPlayer);

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

        plugin.getLogger().info("Создана голограмма для региона " + regionName + " в позиции " + hologramLocation);
    }

    /**
     * НОВЫЙ метод для детальной диагностики проблем создания региона
     */
    private void handleRegionCreationFailure(Player player, Location location, String targetPlayer) {
        World world = location.getWorld();

        // Сначала проверяем лимит регионов
        int maxRegions = plugin.getConfig().getInt("limits.max-regions-per-player", 5);
        int playerRegions = plugin.getProtectRegionManager().getPlayerRegionCount(world, targetPlayer);

        if (playerRegions >= maxRegions) {
            player.sendMessage("");
            player.sendMessage(ChatColor.RED + "❌ Достигнут лимит приватов!");
            player.sendMessage(ChatColor.YELLOW + "У игрока " + ChatColor.WHITE + targetPlayer +
                    ChatColor.YELLOW + ": " + ChatColor.WHITE + playerRegions + "/" + maxRegions +
                    ChatColor.YELLOW + " регионов");
            player.sendMessage(ChatColor.GRAY + "💡 Удалите ненужные приваты или обратитесь к администратору");
            player.sendMessage("");
            return;
        }

        // Если лимит не достигнут, значит проблема в пересечении
        analyzeRegionCollisions(player, location, targetPlayer);
    }

    /**
     * НОВЫЙ метод для анализа пересечений с подробными сообщениями
     */
    private void analyzeRegionCollisions(Player player, Location location, String targetPlayer) {
        World world = location.getWorld();
        Object regionManager = plugin.getProtectRegionManager().getWorldGuardRegionManager(world);

        if (regionManager == null) {
            player.sendMessage(ChatColor.RED + "Ошибка: WorldGuard не настроен для этого мира!");
            return;
        }

        // Вычисляем границы планируемого региона
        int sizeX = plugin.getConfig().getInt("region.size.x", 3);
        int sizeY = plugin.getConfig().getInt("region.size.y", 3);
        int sizeZ = plugin.getConfig().getInt("region.size.z", 3);

        int centerX = location.getBlockX();
        int centerY = location.getBlockY();
        int centerZ = location.getBlockZ();

        player.sendMessage("");
        player.sendMessage(ChatColor.RED + "❌ Невозможно создать приват!");
        player.sendMessage(ChatColor.YELLOW + "Причина: " + ChatColor.WHITE + "Пересечение с другими регионами");
        player.sendMessage("");
        player.sendMessage(ChatColor.GRAY + "📍 Позиция: " + centerX + ", " + centerY + ", " + centerZ);
        player.sendMessage(ChatColor.GRAY + "📏 Размер: " + sizeX + "x" + sizeY + "x" + sizeZ + " блоков");
        player.sendMessage("");

        // Ищем конкретные пересекающиеся регионы
        try {
            java.lang.reflect.Method getRegionsMethod = regionManager.getClass().getMethod("getRegions");
            @SuppressWarnings("unchecked")
            java.util.Map<String, com.sk89q.worldguard.protection.regions.ProtectedRegion> regions =
                    (java.util.Map<String, com.sk89q.worldguard.protection.regions.ProtectedRegion>)
                            getRegionsMethod.invoke(regionManager);

            // Создаем тестовый регион
            int radiusX = (sizeX - 1) / 2;
            int radiusY = (sizeY - 1) / 2;
            int radiusZ = (sizeZ - 1) / 2;

            com.sk89q.worldedit.math.BlockVector3 min = com.sk89q.worldedit.math.BlockVector3.at(
                    centerX - radiusX, centerY - radiusY, centerZ - radiusZ);
            com.sk89q.worldedit.math.BlockVector3 max = com.sk89q.worldedit.math.BlockVector3.at(
                    centerX + radiusX, centerY + radiusY, centerZ + radiusZ);

            com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion testRegion =
                    new com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion("test", min, max);

            java.util.List<String> conflictingRegions = new java.util.ArrayList<>();
            java.util.List<String> conflictingOwners = new java.util.ArrayList<>();

            for (com.sk89q.worldguard.protection.regions.ProtectedRegion existingRegion : regions.values()) {
                if (hasRegionIntersection(testRegion, existingRegion)) {
                    String ownerName = getRegionOwnerName(existingRegion);

                    // Проверяем, не свой ли это регион
                    if (!isPlayerOwner(existingRegion, targetPlayer)) {
                        conflictingRegions.add(existingRegion.getId());
                        if (!conflictingOwners.contains(ownerName)) {
                            conflictingOwners.add(ownerName);
                        }
                    }
                }
            }

            if (!conflictingOwners.isEmpty()) {
                player.sendMessage(ChatColor.RED + "🚫 Пересечение с регионами игроков:");
                for (String owner : conflictingOwners) {
                    player.sendMessage(ChatColor.RED + "   • " + ChatColor.WHITE + owner);
                }
                player.sendMessage("");

                if (plugin.getConfig().getBoolean("debug.show-region-details", false)) {
                    player.sendMessage(ChatColor.GRAY + "🔍 Конфликтующие регионы:");
                    for (String regionId : conflictingRegions) {
                        player.sendMessage(ChatColor.GRAY + "   • " + regionId);
                    }
                    player.sendMessage("");
                }

                player.sendMessage(ChatColor.YELLOW + "💡 Советы:");
                player.sendMessage(ChatColor.GRAY + "   • Выберите другое место");
                player.sendMessage(ChatColor.GRAY + "   • Отойдите подальше от чужих приватов");
                player.sendMessage(ChatColor.GRAY + "   • Договоритесь с соседями о границах");

                // Показываем ближайшее свободное место
                String direction = findBestDirection(player, location, testRegion, regions);
                if (direction != null) {
                    player.sendMessage(ChatColor.GRAY + "   • Попробуйте переместиться " + direction);
                }
            } else {
                player.sendMessage(ChatColor.YELLOW + "Пересечение с собственными регионами - разрешено");
            }

        } catch (Exception e) {
            player.sendMessage(ChatColor.RED + "Ошибка при анализе пересечений: " + e.getMessage());
            plugin.getLogger().severe("Ошибка в analyzeRegionCollisions: " + e.getMessage());
        }

        player.sendMessage("");
    }

    /**
     * НОВЫЙ метод для поиска лучшего направления для размещения
     */
    private String findBestDirection(Player player, Location location,
                                     com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion testRegion,
                                     java.util.Map<String, com.sk89q.worldguard.protection.regions.ProtectedRegion> regions) {

        int sizeX = plugin.getConfig().getInt("region.size.x", 3);
        int sizeZ = plugin.getConfig().getInt("region.size.z", 3);
        int distance = Math.max(sizeX, sizeZ) + 2; // Минимальное расстояние + буфер

        // Проверяем 4 направления
        String[] directions = {"на север", "на юг", "на запад", "на восток"};
        int[][] offsets = {{0, -distance}, {0, distance}, {-distance, 0}, {distance, 0}};

        for (int i = 0; i < directions.length; i++) {
            Location testLoc = location.clone().add(offsets[i][0], 0, offsets[i][1]);

            if (wouldRegionFitAt(testLoc, regions)) {
                return directions[i] + " (" + Math.abs(offsets[i][0] + offsets[i][1]) + " блоков)";
            }
        }

        return null;
    }

    /**
     * НОВЫЙ метод для проверки, поместится ли регион в указанном месте
     */
    private boolean wouldRegionFitAt(Location location,
                                     java.util.Map<String, com.sk89q.worldguard.protection.regions.ProtectedRegion> regions) {
        try {
            int sizeX = plugin.getConfig().getInt("region.size.x", 3);
            int sizeY = plugin.getConfig().getInt("region.size.y", 3);
            int sizeZ = plugin.getConfig().getInt("region.size.z", 3);

            int centerX = location.getBlockX();
            int centerY = location.getBlockY();
            int centerZ = location.getBlockZ();

            int radiusX = (sizeX - 1) / 2;
            int radiusY = (sizeY - 1) / 2;
            int radiusZ = (sizeZ - 1) / 2;

            com.sk89q.worldedit.math.BlockVector3 min = com.sk89q.worldedit.math.BlockVector3.at(
                    centerX - radiusX, centerY - radiusY, centerZ - radiusZ);
            com.sk89q.worldedit.math.BlockVector3 max = com.sk89q.worldedit.math.BlockVector3.at(
                    centerX + radiusX, centerY + radiusY, centerZ + radiusZ);

            com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion testRegion =
                    new com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion("test", min, max);

            // Проверяем пересечения
            for (com.sk89q.worldguard.protection.regions.ProtectedRegion existingRegion : regions.values()) {
                if (hasRegionIntersection(testRegion, existingRegion)) {
                    return false;
                }
            }

            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * ВСПОМОГАТЕЛЬНЫЕ методы для проверки пересечений и владельцев
     */
    private boolean hasRegionIntersection(com.sk89q.worldguard.protection.regions.ProtectedRegion region1,
                                          com.sk89q.worldguard.protection.regions.ProtectedRegion region2) {
        com.sk89q.worldedit.math.BlockVector3 min1 = region1.getMinimumPoint();
        com.sk89q.worldedit.math.BlockVector3 max1 = region1.getMaximumPoint();
        com.sk89q.worldedit.math.BlockVector3 min2 = region2.getMinimumPoint();
        com.sk89q.worldedit.math.BlockVector3 max2 = region2.getMaximumPoint();

        return !(max1.x() < min2.x() || min1.x() > max2.x() ||
                max1.y() < min2.y() || min1.y() > max2.y() ||
                max1.z() < min2.z() || min1.z() > max2.z());
    }

    private String getRegionOwnerName(com.sk89q.worldguard.protection.regions.ProtectedRegion region) {
        if (!region.getOwners().getUniqueIds().isEmpty()) {
            UUID ownerUUID = region.getOwners().getUniqueIds().iterator().next();
            String ownerName = plugin.getServer().getOfflinePlayer(ownerUUID).getName();
            return ownerName != null ? ownerName : "Неизвестно";
        }
        if (!region.getOwners().getPlayers().isEmpty()) {
            return region.getOwners().getPlayers().iterator().next();
        }
        return "Неизвестно";
    }

    private boolean isPlayerOwner(com.sk89q.worldguard.protection.regions.ProtectedRegion region, String playerName) {
        UUID playerUUID = getPlayerUUID(playerName);
        if (playerUUID == null) {
            return false;
        }
        return region.getOwners().contains(playerUUID) || region.getOwners().contains(playerName);
    }

    private UUID getPlayerUUID(String playerName) {
        try {
            Player onlinePlayer = plugin.getServer().getPlayer(playerName);
            if (onlinePlayer != null) {
                return onlinePlayer.getUniqueId();
            } else {
                return plugin.getServer().getOfflinePlayer(playerName).getUniqueId();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * ОРИГИНАЛЬНЫЕ методы для проверки блока привата
     */
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