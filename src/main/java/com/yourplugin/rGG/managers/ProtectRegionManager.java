package com.yourplugin.rGG.managers;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.domains.DefaultDomain;

import com.yourplugin.rGG.RGProtectPlugin;

import java.util.UUID;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.HashMap;

public class ProtectRegionManager {

    private final RGProtectPlugin plugin;
    private final RegionContainer regionContainer;

    public ProtectRegionManager(RGProtectPlugin plugin) {
        this.plugin = plugin;
        this.regionContainer = WorldGuard.getInstance().getPlatform().getRegionContainer();
    }

    /**
     * ПОЛНОСТЬЮ ПЕРЕПИСАННАЯ проверка возможности создания региона
     * Теперь с детальным анализом пересечений и понятными сообщениями
     */
    public boolean canCreateRegion(Location location, String playerName) {
        if (location == null || location.getWorld() == null || playerName == null) {
            plugin.getLogger().warning("КОЛЛИЗИЯ: Недопустимые параметры для создания региона");
            return false;
        }

        World world = location.getWorld();
        RegionManager regionManager = getWorldGuardRegionManager(world);

        if (regionManager == null) {
            plugin.getLogger().warning("КОЛЛИЗИЯ: RegionManager не найден для мира " + world.getName());
            return false;
        }

        // Получаем размеры из конфига
        int sizeX = plugin.getConfig().getInt("region.size.x", 3);
        int sizeY = plugin.getConfig().getInt("region.size.y", 3);
        int sizeZ = plugin.getConfig().getInt("region.size.z", 3);

        // Вычисляем границы будущего региона
        int centerX = location.getBlockX();
        int centerY = location.getBlockY();
        int centerZ = location.getBlockZ();

        int radiusX = (sizeX - 1) / 2;
        int radiusY = (sizeY - 1) / 2;
        int radiusZ = (sizeZ - 1) / 2;

        BlockVector3 min = BlockVector3.at(
                centerX - radiusX,
                centerY - radiusY,
                centerZ - radiusZ
        );

        BlockVector3 max = BlockVector3.at(
                centerX + radiusX,
                centerY + radiusY,
                centerZ + radiusZ
        );

        plugin.getLogger().info("КОЛЛИЗИЯ: Проверка создания региона для " + playerName);
        plugin.getLogger().info("КОЛЛИЗИЯ: Позиция " + centerX + "," + centerY + "," + centerZ);
        plugin.getLogger().info("КОЛЛИЗИЯ: Размер " + sizeX + "x" + sizeY + "x" + sizeZ);
        plugin.getLogger().info("КОЛЛИЗИЯ: Границы " + min + " -> " + max);

        ProtectedCuboidRegion testRegion = new ProtectedCuboidRegion("test", min, max);

        // Проверяем пересечения с существующими регионами
        try {
            Map<String, ProtectedRegion> regions = regionManager.getRegions();

            for (ProtectedRegion existingRegion : regions.values()) {
                if (hasDetailedIntersection(testRegion, existingRegion)) {
                    String ownerName = getRegionOwnerName(existingRegion);

                    plugin.getLogger().warning("КОЛЛИЗИЯ: Пересечение с регионом " + existingRegion.getId());
                    plugin.getLogger().warning("КОЛЛИЗИЯ: Владелец существующего региона: " + ownerName);
                    plugin.getLogger().warning("КОЛЛИЗИЯ: Границы существующего: " +
                            existingRegion.getMinimumPoint() + " -> " + existingRegion.getMaximumPoint());

                    // Проверяем владельца
                    if (!isPlayerOwner(existingRegion, playerName)) {
                        plugin.getLogger().warning("КОЛЛИЗИЯ: ЗАПРЕЩЕНО - Чужой регион!");
                        return false;
                    } else {
                        plugin.getLogger().info("КОЛЛИЗИЯ: Разрешено - Собственный регион");
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("КОЛЛИЗИЯ: Ошибка проверки: " + e.getMessage());
            e.printStackTrace();
            return false; // При ошибке запрещаем для безопасности
        }

        // Проверяем лимит регионов
        int maxRegions = plugin.getConfig().getInt("limits.max-regions-per-player", 5);
        int playerRegions = getPlayerRegionCountInternal(world, playerName);

        if (playerRegions >= maxRegions) {
            plugin.getLogger().info("КОЛЛИЗИЯ: ЗАПРЕЩЕНО - Лимит регионов " + playerRegions + "/" + maxRegions);
            return false;
        }

        plugin.getLogger().info("КОЛЛИЗИЯ: ✅ РАЗРЕШЕНО - Создание возможно");
        return true;
    }

    /**
     * НОВЫЙ метод детальной проверки пересечений с логированием
     */
    private boolean hasDetailedIntersection(ProtectedRegion region1, ProtectedRegion region2) {
        BlockVector3 min1 = region1.getMinimumPoint();
        BlockVector3 max1 = region1.getMaximumPoint();
        BlockVector3 min2 = region2.getMinimumPoint();
        BlockVector3 max2 = region2.getMaximumPoint();

        // Проверка по каждой оси
        boolean overlapX = !(max1.x() < min2.x() || min1.x() > max2.x());
        boolean overlapY = !(max1.y() < min2.y() || min1.y() > max2.y());
        boolean overlapZ = !(max1.z() < min2.z() || min1.z() > max2.z());

        boolean hasIntersection = overlapX && overlapY && overlapZ;

        if (hasIntersection && plugin.getConfig().getBoolean("debug.log-collision-details", true)) {
            plugin.getLogger().info("ДЕТАЛИ КОЛЛИЗИИ:");
            plugin.getLogger().info("  Регион 1: " + min1 + " -> " + max1);
            plugin.getLogger().info("  Регион 2: " + min2 + " -> " + max2);
            plugin.getLogger().info("  Пересечение X: " + overlapX + " (" + min1.x() + "-" + max1.x() + " vs " + min2.x() + "-" + max2.x() + ")");
            plugin.getLogger().info("  Пересечение Y: " + overlapY + " (" + min1.y() + "-" + max1.y() + " vs " + min2.y() + "-" + max2.y() + ")");
            plugin.getLogger().info("  Пересечение Z: " + overlapZ + " (" + min1.z() + "-" + max1.z() + " vs " + min2.z() + "-" + max2.z() + ")");

            if (hasIntersection) {
                // Вычисляем область пересечения
                int overlapMinX = Math.max(min1.x(), min2.x());
                int overlapMaxX = Math.min(max1.x(), max2.x());
                int overlapMinY = Math.max(min1.y(), min2.y());
                int overlapMaxY = Math.min(max1.y(), max2.y());
                int overlapMinZ = Math.max(min1.z(), min2.z());
                int overlapMaxZ = Math.min(max1.z(), max2.z());

                plugin.getLogger().info("  Область пересечения: (" + overlapMinX + "," + overlapMinY + "," + overlapMinZ +
                        ") -> (" + overlapMaxX + "," + overlapMaxY + "," + overlapMaxZ + ")");
            }
        }

        return hasIntersection;
    }

    /**
     * НОВЫЙ метод проверки возможности расширения региона
     */
    public boolean canExpandRegion(ProtectedRegion region, int newLevel, String playerName) {
        if (region == null) {
            plugin.getLogger().warning("РАСШИРЕНИЕ: Регион равен null");
            return false;
        }

        World world = findWorldForRegion(region.getId());
        if (world == null) {
            plugin.getLogger().warning("РАСШИРЕНИЕ: Мир не найден для " + region.getId());
            return false;
        }

        RegionManager regionManager = getWorldGuardRegionManager(world);
        if (regionManager == null) {
            plugin.getLogger().warning("РАСШИРЕНИЕ: RegionManager не найден");
            return false;
        }

        // Вычисляем размеры после расширения
        int baseX = plugin.getConfig().getInt("region-expansion.base-size.x", 3);
        int baseY = plugin.getConfig().getInt("region-expansion.base-size.y", 3);
        int baseZ = plugin.getConfig().getInt("region-expansion.base-size.z", 3);

        int newSizeX = baseX + (newLevel * 2);
        int newSizeY = baseY + (newLevel * 2);
        int newSizeZ = baseZ + (newLevel * 2);

        // Центр остается тот же
        int centerX = (region.getMinimumPoint().x() + region.getMaximumPoint().x()) / 2;
        int centerY = (region.getMinimumPoint().y() + region.getMaximumPoint().y()) / 2;
        int centerZ = (region.getMinimumPoint().z() + region.getMaximumPoint().z()) / 2;

        int radiusX = (newSizeX - 1) / 2;
        int radiusY = (newSizeY - 1) / 2;
        int radiusZ = (newSizeZ - 1) / 2;

        BlockVector3 newMin = BlockVector3.at(centerX - radiusX, centerY - radiusY, centerZ - radiusZ);
        BlockVector3 newMax = BlockVector3.at(centerX + radiusX, centerY + radiusY, centerZ + radiusZ);

        plugin.getLogger().info("РАСШИРЕНИЕ: Проверка для " + region.getId());
        plugin.getLogger().info("РАСШИРЕНИЕ: Текущий размер: " + getCurrentRegionSizeString(region));
        plugin.getLogger().info("РАСШИРЕНИЕ: Новый уровень: " + newLevel);
        plugin.getLogger().info("РАСШИРЕНИЕ: Новый размер: " + newSizeX + "x" + newSizeY + "x" + newSizeZ);
        plugin.getLogger().info("РАСШИРЕНИЕ: Новые границы: " + newMin + " -> " + newMax);

        ProtectedCuboidRegion testRegion = new ProtectedCuboidRegion("test_expansion", newMin, newMax);

        // Проверяем пересечения
        try {
            Map<String, ProtectedRegion> regions = regionManager.getRegions();

            for (ProtectedRegion existingRegion : regions.values()) {
                // Пропускаем сам расширяемый регион
                if (existingRegion.getId().equals(region.getId())) {
                    continue;
                }

                if (hasDetailedIntersection(testRegion, existingRegion)) {
                    String ownerName = getRegionOwnerName(existingRegion);

                    plugin.getLogger().warning("РАСШИРЕНИЕ: Пересечение с " + existingRegion.getId());
                    plugin.getLogger().warning("РАСШИРЕНИЕ: Владелец: " + ownerName);

                    if (!isPlayerOwner(existingRegion, playerName)) {
                        plugin.getLogger().warning("РАСШИРЕНИЕ: ЗАПРЕЩЕНО - Чужой регион!");
                        return false;
                    } else {
                        plugin.getLogger().info("РАСШИРЕНИЕ: Разрешено - Собственный регион");
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("РАСШИРЕНИЕ: Ошибка проверки: " + e.getMessage());
            return false;
        }

        plugin.getLogger().info("РАСШИРЕНИЕ: ✅ РАЗРЕШЕНО");
        return true;
    }

    /**
     * Создает новый регион в указанном месте
     */
    public String createRegion(Location location, String playerName) {
        if (location == null || location.getWorld() == null || playerName == null) {
            return null;
        }

        World world = location.getWorld();
        RegionManager regionManager = getWorldGuardRegionManager(world);

        if (regionManager == null) {
            return null;
        }

        // Генерируем уникальное имя региона
        String regionName = generateRegionName(playerName);

        // Получаем размеры из конфига
        int sizeX = plugin.getConfig().getInt("region.size.x", 3);
        int sizeY = plugin.getConfig().getInt("region.size.y", 3);
        int sizeZ = plugin.getConfig().getInt("region.size.z", 3);

        // ИСПРАВЛЕНО: Правильный расчет границ для региона 3x3x3
        int centerX = location.getBlockX();
        int centerY = location.getBlockY();
        int centerZ = location.getBlockZ();

        // Для региона 3x3x3: радиус = 1 (от центра по 1 блоку в каждую сторону)
        int radiusX = (sizeX - 1) / 2;
        int radiusY = (sizeY - 1) / 2;
        int radiusZ = (sizeZ - 1) / 2;

        BlockVector3 min = BlockVector3.at(
                centerX - radiusX,
                centerY - radiusY,
                centerZ - radiusZ
        );

        BlockVector3 max = BlockVector3.at(
                centerX + radiusX,
                centerY + radiusY,
                centerZ + radiusZ
        );

        plugin.getLogger().info("DEBUG: Создание региона " + regionName);
        plugin.getLogger().info("DEBUG: Центр: " + centerX + "," + centerY + "," + centerZ);
        plugin.getLogger().info("DEBUG: Размер: " + sizeX + "x" + sizeY + "x" + sizeZ);
        plugin.getLogger().info("DEBUG: Радиусы: X=" + radiusX + " Y=" + radiusY + " Z=" + radiusZ);
        plugin.getLogger().info("DEBUG: Границы региона: min(" + min.x() + "," + min.y() + "," + min.z() + ") max(" + max.x() + "," + max.y() + "," + max.z() + ")");
        plugin.getLogger().info("DEBUG: Итоговый размер: " + (max.x() - min.x() + 1) + "x" + (max.y() - min.y() + 1) + "x" + (max.z() - min.z() + 1));

        try {
            ProtectedCuboidRegion region = new ProtectedCuboidRegion(regionName, min, max);

            // Настраиваем владельца региона
            DefaultDomain owners = new DefaultDomain();
            UUID playerUUID = getPlayerUUID(playerName);
            if (playerUUID != null) {
                owners.addPlayer(playerUUID);
                region.setOwners(owners);
            }

            // Устанавливаем флаги из конфига
            setRegionFlags(region);

            // Добавляем регион в менеджер
            regionManager.addRegion(region);

            // Сохраняем
            try {
                regionManager.save();
            } catch (Exception e) {
                plugin.getLogger().warning("Ошибка сохранения RegionManager: " + e.getMessage());
            }

            plugin.getLogger().info("Создан регион " + regionName + " для игрока " + playerName);
            return regionName;

        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка при создании региона: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Получает WorldGuard RegionManager для указанного мира
     */
    public RegionManager getWorldGuardRegionManager(World world) {
        try {
            return regionContainer.get(BukkitAdapter.adapt(world));
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка получения WorldGuard RegionManager: " + e.getMessage());
            return null;
        }
    }

    /**
     * ИСПРАВЛЕННАЯ проверка пересечения двух регионов (для совместимости)
     */
    private boolean hasIntersection(ProtectedRegion region1, ProtectedRegion region2) {
        return hasDetailedIntersection(region1, region2);
    }

    /**
     * Устанавливает флаги для региона
     */
    private void setRegionFlags(ProtectedRegion region) {
        // Здесь можно настроить флаги региона из конфига
        // Оставляем пустым пока что
    }

    /**
     * Генерирует уникальное имя региона
     */
    private String generateRegionName(String playerName) {
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        return "rgprotect_" + playerName.toLowerCase() + "_" + timestamp;
    }

    /**
     * Получает UUID игрока по имени
     */
    private UUID getPlayerUUID(String playerName) {
        try {
            Player onlinePlayer = plugin.getServer().getPlayer(playerName);
            if (onlinePlayer != null) {
                return onlinePlayer.getUniqueId();
            } else {
                return plugin.getServer().getOfflinePlayer(playerName).getUniqueId();
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка получения UUID игрока " + playerName + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Проверяет, является ли игрок владельцем региона
     */
    private boolean isPlayerOwner(ProtectedRegion region, String playerName) {
        UUID playerUUID = getPlayerUUID(playerName);
        if (playerUUID == null) {
            return false;
        }

        // Проверяем по UUID и по имени (для совместимости)
        return region.getOwners().contains(playerUUID) || region.getOwners().contains(playerName);
    }

    /**
     * Получает количество регионов игрока в мире
     */
    public int getPlayerRegionCount(World world, String playerName) {
        return getPlayerRegionCountInternal(world, playerName);
    }

    /**
     * Внутренний метод для подсчета регионов игрока
     */
    private int getPlayerRegionCountInternal(World world, String playerName) {
        try {
            RegionManager regionManager = getWorldGuardRegionManager(world);
            if (regionManager == null) {
                return 0;
            }

            UUID playerUUID = getPlayerUUID(playerName);
            if (playerUUID == null) {
                return 0;
            }

            Map<String, ProtectedRegion> regions = regionManager.getRegions();

            int count = 0;
            for (ProtectedRegion region : regions.values()) {
                if (region.getOwners().contains(playerUUID) || region.getOwners().contains(playerName)) {
                    count++;
                }
            }

            return count;
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка подсчета регионов для " + playerName + ": " + e.getMessage());
            return 0;
        }
    }

    /**
     * Находит регион в указанной позиции
     */
    public ProtectedRegion getRegionAt(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }

        try {
            RegionManager regionManager = getWorldGuardRegionManager(location.getWorld());
            if (regionManager == null) {
                return null;
            }

            BlockVector3 point = BlockVector3.at(
                    location.getBlockX(),
                    location.getBlockY(),
                    location.getBlockZ()
            );

            Map<String, ProtectedRegion> regions = regionManager.getRegions();

            for (ProtectedRegion region : regions.values()) {
                if (region.contains(point)) {
                    return region;
                }
            }

        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка поиска региона: " + e.getMessage());
        }

        return null;
    }

    /**
     * ВСПОМОГАТЕЛЬНЫЙ метод для получения размера региона как строки
     */
    private String getCurrentRegionSizeString(ProtectedRegion region) {
        int sizeX = region.getMaximumPoint().x() - region.getMinimumPoint().x() + 1;
        int sizeY = region.getMaximumPoint().y() - region.getMinimumPoint().y() + 1;
        int sizeZ = region.getMaximumPoint().z() - region.getMinimumPoint().z() + 1;
        return sizeX + "x" + sizeY + "x" + sizeZ;
    }

    /**
     * ВСПОМОГАТЕЛЬНЫЙ метод для получения имени владельца региона
     */
    private String getRegionOwnerName(ProtectedRegion region) {
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

    /**
     * ВСПОМОГАТЕЛЬНЫЙ метод для поиска мира региона
     */
    private World findWorldForRegion(String regionId) {
        for (World world : plugin.getServer().getWorlds()) {
            try {
                RegionManager rm = getWorldGuardRegionManager(world);
                if (rm != null && rm.getRegion(regionId) != null) {
                    return world;
                }
            } catch (Exception e) {
                // Игнорируем
            }
        }
        return null;
    }
}