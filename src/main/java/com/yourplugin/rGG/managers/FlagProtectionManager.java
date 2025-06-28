package com.yourplugin.rGG.managers;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import com.sk89q.worldguard.protection.flags.Flag;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.yourplugin.rGG.RGProtectPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class FlagProtectionManager {

    private final RGProtectPlugin plugin;
    // Хранение активных флагов с временем истечения
    private final Map<String, Map<String, Long>> regionFlags; // regionId -> flagName -> expirationTime
    // Хранение оригинальных значений флагов для восстановления
    private final Map<String, Map<String, Object>> originalFlagValues; // regionId -> flagName -> originalValue
    // Задача обновления флагов
    private BukkitTask flagTask;
    // Файл для сохранения флагов
    private File flagsFile;
    private FileConfiguration flagsConfig;

    public FlagProtectionManager(RGProtectPlugin plugin) {
        this.plugin = plugin;
        this.regionFlags = new ConcurrentHashMap<>();
        this.originalFlagValues = new ConcurrentHashMap<>();

        // Загружаем сохраненные флаги
        loadFlags();

        // Запускаем задачу проверки флагов
        startFlagTask();
    }

    /**
     * Загрузка сохраненных флагов из файла
     */
    private void loadFlags() {
        flagsFile = new File(plugin.getDataFolder(), "region-flags.yml");

        if (!flagsFile.exists()) {
            try {
                if (flagsFile.createNewFile()) {
                    plugin.getLogger().info("Создан файл region-flags.yml");
                }
            } catch (IOException e) {
                plugin.getLogger().warning("Не удалось создать файл region-flags.yml: " + e.getMessage());
            }
        }

        flagsConfig = YamlConfiguration.loadConfiguration(flagsFile);

        // Загружаем все сохраненные флаги
        if (flagsConfig.contains("flags")) {
            for (String regionId : flagsConfig.getConfigurationSection("flags").getKeys(false)) {
                Map<String, Long> flagMap = new HashMap<>();
                Map<String, Object> originalMap = new HashMap<>();

                for (String flagName : flagsConfig.getConfigurationSection("flags." + regionId).getKeys(false)) {
                    long expirationTime = flagsConfig.getLong("flags." + regionId + "." + flagName + ".expiration");
                    String originalValue = flagsConfig.getString("flags." + regionId + "." + flagName + ".original", "ALLOW");

                    flagMap.put(flagName, expirationTime);
                    originalMap.put(flagName, originalValue);

                    plugin.getLogger().info("Загружен флаг " + flagName + " для региона " + regionId +
                            ", истекает: " + new Date(expirationTime));
                }

                regionFlags.put(regionId, flagMap);
                originalFlagValues.put(regionId, originalMap);
            }
        }
    }

    /**
     * Сохранение флагов в файл
     */
    private void saveFlags() {
        for (Map.Entry<String, Map<String, Long>> regionEntry : regionFlags.entrySet()) {
            String regionId = regionEntry.getKey();
            Map<String, Object> originalValues = originalFlagValues.get(regionId);

            for (Map.Entry<String, Long> flagEntry : regionEntry.getValue().entrySet()) {
                String flagName = flagEntry.getKey();
                Long expirationTime = flagEntry.getValue();
                Object originalValue = originalValues != null ? originalValues.get(flagName) : "ALLOW";

                flagsConfig.set("flags." + regionId + "." + flagName + ".expiration", expirationTime);
                flagsConfig.set("flags." + regionId + "." + flagName + ".original", originalValue.toString());
            }
        }

        try {
            flagsConfig.save(flagsFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить флаги: " + e.getMessage());
        }
    }

    /**
     * Активация флага на указанное время
     */
    public boolean activateFlag(String regionId, String flagName, long durationSeconds, String playerName) {
        plugin.getLogger().info("=== АКТИВАЦИЯ ФЛАГА ===");
        plugin.getLogger().info("Регион: " + regionId + ", флаг: " + flagName + ", время: " + durationSeconds + " сек");

        // Находим регион
        ProtectedRegion region = findRegionById(regionId);
        if (region == null) {
            plugin.getLogger().warning("ОШИБКА: Регион " + regionId + " не найден для активации флага");
            return false;
        }

        // Получаем WorldGuard флаг
        Flag<?> worldGuardFlag = getWorldGuardFlag(flagName);
        if (worldGuardFlag == null) {
            plugin.getLogger().warning("ОШИБКА: Неизвестный флаг " + flagName);
            return false;
        }

        try {
            // Сохраняем оригинальное значение флага если еще не сохранено
            if (!originalFlagValues.containsKey(regionId)) {
                originalFlagValues.put(regionId, new HashMap<>());
            }

            Map<String, Object> regionOriginals = originalFlagValues.get(regionId);
            if (!regionOriginals.containsKey(flagName)) {
                Object originalValue = region.getFlag(worldGuardFlag);
                regionOriginals.put(flagName, originalValue != null ? originalValue : "ALLOW");
                plugin.getLogger().info("Сохранено оригинальное значение флага " + flagName + ": " + originalValue);
            }

            // Применяем новое значение флага
            Object newValue = getFlagValue(flagName);
            if (worldGuardFlag instanceof StateFlag) {
                region.setFlag((StateFlag) worldGuardFlag, (StateFlag.State) newValue);
            } else {
                region.setFlag(worldGuardFlag, newValue);
            }

            plugin.getLogger().info("Применено новое значение флага " + flagName + ": " + newValue);

            // Устанавливаем время истечения
            long expirationTime = System.currentTimeMillis() + (durationSeconds * 1000L);

            if (!regionFlags.containsKey(regionId)) {
                regionFlags.put(regionId, new HashMap<>());
            }
            regionFlags.get(regionId).put(flagName, expirationTime);

            saveFlags();

            plugin.getLogger().info("Активирован флаг " + flagName + " для региона " + regionId +
                    " на " + formatTime(durationSeconds));

            return true;

        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка при активации флага: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Получение WorldGuard флага по имени
     */
    private Flag<?> getWorldGuardFlag(String flagName) {
        switch (flagName.toLowerCase()) {
            case "pvp":
                return Flags.PVP;
            case "explosion_protection":
                return Flags.TNT;
            case "mob_damage":
                return Flags.MOB_DAMAGE;
            case "mob_spawning":
                return Flags.MOB_SPAWNING;
            case "fire_spread":
                return Flags.FIRE_SPREAD;
            case "lava_fire":
                return Flags.LAVA_FIRE;
            case "lightning":
                return Flags.LIGHTNING;
            case "water_flow":
                return Flags.WATER_FLOW;
            case "lava_flow":
                return Flags.LAVA_FLOW;
            case "snow_fall":
                return Flags.SNOW_FALL;
            case "snow_melt":
                return Flags.SNOW_MELT;
            case "ice_form":
                return Flags.ICE_FORM;
            case "ice_melt":
                return Flags.ICE_MELT;
            case "entry":
                return Flags.ENTRY;
            case "exit":
                return Flags.EXIT;
            default:
                return null;
        }
    }

    /**
     * Получение значения флага для активации
     */
    private Object getFlagValue(String flagName) {
        // Получаем значение из конфига
        String configValue = plugin.getConfig().getString("flag-protection.flags." + flagName + ".value", "DENY");

        switch (configValue.toUpperCase()) {
            case "DENY":
                return StateFlag.State.DENY;
            case "ALLOW":
                return StateFlag.State.ALLOW;
            default:
                return StateFlag.State.DENY;
        }
    }

    /**
     * Парсинг времени из строки
     */
    public long parseTimeString(String timeStr) {
        // Паттерн для парсинга времени: 1ч3м2с
        Pattern pattern = Pattern.compile("(?:(\\d+)ч)?(?:(\\d+)м)?(?:(\\d+)с)?");
        Matcher matcher = pattern.matcher(timeStr.toLowerCase());

        if (!matcher.matches()) {
            return -1; // Неверный формат
        }

        long totalSeconds = 0;

        String hoursStr = matcher.group(1);
        String minutesStr = matcher.group(2);
        String secondsStr = matcher.group(3);

        if (hoursStr != null) {
            totalSeconds += Long.parseLong(hoursStr) * 3600;
        }
        if (minutesStr != null) {
            totalSeconds += Long.parseLong(minutesStr) * 60;
        }
        if (secondsStr != null) {
            totalSeconds += Long.parseLong(secondsStr);
        }

        // Минимум 5 минут
        if (totalSeconds < 300) {
            return -1;
        }

        return totalSeconds;
    }

    /**
     * Расчет стоимости флага
     */
    public double calculateFlagCost(String flagName, long durationSeconds) {
        double hourlyPrice = plugin.getConfig().getDouble("flag-protection.flags." + flagName + ".price-per-hour", 1000.0);
        double hours = durationSeconds / 3600.0;
        return hourlyPrice * hours;
    }

    /**
     * Форматирование времени
     */
    public String formatTime(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainingSeconds = seconds % 60;

        StringBuilder sb = new StringBuilder();
        if (hours > 0) {
            sb.append(hours).append(" час");
            if (hours > 1 && hours < 5) sb.append("а");
            else if (hours > 4) sb.append("ов");
        }
        if (minutes > 0) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(minutes).append(" минут");
            if (minutes == 1) sb.append("а");
            else if (minutes > 1 && minutes < 5) sb.append("ы");
        }
        if (remainingSeconds > 0) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(remainingSeconds).append(" секунд");
            if (remainingSeconds == 1) sb.append("а");
            else if (remainingSeconds > 1 && remainingSeconds < 5) sb.append("ы");
        }

        return sb.toString();
    }

    /**
     * Получение оставшегося времени флага
     */
    public long getRemainingTime(String regionId, String flagName) {
        Map<String, Long> flags = regionFlags.get(regionId);
        if (flags == null || !flags.containsKey(flagName)) {
            return -1;
        }

        long expiration = flags.get(flagName);
        long remaining = expiration - System.currentTimeMillis();
        return remaining > 0 ? remaining / 1000 : 0;
    }

    /**
     * Проверка активности флага
     */
    public boolean isFlagActive(String regionId, String flagName) {
        return getRemainingTime(regionId, flagName) > 0;
    }

    /**
     * Получение форматированного оставшегося времени
     */
    public String getFormattedRemainingTime(String regionId, String flagName) {
        long remaining = getRemainingTime(regionId, flagName);
        if (remaining <= 0) {
            return ChatColor.RED + "Неактивен";
        }
        return ChatColor.GREEN + formatTime(remaining);
    }

    /**
     * Запуск задачи проверки истекших флагов
     */
    private void startFlagTask() {
        flagTask = new BukkitRunnable() {
            @Override
            public void run() {
                checkExpiredFlags();
            }
        }.runTaskTimer(plugin, 20L, 20L); // Каждую секунду
    }

    /**
     * Проверка истекших флагов
     */
    private void checkExpiredFlags() {
        Set<String> regionsToProcess = new HashSet<>(regionFlags.keySet());

        for (String regionId : regionsToProcess) {
            Map<String, Long> flags = regionFlags.get(regionId);
            if (flags == null) continue;

            Set<String> flagsToRemove = new HashSet<>();
            long currentTime = System.currentTimeMillis();

            for (Map.Entry<String, Long> flagEntry : flags.entrySet()) {
                if (currentTime >= flagEntry.getValue()) {
                    flagsToRemove.add(flagEntry.getKey());
                }
            }

            // Удаляем истекшие флаги
            for (String flagName : flagsToRemove) {
                handleExpiredFlag(regionId, flagName);
            }
        }
    }

    /**
     * Обработка истекшего флага
     */
    private void handleExpiredFlag(String regionId, String flagName) {
        plugin.getLogger().info("Время действия флага " + flagName + " для региона " + regionId + " истекло");

        // Находим регион
        ProtectedRegion region = findRegionById(regionId);
        if (region == null) {
            removeFlag(regionId, flagName);
            return;
        }

        // Восстанавливаем оригинальное значение флага
        restoreFlagValue(region, regionId, flagName);

        // Удаляем флаг из активных
        removeFlag(regionId, flagName);

        // Уведомляем владельца
        String ownerName = getRegionOwnerName(region);
        Player owner = plugin.getServer().getPlayer(ownerName);
        if (owner != null && owner.isOnline()) {
            String flagDisplayName = plugin.getConfig().getString("flag-protection.flags." + flagName + ".name", flagName);
            String message = plugin.getConfig().getString("messages.flag-expired",
                    "&e⏰ Время действия флага &f{flag}&e истекло!");
            message = message.replace("{flag}", flagDisplayName);
            owner.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
        }
    }

    /**
     * Восстановление оригинального значения флага
     */
    private void restoreFlagValue(ProtectedRegion region, String regionId, String flagName) {
        try {
            Flag<?> worldGuardFlag = getWorldGuardFlag(flagName);
            if (worldGuardFlag == null) {
                return;
            }

            Map<String, Object> originalValues = originalFlagValues.get(regionId);
            if (originalValues != null && originalValues.containsKey(flagName)) {
                Object originalValue = originalValues.get(flagName);

                if (originalValue instanceof String) {
                    String strValue = (String) originalValue;
                    if ("ALLOW".equals(strValue)) {
                        originalValue = StateFlag.State.ALLOW;
                    } else if ("DENY".equals(strValue)) {
                        originalValue = StateFlag.State.DENY;
                    } else {
                        originalValue = null; // Был null
                    }
                }

                if (worldGuardFlag instanceof StateFlag) {
                    region.setFlag((StateFlag) worldGuardFlag, (StateFlag.State) originalValue);
                } else {
                    region.setFlag(worldGuardFlag, originalValue);
                }

                plugin.getLogger().info("Восстановлено оригинальное значение флага " + flagName + ": " + originalValue);
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка при восстановлении флага: " + e.getMessage());
        }
    }

    /**
     * Удаление флага из активных
     */
    private void removeFlag(String regionId, String flagName) {
        Map<String, Long> flags = regionFlags.get(regionId);
        if (flags != null) {
            flags.remove(flagName);
            if (flags.isEmpty()) {
                regionFlags.remove(regionId);
            }
        }

        Map<String, Object> originalValues = originalFlagValues.get(regionId);
        if (originalValues != null) {
            originalValues.remove(flagName);
            if (originalValues.isEmpty()) {
                originalFlagValues.remove(regionId);
            }
        }

        // Очищаем из конфига
        flagsConfig.set("flags." + regionId + "." + flagName, null);
        saveFlags();
    }

    /**
     * Остановка менеджера
     */
    public void shutdown() {
        if (flagTask != null) {
            flagTask.cancel();
        }
        saveFlags();
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
}