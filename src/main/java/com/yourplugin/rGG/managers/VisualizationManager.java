package com.yourplugin.rGG.managers;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.ChatColor;

import com.yourplugin.rGG.RGProtectPlugin;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;

public class VisualizationManager {

    private final RGProtectPlugin plugin;
    private final Map<UUID, BukkitTask> activeTasks;
    // Хранение оригинальных блоков для восстановления
    private final Map<String, Map<Location, Material>> regionBorderBlocks;

    public VisualizationManager(RGProtectPlugin plugin) {
        this.plugin = plugin;
        this.activeTasks = new HashMap<>();
        this.regionBorderBlocks = new HashMap<>();

        plugin.getLogger().info("DEBUG INIT: VisualizationManager инициализирован");
    }
    /**
     * Получает копию сохраненных границ региона
     * @param regionId ID региона
     * @return Копия карты с сохраненными границами или null если границ нет
     */
    public Map<Location, Material> getRegionBordersCopy(String regionId) {
        Map<Location, Material> originalBlocks = regionBorderBlocks.get(regionId);
        if (originalBlocks == null) {
            return null;
        }

        // Возвращаем копию для безопасности
        return new HashMap<>(originalBlocks);
    }

    /**
     * Восстанавливает границы региона из сохраненной копии
     * @param regionId ID региона
     * @param savedBorders Сохраненная копия границ
     * @return true если границы восстановлены, false в случае ошибки
     */
    public boolean restoreRegionBordersFromCopy(String regionId, Map<Location, Material> savedBorders) {
        if (savedBorders == null || savedBorders.isEmpty()) {
            plugin.getLogger().warning("Нет сохраненных границ для восстановления региона " + regionId);
            return false;
        }

        try {
            // Получаем материал границ
            Material borderMaterial;
            try {
                borderMaterial = Material.valueOf(plugin.getConfig().getString("visualization.physical-borders.material", "RED_WOOL"));
            } catch (IllegalArgumentException e) {
                borderMaterial = Material.RED_WOOL;
            }

            // Восстанавливаем границы
            int restoredCount = 0;
            for (Map.Entry<Location, Material> entry : savedBorders.entrySet()) {
                Location loc = entry.getKey();
                Block block = loc.getBlock();

                // Устанавливаем блок границы
                block.setType(borderMaterial);
                restoredCount++;
            }

            // Сохраняем в карту границ
            regionBorderBlocks.put(regionId, new HashMap<>(savedBorders));

            plugin.getLogger().info("Восстановлены границы региона " + regionId + ": " + restoredCount + " блоков");
            return true;

        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка при восстановлении границ региона " + regionId + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Безопасное удаление границ с возможностью отката
     * @param regionId ID региона
     * @return Копия удаленных границ для возможного восстановления
     */
    public Map<Location, Material> removeRegionBordersSafely(String regionId) {
        // Сначала получаем копию
        Map<Location, Material> backup = getRegionBordersCopy(regionId);

        // Затем удаляем
        removeRegionBorders(regionId);

        // Возвращаем копию для возможного восстановления
        return backup;
    }
    /**
     * ВОССТАНОВЛЕННЫЙ метод создания границ со ВСЕЙ оригинальной логикой
     */
    /**
     * ВОССТАНОВЛЕННЫЙ метод создания границ со ВСЕЙ оригинальной логикой
     * КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: Правильно обрабатывает регионы с расширением по высоте
     */
    public void createRegionBorders(ProtectedRegion region, World world) {
        if (region == null) {
            plugin.getLogger().severe("ОШИБКА: Попытка создать границы для null региона!");
            return;
        }

        if (world == null) {
            plugin.getLogger().severe("ОШИБКА: Попытка создать границы в null мире!");
            return;
        }

        String regionId = region.getId();

        // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: Проверяем расширение по высоте
        boolean hasHeightExpansion = plugin.getHeightExpansionManager() != null &&
                plugin.getHeightExpansionManager().hasHeightExpansion(regionId);

        plugin.getLogger().info("DEBUG CREATE BORDERS: Регион " + regionId + " имеет расширение по высоте: " + hasHeightExpansion);

        // Дополнительная проверка что регион существует в WorldGuard
        try {
            Object regionManager = plugin.getProtectRegionManager().getWorldGuardRegionManager(world);
            if (regionManager != null) {
                java.lang.reflect.Method getRegionMethod = regionManager.getClass().getMethod("getRegion", String.class);
                ProtectedRegion checkRegion = (ProtectedRegion) getRegionMethod.invoke(regionManager, regionId);

                if (checkRegion == null) {
                    plugin.getLogger().severe("ОШИБКА: Регион " + regionId + " не найден в WorldGuard!");
                    return;
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Не удалось проверить существование региона: " + e.getMessage());
        }

        if (plugin.getConfig().getBoolean("debug.log-border-creation", false)) {
            plugin.getLogger().info("=== НАЧАЛО СОЗДАНИЯ ГРАНИЦ ===");
            plugin.getLogger().info("DEBUG: Вызван createRegionBorders для региона: " + region.getId());
            plugin.getLogger().info("DEBUG: Мир: " + (world != null ? world.getName() : "NULL"));
        }

        // Проверяем настройки
        boolean visualizationEnabled = plugin.getConfig().getBoolean("visualization.enabled", true);
        boolean bordersEnabled = plugin.getConfig().getBoolean("visualization.physical-borders.enabled", true);

        if (plugin.getConfig().getBoolean("debug.log-border-creation", false)) {
            plugin.getLogger().info("DEBUG: visualization.enabled = " + visualizationEnabled);
            plugin.getLogger().info("DEBUG: physical-borders.enabled = " + bordersEnabled);
        }

        if (!visualizationEnabled || !bordersEnabled) {
            if (plugin.getConfig().getBoolean("debug.log-border-creation", false)) {
                plugin.getLogger().warning("DEBUG: Создание границ отключено в конфиге!");
            }
            return;
        }

        if (world == null) {
            plugin.getLogger().severe("DEBUG: Мир равен NULL! Невозможно создать границы.");
            return;
        }

        // Удаляем старые границы если они есть
        removeRegionBorders(regionId);

        // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: Получаем правильные границы региона
        int minX = region.getMinimumPoint().x();
        int maxX = region.getMaximumPoint().x();
        int minY, maxY;
        int minZ = region.getMinimumPoint().z();
        int maxZ = region.getMaximumPoint().z();

        if (hasHeightExpansion) {
            // ИСПРАВЛЕНИЕ: Для регионов с расширением по высоте используем ОРИГИНАЛЬНЫЕ Y границы
            // Берем базовые размеры из конфига
            int baseY = plugin.getConfig().getInt("region-expansion.base-size.y", 3);

            // Находим блок привата (центр региона по X/Z)
            int centerX = (minX + maxX) / 2;
            int centerZ = (minZ + maxZ) / 2;

            // Ищем блок привата в центральной колонне
            int privatBlockY = findPrivateBlockY(world, centerX, centerZ, regionId);

            if (privatBlockY != -1) {
                // Используем позицию блока привата как центр
                int radiusY = (baseY - 1) / 2;
                minY = privatBlockY - radiusY;
                maxY = privatBlockY + radiusY;
                plugin.getLogger().info("DEBUG CREATE BORDERS: Найден блок привата в Y=" + privatBlockY + ", используем Y границы: " + minY + " -> " + maxY);
            } else {
                // Фолбэк: используем поверхность земли
                int groundY = world.getHighestBlockYAt(centerX, centerZ);
                int radiusY = (baseY - 1) / 2;
                minY = groundY - radiusY;
                maxY = groundY + radiusY;
                plugin.getLogger().info("DEBUG CREATE BORDERS: Блок привата не найден, используем поверхность земли Y=" + groundY + ", границы: " + minY + " -> " + maxY);
            }
        } else {
            // Обычный регион - используем его реальные границы
            minY = region.getMinimumPoint().y();
            maxY = region.getMaximumPoint().y();
            plugin.getLogger().info("DEBUG CREATE BORDERS: Обычный регион, используем реальные Y границы: " + minY + " -> " + maxY);
        }

        if (plugin.getConfig().getBoolean("debug.log-border-creation", false)) {
            plugin.getLogger().info("DEBUG: Границы региона (исправленные):");
            plugin.getLogger().info("DEBUG: X: " + minX + " -> " + maxX + " (размер: " + (maxX - minX + 1) + ")");
            plugin.getLogger().info("DEBUG: Y: " + minY + " -> " + maxY + " (размер: " + (maxY - minY + 1) + ")");
            plugin.getLogger().info("DEBUG: Z: " + minZ + " -> " + maxZ + " (размер: " + (maxZ - minZ + 1) + ")");
        }

        // Получаем материал для границ
        String materialName = plugin.getConfig().getString("visualization.physical-borders.material", "RED_WOOL");
        Material borderMaterial;
        try {
            borderMaterial = Material.valueOf(materialName);
            if (plugin.getConfig().getBoolean("debug.log-border-creation", false)) {
                plugin.getLogger().info("DEBUG: Материал границ: " + borderMaterial);
            }
        } catch (IllegalArgumentException e) {
            plugin.getLogger().severe("DEBUG: Неверный материал в конфиге: " + materialName + ", используем RED_WOOL");
            borderMaterial = Material.RED_WOOL;
        }

        // Проверяем стратегию размещения
        String strategy = plugin.getConfig().getString("visualization.physical-borders.placement.strategy", "surface_contact");
        if (plugin.getConfig().getBoolean("debug.log-border-creation", false)) {
            plugin.getLogger().info("DEBUG: Стратегия размещения: " + strategy);
        }

        // Генерируем позиции границ в зависимости от стратегии
        Set<Location> borderLocations;

        if ("visibility_based".equals(strategy)) {
            // Размещение с приоритетом видимости НО с проверкой земли
            int centerY = (minY + maxY) / 2;
            borderLocations = getBorderLocationsWithVisibilityButOnGround(world, minX, maxX, minZ, maxZ, centerY);
        } else if ("below_center".equals(strategy)) {
            // Создаем границы на уровне центра региона
            int centerY = (minY + maxY) / 2;
            if (plugin.getConfig().getBoolean("debug.log-border-creation", false)) {
                plugin.getLogger().info("DEBUG: Центральный уровень Y для границ: " + centerY);
            }
            borderLocations = generateSimpleBorderLocations(world, minX, maxX, minZ, maxZ, centerY);
        } else {
            // используем поиск земли
            if (plugin.getConfig().getBoolean("debug.log-border-creation", false)) {
                plugin.getLogger().info("DEBUG: Используется умная стратегия размещения");
            }
            borderLocations = getBorderLocationsWithSmartPlacement(world, minX, maxX, minY, maxY, minZ, maxZ);
        }

        if (plugin.getConfig().getBoolean("debug.log-border-creation", false)) {
            plugin.getLogger().info("DEBUG: Сгенерировано " + borderLocations.size() + " позиций для границ");
        }

        if (borderLocations.isEmpty()) {
            plugin.getLogger().severe("DEBUG: КРИТИЧЕСКАЯ ОШИБКА - нет позиций для границ!");
            return;
        }

        // Размещаем границы с обработкой растительности
        Map<Location, Material> originalBlocks = new HashMap<>();
        int successCount = 0;
        int errorCount = 0;
        int grassReplacedCount = 0;
        int flowersReplacedCount = 0;
        int otherVegetationCount = 0;
        int placedAboveCenterCount = 0;
        int placedBelowCenterCount = 0;
        int placedOnGroundCount = 0;
        boolean replaceVegetation = plugin.getConfig().getBoolean("visualization.physical-borders.placement.replace_vegetation", true);

        // Центр для подсчета статистики
        int centerY = (minY + maxY) / 2;

        for (Location loc : borderLocations) {
            try {
                if (plugin.getConfig().getBoolean("debug.log-border-placement", false)) {
                    plugin.getLogger().info("DEBUG: Обрабатываем позицию: " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ());
                }

                Block block = loc.getBlock();
                Material originalMaterial = block.getType();

                // Проверяем, что блок на земле
                Block blockBelow = world.getBlockAt(loc.getBlockX(), loc.getBlockY() - 1, loc.getBlockZ());
                boolean isOnGround = isSolidBlock(blockBelow);
                if (isOnGround) {
                    placedOnGroundCount++;
                    if (plugin.getConfig().getBoolean("debug.log-border-placement", false)) {
                        plugin.getLogger().info("DEBUG: ✅ Блок НА ЗЕМЛЕ (под ним: " + blockBelow.getType() + ")");
                    }
                } else {
                    if (plugin.getConfig().getBoolean("debug.log-border-placement", false)) {
                        plugin.getLogger().warning("DEBUG: ⚠️ Блок НЕ на земле! Под ним: " + blockBelow.getType());
                    }
                }

                // Проверяем, что заменяем
                boolean replacingGrass = isGrass(block);
                boolean replacingFlower = isFlower(block);
                boolean replacingOtherVegetation = !replacingGrass && !replacingFlower && isVegetation(block);

                if (replacingGrass) {
                    grassReplacedCount++;
                    if (plugin.getConfig().getBoolean("debug.log-grass-processing", false)) {
                        plugin.getLogger().info("DEBUG GRASS: Заменяем траву " + originalMaterial + " на " + borderMaterial + " в " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ());
                    }
                } else if (replacingFlower) {
                    flowersReplacedCount++;
                    if (plugin.getConfig().getBoolean("debug.log-grass-processing", false)) {
                        plugin.getLogger().info("DEBUG FLOWERS: Заменяем цветок " + originalMaterial + " на " + borderMaterial + " в " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ());
                    }
                } else if (replacingOtherVegetation) {
                    otherVegetationCount++;
                    if (plugin.getConfig().getBoolean("debug.log-grass-processing", false)) {
                        plugin.getLogger().info("DEBUG VEGETATION: Заменяем растение " + originalMaterial + " на " + borderMaterial + " в " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ());
                    }
                }

                // Подсчитываем размещение относительно центра
                if (loc.getBlockY() > centerY) {
                    placedAboveCenterCount++;
                } else if (loc.getBlockY() < centerY) {
                    placedBelowCenterCount++;
                }

                if (plugin.getConfig().getBoolean("debug.log-border-placement", false)) {
                    plugin.getLogger().info("DEBUG: Оригинальный материал: " + originalMaterial);

                    String positionInfo = "";
                    if (loc.getBlockY() > centerY) {
                        positionInfo = " (ВЫШЕ центра на " + (loc.getBlockY() - centerY) + ")";
                    } else if (loc.getBlockY() < centerY) {
                        positionInfo = " (ниже центра на " + (centerY - loc.getBlockY()) + ")";
                    } else {
                        positionInfo = " (на уровне центра)";
                    }
                    plugin.getLogger().info("DEBUG: Позиция относительно центра" + positionInfo);
                }

                // Сохраняем оригинальный материал
                originalBlocks.put(loc.clone(), originalMaterial);

                // Устанавливаем материал границы
                block.setType(borderMaterial);
                successCount++;

                if (plugin.getConfig().getBoolean("debug.log-border-placement", false)) {
                    plugin.getLogger().info("DEBUG: ✅ Успешно установлен " + borderMaterial + " в позиции " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ());

                    // Проверяем, действительно ли блок установился
                    Material checkMaterial = loc.getBlock().getType();
                    if (checkMaterial != borderMaterial) {
                        plugin.getLogger().severe("DEBUG: ❌ ОШИБКА! Блок не установился! Ожидался " + borderMaterial + ", получили " + checkMaterial);
                        errorCount++;
                    } else {
                        plugin.getLogger().info("DEBUG: ✅ Блок успешно установлен и проверен");
                    }
                }

                // Дополнительные сообщения о замене
                if (plugin.getConfig().getBoolean("debug.log-border-creation", false)) {
                    String replacementType = replacingGrass ? " (заменена трава)" :
                            replacingFlower ? " (заменен цветок)" :
                                    replacingOtherVegetation ? " (заменено растение)" : "";
                    plugin.getLogger().info("DEBUG BORDERS: Установлен " + borderMaterial + " в " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ() + replacementType);
                }

            } catch (Exception e) {
                plugin.getLogger().severe("DEBUG: Ошибка при установке блока в " + loc + ": " + e.getMessage());
                e.printStackTrace();
                errorCount++;
            }
        }

        // Логируем информацию о замене растительности
        if (grassReplacedCount > 0 || flowersReplacedCount > 0 || otherVegetationCount > 0) {
            plugin.getLogger().info("DEBUG VEGETATION: При создании границ региона " + regionId + " заменено: " +
                    "травы - " + grassReplacedCount + ", цветов - " + flowersReplacedCount + ", других растений - " + otherVegetationCount);
        }

        // Сохраняем информацию о границах региона
        regionBorderBlocks.put(regionId, originalBlocks);

        if (plugin.getConfig().getBoolean("debug.log-border-creation", false)) {
            plugin.getLogger().info("=== РЕЗУЛЬТАТ СОЗДАНИЯ ГРАНИЦ ===");
            plugin.getLogger().info("DEBUG: Регион: " + regionId);
            plugin.getLogger().info("DEBUG: Успешно установлено: " + successCount + " блоков");
            plugin.getLogger().info("DEBUG: НА ЗЕМЛЕ размещено: " + placedOnGroundCount + " блоков");
            if (placedBelowCenterCount > 0 || placedAboveCenterCount > 0) {
                plugin.getLogger().info("DEBUG: Размещено ниже центра: " + placedBelowCenterCount + " блоков");
                plugin.getLogger().info("DEBUG: Размещено ВЫШЕ центра: " + placedAboveCenterCount + " блоков");
            }
            plugin.getLogger().info("DEBUG: Ошибок: " + errorCount);
            plugin.getLogger().info("DEBUG: Сохранено оригинальных блоков: " + originalBlocks.size());
            plugin.getLogger().info("DEBUG: Границы сохранены в карте: " + regionBorderBlocks.containsKey(regionId));

            if (successCount > 0) {
                plugin.getLogger().info("DEBUG: ✅ ГРАНИЦЫ УСПЕШНО СОЗДАНЫ!");
            } else {
                plugin.getLogger().severe("DEBUG: ❌ НИ ОДНА ГРАНИЦА НЕ БЫЛА СОЗДАНА!");
            }
        }

        plugin.getLogger().info("DEBUG BORDERS: Создано " + borderLocations.size() + " блоков границ для региона " + regionId);
    }
    private int findPrivateBlockY(World world, int centerX, int centerZ, String regionId) {
        try {
            // Получаем материал блока привата из конфига
            org.bukkit.Material protectMaterial;
            try {
                protectMaterial = org.bukkit.Material.valueOf(
                        plugin.getConfig().getString("protect-block.material", "DIAMOND_BLOCK"));
            } catch (IllegalArgumentException e) {
                protectMaterial = org.bukkit.Material.DIAMOND_BLOCK;
            }

            plugin.getLogger().info("DEBUG FIND PRIVATE: Ищем блок привата (" + protectMaterial + ") в центральной колонне X=" + centerX + " Z=" + centerZ);

            // Ищем блок привата от поверхности земли вниз до бедрока
            int highestY = world.getHighestBlockYAt(centerX, centerZ);

            // Начинаем поиск с поверхности и идем вниз
            for (int y = highestY; y >= world.getMinHeight(); y--) {
                org.bukkit.block.Block block = world.getBlockAt(centerX, y, centerZ);

                if (block.getType() == protectMaterial) {
                    plugin.getLogger().info("DEBUG FIND PRIVATE: ✅ Найден блок привата в Y=" + y);
                    return y;
                }
            }

            // Если не нашли вниз, ищем вверх от поверхности
            for (int y = highestY + 1; y <= world.getMaxHeight() - 1; y++) {
                org.bukkit.block.Block block = world.getBlockAt(centerX, y, centerZ);

                if (block.getType() == protectMaterial) {
                    plugin.getLogger().info("DEBUG FIND PRIVATE: ✅ Найден блок привата в Y=" + y + " (выше поверхности)");
                    return y;
                }
            }

            plugin.getLogger().warning("DEBUG FIND PRIVATE: ❌ Блок привата не найден в центральной колонне региона " + regionId);
            return -1;

        } catch (Exception e) {
            plugin.getLogger().severe("DEBUG FIND PRIVATE: Ошибка при поиске блока привата: " + e.getMessage());
            return -1;
        }
    }
    /**
     * Генерация позиций границ с приоритетом видимости НО с проверкой земли
     */
    private Set<Location> getBorderLocationsWithVisibilityButOnGround(World world, int minX, int maxX, int minZ, int maxZ, int centerY) {
        Set<Location> locations = new HashSet<>();

        if (plugin.getConfig().getBoolean("debug.log-border-placement", false)) {
            plugin.getLogger().info("DEBUG VISIBILITY: Генерация видимых границ с проверкой земли для центра Y=" + centerY);
        }

        // Северная сторона (minZ)
        for (int x = minX; x <= maxX; x++) {
            Location borderLoc = findVisibleBorderLocationWithGroundCheck(world, x, centerY, minZ);
            if (borderLoc != null) {
                locations.add(borderLoc);
            }
        }

        // Южная сторона (maxZ)
        for (int x = minX; x <= maxX; x++) {
            Location borderLoc = findVisibleBorderLocationWithGroundCheck(world, x, centerY, maxZ);
            if (borderLoc != null) {
                locations.add(borderLoc);
            }
        }

        // Западная сторона (minX), исключаем углы
        for (int z = minZ + 1; z < maxZ; z++) {
            Location borderLoc = findVisibleBorderLocationWithGroundCheck(world, minX, centerY, z);
            if (borderLoc != null) {
                locations.add(borderLoc);
            }
        }

        // Восточная сторона (maxX), исключаем углы
        for (int z = minZ + 1; z < maxZ; z++) {
            Location borderLoc = findVisibleBorderLocationWithGroundCheck(world, maxX, centerY, z);
            if (borderLoc != null) {
                locations.add(borderLoc);
            }
        }

        if (plugin.getConfig().getBoolean("debug.log-border-placement", false)) {
            plugin.getLogger().info("DEBUG VISIBILITY: Итого найдено " + locations.size() + " позиций с проверкой земли");
        }

        return locations;
    }

    /**
     * Поиск видимой позиции для границы с проверкой земли
     */
    private Location findVisibleBorderLocationWithGroundCheck(World world, int x, int centerY, int z) {
        // ПРИОРИТЕТ 1: Пробуем на 1 блок ниже центра
        int preferredY = centerY - 1;
        Location preferredLoc = new Location(world, x, preferredY, z);

        if (plugin.getConfig().getBoolean("debug.verbose-border-search", false)) {
            plugin.getLogger().info("DEBUG VISIBILITY: Проверяем приоритетную позицию X=" + x + " Y=" + preferredY + " Z=" + z);
        }

        // Проверяем что под блоком есть земля
        Block blockBelow = world.getBlockAt(x, preferredY - 1, z);
        if (isBorderVisible(world, x, preferredY, z) && isSolidBlock(blockBelow)) {
            if (plugin.getConfig().getBoolean("debug.verbose-border-search", false)) {
                plugin.getLogger().info("DEBUG VISIBILITY: ✅ Позиция видима И НА ЗЕМЛЕ, размещаем в Y=" + preferredY);
            }
            return preferredLoc;
        }

        // ПРИОРИТЕТ 2: Ищем ближайшую позицию НА ЗЕМЛЕ
        int maxSearchHeight = plugin.getConfig().getInt("visualization.physical-borders.placement.max_height_search", 20);

        // Сначала ищем вниз - ближе к земле
        for (int y = preferredY - 1; y >= Math.max(centerY - maxSearchHeight, world.getMinHeight()); y--) {
            Block groundBlock = world.getBlockAt(x, y, z);
            if (isSolidBlock(groundBlock)) {
                Location groundLoc = new Location(world, x, y + 1, z);
                if (plugin.getConfig().getBoolean("debug.verbose-border-search", false)) {
                    plugin.getLogger().info("DEBUG VISIBILITY: ✅ Найдена земля НИЖЕ в Y=" + y + ", размещаем границу в Y=" + (y + 1));
                }
                return groundLoc;
            }
        }

        // Потом ищем вверх
        for (int y = centerY; y <= Math.min(centerY + maxSearchHeight, world.getMaxHeight() - 1); y++) {
            Block groundBlock = world.getBlockAt(x, y, z);
            if (isSolidBlock(groundBlock)) {
                Location groundLoc = new Location(world, x, y + 1, z);
                if (plugin.getConfig().getBoolean("debug.verbose-border-search", false)) {
                    plugin.getLogger().info("DEBUG VISIBILITY: ✅ Найдена земля ВЫШЕ в Y=" + y + ", размещаем границу в Y=" + (y + 1));
                }
                return groundLoc;
            }
        }

        // КРАЙНИЙ СЛУЧАЙ: Используем оригинальную позицию
        if (plugin.getConfig().getBoolean("debug.verbose-border-search", false)) {
            plugin.getLogger().warning("DEBUG VISIBILITY: ⚠️ Не найдена земля, используем оригинальную позицию Y=" + centerY);
        }
        return new Location(world, x, centerY, z);
    }

    /**
     * Проверка видимости блока границы
     */
    private boolean isBorderVisible(World world, int x, int y, int z) {
        Block borderBlock = world.getBlockAt(x, y, z);
        Block blockAbove = world.getBlockAt(x, y + 1, z);

        // Проверяем, что позиция для границы подходящая (воздух или заменяемая растительность)
        boolean canPlaceBorder = isAirOrReplaceable(borderBlock) || isReplaceableVegetation(borderBlock);

        // Проверяем, что блок будет виден (над ним воздух или прозрачный блок)
        boolean isVisible = isTransparentOrAir(blockAbove);

        if (plugin.getConfig().getBoolean("debug.verbose-border-search", false)) {
            plugin.getLogger().info("DEBUG VISIBILITY: Y=" + y + " можно разместить=" + canPlaceBorder + " видимо=" + isVisible + " (блок: " + borderBlock.getType() + ", сверху: " + blockAbove.getType() + ")");
        }

        return canPlaceBorder && isVisible;
    }

    /**
     * Проверка прозрачности блока
     */
    private boolean isTransparentOrAir(Block block) {
        Material type = block.getType();

        // Воздух и жидкости - прозрачные
        if (type == Material.AIR ||
                type == Material.CAVE_AIR ||
                type == Material.VOID_AIR ||
                type == Material.WATER ||
                type == Material.LAVA) {
            return true;
        }

        // Растения и нетвердые блоки - прозрачные
        if (isVegetation(block) ||
                isGrass(block) ||
                isFlower(block) ||
                type.toString().contains("GLASS") ||
                type.toString().contains("LEAVES") ||
                type == Material.SNOW ||
                type == Material.TORCH ||
                type == Material.REDSTONE_TORCH ||
                type == Material.LEVER ||
                type == Material.STONE_BUTTON ||
                type == Material.OAK_BUTTON ||
                type == Material.TRIPWIRE_HOOK ||
                type == Material.STRING) {
            return true;
        }

        // Дополнительные прозрачные блоки
        String typeName = type.toString();
        if (typeName.contains("SIGN") ||
                typeName.contains("BANNER") ||
                typeName.contains("CARPET") ||
                typeName.contains("PRESSURE_PLATE") ||
                typeName.contains("RAIL") ||
                typeName.equals("LADDER") ||
                typeName.equals("VINE") ||
                typeName.equals("COBWEB") ||
                typeName.equals("BARRIER")) {
            return true;
        }

        return false;
    }
    /**
     * УМНОЕ размещение границ - поиск оптимальной позиции для каждой границы
     */
    /**
     * ПРОСТОЙ метод генерации позиций границ - прямоугольник на одном уровне
     */
    private Set<Location> generateSimpleBorderLocations(World world, int minX, int maxX, int minZ, int maxZ, int y) {
        Set<Location> locations = new HashSet<>();

        if (plugin.getConfig().getBoolean("debug.log-border-placement", false)) {
            plugin.getLogger().info("DEBUG: Генерация простых границ на уровне Y=" + y);
            plugin.getLogger().info("DEBUG: Прямоугольник от X=" + minX + " до X=" + maxX + ", от Z=" + minZ + " до Z=" + maxZ);
        }

        // Северная сторона (minZ)
        for (int x = minX; x <= maxX; x++) {
            Location loc = new Location(world, x, y, minZ);
            locations.add(loc);
            if (plugin.getConfig().getBoolean("debug.log-border-placement", false)) {
                plugin.getLogger().info("DEBUG: Добавлена северная граница: " + x + "," + y + "," + minZ);
            }
        }

        // Южная сторона (maxZ)
        for (int x = minX; x <= maxX; x++) {
            Location loc = new Location(world, x, y, maxZ);
            locations.add(loc);
            if (plugin.getConfig().getBoolean("debug.log-border-placement", false)) {
                plugin.getLogger().info("DEBUG: Добавлена южная граница: " + x + "," + y + "," + maxZ);
            }
        }

        // Западная сторона (minX), исключаем углы
        for (int z = minZ + 1; z < maxZ; z++) {
            Location loc = new Location(world, minX, y, z);
            locations.add(loc);
            if (plugin.getConfig().getBoolean("debug.log-border-placement", false)) {
                plugin.getLogger().info("DEBUG: Добавлена западная граница: " + minX + "," + y + "," + z);
            }
        }

        // Восточная сторона (maxX), исключаем углы
        for (int z = minZ + 1; z < maxZ; z++) {
            Location loc = new Location(world, maxX, y, z);
            locations.add(loc);
            if (plugin.getConfig().getBoolean("debug.log-border-placement", false)) {
                plugin.getLogger().info("DEBUG: Добавлена восточная граница: " + maxX + "," + y + "," + z);
            }
        }

        if (plugin.getConfig().getBoolean("debug.log-border-placement", false)) {
            plugin.getLogger().info("DEBUG: Итого сгенерировано " + locations.size() + " позиций границ");
        }
        return locations;
    }

    /**
     * УМНЫЙ метод генерации позиций границ с поиском земли
     */
    private Set<Location> getBorderLocationsWithSmartPlacement(World world, int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        Set<Location> locations = new HashSet<>();

        // Получаем координаты центрального блока
        int centerX = (minX + maxX) / 2;
        int centerY = (minY + maxY) / 2;
        int centerZ = (minZ + maxZ) / 2;

        if (plugin.getConfig().getBoolean("debug.log-border-placement", false)) {
            plugin.getLogger().info("DEBUG BORDERS: Центральный блок (блок привата): " + centerX + "," + centerY + "," + centerZ);
            plugin.getLogger().info("DEBUG BORDERS: Умное размещение границ: приоритет Y=" + (centerY - 1) + ", резерв - поиск земли");
        }

        // Северная и южная стороны (по оси Z)
        for (int x = minX; x <= maxX; x++) {
            // Северная сторона (minZ)
            Location northLoc = findOptimalBorderLocation(world, x, centerY, minZ);
            if (northLoc != null) {
                locations.add(northLoc);
            }

            // Южная сторона (maxZ)
            Location southLoc = findOptimalBorderLocation(world, x, centerY, maxZ);
            if (southLoc != null) {
                locations.add(southLoc);
            }
        }

        // Западная и восточная стороны (по оси X), исключаем углы
        for (int z = minZ + 1; z < maxZ; z++) {
            // Западная сторона (minX)
            Location westLoc = findOptimalBorderLocation(world, minX, centerY, z);
            if (westLoc != null) {
                locations.add(westLoc);
            }

            // Восточная сторона (maxX)
            Location eastLoc = findOptimalBorderLocation(world, maxX, centerY, z);
            if (eastLoc != null) {
                locations.add(eastLoc);
            }
        }

        if (plugin.getConfig().getBoolean("debug.log-border-placement", false)) {
            plugin.getLogger().info("DEBUG BORDERS: Подготовлено " + locations.size() + " позиций для умного размещения границ");
        }

        return locations;
    }
    private Location findOptimalBorderLocation(World world, int x, int centerY, int z) {
        // Получаем настройки из конфига
        String strategy = plugin.getConfig().getString("visualization.physical-borders.placement.strategy", "surface_contact");
        int maxDepthSearch = plugin.getConfig().getInt("visualization.physical-borders.placement.max_depth_search", 20);
        int maxHeightSearch = plugin.getConfig().getInt("visualization.physical-borders.placement.max_height_search", 10);
        boolean replaceVegetation = plugin.getConfig().getBoolean("visualization.physical-borders.placement.replace_vegetation", true);

        if (plugin.getConfig().getBoolean("debug.verbose-border-search", false)) {
            plugin.getLogger().info("DEBUG BORDERS: Поиск позиции для границы в X=" + x + " Z=" + z + " центр на Y=" + centerY);
        }

        if ("below_center".equals(strategy)) {
            // Простая стратегия - всегда на 1 блок ниже центра
            int targetY = centerY - 1;
            Location targetLoc = new Location(world, x, targetY, z);
            if (plugin.getConfig().getBoolean("debug.verbose-border-search", false)) {
                plugin.getLogger().info("DEBUG BORDERS: Используется стратегия 'below_center', размещение в Y=" + targetY);
            }
            return targetLoc;
        }

        // Умная стратегия - всегда на земле
        return findGroundBasedBorderLocation(world, x, centerY, z, maxDepthSearch, maxHeightSearch, replaceVegetation);
    }

    /**
     * УМНЫЙ метод для размещения границ с гарантией контакта с землей
     */
    private Location findGroundBasedBorderLocation(World world, int x, int centerY, int z, int maxDepthSearch, int maxHeightSearch, boolean replaceVegetation) {
        if (plugin.getConfig().getBoolean("debug.verbose-border-search", false)) {
            plugin.getLogger().info("DEBUG BORDERS: Поиск места на земле (границы не должны летать)");
        }

        // ПРИОРИТЕТ 1: Пытаемся поставить на 1 блок ниже центра (если есть опора)
        int preferredY = centerY - 1;
        Block preferredBlock = world.getBlockAt(x, preferredY, z);
        Block belowPreferred = world.getBlockAt(x, preferredY - 1, z);

        if (plugin.getConfig().getBoolean("debug.verbose-border-search", false)) {
            plugin.getLogger().info("DEBUG BORDERS: Приоритет 1 - проверяем Y=" + preferredY + " тип: " + preferredBlock.getType() + ", снизу: " + belowPreferred.getType());
        }

        // Если позиция подходит И есть опора снизу
        if ((isAirOrReplaceable(preferredBlock) || (replaceVegetation && isReplaceableVegetation(preferredBlock)))
                && isSolidBlock(belowPreferred)) {
            if (plugin.getConfig().getBoolean("debug.verbose-border-search", false)) {
                plugin.getLogger().info("DEBUG BORDERS: ✅ Приоритет 1 - размещаем в Y=" + preferredY + " (есть опора снизу)");
            }
            return new Location(world, x, preferredY, z);
        }

        if (plugin.getConfig().getBoolean("debug.verbose-border-search", false)) {
            plugin.getLogger().info("DEBUG BORDERS: ❌ Приоритет 1 невозможен - ищем ближайшую поверхность земли");
        }

        // ПРИОРИТЕТ 2: Ищем ближайшую твердую поверхность (сначала вниз, потом вверх)

        // Ищем вниз от предпочтительной позиции
        if (plugin.getConfig().getBoolean("debug.verbose-border-search", false)) {
            plugin.getLogger().info("DEBUG BORDERS: Поиск поверхности вниз от Y=" + preferredY);
        }
        for (int y = preferredY - 1; y >= Math.max(preferredY - maxDepthSearch, world.getMinHeight()); y--) {
            Block groundBlock = world.getBlockAt(x, y, z);
            Block aboveGround = world.getBlockAt(x, y + 1, z);

            if (plugin.getConfig().getBoolean("debug.verbose-border-search", false)) {
                plugin.getLogger().info("DEBUG BORDERS: Проверяем землю Y=" + y + " тип: " + groundBlock.getType() + ", сверху: " + aboveGround.getType());
            }

            // Если найден твердый блок (земля) И сверху можно поставить границу
            if (isSolidBlock(groundBlock) &&
                    (isAirOrReplaceable(aboveGround) || (replaceVegetation && isReplaceableVegetation(aboveGround)))) {
                int borderY = y + 1;
                if (plugin.getConfig().getBoolean("debug.verbose-border-search", false)) {
                    plugin.getLogger().info("DEBUG BORDERS: ✅ Найдена поверхность земли в Y=" + y + ", размещаем границу в Y=" + borderY);
                }
                return new Location(world, x, borderY, z);
            }
        }

        // Ищем вверх от предпочтительной позиции
        if (plugin.getConfig().getBoolean("debug.verbose-border-search", false)) {
            plugin.getLogger().info("DEBUG BORDERS: Поиск поверхности вверх от Y=" + preferredY);
        }
        for (int y = preferredY; y <= Math.min(centerY + maxHeightSearch, world.getMaxHeight() - 1); y++) {
            Block groundBlock = world.getBlockAt(x, y, z);
            Block aboveGround = world.getBlockAt(x, y + 1, z);

            if (plugin.getConfig().getBoolean("debug.verbose-border-search", false)) {
                plugin.getLogger().info("DEBUG BORDERS: Проверяем землю Y=" + y + " тип: " + groundBlock.getType() + ", сверху: " + aboveGround.getType());
            }

            // Если найден твердый блок (земля) И сверху можно поставить границу
            if (isSolidBlock(groundBlock) &&
                    (isAirOrReplaceable(aboveGround) || (replaceVegetation && isReplaceableVegetation(aboveGround)))) {
                int borderY = y + 1;
                if (plugin.getConfig().getBoolean("debug.verbose-border-search", false)) {
                    plugin.getLogger().info("DEBUG BORDERS: ✅ Найдена поверхность земли в Y=" + y + ", размещаем границу в Y=" + borderY);
                }
                return new Location(world, x, borderY, z);
            }
        }

        // КРАЙНИЙ СЛУЧАЙ: Ищем любую твердую поверхность в широком диапазоне
        if (plugin.getConfig().getBoolean("debug.verbose-border-search", false)) {
            plugin.getLogger().warning("DEBUG BORDERS: ⚠️ Расширенный поиск поверхности земли");
        }

        // Расширенный поиск вниз до бедрока
        for (int y = preferredY - maxDepthSearch - 1; y >= world.getMinHeight(); y--) {
            Block groundBlock = world.getBlockAt(x, y, z);
            if (isSolidBlock(groundBlock)) {
                int borderY = y + 1;
                if (plugin.getConfig().getBoolean("debug.verbose-border-search", false)) {
                    plugin.getLogger().info("DEBUG BORDERS: ✅ Расширенный поиск - найдена поверхность в Y=" + y + ", размещаем границу в Y=" + borderY);
                }
                return new Location(world, x, borderY, z);
            }
        }

        // КРИТИЧЕСКИЙ СЛУЧАЙ: Если вообще ничего не найдено, ставим на уровне мира
        int emergencyY = world.getHighestBlockYAt(x, z) + 1;
        if (plugin.getConfig().getBoolean("debug.verbose-border-search", false)) {
            plugin.getLogger().warning("DEBUG BORDERS: ⚠️ КРИТИЧЕСКИЙ СЛУЧАЙ - используем высшую точку мира Y=" + emergencyY);
        }
        return new Location(world, x, emergencyY, z);
    }
    /**
     * Метод для определения цветов и декоративных растений
     */
    private boolean isFlower(Block block) {
        Material type = block.getType();
        String typeName = type.toString();

        // Прямые проверки для известных цветов
        if (typeName.equals("DANDELION") ||
                typeName.equals("POPPY") ||
                typeName.equals("BLUE_ORCHID") ||
                typeName.equals("ALLIUM") ||
                typeName.equals("AZURE_BLUET") ||
                typeName.equals("OXEYE_DAISY") ||
                typeName.equals("CORNFLOWER") ||
                typeName.equals("LILY_OF_THE_VALLEY")) {
            return true;
        }

        // Проверки по строковому названию для совместимости
        return typeName.contains("FLOWER") ||
                typeName.contains("TULIP") ||
                typeName.contains("ROSE") ||
                typeName.contains("ORCHID") ||
                typeName.equals("SUNFLOWER") ||
                typeName.equals("LILAC") ||
                typeName.equals("PEONY") ||
                typeName.equals("ROSE_BUSH") ||
                // Дополнительные декоративные растения
                typeName.contains("MUSHROOM") ||
                typeName.equals("BROWN_MUSHROOM") ||
                typeName.equals("RED_MUSHROOM");
    }

    /**
     * Универсальный метод для определения всей заменяемой растительности
     */
    private boolean isReplaceableVegetation(Block block) {
        return isGrass(block) || isFlower(block) || isVegetation(block);
    }

    /**
     * Метод для определения травы
     */
    private boolean isGrass(Block block) {
        Material type = block.getType();
        String typeName = type.toString();

        // Основные проверки для травы
        if (type == Material.TALL_GRASS) {
            return true;
        }

        // Проверяем по строковому названию для совместимости с разными версиями
        if (typeName.equals("GRASS") ||           // Для старых версий
                typeName.equals("SHORT_GRASS")) {     // Для новых версий Minecraft 1.20+
            return true;
        }

        // Дополнительная проверка для случаев, когда материал называется по-другому
        if (typeName.contains("GRASS") &&
                !typeName.contains("BLOCK") &&
                !typeName.contains("PATH") &&
                !typeName.contains("STAINED")) {

            if (plugin.getConfig().getBoolean("debug.log-grass-processing", false)) {
                plugin.getLogger().info("DEBUG GRASS: Найден неизвестный тип травы: " + typeName + " - считаем травой");
            }
            return true;
        }

        return false;
    }

    /**
     * Метод для определения растительности
     */
    private boolean isVegetation(Block block) {
        Material type = block.getType();
        String typeName = type.toString();

        // Прямые проверки для известных материалов
        if (type == Material.FERN ||
                type == Material.LARGE_FERN ||
                type == Material.DEAD_BUSH ||
                type == Material.SEAGRASS ||
                type == Material.TALL_SEAGRASS ||
                type == Material.KELP ||
                type == Material.KELP_PLANT) {
            return true;
        }

        // Проверки по строковому названию для совместимости
        return typeName.contains("SAPLING") ||
                typeName.contains("VINE") ||
                typeName.equals("WHEAT") ||
                typeName.equals("CARROTS") ||
                typeName.equals("POTATOES") ||
                typeName.equals("BEETROOTS");
    }

    /**
     * Проверяет, является ли блок воздухом или заменяемым
     */
    private boolean isAirOrReplaceable(Block block) {
        Material type = block.getType();
        return type == Material.AIR ||
                type == Material.CAVE_AIR ||
                type == Material.VOID_AIR ||
                type == Material.WATER ||
                type == Material.LAVA ||
                type == Material.FERN ||
                type == Material.LARGE_FERN ||
                type == Material.DEAD_BUSH ||
                type == Material.SEAGRASS ||
                type == Material.TALL_SEAGRASS ||
                type == Material.KELP ||
                type == Material.KELP_PLANT;
    }

    /**
     * Улучшенный метод проверки твердого блока
     */
    private boolean isSolidBlock(Block block) {
        Material type = block.getType();

        // Исключаем жидкости и нетвердые блоки
        if (type == Material.AIR ||
                type == Material.CAVE_AIR ||
                type == Material.VOID_AIR ||
                type == Material.WATER ||
                type == Material.LAVA) {
            return false;
        }

        // Исключаем растения и другие нетвердые блоки
        if (type.toString().contains("SAPLING") ||
                type.toString().contains("FLOWER") ||
                type.toString().contains("GRASS") ||
                type.toString().contains("FERN") ||
                type == Material.DEAD_BUSH ||
                type == Material.SEAGRASS ||
                type == Material.TALL_SEAGRASS ||
                type == Material.KELP ||
                type == Material.KELP_PLANT ||
                type == Material.TORCH ||
                type == Material.REDSTONE_TORCH ||
                type == Material.LEVER ||
                type == Material.STONE_BUTTON ||
                type == Material.OAK_BUTTON ||
                type == Material.SNOW ||
                type == Material.POWDER_SNOW) {
            return false;
        }

        // Все остальное считаем твердым (включая землю, камень, бедрок и т.д.)
        return true;
    }
    /**
     * Перегруженный метод для совместимости
     */
    public void createRegionBorders(ProtectedRegion region) {
        if (plugin.getConfig().getBoolean("debug.log-world-detection", false)) {
            plugin.getLogger().info("DEBUG: Вызван createRegionBorders без указания мира, ищем мир автоматически");
        }

        World world = findWorldForRegion(region);
        if (world == null) {
            plugin.getLogger().severe("DEBUG: Не удалось определить мир для региона " + region.getId());
            return;
        }

        if (plugin.getConfig().getBoolean("debug.log-world-detection", false)) {
            plugin.getLogger().info("DEBUG: Найден мир для региона: " + world.getName());
        }
        createRegionBorders(region, world);
    }

    /**
     * Метод для поиска мира региона
     */
    private World findWorldForRegion(ProtectedRegion region) {
        if (plugin.getConfig().getBoolean("debug.log-world-detection", false)) {
            plugin.getLogger().info("DEBUG: Поиск мира для региона " + region.getId());
        }

        // Проверяем все миры
        for (World world : plugin.getServer().getWorlds()) {
            if (plugin.getConfig().getBoolean("debug.log-world-detection", false)) {
                plugin.getLogger().info("DEBUG: Проверяем мир: " + world.getName());
            }

            try {
                Object regionManager = plugin.getProtectRegionManager().getWorldGuardRegionManager(world);
                if (regionManager != null) {
                    java.lang.reflect.Method getRegionMethod = regionManager.getClass().getMethod("getRegion", String.class);
                    ProtectedRegion foundRegion = (ProtectedRegion) getRegionMethod.invoke(regionManager, region.getId());
                    if (foundRegion != null) {
                        if (plugin.getConfig().getBoolean("debug.log-world-detection", false)) {
                            plugin.getLogger().info("DEBUG: ✅ Найден регион " + region.getId() + " в мире " + world.getName());
                        }
                        return world;
                    }
                }
            } catch (Exception e) {
                if (plugin.getConfig().getBoolean("debug.log-world-detection", false)) {
                    plugin.getLogger().warning("DEBUG: Ошибка при поиске в мире " + world.getName() + ": " + e.getMessage());
                }
            }
        }

        // Если не нашли, используем первый доступный мир
        if (!plugin.getServer().getWorlds().isEmpty()) {
            World fallbackWorld = plugin.getServer().getWorlds().get(0);
            if (plugin.getConfig().getBoolean("debug.log-world-detection", false)) {
                plugin.getLogger().warning("DEBUG: ⚠️ Не удалось найти мир для региона, используем основной мир: " + fallbackWorld.getName());
            }
            return fallbackWorld;
        }

        plugin.getLogger().severe("DEBUG: ❌ Нет доступных миров!");
        return null;
    }

    /**
     * Удаляет физические границы региона
     */
    public void removeRegionBorders(String regionId) {
        if (plugin.getConfig().getBoolean("debug.log-border-removal", false)) {
            plugin.getLogger().info("DEBUG: Удаление границ региона " + regionId);
        }

        Map<Location, Material> originalBlocks = regionBorderBlocks.get(regionId);

        if (originalBlocks != null) {
            if (plugin.getConfig().getBoolean("debug.log-border-removal", false)) {
                plugin.getLogger().info("DEBUG: Найдено " + originalBlocks.size() + " блоков для восстановления");
            }

            // Получаем материал границ для проверки
            Material borderMaterial;
            try {
                borderMaterial = Material.valueOf(plugin.getConfig().getString("visualization.physical-borders.material", "RED_WOOL"));
            } catch (IllegalArgumentException e) {
                borderMaterial = Material.RED_WOOL;
            }

            int restoredCount = 0;

            // Восстанавливаем оригинальные блоки
            for (Map.Entry<Location, Material> entry : originalBlocks.entrySet()) {
                Location loc = entry.getKey();
                Material originalMaterial = entry.getValue();

                Block block = loc.getBlock();
                // Проверяем что блок все еще является границей
                if (block.getType() == borderMaterial) {
                    block.setType(originalMaterial);
                    restoredCount++;
                    if (plugin.getConfig().getBoolean("debug.log-border-removal", false)) {
                        plugin.getLogger().info("DEBUG: Восстановлен блок в " + loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ() + " на " + originalMaterial);
                    }
                }
            }

            // Удаляем информацию о границах
            regionBorderBlocks.remove(regionId);
            plugin.getLogger().info("DEBUG BORDERS: Границы региона " + regionId + " удалены, восстановлено " + restoredCount + " блоков");
        } else {
            if (plugin.getConfig().getBoolean("debug.log-border-removal", false)) {
                plugin.getLogger().info("DEBUG: Границы для региона " + regionId + " не найдены");
            }
        }
    }
    /**
     * Показывает предварительную визуализацию (можно оставить для предпросмотра)
     */
    public void showVisualization(Player player, Location centerLocation) {
        if (!plugin.getConfig().getBoolean("visualization.enabled", true)) {
            return;
        }

        player.sendMessage("§7Предпросмотр будущего региона...");
    }

    /**
     * Создание границ для нового региона
     */
    public void showCreatedRegionVisualization(Player player, ProtectedRegion region) {
        if (plugin.getConfig().getBoolean("debug.log-border-creation", false)) {
            plugin.getLogger().info("DEBUG: showCreatedRegionVisualization вызван для игрока " + player.getName() + " и региона " + region.getId());
        }

        // Создаем физические границы для региона с указанием мира игрока
        createRegionBorders(region, player.getWorld());

        // Отправляем сообщение игроку
        String message = plugin.getConfig().getString("messages.region-borders-created", "&aГраницы региона отмечены красной шерстью!");
        player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));

        if (plugin.getConfig().getBoolean("debug.log-border-creation", false)) {
            plugin.getLogger().info("DEBUG BORDERS: Показана визуализация региона " + region.getId() + " игроку " + player.getName() + " в мире " + player.getWorld().getName());
        }
    }

    /**
     * Показывает визуализацию региона
     */
    public void showRegionVisualization(Player player, Location center) {
        ProtectedRegion region = plugin.getProtectRegionManager().getRegionAt(center);

        if (region != null) {
            if (!regionBorderBlocks.containsKey(region.getId())) {
                createRegionBorders(region, center.getWorld());
            }
            player.sendMessage("§aРегион найден! Границы отмечены красной шерстью.");
        } else {
            player.sendMessage("§cРегион не найден в этой позиции.");
        }
    }

    // Остальные методы для совместимости
    public void clearVisualization(Player player) {
        BukkitTask task = activeTasks.get(player.getUniqueId());
        if (task != null) {
            task.cancel();
            activeTasks.remove(player.getUniqueId());
        }
    }

    public void clearAllVisualizations() {
        for (BukkitTask task : activeTasks.values()) {
            if (task != null) {
                task.cancel();
            }
        }
        activeTasks.clear();
    }

    public void removeAllRegionBorders() {
        plugin.getLogger().info("DEBUG BORDERS: Удаление всех границ регионов...");
        for (String regionId : new ArrayList<>(regionBorderBlocks.keySet())) {
            removeRegionBorders(regionId);
        }
        plugin.getLogger().info("DEBUG BORDERS: Все границы регионов удалены");
    }

    public boolean isLocationBorderBlock(Location location, String regionId) {
        Map<Location, Material> originalBlocks = regionBorderBlocks.get(regionId);
        if (originalBlocks == null) {
            return false;
        }

        for (Location borderLoc : originalBlocks.keySet()) {
            if (borderLoc.getBlockX() == location.getBlockX() &&
                    borderLoc.getBlockY() == location.getBlockY() &&
                    borderLoc.getBlockZ() == location.getBlockZ() &&
                    borderLoc.getWorld().equals(location.getWorld())) {
                return true;
            }
        }

        return false;
    }

    public boolean hasActiveVisualization(Player player) {
        return activeTasks.containsKey(player.getUniqueId());
    }

    public int getActiveVisualizationCount() {
        return activeTasks.size();
    }

    public int getRegionBordersCount() {
        return regionBorderBlocks.size();
    }

    public boolean hasRegionBorders(String regionId) {
        return regionBorderBlocks.containsKey(regionId);
    }
}