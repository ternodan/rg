package com.yourplugin.rGG.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.entity.Player;
import org.bukkit.Location;
import org.bukkit.ChatColor;
import org.bukkit.block.Block;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.yourplugin.rGG.RGProtectPlugin;

import java.util.List;
import java.util.ArrayList;

public class BlockBreakListener implements Listener {

    private final RGProtectPlugin plugin;

    public BlockBreakListener(RGProtectPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Location location = block.getLocation();

        // Проверяем, пытается ли игрок сломать блок границы региона
        if (isRegionBorderMaterial(block.getType()) && plugin.getConfig().getBoolean("visualization.physical-borders.prevent-breaking", true)) {
            if (isRegionBorderBlock(location)) {
                String message = plugin.getConfig().getString("messages.cannot-break-border", "&cНельзя ломать границы региона! Удалите центральный блок привата.");
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
                event.setCancelled(true);
                return;
            }
        }

        // Проверяем, есть ли регион в этом месте
        ProtectedRegion region = plugin.getProtectRegionManager().getRegionAt(location);

        if (region == null) {
            return; // Нет региона - обычное ломание блока
        }

        // Проверяем, является ли это центральным блоком региона
        if (isCenterBlock(location, region)) {
            handleRegionBlockBreak(event, player, region, location);
        }
    }

    private boolean isRegionBorderMaterial(Material material) {
        // Получаем материал границ из конфига
        try {
            Material borderMaterial = Material.valueOf(plugin.getConfig().getString("visualization.physical-borders.material", "RED_WOOL"));
            return material == borderMaterial;
        } catch (IllegalArgumentException e) {
            return material == Material.RED_WOOL; // Фолбэк на красную шерсть
        }
    }

    private boolean isRegionBorderBlock(Location location) {
        // Находим ближайший регион
        ProtectedRegion region = plugin.getProtectRegionManager().getRegionAt(location);
        if (region == null) {
            // Если прямо в этой позиции региона нет, проверяем окружающие блоки
            // так как границы могут быть размещены рядом с регионом
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Location checkLoc = location.clone().add(dx, 0, dz);
                    region = plugin.getProtectRegionManager().getRegionAt(checkLoc);
                    if (region != null) {
                        break;
                    }
                }
                if (region != null) break;
            }
        }

        if (region == null) {
            return false;
        }

        // Проверяем, есть ли этот блок в сохраненных границах региона
        String regionId = region.getId();
        if (plugin.getVisualizationManager().hasRegionBorders(regionId)) {
            // Если у нас есть информация о границах этого региона,
            // проверяем через VisualizationManager
            return checkIfLocationIsStoredBorder(location, regionId);
        }

        // Фолбэк: проверяем по геометрии (старая логика)
        return checkIfLocationIsGeometricBorder(location, region);
    }

    /**
     * Проверяет, является ли локация сохраненной границей региона
     */
    private boolean checkIfLocationIsStoredBorder(Location location, String regionId) {
        // Получаем информацию о сохраненных границах из VisualizationManager
        // Это требует добавления публичного метода в VisualizationManager
        return plugin.getVisualizationManager().isLocationBorderBlock(location, regionId);
    }

    /**
     * Проверяет, является ли локация границей по геометрии (фолбэк)
     */
    private boolean checkIfLocationIsGeometricBorder(Location location, ProtectedRegion region) {
        int minX = region.getMinimumPoint().x();
        int maxX = region.getMaximumPoint().x();
        int minY = region.getMinimumPoint().y();
        int maxY = region.getMaximumPoint().y();
        int minZ = region.getMinimumPoint().z();
        int maxZ = region.getMaximumPoint().z();

        int blockX = location.getBlockX();
        int blockY = location.getBlockY();
        int blockZ = location.getBlockZ();

        // Вычисляем центр для определения ожидаемой высоты границ
        int centerY = (minY + maxY) / 2;
        int expectedBorderY = centerY - 1; // Ожидаемая высота границ

        // Проверяем, может ли это быть границей (в разумных пределах по высоте)
        if (Math.abs(blockY - expectedBorderY) > 20) {
            return false; // Слишком далеко от ожидаемой высоты
        }

        // Проверяем северную и южную стороны
        if ((blockZ == minZ || blockZ == maxZ) && blockX >= minX && blockX <= maxX) {
            return true;
        }
        // Проверяем западную и восточную стороны (исключая углы)
        if ((blockX == minX || blockX == maxX) && blockZ > minZ && blockZ < maxZ) {
            return true;
        }

        return false;
    }

    /**
     * ИСПРАВЛЕННЫЙ метод проверки центрального блока
     * Теперь ищет блок привата в любой точке центральной колонны
     */
    private boolean isCenterBlock(Location blockLocation, ProtectedRegion region) {
        String regionId = region.getId();

        // Получаем границы региона
        int regionMinX = region.getMinimumPoint().x();
        int regionMaxX = region.getMaximumPoint().x();
        int regionMinY = region.getMinimumPoint().y();
        int regionMaxY = region.getMaximumPoint().y();
        int regionMinZ = region.getMinimumPoint().z();
        int regionMaxZ = region.getMaximumPoint().z();

        // ИСПРАВЛЕНИЕ: Вычисляем центр по X и Z (они не меняются при расширении по высоте)
        int centerX = (regionMinX + regionMaxX) / 2;
        int centerZ = (regionMinZ + regionMaxZ) / 2;

        // Проверяем X и Z координаты сначала
        if (blockLocation.getBlockX() != centerX || blockLocation.getBlockZ() != centerZ) {
            return false; // Не в центре по X/Z
        }

        // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: Проверяем, является ли кликнутый блок блоком привата
        try {
            org.bukkit.Material protectMaterial = org.bukkit.Material.valueOf(
                    plugin.getConfig().getString("protect-block.material", "DIAMOND_BLOCK"));

            org.bukkit.block.Block clickedBlock = blockLocation.getBlock();

            // Проверяем, является ли кликнутый блок блоком привата
            if (clickedBlock.getType() == protectMaterial) {
                if (plugin.getConfig().getBoolean("debug.enabled", true)) {
                    plugin.getLogger().info("DEBUG BREAK: Найден блок привата в позиции " +
                            blockLocation.getBlockX() + "," + blockLocation.getBlockY() + "," + blockLocation.getBlockZ());

                    boolean hasHeightExpansion = plugin.getHeightExpansionManager() != null &&
                            plugin.getHeightExpansionManager().hasHeightExpansion(regionId);
                    plugin.getLogger().info("DEBUG BREAK: Регион расширен по высоте: " + hasHeightExpansion);

                    if (hasHeightExpansion) {
                        plugin.getLogger().info("DEBUG BREAK: Это блок привата в расширенном по высоте регионе");
                    }
                }

                return true; // Это блок привата в центральной позиции
            }

        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при проверке материала блока: " + e.getMessage());
        }

        return false; // Это не блок привата
    }

    private void handleRegionBlockBreak(BlockBreakEvent event, Player player, ProtectedRegion region, Location location) {
        // Проверяем права на удаление региона
        if (!canPlayerRemoveRegion(player, region)) {
            player.sendMessage(ChatColor.RED + "Вы не можете удалить этот приват!");
            event.setCancelled(true);
            return;
        }

        // Отменяем стандартное ломание
        event.setCancelled(true);

        // Получаем имя владельца региона
        String ownerName = getRegionOwnerName(region);
        String regionId = region.getId();

        // ИСПРАВЛЕНИЕ: Отключаем расширение по высоте если активно
        if (plugin.getHeightExpansionManager() != null && plugin.getHeightExpansionManager().hasHeightExpansion(regionId)) {
            plugin.getLogger().info("DEBUG BREAK: Отключаем расширение по высоте перед удалением региона");
            plugin.getHeightExpansionManager().disableHeightExpansion(regionId);
        }

        // ИСПРАВЛЕНИЕ: Удаляем границы из красной шерсти
        plugin.getVisualizationManager().removeRegionBorders(regionId);

        // Удаляем голограмму
        plugin.getHologramManager().removeHologram(regionId);

        // ИСПРАВЛЕНИЕ: Удаляем таймер если есть
        if (plugin.getRegionTimerManager() != null && plugin.getRegionTimerManager().hasTimer(regionId)) {
            plugin.getRegionTimerManager().removeRegionTimer(regionId);
            plugin.getLogger().info("DEBUG BREAK: Таймер региона удален");
        }

        // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: Убираем центральный блок правильно
        removeCenterBlockFromRegion(region, location.getWorld());

        // Удаляем регион из WorldGuard
        removeRegionFromWorldGuard(location, region);

        // ИСПРАВЛЕНИЕ: Возвращаем блок привата в инвентарь
        giveProtectBlockBack(player, ownerName);

        // Показываем сообщения
        String deleteMessage = plugin.getConfig().getString("messages.region-deleted", "&aПриват удален!");
        String borderMessage = plugin.getConfig().getString("messages.region-borders-restored", "&7Границы из красной шерсти восстановлены до исходного состояния.");

        player.sendMessage(ChatColor.translateAlternateColorCodes('&', deleteMessage));
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', borderMessage));

        // Логируем удаление
        plugin.getLogger().info("Игрок " + player.getName() + " удалил приват " + regionId);
        if (plugin.getConfig().getBoolean("debug.log-border-removal", false)) {
            plugin.getLogger().info("Удалены границы из красной шерсти для региона " + regionId);
        }
    }

    /**
     * ИСПРАВЛЕННЫЙ метод удаления центрального блока
     * Ищет и удаляет блок привата в центральной колонне
     */
    private void removeCenterBlockFromRegion(ProtectedRegion region, org.bukkit.World world) {
        try {
            String regionId = region.getId();

            // ИСПРАВЛЕНИЕ: Вычисляем центр только по X/Z
            int centerX = (region.getMinimumPoint().x() + region.getMaximumPoint().x()) / 2;
            int centerZ = (region.getMinimumPoint().z() + region.getMaximumPoint().z()) / 2;

            // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: Ищем блок привата во всей центральной колонне
            org.bukkit.Material protectMaterial;
            try {
                protectMaterial = org.bukkit.Material.valueOf(
                        plugin.getConfig().getString("protect-block.material", "DIAMOND_BLOCK"));
            } catch (IllegalArgumentException e) {
                protectMaterial = org.bukkit.Material.DIAMOND_BLOCK;
            }

            plugin.getLogger().info("DEBUG REMOVE: Поиск блока привата в центральной колонне региона " + regionId);
            plugin.getLogger().info("DEBUG REMOVE: Центр: X=" + centerX + ", Z=" + centerZ);
            plugin.getLogger().info("DEBUG REMOVE: Границы региона по Y: " + region.getMinimumPoint().y() + " -> " + region.getMaximumPoint().y());

            // Ищем блок привата от верха до низа центральной колонны
            boolean foundAndRemoved = false;
            for (int y = region.getMaximumPoint().y(); y >= region.getMinimumPoint().y(); y--) {
                org.bukkit.Location blockLoc = new org.bukkit.Location(world, centerX, y, centerZ);
                org.bukkit.block.Block block = blockLoc.getBlock();

                if (block.getType() == protectMaterial) {
                    block.setType(org.bukkit.Material.AIR);
                    plugin.getLogger().info("DEBUG REMOVE: ✅ Удален блок привата из позиции " + centerX + "," + y + "," + centerZ);
                    foundAndRemoved = true;
                    break; // Блок найден и удален
                }
            }

            if (!foundAndRemoved) {
                plugin.getLogger().warning("DEBUG REMOVE: ❌ Блок привата не найден в центральной колонне региона " + regionId + "!");

                // ДОПОЛНИТЕЛЬНАЯ ДИАГНОСТИКА: Показываем что находится в центральной колонне
                plugin.getLogger().info("DEBUG REMOVE: Содержимое центральной колонны:");
                for (int y = region.getMaximumPoint().y(); y >= region.getMinimumPoint().y(); y--) {
                    org.bukkit.Location blockLoc = new org.bukkit.Location(world, centerX, y, centerZ);
                    org.bukkit.block.Block block = blockLoc.getBlock();
                    plugin.getLogger().info("DEBUG REMOVE: Y=" + y + ": " + block.getType());
                }
            }

        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка при удалении центрального блока: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private boolean canPlayerRemoveRegion(Player player, ProtectedRegion region) {
        // Админы могут удалять любые приваты
        if (player.hasPermission("rgprotect.admin")) {
            return true;
        }

        // Проверяем владельца по UUID
        if (region.getOwners().contains(player.getUniqueId())) {
            return true;
        }

        // Проверяем владельца по имени (для совместимости)
        if (region.getOwners().contains(player.getName())) {
            return true;
        }

        return false;
    }

    private String getRegionOwnerName(ProtectedRegion region) {
        // Сначала пробуем получить по UUID
        if (!region.getOwners().getUniqueIds().isEmpty()) {
            java.util.UUID ownerUUID = region.getOwners().getUniqueIds().iterator().next();
            String ownerName = plugin.getServer().getOfflinePlayer(ownerUUID).getName();
            return ownerName != null ? ownerName : "Unknown";
        }

        // Если UUID нет, пробуем получить по имени
        if (!region.getOwners().getPlayers().isEmpty()) {
            return region.getOwners().getPlayers().iterator().next();
        }

        return "Unknown";
    }

    private void removeRegionFromWorldGuard(Location location, ProtectedRegion region) {
        try {
            Object regionManager = plugin.getProtectRegionManager().getWorldGuardRegionManager(location.getWorld());
            if (regionManager != null) {
                // Удаляем регион через рефлексию
                java.lang.reflect.Method removeRegionMethod = regionManager.getClass().getMethod("removeRegion", String.class);
                removeRegionMethod.invoke(regionManager, region.getId());

                // Сохраняем изменения
                java.lang.reflect.Method saveMethod = regionManager.getClass().getMethod("save");
                saveMethod.invoke(regionManager);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка при удалении региона: " + e.getMessage());
        }
    }

    private void giveProtectBlockBack(Player player, String ownerName) {
        try {
            // Создаем блок привата
            Material blockType;
            try {
                blockType = Material.valueOf(plugin.getConfig().getString("protect-block.material", "DIAMOND_BLOCK"));
            } catch (IllegalArgumentException e) {
                blockType = Material.DIAMOND_BLOCK;
            }

            ItemStack protectBlock = new ItemStack(blockType, 1);
            ItemMeta meta = protectBlock.getItemMeta();

            String displayName = plugin.getConfig().getString("protect-block.display-name", "&aБлок привата")
                    .replace("{player}", ownerName);
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', displayName));

            List<String> lore = plugin.getConfig().getStringList("protect-block.lore");
            if (!lore.isEmpty()) {
                List<String> newLore = new ArrayList<>();
                for (String line : lore) {
                    newLore.add(ChatColor.translateAlternateColorCodes('&',
                            line.replace("{player}", ownerName)));
                }
                // Добавляем скрытый тег
                newLore.add(ChatColor.DARK_GRAY + "RGProtect:" + ownerName);
                meta.setLore(newLore);
            }

            protectBlock.setItemMeta(meta);

            // ИСПРАВЛЕНИЕ: Даем игроку блок правильно
            if (player.getInventory().firstEmpty() != -1) {
                player.getInventory().addItem(protectBlock);
                player.sendMessage(ChatColor.GREEN + "Блок привата возвращен в инвентарь!");
            } else {
                player.getWorld().dropItemNaturally(player.getLocation(), protectBlock);
                player.sendMessage(ChatColor.YELLOW + "Блок привата выпал на землю - инвентарь полон!");
            }

        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка при возврате блока привата: " + e.getMessage());
            e.printStackTrace();
        }
    }
}