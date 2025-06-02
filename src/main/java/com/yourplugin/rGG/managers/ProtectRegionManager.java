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
     * Проверяет, можно ли создать регион в указанном месте
     */
    public boolean canCreateRegion(Location location, String playerName) {
        if (location == null || location.getWorld() == null || playerName == null) {
            return false;
        }

        World world = location.getWorld();
        RegionManager regionManager = getWorldGuardRegionManager(world);

        if (regionManager == null) {
            return false;
        }

        // Получаем размеры из конфига
        int sizeX = plugin.getConfig().getInt("region.size.x", 3);
        int sizeY = plugin.getConfig().getInt("region.size.y", 3);
        int sizeZ = plugin.getConfig().getInt("region.size.z", 3);

        // ИСПРАВЛЕНО: Правильный расчет границ для региона 3x3x3
        // Центральный блок находится в позиции location
        // Регион должен включать по 1 блоку в каждую сторону от центра
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

        plugin.getLogger().info("DEBUG: Проверка возможности создания региона");
        plugin.getLogger().info("DEBUG: Центр: " + centerX + "," + centerY + "," + centerZ);
        plugin.getLogger().info("DEBUG: Размер: " + sizeX + "x" + sizeY + "x" + sizeZ);
        plugin.getLogger().info("DEBUG: Радиусы: X=" + radiusX + " Y=" + radiusY + " Z=" + radiusZ);
        plugin.getLogger().info("DEBUG: Границы: min(" + min.x() + "," + min.y() + "," + min.z() + ") max(" + max.x() + "," + max.y() + "," + max.z() + ")");

        ProtectedCuboidRegion testRegion = new ProtectedCuboidRegion("test", min, max);

        // Проверяем пересечения с существующими регионами
        try {
            Map<String, ProtectedRegion> regions = regionManager.getRegions();
            for (ProtectedRegion existingRegion : regions.values()) {
                if (hasIntersection(testRegion, existingRegion)) {
                    // Если пересекается с регионом другого игрока
                    if (!isPlayerOwner(existingRegion, playerName)) {
                        return false;
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при проверке пересечений: " + e.getMessage());
            // В случае ошибки разрешаем создание
            return true;
        }

        // Проверяем лимит регионов для игрока
        int maxRegions = plugin.getConfig().getInt("limits.max-regions-per-player", 5);
        int playerRegions = getPlayerRegionCountInternal(world, playerName);

        return playerRegions < maxRegions;
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
     * Проверяет пересечение двух регионов
     */
    private boolean hasIntersection(ProtectedRegion region1, ProtectedRegion region2) {
        BlockVector3 min1 = region1.getMinimumPoint();
        BlockVector3 max1 = region1.getMaximumPoint();
        BlockVector3 min2 = region2.getMinimumPoint();
        BlockVector3 max2 = region2.getMaximumPoint();

        return !(max1.x() < min2.x() || min1.x() > max2.x() ||
                max1.y() < min2.y() || min1.y() > max2.y() ||
                max1.z() < min2.z() || min1.z() > max2.z());
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
}