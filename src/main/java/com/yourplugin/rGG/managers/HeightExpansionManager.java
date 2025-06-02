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
                expansionFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().warning("Не удалось создать файл height-expansions.yml: " + e.getMessage());
            }
        }

        expansionConfig = YamlConfiguration.loadConfiguration(expansionFile);

        // Загружаем все сохраненные расширения
        if (expansionConfig.contains("expansions")) {
            for (String regionId : expansionConfig.getConfigurationSection("expansions").getKeys(false)) {
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

    /**
     * Сохранение расширений в файл
     */
    private void saveExpansions() {
        for (Map.Entry<String, Long> entry : regionExpansionTimes.entrySet()) {
            String regionId = entry.getKey();
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
     * Активирует временное расширение по высоте для региона
     */
    public boolean activateHeightExpansion(String regionId, int hours) {
        if (!plugin.getConfig().getBoolean("height-expansion.enabled", true)) {
            return false;
        }

        ProtectedRegion region = findRegionById(regionId);
        if (region == null) {
            return false;
        }

        org.bukkit.World world = findWorldForRegion(regionId);
        if (world == null) {
            return false;
        }

        // Сохраняем оригинальные границы если еще не сохранены
        if (!originalBounds.containsKey(regionId)) {
            originalBounds.put(regionId, new RegionBounds(
                    region.getMinimumPoint().y(),
                    region.getMaximumPoint().y()
            ));
        }

        // Расширяем регион до максимальной высоты
        if (!expandRegionToMaxHeight(region, world)) {
            return false;
        }

        // Устанавливаем время истечения
        long expirationTime;
        if (regionExpansionTimes.containsKey(regionId)) {
            // Продлеваем существующее расширение
            expirationTime = regionExpansionTimes.get(regionId) + (hours * 60 * 60 * 1000L);
        } else {
            // Новое расширение
            expirationTime = System.currentTimeMillis() + (hours * 60 * 60 * 1000L);
        }

        regionExpansionTimes.put(regionId, expirationTime);
        sentNotifications.put(regionId, new HashSet<>());

        saveExpansions();

        plugin.getLogger().info("Активировано временное расширение по высоте для региона " + regionId +
                " на " + hours + " часов");

        return true;
    }

    /**
     * Отключает временное расширение по высоте для региона
     */
    public boolean disableHeightExpansion(String regionId) {
        if (!hasHeightExpansion(regionId)) {
            return false;
        }

        ProtectedRegion region = findRegionById(regionId);
        if (region == null) {
            return false;
        }

        RegionBounds bounds = originalBounds.get(regionId);
        if (bounds == null) {
            return false;
        }

        // Восстанавливаем оригинальные границы
        if (!restoreRegionHeight(region, bounds)) {
            return false;
        }

        // Удаляем из хранилищ
        regionExpansionTimes.remove(regionId);
        originalBounds.remove(regionId);
        sentNotifications.remove(regionId);

        // Удаляем из конфига
        expansionConfig.set("expansions." + regionId, null);
        saveExpansions();

        plugin.getLogger().info("Отключено временное расширение по высоте для региона " + regionId);

        return true;
    }

    /**
     * Расширяет регион до максимальной высоты мира
     */
    private boolean expandRegionToMaxHeight(ProtectedRegion region, org.bukkit.World world) {
        try {
            if (!(region instanceof ProtectedCuboidRegion)) {
                return false;
            }

            int minX = region.getMinimumPoint().x();
            int maxX = region.getMaximumPoint().x();
            int minZ = region.getMinimumPoint().z();
            int maxZ = region.getMaximumPoint().z();

            // Расширяем до максимальной высоты мира
            int minY = world.getMinHeight();
            int maxY = world.getMaxHeight() - 1;

            BlockVector3 newMin = BlockVector3.at(minX, minY, minZ);
            BlockVector3 newMax = BlockVector3.at(maxX, maxY, maxZ);

            // Получаем RegionManager для обновления региона
            Object regionManager = plugin.getProtectRegionManager().getWorldGuardRegionManager(world);
            if (regionManager == null) {
                return false;
            }

            // Создаем новый регион с расширенными границами
            ProtectedCuboidRegion newRegion = new ProtectedCuboidRegion(region.getId(), newMin, newMax);

            // Копируем все параметры
            newRegion.setOwners(region.getOwners());
            newRegion.setMembers(region.getMembers());
            newRegion.setFlags(region.getFlags());
            newRegion.setPriority(region.getPriority());

            try {
                // Удаляем старый регион и добавляем новый
                java.lang.reflect.Method removeRegionMethod = regionManager.getClass().getMethod("removeRegion", String.class);
                removeRegionMethod.invoke(regionManager, region.getId());

                java.lang.reflect.Method addRegionMethod = regionManager.getClass().getMethod("addRegion", ProtectedRegion.class);
                addRegionMethod.invoke(regionManager, newRegion);

                java.lang.reflect.Method saveMethod = regionManager.getClass().getMethod("save");
                saveMethod.invoke(regionManager);

                plugin.getLogger().info("Регион " + region.getId() + " расширен по высоте: " + minY + " -> " + maxY);
                return true;

            } catch (Exception e) {
                plugin.getLogger().severe("Ошибка при расширении региона по высоте: " + e.getMessage());
                return false;
            }

        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка при расширении региона: " + e.getMessage());
            return false;
        }
    }

    /**
     * Восстанавливает оригинальную высоту региона
     */
    private boolean restoreRegionHeight(ProtectedRegion region, RegionBounds bounds) {
        try {
            if (!(region instanceof ProtectedCuboidRegion)) {
                return false;
            }

            int minX = region.getMinimumPoint().x();
            int maxX = region.getMaximumPoint().x();
            int minZ = region.getMinimumPoint().z();
            int maxZ = region.getMaximumPoint().z();

            BlockVector3 newMin = BlockVector3.at(minX, bounds.minY, minZ);
            BlockVector3 newMax = BlockVector3.at(maxX, bounds.maxY, maxZ);

            // Получаем мир региона
            org.bukkit.World world = findWorldForRegion(region.getId());
            if (world == null) {
                return false;
            }

            Object regionManager = plugin.getProtectRegionManager().getWorldGuardRegionManager(world);
            if (regionManager == null) {
                return false;
            }

            // Создаем новый регион с оригинальными границами
            ProtectedCuboidRegion newRegion = new ProtectedCuboidRegion(region.getId(), newMin, newMax);

            // Копируем все параметры
            newRegion.setOwners(region.getOwners());
            newRegion.setMembers(region.getMembers());
            newRegion.setFlags(region.getFlags());
            newRegion.setPriority(region.getPriority());

            try {
                // Удаляем старый регион и добавляем новый
                java.lang.reflect.Method removeRegionMethod = regionManager.getClass().getMethod("removeRegion", String.class);
                removeRegionMethod.invoke(regionManager, region.getId());

                java.lang.reflect.Method addRegionMethod = regionManager.getClass().getMethod("addRegion", ProtectedRegion.class);
                addRegionMethod.invoke(regionManager, newRegion);

                java.lang.reflect.Method saveMethod = regionManager.getClass().getMethod("save");
                saveMethod.invoke(regionManager);

                plugin.getLogger().info("Регион " + region.getId() + " восстановлен к оригинальной высоте: " +
                        bounds.minY + " -> " + bounds.maxY);
                return true;

            } catch (Exception e) {
                plugin.getLogger().severe("Ошибка при восстановлении высоты региона: " + e.getMessage());
                return false;
            }

        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка при восстановлении региона: " + e.getMessage());
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
     * Проверка истекших расширений
     */
    private void checkExpiredExpansions() {
        Set<String> expiredRegions = new HashSet<>();

        for (Map.Entry<String, Long> entry : regionExpansionTimes.entrySet()) {
            if (System.currentTimeMillis() >= entry.getValue()) {
                expiredRegions.add(entry.getKey());
            }
        }

        // Отключаем истекшие расширения
        for (String regionId : expiredRegions) {
            handleExpiredExpansion(regionId);
        }
    }

    /**
     * Обработка истекшего расширения
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

        // Отключаем расширение
        disableHeightExpansion(regionId);
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
     * Отправка предупреждения об истечении
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
            owner.playSound(owner.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
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