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

public class HologramManager {

    private final RGProtectPlugin plugin;
    private final Map<String, List<ArmorStand>> holograms;

    public HologramManager(RGProtectPlugin plugin) {
        this.plugin = plugin;
        this.holograms = new HashMap<>();

        // Запускаем задачу обновления голограмм
        startUpdateTask();
    }

    public void createHologram(Location location, String playerName, String regionName) {
        if (!plugin.getConfig().getBoolean("hologram.enabled", true)) {
            return;
        }

        List<String> lines = plugin.getConfig().getStringList("hologram.lines");
        if (lines.isEmpty()) {
            lines.add("&6Регион игрока: &e{player}");
            lines.add("&7Создан: &f{date}");
            lines.add("&7Время жизни: &f{timer}");
            lines.add("&7Расширение ↕: &f{height_expansion}");
        }

        String currentDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));

        List<ArmorStand> hologramStands = new ArrayList<>();
        double heightOffset = plugin.getConfig().getDouble("hologram.height-offset", 1.5);

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i)
                    .replace("{player}", playerName)
                    .replace("{date}", currentDate);

            // ОБНОВЛЕНО: Обработка тега {timer}
            if (line.contains("{timer}")) {
                String timerText = getTimerText(regionName);
                line = line.replace("{timer}", timerText);
            }

            // НОВОЕ: Обработка тега {height_expansion}
            if (line.contains("{height_expansion}")) {
                String heightExpansionText = getHeightExpansionText(regionName);
                line = line.replace("{height_expansion}", heightExpansionText);
            }

            line = ChatColor.translateAlternateColorCodes('&', line);

            Location hologramLoc = location.clone().add(0, heightOffset - (i * 0.25), 0);
            ArmorStand armorStand = (ArmorStand) location.getWorld().spawnEntity(hologramLoc, EntityType.ARMOR_STAND);

            armorStand.setVisible(false);
            armorStand.setGravity(false);
            armorStand.setCanPickupItems(false);
            armorStand.setCustomName(line);
            armorStand.setCustomNameVisible(true);
            armorStand.setInvulnerable(true);
            armorStand.setMarker(true);

            hologramStands.add(armorStand);
        }

        holograms.put(regionName, hologramStands);
    }

    /**
     * НОВЫЙ метод для получения текста расширения по высоте
     */
    private String getHeightExpansionText(String regionName) {
        if (plugin.getHeightExpansionManager() != null &&
                plugin.getHeightExpansionManager().hasHeightExpansion(regionName)) {

            String remainingTime = plugin.getHeightExpansionManager().getFormattedRemainingTime(regionName);
            return ChatColor.LIGHT_PURPLE + "Активно (" + remainingTime + ")";
        } else {
            return ChatColor.GRAY + "Неактивно";
        }
    }

    /**
     * ОБНОВЛЕННЫЙ метод для получения текста таймера
     */
    private String getTimerText(String regionName) {
        if (plugin.getRegionTimerManager() != null && plugin.getRegionTimerManager().hasTimer(regionName)) {
            return plugin.getRegionTimerManager().getFormattedRemainingTime(regionName);
        } else {
            return ChatColor.GRAY + "Нет таймера";
        }
    }

    public void removeHologram(String regionName) {
        List<ArmorStand> stands = holograms.get(regionName);
        if (stands != null) {
            for (ArmorStand stand : stands) {
                if (stand != null && !stand.isDead()) {
                    stand.remove();
                }
            }
            holograms.remove(regionName);
        }
    }

    public void removeAllHolograms() {
        for (String regionName : new ArrayList<>(holograms.keySet())) {
            removeHologram(regionName);
        }
    }

    public void updateHologram(String regionName, String playerName) {
        List<ArmorStand> stands = holograms.get(regionName);
        if (stands == null || stands.isEmpty()) {
            return;
        }

        List<String> lines = plugin.getConfig().getStringList("hologram.lines");
        if (lines.isEmpty()) {
            return;
        }

        String currentDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));

        for (int i = 0; i < Math.min(lines.size(), stands.size()); i++) {
            ArmorStand stand = stands.get(i);
            if (stand != null && !stand.isDead()) {
                String line = lines.get(i)
                        .replace("{player}", playerName)
                        .replace("{date}", currentDate);

                // ОБНОВЛЕНО: Обработка тега {timer}
                if (line.contains("{timer}")) {
                    String timerText = getTimerText(regionName);
                    line = line.replace("{timer}", timerText);
                }

                // НОВОЕ: Обработка тега {height_expansion}
                if (line.contains("{height_expansion}")) {
                    String heightExpansionText = getHeightExpansionText(regionName);
                    line = line.replace("{height_expansion}", heightExpansionText);
                }

                line = ChatColor.translateAlternateColorCodes('&', line);
                stand.setCustomName(line);
            }
        }
    }

    private void startUpdateTask() {
        int updateInterval = plugin.getConfig().getInt("hologram.update-interval", 20);

        new BukkitRunnable() {
            @Override
            public void run() {
                // Проверяем, что голограммы все еще существуют
                for (Map.Entry<String, List<ArmorStand>> entry : new HashMap<>(holograms).entrySet()) {
                    List<ArmorStand> stands = entry.getValue();
                    stands.removeIf(stand -> stand == null || stand.isDead());

                    if (stands.isEmpty()) {
                        holograms.remove(entry.getKey());
                    } else {
                        // ОБНОВЛЕНО: Обновляем голограммы для отображения актуального времени жизни И расширения по высоте
                        String regionName = entry.getKey();
                        String ownerName = getRegionOwnerName(regionName);
                        if (ownerName != null) {
                            updateHologram(regionName, ownerName);
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, updateInterval, updateInterval);
    }

    /**
     * ОБНОВЛЕННЫЙ метод для получения имени владельца региона
     */
    private String getRegionOwnerName(String regionName) {
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
                        if (!region.getOwners().getUniqueIds().isEmpty()) {
                            java.util.UUID ownerUUID = region.getOwners().getUniqueIds().iterator().next();
                            String ownerName = plugin.getServer().getOfflinePlayer(ownerUUID).getName();
                            return ownerName != null ? ownerName : "Unknown";
                        }

                        if (!region.getOwners().getPlayers().isEmpty()) {
                            return region.getOwners().getPlayers().iterator().next();
                        }
                    }
                }
            } catch (Exception e) {
                // Игнорируем
            }
        }
        return null;
    }

    public boolean hasHologram(String regionName) {
        return holograms.containsKey(regionName);
    }

    public int getHologramCount() {
        return holograms.size();
    }
}