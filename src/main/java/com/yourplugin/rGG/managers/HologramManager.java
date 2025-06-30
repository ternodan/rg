package com.yourplugin.rGG.managers;

import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.ChatColor;
import org.bukkit.scheduler.BukkitRunnable;

import com.yourplugin.rGG.RGProtectPlugin;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Менеджер голограмм для плагина RGProtect
 * Управляет созданием, обновлением и удалением голограмм над регионами
 *
 * Возможности:
 * - Создание многострочных голограмм
 * - Автоматическое обновление информации
 * - Поддержка плейсхолдеров для динамической информации
 * - Отображение времени жизни регионов
 * - Отображение статуса расширения по высоте
 * - Отображение активных защитных флагов
 */
public class HologramManager {

    private final RGProtectPlugin plugin;
    private final Map<String, List<ArmorStand>> holograms;
    private final Map<String, Long> lastUpdateTimes;

    // Кэш для быстрого доступа к данным голограмм
    private final Map<String, HologramData> hologramCache;

    public HologramManager(RGProtectPlugin plugin) {
        this.plugin = plugin;
        this.holograms = new HashMap<>();
        this.lastUpdateTimes = new HashMap<>();
        this.hologramCache = new HashMap<>();

        // Запускаем задачу обновления голограмм
        startUpdateTask();

        plugin.getLogger().info("HologramManager инициализирован");
    }

    /**
     * Внутренний класс для хранения данных голограммы
     */
    private static class HologramData {
        public final String playerName;
        public final String creationDate;
        public Location location;

        public HologramData(String playerName, String creationDate, Location location) {
            this.playerName = playerName;
            this.creationDate = creationDate;
            this.location = location.clone();
        }
    }

    /**
     * Создает голограмму для региона
     * @param location Позиция голограммы
     * @param playerName Имя владельца региона
     * @param regionName ID региона
     */
    public void createHologram(Location location, String playerName, String regionName) {
        if (!plugin.getConfig().getBoolean("hologram.enabled", true)) {
            plugin.getLogger().info("Голограммы отключены в конфиге");
            return;
        }

        if (location == null || playerName == null || regionName == null) {
            plugin.getLogger().warning("Попытка создать голограмму с null параметрами");
            return;
        }

        try {
            // Удаляем существующую голограмму если есть
            removeHologram(regionName);

            List<String> lines = plugin.getConfig().getStringList("hologram.lines");
            if (lines.isEmpty()) {
                // Стандартные строки если конфигурация пуста
                lines.add("&6Регион игрока: &e{player}");
                lines.add("&7Создан: &f{date}");
                lines.add("&7Время жизни: &f{timer}");
                lines.add("&7Расширение ↕: &f{height_expansion}");
                lines.add("&dФлаги: &f{flag_protection}");
            }

            String currentDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));

            List<ArmorStand> hologramStands = new ArrayList<>();
            double heightOffset = plugin.getConfig().getDouble("hologram.height-offset", 1.5);

            // Создаем каждую строку голограммы
            for (int i = 0; i < lines.size(); i++) {
                String line = processHologramLine(lines.get(i), playerName, regionName, currentDate);

                // Вычисляем позицию для каждой строки
                Location hologramLoc = location.clone().add(0, heightOffset - (i * 0.25), 0);
                ArmorStand armorStand = createHologramLine(hologramLoc, line);

                if (armorStand != null) {
                    hologramStands.add(armorStand);
                }
            }

            // Сохраняем голограмму
            if (!hologramStands.isEmpty()) {
                holograms.put(regionName, hologramStands);
                lastUpdateTimes.put(regionName, System.currentTimeMillis());

                // Сохраняем данные в кэш
                HologramData data = new HologramData(playerName, currentDate, location);
                hologramCache.put(regionName, data);

                plugin.getLogger().info("Создана голограмма для региона " + regionName +
                        " (" + hologramStands.size() + " строк)");
            } else {
                plugin.getLogger().warning("Не удалось создать ни одной строки голограммы для региона " + regionName);
            }

        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка при создании голограммы для региона " + regionName + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Создает одну строку голограммы
     * @param location Позиция строки
     * @param text Текст строки
     * @return ArmorStand или null при ошибке
     */
    private ArmorStand createHologramLine(Location location, String text) {
        try {
            if (location.getWorld() == null) {
                plugin.getLogger().warning("Мир для голограммы равен null");
                return null;
            }

            ArmorStand armorStand = (ArmorStand) location.getWorld().spawnEntity(location, EntityType.ARMOR_STAND);

            // Настраиваем ArmorStand как голограмму
            armorStand.setVisible(false);
            armorStand.setGravity(false);
            armorStand.setCanPickupItems(false);
            armorStand.setCustomName(text);
            armorStand.setCustomNameVisible(true);
            armorStand.setInvulnerable(true);
            armorStand.setMarker(true);
            armorStand.setSmall(true);

            // Убираем руки чтобы они не мешали
            armorStand.setArms(false);
            armorStand.setBasePlate(false);

            return armorStand;

        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка при создании строки голограммы: " + e.getMessage());
            return null;
        }
    }
    /**
     * Обрабатывает строку голограммы с заменой плейсхолдеров
     * @param line Исходная строка
     * @param playerName Имя игрока
     * @param regionName ID региона
     * @param currentDate Текущая дата
     * @return Обработанная строка
     */
    private String processHologramLine(String line, String playerName, String regionName, String currentDate) {
        // Базовые замены
        line = line.replace("{player}", playerName)
                .replace("{date}", currentDate)
                .replace("{region}", regionName);

        // Обработка тега {timer}
        if (line.contains("{timer}")) {
            String timerText = getTimerText(regionName);
            line = line.replace("{timer}", timerText);
        }

        // Обработка тега {height_expansion}
        if (line.contains("{height_expansion}")) {
            String heightExpansionText = getHeightExpansionText(regionName);
            line = line.replace("{height_expansion}", heightExpansionText);
        }

        // Обработка тега {flag_protection}
        if (line.contains("{flag_protection}")) {
            String flagProtectionText = getFlagProtectionText(regionName);
            line = line.replace("{flag_protection}", flagProtectionText);
        }

        // Обработка дополнительных тегов
        if (line.contains("{size}")) {
            String sizeText = getRegionSizeText(regionName);
            line = line.replace("{size}", sizeText);
        }

        if (line.contains("{level}")) {
            String levelText = getRegionLevelText(regionName);
            line = line.replace("{level}", levelText);
        }

        if (line.contains("{owner}")) {
            line = line.replace("{owner}", playerName);
        }

        if (line.contains("{time}")) {
            String timeText = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            line = line.replace("{time}", timeText);
        }

        return ChatColor.translateAlternateColorCodes('&', line);
    }

    /**
     * Получает текст для отображения времени жизни региона
     * @param regionName ID региона
     * @return Форматированный текст
     */
    private String getTimerText(String regionName) {
        try {
            if (plugin.getRegionTimerManager() != null && plugin.getRegionTimerManager().hasTimer(regionName)) {
                return plugin.getRegionTimerManager().getFormattedRemainingTime(regionName);
            } else {
                return ChatColor.GRAY + "Бессрочный";
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при получении информации о таймере для " + regionName + ": " + e.getMessage());
            return ChatColor.RED + "Ошибка";
        }
    }

    /**
     * Получает текст для отображения расширения по высоте
     * @param regionName ID региона
     * @return Форматированный текст
     */
    private String getHeightExpansionText(String regionName) {
        try {
            if (plugin.getHeightExpansionManager() != null &&
                    plugin.getHeightExpansionManager().hasHeightExpansion(regionName)) {

                String remainingTime = plugin.getHeightExpansionManager().getFormattedRemainingTime(regionName);
                return ChatColor.LIGHT_PURPLE + "Активно (" + remainingTime + ")";
            } else {
                return ChatColor.GRAY + "Неактивно";
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при получении информации о расширении по высоте для " + regionName + ": " + e.getMessage());
            return ChatColor.RED + "Ошибка";
        }
    }

    /**
     * Получает текст для отображения защитных флагов
     * @param regionName ID региона
     * @return Форматированный текст
     */
    private String getFlagProtectionText(String regionName) {
        try {
            if (plugin.getFlagProtectionManager() == null) {
                return ChatColor.GRAY + "Отключены";
            }

            // Подсчитываем активные флаги
            int activeFlags = 0;
            if (plugin.getConfig().contains("flag-protection.flags")) {
                for (String flagKey : plugin.getConfig().getConfigurationSection("flag-protection.flags").getKeys(false)) {
                    if (plugin.getFlagProtectionManager().isFlagActive(regionName, flagKey)) {
                        activeFlags++;
                    }
                }
            }

            if (activeFlags > 0) {
                return ChatColor.GREEN + "Активно (" + activeFlags + ")";
            } else {
                return ChatColor.GRAY + "Неактивно";
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при получении информации о флагах для " + regionName + ": " + e.getMessage());
            return ChatColor.RED + "Ошибка";
        }
    }

    /**
     * Получает текст размера региона
     * @param regionName ID региона
     * @return Размер региона
     */
    private String getRegionSizeText(String regionName) {
        try {
            com.sk89q.worldguard.protection.regions.ProtectedRegion region = findRegionById(regionName);
            if (region != null) {
                int sizeX = region.getMaximumPoint().x() - region.getMinimumPoint().x() + 1;
                int sizeY = region.getMaximumPoint().y() - region.getMinimumPoint().y() + 1;
                int sizeZ = region.getMaximumPoint().z() - region.getMinimumPoint().z() + 1;
                return sizeX + "×" + sizeY + "×" + sizeZ;
            }
            return ChatColor.GRAY + "Неизвестно";
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при получении размера региона " + regionName + ": " + e.getMessage());
            return ChatColor.RED + "Ошибка";
        }
    }

    /**
     * Получает текст уровня расширения региона
     * @param regionName ID региона
     * @return Уровень региона
     */
    private String getRegionLevelText(String regionName) {
        try {
            com.sk89q.worldguard.protection.regions.ProtectedRegion region = findRegionById(regionName);
            if (region != null) {
                int baseX = plugin.getConfig().getInt("region-expansion.base-size.x", 3);
                int currentX = region.getMaximumPoint().x() - region.getMinimumPoint().x() + 1;
                int level = Math.max(0, (currentX - baseX) / 2);
                int maxLevel = plugin.getConfig().getInt("region-expansion.max-level", 10);
                return level + "/" + maxLevel;
            }
            return ChatColor.GRAY + "0/10";
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при получении уровня региона " + regionName + ": " + e.getMessage());
            return ChatColor.RED + "Ошибка";
        }
    }
    /**
     * Удаляет голограмму региона
     * @param regionName ID региона
     */
    public void removeHologram(String regionName) {
        if (regionName == null) {
            plugin.getLogger().warning("Попытка удалить голограмму с null regionName");
            return;
        }

        try {
            List<ArmorStand> stands = holograms.get(regionName);
            if (stands != null) {
                int removedCount = 0;
                for (ArmorStand stand : stands) {
                    if (stand != null && !stand.isDead()) {
                        stand.remove();
                        removedCount++;
                    }
                }

                holograms.remove(regionName);
                lastUpdateTimes.remove(regionName);
                hologramCache.remove(regionName);

                plugin.getLogger().info("Удалена голограмма региона " + regionName +
                        " (" + removedCount + " строк)");
            } else {
                if (plugin.getConfig().getBoolean("debug.log-hologram-operations", false)) {
                    plugin.getLogger().info("Голограмма для региона " + regionName + " не найдена");
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка при удалении голограммы " + regionName + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Удаляет все голограммы
     */
    public void removeAllHolograms() {
        plugin.getLogger().info("Удаление всех голограмм...");

        int totalRemoved = 0;
        for (String regionName : new ArrayList<>(holograms.keySet())) {
            List<ArmorStand> stands = holograms.get(regionName);
            if (stands != null) {
                totalRemoved += stands.size();
            }
            removeHologram(regionName);
        }

        holograms.clear();
        lastUpdateTimes.clear();
        hologramCache.clear();

        plugin.getLogger().info("Удалено голограмм: " + totalRemoved);
    }

    /**
     * Обновляет голограмму региона
     * @param regionName ID региона
     * @param playerName Имя владельца
     */
    public void updateHologram(String regionName, String playerName) {
        if (regionName == null || playerName == null) {
            plugin.getLogger().warning("Попытка обновить голограмму с null параметрами");
            return;
        }

        try {
            List<ArmorStand> stands = holograms.get(regionName);
            if (stands == null || stands.isEmpty()) {
                if (plugin.getConfig().getBoolean("debug.log-hologram-operations", false)) {
                    plugin.getLogger().info("Голограмма для региона " + regionName + " не найдена для обновления");
                }
                return;
            }

            List<String> lines = plugin.getConfig().getStringList("hologram.lines");
            if (lines.isEmpty()) {
                plugin.getLogger().warning("Строки голограммы не настроены в конфиге");
                return;
            }

            // Получаем сохраненную дату создания или используем текущую
            HologramData cachedData = hologramCache.get(regionName);
            String creationDate = cachedData != null ? cachedData.creationDate :
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));

            // Обновляем каждую строку
            int updatedLines = 0;
            for (int i = 0; i < Math.min(lines.size(), stands.size()); i++) {
                ArmorStand stand = stands.get(i);
                if (stand != null && !stand.isDead()) {
                    String line = processHologramLine(lines.get(i), playerName, regionName, creationDate);
                    stand.setCustomName(line);
                    updatedLines++;
                } else {
                    // Если ArmorStand мертв, пытаемся пересоздать голограмму
                    plugin.getLogger().warning("ArmorStand голограммы региона " + regionName + " мертв, пересоздаем голограмму");
                    recreateHologram(regionName, playerName);
                    return;
                }
            }

            // Обновляем время последнего обновления
            lastUpdateTimes.put(regionName, System.currentTimeMillis());

            if (plugin.getConfig().getBoolean("debug.log-hologram-operations", false)) {
                plugin.getLogger().info("Обновлена голограмма региона " + regionName +
                        " (" + updatedLines + " строк)");
            }

        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка при обновлении голограммы " + regionName + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Пересоздает голограмму на том же месте
     * @param regionName ID региона
     * @param playerName Имя владельца
     */
    private void recreateHologram(String regionName, String playerName) {
        try {
            HologramData cachedData = hologramCache.get(regionName);
            if (cachedData != null && cachedData.location != null) {
                plugin.getLogger().info("Пересоздаем голограмму для региона " + regionName);
                createHologram(cachedData.location, playerName, regionName);
            } else {
                plugin.getLogger().warning("Не удалось пересоздать голограмму для региона " + regionName +
                        " - нет сохраненной позиции");
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка при пересоздании голограммы " + regionName + ": " + e.getMessage());
        }
    }

    /**
     * Запускает задачу автоматического обновления голограмм
     */
    private void startUpdateTask() {
        int updateInterval = plugin.getConfig().getInt("hologram.update-interval", 20);

        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    updateAllHolograms();
                } catch (Exception e) {
                    plugin.getLogger().severe("Ошибка в задаче обновления голограмм: " + e.getMessage());
                    if (plugin.getConfig().getBoolean("debug.log-stack-traces", false)) {
                        e.printStackTrace();
                    }
                }
            }
        }.runTaskTimer(plugin, updateInterval, updateInterval);

        plugin.getLogger().info("Запущена задача обновления голограмм (интервал: " + updateInterval + " тиков)");
    }
    /**
     * Обновляет все голограммы
     */
    private void updateAllHolograms() {
        if (holograms.isEmpty()) {
            return;
        }

        // Проверяем существование голограмм и обновляем их
        for (Map.Entry<String, List<ArmorStand>> entry : new HashMap<>(holograms).entrySet()) {
            String regionName = entry.getKey();
            List<ArmorStand> stands = entry.getValue();

            try {
                // Удаляем мертвые ArmorStand'ы
                stands.removeIf(stand -> stand == null || stand.isDead());

                if (stands.isEmpty()) {
                    // Если все ArmorStand'ы мертвы, удаляем голограмму
                    holograms.remove(regionName);
                    lastUpdateTimes.remove(regionName);
                    hologramCache.remove(regionName);
                    plugin.getLogger().info("Удалена голограмма с мертвыми ArmorStand'ами: " + regionName);
                } else {
                    // Обновляем существующую голограмму
                    String ownerName = getRegionOwnerName(regionName);
                    if (ownerName != null) {
                        updateHologram(regionName, ownerName);
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Ошибка при обновлении голограммы " + regionName + ": " + e.getMessage());
            }
        }
    }

    /**
     * Получает имя владельца региона
     * @param regionName ID региона
     * @return Имя владельца или null
     */
    private String getRegionOwnerName(String regionName) {
        try {
            // Сначала проверяем кэш
            HologramData cachedData = hologramCache.get(regionName);
            if (cachedData != null && cachedData.playerName != null) {
                return cachedData.playerName;
            }

            // Ищем регион во всех мирах
            for (org.bukkit.World world : plugin.getServer().getWorlds()) {
                try {
                    Object regionManager = plugin.getProtectRegionManager().getWorldGuardRegionManager(world);
                    if (regionManager != null) {
                        java.lang.reflect.Method getRegionMethod = regionManager.getClass().getMethod("getRegion", String.class);
                        com.sk89q.worldguard.protection.regions.ProtectedRegion region =
                                (com.sk89q.worldguard.protection.regions.ProtectedRegion) getRegionMethod.invoke(regionManager, regionName);

                        if (region != null) {
                            // Получаем имя владельца
                            String ownerName = extractOwnerName(region);

                            // Обновляем кэш
                            if (cachedData != null) {
                                hologramCache.put(regionName, new HologramData(ownerName, cachedData.creationDate, cachedData.location));
                            }

                            return ownerName;
                        }
                    }
                } catch (Exception e) {
                    // Игнорируем ошибки поиска в конкретном мире
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при получении владельца региона " + regionName + ": " + e.getMessage());
        }

        return null;
    }

    /**
     * Извлекает имя владельца из региона
     * @param region Регион WorldGuard
     * @return Имя владельца
     */
    private String extractOwnerName(com.sk89q.worldguard.protection.regions.ProtectedRegion region) {
        try {
            if (!region.getOwners().getUniqueIds().isEmpty()) {
                UUID ownerUUID = region.getOwners().getUniqueIds().iterator().next();
                String ownerName = plugin.getServer().getOfflinePlayer(ownerUUID).getName();
                return ownerName != null ? ownerName : "Unknown";
            }

            if (!region.getOwners().getPlayers().isEmpty()) {
                return region.getOwners().getPlayers().iterator().next();
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при извлечении имени владельца: " + e.getMessage());
        }

        return "Unknown";
    }

    /**
     * Находит регион по ID во всех мирах
     * @param regionId ID региона
     * @return Регион или null
     */
    private com.sk89q.worldguard.protection.regions.ProtectedRegion findRegionById(String regionId) {
        for (org.bukkit.World world : plugin.getServer().getWorlds()) {
            try {
                Object regionManager = plugin.getProtectRegionManager().getWorldGuardRegionManager(world);
                if (regionManager != null) {
                    java.lang.reflect.Method getRegionMethod = regionManager.getClass().getMethod("getRegion", String.class);
                    com.sk89q.worldguard.protection.regions.ProtectedRegion region =
                            (com.sk89q.worldguard.protection.regions.ProtectedRegion) getRegionMethod.invoke(regionManager, regionId);
                    if (region != null) {
                        return region;
                    }
                }
            } catch (Exception e) {
                // Игнорируем ошибки поиска в конкретном мире
            }
        }
        return null;
    }

    // ===== ПУБЛИЧНЫЕ МЕТОДЫ ДЛЯ СТАТИСТИКИ И УПРАВЛЕНИЯ =====

    /**
     * Проверяет, существует ли голограмма для региона
     * @param regionName ID региона
     * @return true если голограмма существует
     */
    public boolean hasHologram(String regionName) {
        return holograms.containsKey(regionName) && !holograms.get(regionName).isEmpty();
    }

    /**
     * Получает количество активных голограмм
     * @return Количество голограмм
     */
    public int getHologramCount() {
        return holograms.size();
    }

    /**
     * Получает общее количество ArmorStand'ов
     * @return Количество entities
     */
    public int getTotalArmorStandCount() {
        int total = 0;
        for (List<ArmorStand> stands : holograms.values()) {
            total += stands.size();
        }
        return total;
    }

    /**
     * Получает количество строк в голограмме
     * @param regionName ID региона
     * @return Количество строк или 0
     */
    public int getHologramLineCount(String regionName) {
        List<ArmorStand> stands = holograms.get(regionName);
        return stands != null ? stands.size() : 0;
    }

    /**
     * Получает позицию голограммы
     * @param regionName ID региона
     * @return Позиция или null
     */
    public Location getHologramLocation(String regionName) {
        HologramData data = hologramCache.get(regionName);
        return data != null ? data.location.clone() : null;
    }

    /**
     * Получает время последнего обновления голограммы
     * @param regionName ID региона
     * @return Время в миллисекундах или -1
     */
    public long getLastUpdateTime(String regionName) {
        return lastUpdateTimes.getOrDefault(regionName, -1L);
    }

    /**
     * Принудительно обновляет конкретную голограмму
     * @param regionName ID региона
     */
    public void forceUpdateHologram(String regionName) {
        String ownerName = getRegionOwnerName(regionName);
        if (ownerName != null) {
            updateHologram(regionName, ownerName);
            plugin.getLogger().info("Принудительно обновлена голограмма региона: " + regionName);
        } else {
            plugin.getLogger().warning("Не удалось принудительно обновить голограмму: владелец не найден для " + regionName);
        }
    }

    /**
     * Перемещает голограмму на новую позицию
     * @param regionName ID региона
     * @param newLocation Новая позиция
     */
    public void moveHologram(String regionName, Location newLocation) {
        if (hasHologram(regionName)) {
            HologramData cachedData = hologramCache.get(regionName);
            String playerName = cachedData != null ? cachedData.playerName : getRegionOwnerName(regionName);

            removeHologram(regionName);
            createHologram(newLocation, playerName, regionName);

            plugin.getLogger().info("Голограмма региона " + regionName + " перемещена на новую позицию");
        } else {
            plugin.getLogger().warning("Попытка переместить несуществующую голограмму: " + regionName);
        }
    }

    /**
     * Получает список всех регионов с голограммами
     * @return Набор ID регионов
     */
    public java.util.Set<String> getHologramRegions() {
        return new java.util.HashSet<>(holograms.keySet());
    }

    /**
     * Проверяет здоровье голограммы (все ли ArmorStand'ы живы)
     * @param regionName ID региона
     * @return true если голограмма здорова
     */
    public boolean isHologramHealthy(String regionName) {
        List<ArmorStand> stands = holograms.get(regionName);
        if (stands == null || stands.isEmpty()) {
            return false;
        }

        for (ArmorStand stand : stands) {
            if (stand == null || stand.isDead()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Исправляет поврежденные голограммы
     * @return Количество исправленных голограмм
     */
    public int repairDamagedHolograms() {
        int repairedCount = 0;

        for (String regionName : new ArrayList<>(holograms.keySet())) {
            if (!isHologramHealthy(regionName)) {
                String ownerName = getRegionOwnerName(regionName);
                if (ownerName != null) {
                    recreateHologram(regionName, ownerName);
                    repairedCount++;
                    plugin.getLogger().info("Исправлена поврежденная голограмма: " + regionName);
                } else {
                    removeHologram(regionName);
                    plugin.getLogger().info("Удалена голограмма без владельца: " + regionName);
                }
            }
        }

        return repairedCount;
    }

    /**
     * Получает детальную информацию о голограмме
     * @param regionName ID региона
     * @return Информация о голограмме
     */
    public String getHologramInfo(String regionName) {
        if (!hasHologram(regionName)) {
            return "Голограмма не найдена";
        }

        List<ArmorStand> stands = holograms.get(regionName);
        HologramData data = hologramCache.get(regionName);
        long lastUpdate = getLastUpdateTime(regionName);

        StringBuilder info = new StringBuilder();
        info.append("Регион: ").append(regionName).append("\n");
        info.append("Владелец: ").append(data != null ? data.playerName : "Неизвестно").append("\n");
        info.append("Строк: ").append(stands.size()).append("\n");
        info.append("Здорова: ").append(isHologramHealthy(regionName) ? "Да" : "Нет").append("\n");
        info.append("Последнее обновление: ");

        if (lastUpdate > 0) {
            long timeSince = System.currentTimeMillis() - lastUpdate;
            info.append(timeSince / 1000).append(" сек назад");
        } else {
            info.append("Никогда");
        }

        if (data != null && data.location != null) {
            info.append("\nПозиция: ").append(data.location.getBlockX())
                    .append(", ").append(data.location.getBlockY())
                    .append(", ").append(data.location.getBlockZ())
                    .append(" (").append(data.location.getWorld().getName()).append(")");
        }

        return info.toString();
    }

    /**
     * Устанавливает кастомную строку для голограммы
     * @param regionName ID региона
     * @param lineIndex Индекс строки (0-based)
     * @param text Новый текст
     * @return true если успешно
     */
    public boolean setCustomLine(String regionName, int lineIndex, String text) {
        List<ArmorStand> stands = holograms.get(regionName);
        if (stands == null || lineIndex < 0 || lineIndex >= stands.size()) {
            return false;
        }

        try {
            ArmorStand stand = stands.get(lineIndex);
            if (stand != null && !stand.isDead()) {
                String processedText = ChatColor.translateAlternateColorCodes('&', text);
                stand.setCustomName(processedText);
                return true;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при установке кастомной строки: " + e.getMessage());
        }

        return false;
    }

    /**
     * Получает статистику всех голограмм
     * @return Строка со статистикой
     */
    public String getStatistics() {
        int totalHolograms = getHologramCount();
        int totalArmorStands = getTotalArmorStandCount();
        int healthyHolograms = 0;

        for (String regionName : holograms.keySet()) {
            if (isHologramHealthy(regionName)) {
                healthyHolograms++;
            }
        }

        return String.format("Голограмм: %d, ArmorStand'ов: %d, Здоровых: %d/%d",
                totalHolograms, totalArmorStands, healthyHolograms, totalHolograms);
    }

    /**
     * Очищает кэш голограмм
     */
    public void clearCache() {
        hologramCache.clear();
        lastUpdateTimes.clear();
        plugin.getLogger().info("Кэш голограмм очищен");
    }

    /**
     * Сохраняет данные голограмм (для перезагрузки)
     * @return Map с данными для восстановления
     */
    public Map<String, HologramData> saveHologramData() {
        return new HashMap<>(hologramCache);
    }

    /**
     * Восстанавливает голограммы из сохраненных данных
     * @param savedData Сохраненные данные
     */
    public void restoreHolograms(Map<String, HologramData> savedData) {
        if (savedData == null || savedData.isEmpty()) {
            return;
        }

        int restoredCount = 0;
        for (Map.Entry<String, HologramData> entry : savedData.entrySet()) {
            String regionName = entry.getKey();
            HologramData data = entry.getValue();

            try {
                createHologram(data.location, data.playerName, regionName);
                restoredCount++;
            } catch (Exception e) {
                plugin.getLogger().warning("Не удалось восстановить голограмму " + regionName + ": " + e.getMessage());
            }
        }

        plugin.getLogger().info("Восстановлено голограмм: " + restoredCount);
    }

    /**
     * Проверяет и исправляет все голограммы
     */
    public void validateAndRepairAll() {
        plugin.getLogger().info("Начинается проверка и исправление всех голограмм...");

        int repairedCount = repairDamagedHolograms();
        int totalCount = getHologramCount();

        plugin.getLogger().info(String.format("Проверка завершена. Всего голограмм: %d, исправлено: %d",
                totalCount, repairedCount));
    }

    /**
     * Обновляет конфигурацию голограмм на лету
     */
    public void reloadConfiguration() {
        plugin.getLogger().info("Перезагрузка конфигурации голограмм...");

        // Сохраняем текущие данные
        Map<String, HologramData> savedData = saveHologramData();

        // Удаляем все голограммы
        removeAllHolograms();

        // Восстанавливаем с новой конфигурацией
        restoreHolograms(savedData);

        plugin.getLogger().info("Конфигурация голограмм перезагружена");
    }

    /**
     * Устанавливает видимость голограммы
     * @param regionName ID региона
     * @param visible Видимость
     */
    public void setHologramVisible(String regionName, boolean visible) {
        List<ArmorStand> stands = holograms.get(regionName);
        if (stands == null) {
            return;
        }

        for (ArmorStand stand : stands) {
            if (stand != null && !stand.isDead()) {
                stand.setCustomNameVisible(visible);
            }
        }

        plugin.getLogger().info("Видимость голограммы " + regionName + " изменена на: " + visible);
    }

    /**
     * Получает видимость голограммы
     * @param regionName ID региона
     * @return Видимость или false если голограмма не найдена
     */
    public boolean isHologramVisible(String regionName) {
        List<ArmorStand> stands = holograms.get(regionName);
        if (stands == null || stands.isEmpty()) {
            return false;
        }

        ArmorStand firstStand = stands.get(0);
        return firstStand != null && !firstStand.isDead() && firstStand.isCustomNameVisible();
    }

    /**
     * Добавляет временную строку к голограмме
     * @param regionName ID региона
     * @param text Текст строки
     * @param durationSeconds Продолжительность в секундах
     */
    public void addTemporaryLine(String regionName, String text, int durationSeconds) {
        if (!hasHologram(regionName)) {
            return;
        }

        try {
            HologramData data = hologramCache.get(regionName);
            if (data == null || data.location == null) {
                return;
            }

            // Создаем временную строку выше существующей голограммы
            Location tempLocation = data.location.clone().add(0, 0.5, 0);
            String processedText = ChatColor.translateAlternateColorCodes('&', text);
            ArmorStand tempStand = createHologramLine(tempLocation, processedText);

            if (tempStand != null) {
                // Удаляем временную строку через указанное время
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (!tempStand.isDead()) {
                        tempStand.remove();
                    }
                }, durationSeconds * 20L);

                plugin.getLogger().info("Добавлена временная строка к голограмме " + regionName + " на " + durationSeconds + " секунд");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при добавлении временной строки: " + e.getMessage());
        }
    }

    /**
     * Анимирует голограмму (мерцание)
     * @param regionName ID региона
     * @param times Количество мерцаний
     */
    public void animateHologram(String regionName, int times) {
        if (!hasHologram(regionName)) {
            return;
        }

        final boolean originalVisibility = isHologramVisible(regionName);

        new BukkitRunnable() {
            private int currentFlash = 0;
            private boolean currentlyVisible = originalVisibility;

            @Override
            public void run() {
                if (currentFlash >= times * 2) {
                    // Восстанавливаем оригинальную видимость
                    setHologramVisible(regionName, originalVisibility);
                    cancel();
                    return;
                }

                currentlyVisible = !currentlyVisible;
                setHologramVisible(regionName, currentlyVisible);
                currentFlash++;
            }
        }.runTaskTimer(plugin, 0L, 10L); // Мерцание каждые 0.5 секунды
    }

    /**
     * Проверяет, находится ли голограмма в загруженном чанке
     * @param regionName ID региона
     * @return true если чанк загружен
     */
    public boolean isHologramInLoadedChunk(String regionName) {
        HologramData data = hologramCache.get(regionName);
        if (data == null || data.location == null || data.location.getWorld() == null) {
            return false;
        }

        return data.location.getWorld().isChunkLoaded(
                data.location.getBlockX() >> 4,
                data.location.getBlockZ() >> 4
        );
    }

    /**
     * Получает дистанцию от голограммы до игрока
     * @param regionName ID региона
     * @param playerLocation Позиция игрока
     * @return Расстояние или -1 если голограмма не найдена
     */
    public double getDistanceToHologram(String regionName, Location playerLocation) {
        HologramData data = hologramCache.get(regionName);
        if (data == null || data.location == null || playerLocation == null) {
            return -1;
        }

        if (!data.location.getWorld().equals(playerLocation.getWorld())) {
            return -1;
        }

        return data.location.distance(playerLocation);
    }

    /**
     * Очистка ресурсов при выключении плагина
     */
    public void shutdown() {
        plugin.getLogger().info("HologramManager: Остановка...");

        try {
            // Сохраняем данные перед выключением
            Map<String, HologramData> backupData = saveHologramData();
            plugin.getLogger().info("Сохранены данные " + backupData.size() + " голограмм");

            // Удаляем все голограммы
            removeAllHolograms();

            // Очищаем коллекции
            holograms.clear();
            lastUpdateTimes.clear();
            hologramCache.clear();

            plugin.getLogger().info("HologramManager остановлен");

        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка при остановке HologramManager: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Получает отладочную информацию
     * @return Отладочная информация
     */
    public String getDebugInfo() {
        StringBuilder debug = new StringBuilder();
        debug.append("=== HologramManager Debug Info ===\n");
        debug.append("Активных голограмм: ").append(getHologramCount()).append("\n");
        debug.append("Всего ArmorStand'ов: ").append(getTotalArmorStandCount()).append("\n");
        debug.append("Записей в кэше: ").append(hologramCache.size()).append("\n");
        debug.append("Записей времени обновления: ").append(lastUpdateTimes.size()).append("\n");

        debug.append("\nДетали по голограммам:\n");
        for (String regionName : holograms.keySet()) {
            debug.append("- ").append(regionName).append(": ");
            debug.append(getHologramLineCount(regionName)).append(" строк, ");
            debug.append("здорова: ").append(isHologramHealthy(regionName) ? "да" : "нет");
            debug.append(", в загруженном чанке: ").append(isHologramInLoadedChunk(regionName) ? "да" : "нет");
            debug.append("\n");
        }

        return debug.toString();
    }

    /**
     * Выполняет диагностику системы голограмм
     * @return Отчет о диагностике
     */
    public String performDiagnostics() {
        StringBuilder report = new StringBuilder();
        report.append("=== Диагностика HologramManager ===\n");

        int totalHolograms = getHologramCount();
        int healthyHolograms = 0;
        int inLoadedChunks = 0;
        int withMissingOwners = 0;

        for (String regionName : holograms.keySet()) {
            if (isHologramHealthy(regionName)) {
                healthyHolograms++;
            }

            if (isHologramInLoadedChunk(regionName)) {
                inLoadedChunks++;
            }

            if (getRegionOwnerName(regionName) == null) {
                withMissingOwners++;
            }
        }

        report.append("Общее количество голограмм: ").append(totalHolograms).append("\n");
        report.append("Здоровых голограмм: ").append(healthyHolograms).append("/").append(totalHolograms).append("\n");
        report.append("В загруженных чанках: ").append(inLoadedChunks).append("/").append(totalHolograms).append("\n");
        report.append("С отсутствующими владельцами: ").append(withMissingOwners).append("\n");

        // Рекомендации
        report.append("\nРекомендации:\n");
        if (healthyHolograms < totalHolograms) {
            report.append("- Выполните repairDamagedHolograms() для исправления поврежденных голограмм\n");
        }
        if (withMissingOwners > 0) {
            report.append("- Найдено ").append(withMissingOwners).append(" голограмм без владельцев\n");
        }
        if (inLoadedChunks < totalHolograms) {
            report.append("- ").append(totalHolograms - inLoadedChunks).append(" голограмм в незагруженных чанках\n");
        }

        return report.toString();
    }
}