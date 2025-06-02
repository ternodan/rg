package com.yourplugin.rGG.managers;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.yourplugin.rGG.RGProtectPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class RegionTimerManager {

    private final RGProtectPlugin plugin;
    // Хранение времени истечения для каждого региона
    private final Map<String, Long> regionExpirationTimes;
    // Хранение последних уведомлений для каждого региона
    private final Map<String, Set<Integer>> sentNotifications;
    // Задача обновления таймеров
    private BukkitTask timerTask;
    // Файл для сохранения таймеров
    private File timersFile;
    private FileConfiguration timersConfig;

    public RegionTimerManager(RGProtectPlugin plugin) {
        this.plugin = plugin;
        this.regionExpirationTimes = new ConcurrentHashMap<>();
        this.sentNotifications = new ConcurrentHashMap<>();

        // Загружаем сохраненные таймеры
        loadTimers();

        // Запускаем задачу проверки таймеров
        startTimerTask();
    }

    /**
     * Загрузка сохраненных таймеров из файла
     */
    private void loadTimers() {
        timersFile = new File(plugin.getDataFolder(), "region-timers.yml");

        if (!timersFile.exists()) {
            try {
                timersFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().warning("Не удалось создать файл region-timers.yml: " + e.getMessage());
            }
        }

        timersConfig = YamlConfiguration.loadConfiguration(timersFile);

        // Загружаем все сохраненные таймеры
        if (timersConfig.contains("timers")) {
            for (String regionId : timersConfig.getConfigurationSection("timers").getKeys(false)) {
                long expirationTime = timersConfig.getLong("timers." + regionId + ".expiration");
                regionExpirationTimes.put(regionId, expirationTime);

                if (plugin.getConfig().getBoolean("debug.log-timer-loading", false)) {
                    plugin.getLogger().info("DEBUG TIMER: Загружен таймер для региона " + regionId +
                            ", истекает: " + new Date(expirationTime));
                }
            }
        }
    }

    /**
     * Сохранение таймеров в файл
     */
    private void saveTimers() {
        for (Map.Entry<String, Long> entry : regionExpirationTimes.entrySet()) {
            timersConfig.set("timers." + entry.getKey() + ".expiration", entry.getValue());
        }

        try {
            timersConfig.save(timersFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить таймеры: " + e.getMessage());
        }
    }

    /**
     * Создание таймера для нового региона
     */
    public void createRegionTimer(String regionId, String ownerName) {
        // Получаем начальное время жизни из конфига (в минутах)
        int initialMinutes = plugin.getConfig().getInt("region-timer.initial-lifetime-minutes", 5);
        long expirationTime = System.currentTimeMillis() + (initialMinutes * 60 * 1000L);

        regionExpirationTimes.put(regionId, expirationTime);
        sentNotifications.put(regionId, new HashSet<>());

        saveTimers();

        plugin.getLogger().info("Создан таймер для региона " + regionId + " владельца " + ownerName +
                " на " + initialMinutes + " минут");
    }

    /**
     * Продление времени жизни региона
     */
    public boolean extendRegionTime(String regionId, int minutes) {
        if (!regionExpirationTimes.containsKey(regionId)) {
            return false;
        }

        long currentExpiration = regionExpirationTimes.get(regionId);
        long newExpiration = currentExpiration + (minutes * 60 * 1000L);

        // Проверяем максимальное время жизни
        int maxLifetimeHours = plugin.getConfig().getInt("region-timer.max-lifetime-hours", 168); // 7 дней
        long maxExpiration = System.currentTimeMillis() + (maxLifetimeHours * 60 * 60 * 1000L);

        if (newExpiration > maxExpiration) {
            newExpiration = maxExpiration;
        }

        regionExpirationTimes.put(regionId, newExpiration);

        // Сбрасываем отправленные уведомления
        sentNotifications.get(regionId).clear();

        saveTimers();

        plugin.getLogger().info("Время жизни региона " + regionId + " продлено на " + minutes + " минут");

        return true;
    }

    /**
     * Получение оставшегося времени жизни региона
     */
    public long getRemainingTime(String regionId) {
        if (!regionExpirationTimes.containsKey(regionId)) {
            return -1;
        }

        long expiration = regionExpirationTimes.get(regionId);
        long remaining = expiration - System.currentTimeMillis();

        return remaining > 0 ? remaining : 0;
    }

    /**
     * Получение форматированного времени жизни для голограммы
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
            return ChatColor.YELLOW + String.format("%dч %dм %dс", hours, minutes % 60, seconds % 60);
        } else if (minutes > 0) {
            return ChatColor.GOLD + String.format("%dм %dс", minutes, seconds % 60);
        } else {
            return ChatColor.RED + String.format("%dс", seconds);
        }
    }

    /**
     * Удаление таймера региона
     */
    public void removeRegionTimer(String regionId) {
        regionExpirationTimes.remove(regionId);
        sentNotifications.remove(regionId);

        timersConfig.set("timers." + regionId, null);
        saveTimers();

        plugin.getLogger().info("Удален таймер для региона " + regionId);
    }

    /**
     * Запуск задачи проверки таймеров
     */
    private void startTimerTask() {
        timerTask = new BukkitRunnable() {
            @Override
            public void run() {
                checkExpiredRegions();
                sendNotifications();
            }
        }.runTaskTimer(plugin, 20L, 20L); // Каждую секунду
    }

    /**
     * Проверка истекших регионов
     */
    private void checkExpiredRegions() {
        Set<String> expiredRegions = new HashSet<>();

        for (Map.Entry<String, Long> entry : regionExpirationTimes.entrySet()) {
            if (System.currentTimeMillis() >= entry.getValue()) {
                expiredRegions.add(entry.getKey());
            }
        }

        // Удаляем истекшие регионы
        for (String regionId : expiredRegions) {
            handleExpiredRegion(regionId);
        }
    }

    /**
     * Обработка истекшего региона
     */
    private void handleExpiredRegion(String regionId) {
        plugin.getLogger().info("Время жизни региона " + regionId + " истекло!");

        // Находим регион
        ProtectedRegion region = findRegionById(regionId);
        if (region == null) {
            removeRegionTimer(regionId);
            return;
        }

        // Получаем владельца
        String ownerName = getRegionOwnerName(region);
        Player owner = plugin.getServer().getPlayer(ownerName);

        // Уведомляем владельца
        if (owner != null && owner.isOnline()) {
            owner.sendMessage("");
            owner.sendMessage(ChatColor.RED + "⏰ ═══════════════════════════════════");
            owner.sendMessage(ChatColor.RED + "   ВРЕМЯ ЖИЗНИ РЕГИОНА ИСТЕКЛО!");
            owner.sendMessage(ChatColor.RED + "═══════════════════════════════════");
            owner.sendMessage(ChatColor.YELLOW + "Регион: " + ChatColor.WHITE + regionId);
            owner.sendMessage(ChatColor.YELLOW + "Регион был автоматически удален.");
            owner.sendMessage(ChatColor.GREEN + "Блок привата возвращен в инвентарь.");
            owner.sendMessage(ChatColor.RED + "═══════════════════════════════════");
            owner.sendMessage("");
        }

        // Выполняем удаление региона в основном потоке
        Bukkit.getScheduler().runTask(plugin, () -> {
            // Удаляем регион через менеджер
            deleteExpiredRegion(region, ownerName);

            // Удаляем таймер
            removeRegionTimer(regionId);
        });
    }

    /**
     * Удаление истекшего региона
     */
    private void deleteExpiredRegion(ProtectedRegion region, String ownerName) {
        String regionId = region.getId();

        try {
            // Находим мир региона
            org.bukkit.World regionWorld = null;
            com.sk89q.worldguard.protection.managers.RegionManager regionManager = null;

            for (org.bukkit.World world : plugin.getServer().getWorlds()) {
                com.sk89q.worldguard.protection.managers.RegionManager rm =
                        plugin.getProtectRegionManager().getWorldGuardRegionManager(world);
                if (rm != null) {
                    try {
                        com.sk89q.worldguard.protection.regions.ProtectedRegion testRegion = rm.getRegion(regionId);
                        if (testRegion != null) {
                            regionWorld = world;
                            regionManager = rm;
                            break;
                        }
                    } catch (Exception e) {
                        // Игнорируем
                    }
                }
            }

            if (regionWorld == null || regionManager == null) {
                return;
            }

            // Удаляем физические границы
            plugin.getVisualizationManager().removeRegionBorders(regionId);

            // Удаляем голограмму
            plugin.getHologramManager().removeHologram(regionId);

            // Удаляем центральный блок
            removeCenterBlock(region, regionWorld);

            // Удаляем регион из WorldGuard
            regionManager.removeRegion(regionId);
            regionManager.save();

            // Возвращаем блок привата владельцу
            Player owner = plugin.getServer().getPlayer(ownerName);
            if (owner != null && owner.isOnline()) {
                giveProtectBlockBack(owner, ownerName);
            }

            plugin.getLogger().info("Регион " + regionId + " удален из-за истечения времени жизни");

        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка при удалении истекшего региона: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Отправка уведомлений о скором истечении
     */
    private void sendNotifications() {
        // Интервалы уведомлений (в минутах)
        int[] notificationIntervals = {30, 10, 5, 1}; // 30 мин, 10 мин, 5 мин, 1 мин (30 сек)

        for (Map.Entry<String, Long> entry : regionExpirationTimes.entrySet()) {
            String regionId = entry.getKey();
            long remaining = getRemainingTime(regionId);

            if (remaining <= 0) continue;

            long remainingMinutes = remaining / 60000;
            long remainingSeconds = remaining / 1000;

            Set<Integer> sent = sentNotifications.get(regionId);
            if (sent == null) {
                sent = new HashSet<>();
                sentNotifications.put(regionId, sent);
            }

            // Проверяем каждый интервал
            for (int interval : notificationIntervals) {
                if (interval == 1 && remainingSeconds <= 30 && !sent.contains(1)) {
                    // Особый случай для 30 секунд
                    sendExpirationWarning(regionId, "30 секунд");
                    sent.add(1);
                } else if (remainingMinutes <= interval && remainingMinutes > interval - 1 && !sent.contains(interval)) {
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
        ProtectedRegion region = findRegionById(regionId);
        if (region == null) return;

        String ownerName = getRegionOwnerName(region);
        Player owner = plugin.getServer().getPlayer(ownerName);

        if (owner != null && owner.isOnline()) {
            owner.sendMessage("");
            owner.sendMessage(ChatColor.GOLD + "⚠ ВНИМАНИЕ! ⚠");
            owner.sendMessage(ChatColor.YELLOW + "Регион " + ChatColor.WHITE + regionId +
                    ChatColor.YELLOW + " будет удален через " + ChatColor.RED + timeLeft + "!");
            owner.sendMessage(ChatColor.YELLOW + "Не забудьте продлить время жизни региона!");
            owner.sendMessage(ChatColor.GRAY + "Используйте меню региона (ПКМ по центральному блоку)");
            owner.sendMessage("");

            // Звуковое уведомление
            owner.playSound(owner.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, 1.0f);
        }
    }

    /**
     * Проверка, управляется ли регион таймером
     */
    public boolean hasTimer(String regionId) {
        return regionExpirationTimes.containsKey(regionId);
    }

    /**
     * Получение времени истечения
     */
    public long getExpirationTime(String regionId) {
        return regionExpirationTimes.getOrDefault(regionId, -1L);
    }

    /**
     * Остановка менеджера таймеров
     */
    public void shutdown() {
        if (timerTask != null) {
            timerTask.cancel();
        }
        saveTimers();
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

    private String getRegionOwnerName(ProtectedRegion region) {
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

    private void removeCenterBlock(ProtectedRegion region, org.bukkit.World world) {
        try {
            int centerX = (region.getMinimumPoint().x() + region.getMaximumPoint().x()) / 2;
            int centerY = (region.getMinimumPoint().y() + region.getMaximumPoint().y()) / 2;
            int centerZ = (region.getMinimumPoint().z() + region.getMaximumPoint().z()) / 2;

            org.bukkit.Location centerLoc = new org.bukkit.Location(world, centerX, centerY, centerZ);
            org.bukkit.block.Block centerBlock = centerLoc.getBlock();

            org.bukkit.Material protectMaterial;
            try {
                protectMaterial = org.bukkit.Material.valueOf(
                        plugin.getConfig().getString("protect-block.material", "DIAMOND_BLOCK"));
            } catch (IllegalArgumentException e) {
                protectMaterial = org.bukkit.Material.DIAMOND_BLOCK;
            }

            if (centerBlock.getType() == protectMaterial) {
                centerBlock.setType(org.bukkit.Material.AIR);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при удалении центрального блока: " + e.getMessage());
        }
    }

    private void giveProtectBlockBack(Player player, String ownerName) {
        try {
            org.bukkit.Material blockType;
            try {
                blockType = org.bukkit.Material.valueOf(
                        plugin.getConfig().getString("protect-block.material", "DIAMOND_BLOCK"));
            } catch (IllegalArgumentException e) {
                blockType = org.bukkit.Material.DIAMOND_BLOCK;
            }

            org.bukkit.inventory.ItemStack protectBlock = new org.bukkit.inventory.ItemStack(blockType, 1);
            org.bukkit.inventory.meta.ItemMeta meta = protectBlock.getItemMeta();

            String displayName = plugin.getConfig().getString("protect-block.display-name", "&aБлок привата")
                    .replace("{player}", ownerName);
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', displayName));

            List<String> lore = plugin.getConfig().getStringList("protect-block.lore");
            if (!lore.isEmpty()) {
                List<String> newLore = new ArrayList<>();
                for (String line : lore) {
                    newLore.add(ChatColor.translateAlternateColorCodes('&', line.replace("{player}", ownerName)));
                }
                newLore.add(ChatColor.DARK_GRAY + "RGProtect:" + ownerName);
                meta.setLore(newLore);
            }

            protectBlock.setItemMeta(meta);

            if (player.getInventory().firstEmpty() != -1) {
                player.getInventory().addItem(protectBlock);
            } else {
                player.getWorld().dropItemNaturally(player.getLocation(), protectBlock);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при возврате блока: " + e.getMessage());
        }
    }
}