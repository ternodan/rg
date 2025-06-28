package com.yourplugin.rGG.managers;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldedit.math.BlockVector3;
import com.yourplugin.rGG.RGProtectPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class HeightExpansionManager {

    private final RGProtectPlugin plugin;
    // Хранение времени истечения расширения для каждого региона
    private final Map<String, Long> regionExpansionTimes;
    // Хранение оригинальных границ регионов
    private final Map<String, RegionBounds> originalBounds;
    // Хранение последних уведомлений для каждого региона
    private final Map<String, Set<Integer>> sentNotifications;
    // Задача обновления таймеров
    private BukkitTask timerTask;
    // Файл для сохранения данных расширений
    private File expansionFile;
    private FileConfiguration expansionConfig;

    public HeightExpansionManager(RGProtectPlugin plugin) {
        this.plugin = plugin;
        this.regionExpansionTimes = new ConcurrentHashMap<>();
        this.originalBounds = new ConcurrentHashMap<>();
        this.sentNotifications = new ConcurrentHashMap<>();

        // Загружаем сохраненные расширения
        loadExpansions();

        // Запускаем задачу проверки таймеров
        startTimerTask();
    }

    /**
     * Внутренний класс для хранения оригинальных границ региона
     */
    private static class RegionBounds {
        public final int minY;
        public final int maxY;

        public RegionBounds(int minY, int maxY) {
            this.minY = minY;
            this.maxY = maxY;
        }
    }

    /**
     * Загрузка сохраненных расширений из файла
     */
    private void loadExpansions() {
        expansionFile = new File(plugin.getDataFolder(), "height-expansions.yml");

        if (!expansionFile.exists()) {
            try {
                if (expansionFile.createNewFile()) {
                    plugin.getLogger().info("Создан файл height-expansions.yml");
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Не удалось создать файл height-expansions.yml: " + e.getMessage());
            }
        }

        expansionConfig = YamlConfiguration.loadConfiguration(expansionFile);

        // Загружаем все сохраненные расширения
        if (expansionConfig.contains("expansions")) {
            org.bukkit.configuration.ConfigurationSection section = expansionConfig.getConfigurationSection("expansions");
            if (section != null) {
                for (String regionId : section.getKeys(false)) {
                    long expirationTime = expansionConfig.getLong("expansions." + regionId + ".expiration");
                    int originalMinY = expansionConfig.getInt("expansions." + regionId + ".original-min-y");
                    int originalMaxY = expansionConfig.getInt("expansions." + regionId + ".original-max-y");

                    regionExpansionTimes.put(regionId, expirationTime);
                    originalBounds.put(regionId, new RegionBounds(originalMinY, originalMaxY));
                    sentNotifications.put(regionId, new HashSet<>());

                    plugin.getLogger().info("Загружено временное расширение для региона " + regionId +
                            ", истекает: " + new Date(expirationTime));
                }
            }
        }
    }

    /**
     * Сохранение расширений в файл
     */
    private void saveExpansions() {
        for (Map.Entry<String, Long> entry : regionExpansionTimes.entrySet()) {
            final String regionId = entry.getKey();
            RegionBounds bounds = originalBounds.get(regionId);

            if (bounds != null) {
                expansionConfig.set("expansions." + regionId + ".expiration", entry.getValue());
                expansionConfig.set("expansions." + regionId + ".original-min-y", bounds.minY);
                expansionConfig.set("expansions." + regionId + ".original-max-y", bounds.maxY);
            }
        }

        try {
            expansionConfig.save(expansionFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить расширения: " + e.getMessage());
        }
    }

    /**
     * НОВЫЙ метод для активации расширения с точным временем в секундах
     */
    public boolean activateHeightExpansionSeconds(String regionId, int seconds) {
        plugin.getLogger().info("=== АКТИВАЦИЯ РАСШИРЕНИЯ ПО ВЫСОТЕ (СЕКУНДЫ) ===");
        plugin.getLogger().info("Регион: " + regionId + ", секунды: " + seconds);

        if (!plugin.getConfig().getBoolean("height-expansion.enabled", true)) {
            plugin.getLogger().warning("Расширение по высоте отключено в конфиге");
            return false;
        }

        ProtectedRegion region = findRegionById(regionId);
        if (region == null) {
            plugin.getLogger().warning("ОШИБКА: Регион " + regionId + " не найден для расширения по высоте");
            return false;
        }

        plugin.getLogger().info("Регион найден: " + region.getMinimumPoint() + " -> " + region.getMaximumPoint());

        org.bukkit.World world = findWorldForRegion(regionId);
        if (world == null) {
            plugin.getLogger().warning("ОШИБКА: Не найден мир для региона " + regionId);
            return false;
        }

        plugin.getLogger().info("Мир найден: " + world.getName());

        // Сохраняем оригинальные границы ДО изменения региона
        if (!originalBounds.containsKey(regionId)) {
            RegionBounds bounds = new RegionBounds(
                    region.getMinimumPoint().y(),
                    region.getMaximumPoint().y()
            );
            originalBounds.put(regionId, bounds);
            plugin.getLogger().info("Сохранены оригинальные границы: Y=" + bounds.minY + " -> " + bounds.maxY);
        } else {
            plugin.getLogger().info("Оригинальные границы уже сохранены");
        }

        plugin.getLogger().info("Начинаем БЕЗОПАСНОЕ расширение региона ТОЛЬКО по высоте БЕЗ удаления границ...");

        // Используем безопасное расширение
        boolean expansionSuccess = safeExpandRegionHeightOnly(region, world);

        if (!expansionSuccess) {
            plugin.getLogger().severe("ОШИБКА: Не удалось расширить регион по высоте");
            return false;
        }

        plugin.getLogger().info("Регион успешно расширен по высоте");

        // Получаем ОБНОВЛЕННЫЙ регион после изменения
        ProtectedRegion updatedRegion = findRegionById(regionId);
        if (updatedRegion == null) {
            plugin.getLogger().severe("КРИТИЧЕСКАЯ ОШИБКА: Регион исчез после расширения!");
            return false;
        }

        plugin.getLogger().info("Обновленный регион: " + updatedRegion.getMinimumPoint() + " -> " + updatedRegion.getMaximumPoint());

        // Устанавливаем время истечения в миллисекундах (конвертируем секунды)
        long durationMillis = seconds * 1000L;
        long expirationTime;

        if (regionExpansionTimes.containsKey(regionId)) {
            expirationTime = regionExpansionTimes.get(regionId) + durationMillis;
            plugin.getLogger().info("Продлеваем существующее расширение на " + seconds + " секунд");
        } else {
            expirationTime = System.currentTimeMillis() + durationMillis;
            plugin.getLogger().info("Создаем новое расширение на " + seconds + " секунд");
        }

        regionExpansionTimes.put(regionId, expirationTime);
        sentNotifications.put(regionId, new HashSet<>());

        saveExpansions();
        plugin.getLogger().info("Данные расширения сохранены");

        plugin.getLogger().info("Границы оставлены на месте - расширение по высоте НЕ влияет на видимые границы");

        plugin.getLogger().info("Активировано временное расширение по высоте для региона " + regionId +
                " на " + formatSecondsToTime(seconds));

        plugin.getLogger().info("=== КОНЕЦ АКТИВАЦИИ РАСШИРЕНИЯ ===");
        return true;
    }

    /**
     * ПОЛНОСТЬЮ ПЕРЕПИСАННЫЙ метод активации временного расширения по высоте
     * КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: НЕ УДАЛЯЕТ границы, а сохраняет их местоположение
     */
    public boolean activateHeightExpansion(String regionId, int hours) {
        plugin.getLogger().info("=== АКТИВАЦИЯ РАСШИРЕНИЯ ПО ВЫСОТЕ ===");
        plugin.getLogger().info("Регион: " + regionId + ", часы: " + hours);

        if (!plugin.getConfig().getBoolean("height-expansion.enabled", true)) {
            plugin.getLogger().warning("Расширение по высоте отключено в конфиге");
            return false;
        }

        ProtectedRegion region = findRegionById(regionId);
        if (region == null) {
            plugin.getLogger().warning("ОШИБКА: Регион " + regionId + " не найден для расширения по высоте");
            return false;
        }

        plugin.getLogger().info("Регион найден: " + region.getMinimumPoint() + " -> " + region.getMaximumPoint());

        org.bukkit.World world = findWorldForRegion(regionId);
        if (world == null) {
            plugin.getLogger().warning("ОШИБКА: Не найден мир для региона " + regionId);
            return false;
        }

        plugin.getLogger().info("Мир найден: " + world.getName());

        // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: Сохраняем оригинальные границы ДО изменения региона
        if (!originalBounds.containsKey(regionId)) {
            RegionBounds bounds = new RegionBounds(
                    region.getMinimumPoint().y(),
                    region.getMaximumPoint().y()
            );
            originalBounds.put(regionId, bounds);
            plugin.getLogger().info("Сохранены оригинальные границы: Y=" + bounds.minY + " -> " + bounds.maxY);
        } else {
            plugin.getLogger().info("Оригинальные границы уже сохранены");
        }

        // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: НЕ ТРОГАЕМ границы вообще!
        // Просто расширяем регион, границы останутся на своих местах
        plugin.getLogger().info("Начинаем БЕЗОПАСНОЕ расширение региона ТОЛЬКО по высоте БЕЗ удаления границ...");

        // Используем новый метод безопасного расширения
        boolean expansionSuccess = safeExpandRegionHeightOnly(region, world);

        if (!expansionSuccess) {
            plugin.getLogger().severe("ОШИБКА: Не удалось расширить регион по высоте");
            return false;
        }

        plugin.getLogger().info("Регион успешно расширен по высоте");

        // Получаем ОБНОВЛЕННЫЙ регион после изменения
        ProtectedRegion updatedRegion = findRegionById(regionId);
        if (updatedRegion == null) {
            plugin.getLogger().severe("КРИТИЧЕСКАЯ ОШИБКА: Регион исчез после расширения!");
            return false;
        }

        plugin.getLogger().info("Обновленный регион: " + updatedRegion.getMinimumPoint() + " -> " + updatedRegion.getMaximumPoint());

        // Устанавливаем время истечения
        long expirationTime;
        if (regionExpansionTimes.containsKey(regionId)) {
            expirationTime = regionExpansionTimes.get(regionId) + (hours * 60 * 60 * 1000L);
            plugin.getLogger().info("Продлеваем существующее расширение");
        } else {
            expirationTime = System.currentTimeMillis() + (hours * 60 * 60 * 1000L);
            plugin.getLogger().info("Создаем новое расширение");
        }

        regionExpansionTimes.put(regionId, expirationTime);
        sentNotifications.put(regionId, new HashSet<>());

        saveExpansions();
        plugin.getLogger().info("Данные расширения сохранены");

        // ИСПРАВЛЕНИЕ: Границы НЕ ТРОГАЕМ, они должны остаться на месте!
        plugin.getLogger().info("Границы оставлены на месте - расширение по высоте НЕ влияет на видимые границы");

        plugin.getLogger().info("Активировано временное расширение по высоте для региона " + regionId +
                " на " + hours + " часов");

        plugin.getLogger().info("=== КОНЕЦ АКТИВАЦИИ РАСШИРЕНИЯ ===");
        return true;
    }
    /**
     * НОВЫЙ БЕЗОПАСНЫЙ метод расширения ТОЛЬКО по высоте
     * НЕ ТРОГАЕТ границы X/Z - только Y расширяет до максимума
     * ЭТО КЛЮЧЕВОЕ ИСПРАВЛЕНИЕ - СОХРАНЯЕТ РАЗМЕРЫ РЕГИОНА ПО ПЛОЩАДИ
     */
    private boolean safeExpandRegionHeightOnly(ProtectedRegion region, org.bukkit.World world) {
        try {
            if (!(region instanceof ProtectedCuboidRegion)) {
                plugin.getLogger().warning("Регион не является кубоидным, расширение невозможно");
                return false;
            }

            // СОХРАНЯЕМ ОРИГИНАЛЬНЫЕ X/Z границы - НЕ ТРОГАЕМ ИХ!
            int minX = region.getMinimumPoint().x();
            int maxX = region.getMaximumPoint().x();
            int minZ = region.getMinimumPoint().z();
            int maxZ = region.getMaximumPoint().z();

            // РАСШИРЯЕМ ТОЛЬКО Y до максимальной высоты мира
            int minY = world.getMinHeight();
            int maxY = world.getMaxHeight() - 1;

            plugin.getLogger().info("БЕЗОПАСНОЕ расширение: оставляем X/Z границы как есть");
            plugin.getLogger().info("X: " + minX + " -> " + maxX + " (НЕ МЕНЯЕМ)");
            plugin.getLogger().info("Z: " + minZ + " -> " + maxZ + " (НЕ МЕНЯЕМ)");
            plugin.getLogger().info("Y: расширяем до " + minY + " -> " + maxY);

            BlockVector3 newMin = BlockVector3.at(minX, minY, minZ);
            BlockVector3 newMax = BlockVector3.at(maxX, maxY, maxZ);

            // Получаем RegionManager для обновления региона
            Object regionManager = plugin.getProtectRegionManager().getWorldGuardRegionManager(world);
            if (regionManager == null) {
                plugin.getLogger().severe("RegionManager не найден для мира " + world.getName());
                return false;
            }

            // Создаем новый регион с расширенными ТОЛЬКО по Y границами
            ProtectedCuboidRegion newRegion = new ProtectedCuboidRegion(region.getId(), newMin, newMax);

            // Копируем все параметры
            newRegion.setOwners(region.getOwners());
            newRegion.setMembers(region.getMembers());
            newRegion.setFlags(region.getFlags());
            newRegion.setPriority(region.getPriority());

            try {
                // АТОМАРНАЯ замена региона
                java.lang.reflect.Method removeRegionMethod = regionManager.getClass()
                        .getMethod("removeRegion", String.class);
                java.lang.reflect.Method addRegionMethod = regionManager.getClass()
                        .getMethod("addRegion", ProtectedRegion.class);
                java.lang.reflect.Method saveMethod = regionManager.getClass().getMethod("save");

                // Удаляем старый регион
                removeRegionMethod.invoke(regionManager, region.getId());
                plugin.getLogger().info("Старый регион удален для замены");

                // Добавляем новый регион с расширенной высотой
                addRegionMethod.invoke(regionManager, newRegion);
                plugin.getLogger().info("Новый регион с расширенной высотой добавлен");

                // Сохраняем изменения
                saveMethod.invoke(regionManager);
                plugin.getLogger().info("Изменения сохранены");

                plugin.getLogger().info("Регион " + region.getId() + " БЕЗОПАСНО расширен по высоте: " + minY + " -> " + maxY);
                plugin.getLogger().info("X/Z границы сохранены: X=" + minX + "->" + maxX + ", Z=" + minZ + "->" + maxZ);
                return true;

            } catch (Exception e) {
                plugin.getLogger().severe("Ошибка при замене региона: " + e.getMessage());
                e.printStackTrace();

                // Пытаемся восстановить оригинальный регион
                try {
                    java.lang.reflect.Method addRegionMethod = regionManager.getClass()
                            .getMethod("addRegion", ProtectedRegion.class);
                    addRegionMethod.invoke(regionManager, region);

                    java.lang.reflect.Method saveMethod = regionManager.getClass().getMethod("save");
                    saveMethod.invoke(regionManager);

                    plugin.getLogger().info("Оригинальный регион восстановлен после ошибки");
                } catch (Exception restoreEx) {
                    plugin.getLogger().severe("КРИТИЧЕСКАЯ ОШИБКА: Не удалось восстановить оригинальный регион: " + restoreEx.getMessage());
                }

                return false;
            }

        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка при безопасном расширении региона: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * КРИТИЧЕСКИ ИСПРАВЛЕННЫЙ метод отключения временного расширения по высоте
     * Теперь НЕ ТРОГАЕТ регион, если его НЕТ в WorldGuard
     */
    public boolean disableHeightExpansion(String regionId) {
        if (!hasHeightExpansion(regionId)) {
            plugin.getLogger().info("DEBUG DISABLE: Регион " + regionId + " не имеет активного расширения по высоте");
            return false;
        }

        plugin.getLogger().info("НАЧАЛО отключения расширения по высоте для региона " + regionId);

        // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: Проверяем существование региона ДО любых операций
        ProtectedRegion region = findRegionById(regionId);
        if (region == null) {
            plugin.getLogger().warning("ОШИБКА: Регион " + regionId + " не найден в WorldGuard при отключении расширения!");
            plugin.getLogger().info("Очищаем данные расширения для несуществующего региона");

            // Очищаем данные расширения для несуществующего региона
            regionExpansionTimes.remove(regionId);
            originalBounds.remove(regionId);
            sentNotifications.remove(regionId);
            expansionConfig.set("expansions." + regionId, null);
            saveExpansions();

            return true; // Данные очищены
        }

        RegionBounds bounds = originalBounds.get(regionId);
        if (bounds == null) {
            plugin.getLogger().warning("ОШИБКА: Не найдены оригинальные границы для региона " + regionId);
            plugin.getLogger().info("Очищаем данные расширения без восстановления границ");

            // Очищаем данные расширения
            regionExpansionTimes.remove(regionId);
            originalBounds.remove(regionId);
            sentNotifications.remove(regionId);
            expansionConfig.set("expansions." + regionId, null);
            saveExpansions();

            return true; // Данные очищены
        }

        plugin.getLogger().info("DEBUG DISABLE: Найдены оригинальные границы: Y=" + bounds.minY + " -> " + bounds.maxY);
        plugin.getLogger().info("DEBUG DISABLE: Текущие границы региона: " + region.getMinimumPoint() + " -> " + region.getMaximumPoint());

        // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: Очищаем данные расширения ПОСЛЕ проверок
        regionExpansionTimes.remove(regionId);
        originalBounds.remove(regionId);
        sentNotifications.remove(regionId);
        expansionConfig.set("expansions." + regionId, null);
        saveExpansions();

        // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: Восстанавливаем оригинальные границы ТОЛЬКО если регион существует
        boolean restored = restoreRegionHeight(region, bounds);

        if (restored) {
            plugin.getLogger().info("✅ Размер региона восстановлен успешно");
            plugin.getLogger().info("Отключено временное расширение по высоте для региона " + regionId);
        } else {
            plugin.getLogger().warning("⚠️ Временное расширение отключено, но восстановить границы не удалось для региона " + regionId);
        }

        return true; // Возвращаем true, так как данные расширения очищены
    }

    /**
     * КРИТИЧЕСКИ ИСПРАВЛЕННЫЙ метод восстановления оригинальной высоты региона
     * Теперь с дополнительными проверками безопасности
     */
    private boolean restoreRegionHeight(ProtectedRegion region, RegionBounds bounds) {
        try {
            if (!(region instanceof ProtectedCuboidRegion)) {
                plugin.getLogger().warning("Регион не является кубоидным, восстановление невозможно");
                return false;
            }

            String regionId = region.getId();

            // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: Проверяем что регион все еще существует
            ProtectedRegion currentRegion = findRegionById(regionId);
            if (currentRegion == null) {
                plugin.getLogger().severe("КРИТИЧЕСКАЯ ОШИБКА: Регион " + regionId + " исчез во время восстановления!");
                return false;
            }

            // СОХРАНЯЕМ ТЕКУЩИЕ X/Z границы - НЕ ТРОГАЕМ ИХ!
            int minX = currentRegion.getMinimumPoint().x();
            int maxX = currentRegion.getMaximumPoint().x();
            int minZ = currentRegion.getMinimumPoint().z();
            int maxZ = currentRegion.getMaximumPoint().z();

            plugin.getLogger().info("БЕЗОПАСНОЕ восстановление: оставляем X/Z границы как есть");
            plugin.getLogger().info("X: " + minX + " -> " + maxX + " (НЕ МЕНЯЕМ)");
            plugin.getLogger().info("Z: " + minZ + " -> " + maxZ + " (НЕ МЕНЯЕМ)");
            plugin.getLogger().info("Y: восстанавливаем до " + bounds.minY + " -> " + bounds.maxY);

            BlockVector3 newMin = BlockVector3.at(minX, bounds.minY, minZ);
            BlockVector3 newMax = BlockVector3.at(maxX, bounds.maxY, maxZ);

            // Получаем мир региона
            org.bukkit.World world = findWorldForRegion(regionId);
            if (world == null) {
                plugin.getLogger().severe("Мир не найден для региона " + regionId);
                return false;
            }

            Object regionManager = plugin.getProtectRegionManager().getWorldGuardRegionManager(world);
            if (regionManager == null) {
                plugin.getLogger().severe("RegionManager не найден для мира " + world.getName());
                return false;
            }

            // Создаем новый регион с восстановленными ТОЛЬКО по Y границами
            ProtectedCuboidRegion newRegion = new ProtectedCuboidRegion(regionId, newMin, newMax);

            // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: Корректно копируем все параметры
            newRegion.setOwners(currentRegion.getOwners());
            newRegion.setMembers(currentRegion.getMembers());
            newRegion.setFlags(currentRegion.getFlags());
            newRegion.setPriority(currentRegion.getPriority());

            try {
                // БЕЗОПАСНАЯ замена региона
                java.lang.reflect.Method removeRegionMethod = regionManager.getClass().getMethod("removeRegion", String.class);
                java.lang.reflect.Method addRegionMethod = regionManager.getClass().getMethod("addRegion", ProtectedRegion.class);
                java.lang.reflect.Method saveMethod = regionManager.getClass().getMethod("save");

                // Удаляем расширенный регион
                removeRegionMethod.invoke(regionManager, regionId);
                plugin.getLogger().info("Расширенный регион удален");

                // Добавляем восстановленный регион
                addRegionMethod.invoke(regionManager, newRegion);
                plugin.getLogger().info("Восстановленный регион добавлен");

                // Сохраняем изменения
                saveMethod.invoke(regionManager);
                plugin.getLogger().info("Изменения сохранены");

                plugin.getLogger().info("Регион " + regionId + " БЕЗОПАСНО восстановлен к оригинальной высоте: " +
                        bounds.minY + " -> " + bounds.maxY);
                plugin.getLogger().info("X/Z границы сохранены: X=" + minX + "->" + maxX + ", Z=" + minZ + "->" + maxZ);
                return true;

            } catch (Exception e) {
                plugin.getLogger().severe("Ошибка при восстановлении региона: " + e.getMessage());
                if (plugin.getConfig().getBoolean("debug.log-stack-traces", false)) {
                    e.printStackTrace();
                }

                // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: Пытаемся восстановить оригинальный регион
                try {
                    java.lang.reflect.Method addRegionMethod = regionManager.getClass()
                            .getMethod("addRegion", ProtectedRegion.class);
                    addRegionMethod.invoke(regionManager, currentRegion);

                    java.lang.reflect.Method saveMethod = regionManager.getClass().getMethod("save");
                    saveMethod.invoke(regionManager);

                    plugin.getLogger().info("Оригинальный регион восстановлен после ошибки");
                } catch (Exception restoreEx) {
                    plugin.getLogger().severe("КРИТИЧЕСКАЯ ОШИБКА: Не удалось восстановить оригинальный регион: " + restoreEx.getMessage());
                }

                return false;
            }

        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка при восстановлении региона: " + e.getMessage());
            if (plugin.getConfig().getBoolean("debug.log-stack-traces", false)) {
                e.printStackTrace();
            }
            return false;
        }
    }

    /**
     * Запуск задачи проверки таймеров
     */
    private void startTimerTask() {
        timerTask = new BukkitRunnable() {
            @Override
            public void run() {
                checkExpiredExpansions();
                sendNotifications();
            }
        }.runTaskTimer(plugin, 20L, 20L); // Каждую секунду
    }

    /**
     * ИСПРАВЛЕННАЯ проверка истекших расширений - БЕЗ СПАМА
     */
    private void checkExpiredExpansions() {
        Set<String> expiredRegions = new HashSet<>();
        long currentTime = System.currentTimeMillis();

        for (Map.Entry<String, Long> entry : regionExpansionTimes.entrySet()) {
            if (currentTime >= entry.getValue()) {
                expiredRegions.add(entry.getKey());
            }
        }

        // ИСПРАВЛЕНИЕ: Обрабатываем истекшие расширения только один раз
        for (String regionId : expiredRegions) {
            if (regionExpansionTimes.containsKey(regionId)) {
                handleExpiredExpansion(regionId);
            }
        }
    }

    /**
     * ИСПРАВЛЕННАЯ обработка истекшего расширения
     */
    private void handleExpiredExpansion(String regionId) {
        plugin.getLogger().info("Время расширения по высоте региона " + regionId + " истекло!");

        // Находим владельца
        String ownerName = getRegionOwnerName(regionId);
        Player owner = plugin.getServer().getPlayer(ownerName);

        // Уведомляем владельца
        if (owner != null && owner.isOnline()) {
            String message = plugin.getConfig().getString("messages.height-expansion-expired",
                    "&c⏰ Время расширения по высоте истекло! Регион вернулся к обычной высоте.");
            owner.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
        }

        // ИСПРАВЛЕНИЕ: Создаем final переменную для использования в lambda
        final String finalRegionId = regionId;

        // ИСПРАВЛЕНИЕ: Отключаем расширение в основном потоке
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                boolean success = disableHeightExpansion(finalRegionId);
                if (success) {
                    plugin.getLogger().info("Высота региона " + finalRegionId + " восстановлена до оригинальной");
                } else {
                    plugin.getLogger().warning("Не удалось восстановить высоту региона " + finalRegionId);
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Ошибка при восстановлении высоты региона " + finalRegionId + ": " + e.getMessage());
                if (plugin.getConfig().getBoolean("debug.log-stack-traces", false)) {
                    e.printStackTrace();
                }
            }
        });
    }

    /**
     * Отправка уведомлений о скором истечении
     */
    private void sendNotifications() {
        // Интервалы уведомлений (в минутах)
        int[] notificationIntervals = {30, 10, 5, 1}; // 30 мин, 10 мин, 5 мин, 1 мин

        for (Map.Entry<String, Long> entry : regionExpansionTimes.entrySet()) {
            String regionId = entry.getKey();
            long remaining = entry.getValue() - System.currentTimeMillis();

            if (remaining <= 0) continue;

            long remainingMinutes = remaining / 60000;

            Set<Integer> sent = sentNotifications.get(regionId);
            if (sent == null) {
                sent = new HashSet<>();
                sentNotifications.put(regionId, sent);
            }

            // Проверяем каждый интервал
            for (int interval : notificationIntervals) {
                if (remainingMinutes <= interval && remainingMinutes > interval - 1 && !sent.contains(interval)) {
                    sendExpirationWarning(regionId, interval + " минут");
                    sent.add(interval);
                }
            }
        }
    }

    /**
     * Отправка предупреждения об истечении расширения
     */
    private void sendExpirationWarning(String regionId, String timeLeft) {
        String ownerName = getRegionOwnerName(regionId);
        Player owner = plugin.getServer().getPlayer(ownerName);

        if (owner != null && owner.isOnline()) {
            String message = plugin.getConfig().getString("messages.height-expansion-warning",
                    "&e⚠ Расширение по высоте истекает через {time}! Не забудьте продлить.");
            message = message.replace("{time}", timeLeft);

            owner.sendMessage(ChatColor.translateAlternateColorCodes('&', message));

            // Звуковое уведомление
            owner.playSound(owner.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 0.5f);
        }
    }

    /**
     * Проверка, активно ли расширение по высоте
     */
    public boolean hasHeightExpansion(String regionId) {
        return regionExpansionTimes.containsKey(regionId);
    }

    /**
     * Получение оставшегося времени расширения
     */
    public long getRemainingTime(String regionId) {
        if (!regionExpansionTimes.containsKey(regionId)) {
            return -1;
        }

        long expiration = regionExpansionTimes.get(regionId);
        long remaining = expiration - System.currentTimeMillis();

        return remaining > 0 ? remaining : 0;
    }

    /**
     * Получение форматированного времени расширения
     */
    public String getFormattedRemainingTime(String regionId) {
        long remaining = getRemainingTime(regionId);

        if (remaining <= 0) {
            return ChatColor.RED + "Истекло!";
        }

        long seconds = remaining / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;

        if (days > 0) {
            return ChatColor.GREEN + String.format("%dд %dч %dм", days, hours % 24, minutes % 60);
        } else if (hours > 0) {
            return ChatColor.YELLOW + String.format("%dч %dм", hours, minutes % 60);
        } else if (minutes > 0) {
            return ChatColor.GOLD + String.format("%dм %dс", minutes, seconds % 60);
        } else {
            return ChatColor.RED + String.format("%dс", seconds);
        }
    }

    /**
     * Получение текущей высоты региона
     */
    public String getCurrentHeightString(String regionId) {
        ProtectedRegion region = findRegionById(regionId);
        if (region == null) {
            return "Неизвестно";
        }

        int minY = region.getMinimumPoint().y();
        int maxY = region.getMaximumPoint().y();
        int height = maxY - minY + 1;

        return height + " блоков (" + minY + " -> " + maxY + ")";
    }

    /**
     * Получение максимальной высоты мира
     */
    public String getMaxHeightString(String regionId) {
        org.bukkit.World world = findWorldForRegion(regionId);
        if (world == null) {
            return "Неизвестно";
        }

        int minY = world.getMinHeight();
        int maxY = world.getMaxHeight() - 1;
        int height = maxY - minY + 1;

        return height + " блоков (" + minY + " -> " + maxY + ")";
    }

    /**
     * НОВЫЙ вспомогательный метод для форматирования времени из секунд
     */
    private String formatSecondsToTime(int seconds) {
        if (seconds < 60) {
            return seconds + " секунд";
        } else if (seconds < 3600) {
            int minutes = seconds / 60;
            int remainingSeconds = seconds % 60;
            if (remainingSeconds == 0) {
                return minutes + " минут";
            } else {
                return minutes + " минут " + remainingSeconds + " секунд";
            }
        } else if (seconds < 86400) {
            int hours = seconds / 3600;
            int remainingMinutes = (seconds % 3600) / 60;
            if (remainingMinutes == 0) {
                return hours + " час" + getHoursSuffix(hours);
            } else {
                return hours + " час" + getHoursSuffix(hours) + " " + remainingMinutes + " минут";
            }
        } else {
            int days = seconds / 86400;
            int remainingHours = (seconds % 86400) / 3600;
            if (remainingHours == 0) {
                return days + " дней";
            } else {
                return days + " дней " + remainingHours + " час" + getHoursSuffix(remainingHours);
            }
        }
    }

    /**
     * НОВЫЙ вспомогательный метод для правильных окончаний часов
     */
    private String getHoursSuffix(int hours) {
        if (hours % 10 == 1 && hours % 100 != 11) {
            return "";
        } else if ((hours % 10 >= 2 && hours % 10 <= 4) && (hours % 100 < 10 || hours % 100 >= 20)) {
            return "а";
        } else {
            return "ов";
        }
    }

    /**
     * Остановка менеджера
     */
    public void shutdown() {
        if (timerTask != null) {
            timerTask.cancel();
        }
        saveExpansions();
    }

    // Вспомогательные методы

    private ProtectedRegion findRegionById(String regionId) {
        for (org.bukkit.World world : plugin.getServer().getWorlds()) {
            try {
                Object regionManager = plugin.getProtectRegionManager().getWorldGuardRegionManager(world);
                if (regionManager != null) {
                    java.lang.reflect.Method getRegionMethod = regionManager.getClass().getMethod("getRegion", String.class);
                    ProtectedRegion region = (ProtectedRegion) getRegionMethod.invoke(regionManager, regionId);
                    if (region != null) {
                        return region;
                    }
                }
            } catch (Exception e) {
                // Игнорируем
            }
        }
        return null;
    }

    private org.bukkit.World findWorldForRegion(String regionId) {
        for (org.bukkit.World world : plugin.getServer().getWorlds()) {
            try {
                Object regionManager = plugin.getProtectRegionManager().getWorldGuardRegionManager(world);
                if (regionManager != null) {
                    java.lang.reflect.Method getRegionMethod = regionManager.getClass().getMethod("getRegion", String.class);
                    ProtectedRegion region = (ProtectedRegion) getRegionMethod.invoke(regionManager, regionId);
                    if (region != null) {
                        return world;
                    }
                }
            } catch (Exception e) {
                // Игнорируем
            }
        }
        return null;
    }

    private String getRegionOwnerName(String regionId) {
        ProtectedRegion region = findRegionById(regionId);
        if (region == null) {
            return "Unknown";
        }

        if (!region.getOwners().getUniqueIds().isEmpty()) {
            UUID ownerUUID = region.getOwners().getUniqueIds().iterator().next();
            String ownerName = plugin.getServer().getOfflinePlayer(ownerUUID).getName();
            return ownerName != null ? ownerName : "Unknown";
        }

        if (!region.getOwners().getPlayers().isEmpty()) {
            return region.getOwners().getPlayers().iterator().next();
        }

        return "Unknown";
    }
}