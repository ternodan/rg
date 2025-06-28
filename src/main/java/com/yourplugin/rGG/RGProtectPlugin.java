package com.yourplugin.rGG;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.RegisteredServiceProvider;

import net.milkbowl.vault.economy.Economy;

import com.yourplugin.rGG.commands.RGProtectCommand;
import com.yourplugin.rGG.listeners.*;
import com.yourplugin.rGG.managers.*;

import java.util.List;

public class RGProtectPlugin extends JavaPlugin {

    private static RGProtectPlugin instance;
    private Economy economy = null;
    private HologramManager hologramManager;
    private VisualizationManager visualizationManager;
    private ProtectRegionManager protectRegionManager;
    private RegionMenuManager regionMenuManager;
    private RegionTimerManager regionTimerManager;
    private RegionLifetimeMenu regionLifetimeMenu;
    private HeightExpansionManager heightExpansionManager;
    private HeightExpansionMenu heightExpansionMenu;
    // НОВЫЕ менеджеры для защитных флагов
    private FlagProtectionManager flagProtectionManager;
    private FlagProtectionMenu flagProtectionMenu;

    @Override
    public void onEnable() {
        instance = this;

        // Проверяем зависимости
        if (!checkDependencies()) {
            getLogger().severe("Отсутствуют необходимые зависимости! Плагин отключается.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // Настройка экономики Vault
        if (!setupEconomy()) {
            getLogger().warning("Vault экономика не найдена! Функции расширения регионов будут недоступны.");
        }

        // Создаем конфигурацию
        saveDefaultConfig();

        // Инициализируем менеджеры В ПРАВИЛЬНОМ ПОРЯДКЕ
        hologramManager = new HologramManager(this);
        visualizationManager = new VisualizationManager(this);
        protectRegionManager = new ProtectRegionManager(this);
        regionTimerManager = new RegionTimerManager(this); // ВАЖНО: до RegionMenuManager
        heightExpansionManager = new HeightExpansionManager(this);

        // НОВЫЕ менеджеры защитных флагов
        flagProtectionManager = new FlagProtectionManager(this);
        flagProtectionMenu = new FlagProtectionMenu(this);

        regionMenuManager = new RegionMenuManager(this);
        regionLifetimeMenu = new RegionLifetimeMenu(this);
        heightExpansionMenu = new HeightExpansionMenu(this);

        // Регистрируем команды
        getCommand("rgprotect").setExecutor(new RGProtectCommand(this));

        // Регистрируем события
        getServer().getPluginManager().registerEvents(new BlockPlaceListener(this), this);
        getServer().getPluginManager().registerEvents(new BlockBreakListener(this), this);
        getServer().getPluginManager().registerEvents(new BlockInteractListener(this), this);
        getServer().getPluginManager().registerEvents(new MenuListener(this), this);
        getServer().getPluginManager().registerEvents(new ChatListener(this), this);
        getServer().getPluginManager().registerEvents(new LifetimeMenuListener(this), this);
        getServer().getPluginManager().registerEvents(new HeightExpansionMenuListener(this), this);
        // НОВЫЙ слушатель для меню защитных флагов
        getServer().getPluginManager().registerEvents(new FlagProtectionMenuListener(this), this);

        getLogger().info("RGProtect успешно загружен!");
        getLogger().info("Новая система физических границ из красной шерсти активна!");
        getLogger().info("Система меню и расширения регионов активна!");
        getLogger().info("Стратегия размещения границ: " + getConfig().getString("visualization.physical-borders.placement.strategy", "surface_contact"));

        // Информация о экономике
        if (economy != null) {
            getLogger().info("Экономика подключена: " + economy.getName());
        }

        // Информация о настройках расширения
        if (getConfig().getBoolean("region-expansion.enabled", true)) {
            int maxLevel = getConfig().getInt("region-expansion.max-level", 10);
            getLogger().info("Система расширения регионов: включена (макс. уровень: " + maxLevel + ")");
        } else {
            getLogger().info("Система расширения регионов: отключена");
        }

        // Информация о настройках подсветки по умолчанию
        boolean bordersEnabledByDefault = getConfig().getBoolean("region-creation.borders-enabled-by-default", true);
        getLogger().info("Подсветка границ для новых регионов: " + (bordersEnabledByDefault ? "включена" : "выключена"));

        // Информация о таймерах
        if (getConfig().getBoolean("region-timer.enabled", true)) {
            int initialMinutes = getConfig().getInt("region-timer.initial-lifetime-minutes", 5);
            getLogger().info("Система таймеров регионов: включена (начальное время: " + initialMinutes + " минут)");
        } else {
            getLogger().info("Система таймеров регионов: отключена");
        }

        // Информация о расширении по высоте
        if (getConfig().getBoolean("height-expansion.enabled", true)) {
            getLogger().info("Система временного расширения по высоте: включена");
        } else {
            getLogger().info("Система временного расширения по высоте: отключена");
        }

        // НОВАЯ информация о защитных флагах
        if (getConfig().getBoolean("flag-protection.enabled", true)) {
            getLogger().info("Система защитных флагов: включена");
        } else {
            getLogger().info("Система защитных флагов: отключена");
        }

        // Информация о настройках голограмм
        if (getConfig().getBoolean("hologram.enabled", true)) {
            getLogger().info("Система голограмм: включена");
            List<String> hologramLines = getConfig().getStringList("hologram.lines");

            // Проверяем есть ли строка с расширением по высоте
            boolean hasHeightExpansionLine = false;
            for (String line : hologramLines) {
                if (line.contains("{height_expansion}")) {
                    hasHeightExpansionLine = true;
                    break;
                }
            }

            if (hasHeightExpansionLine) {
                getLogger().info("Голограммы отображают информацию о расширении по высоте");
            } else {
                getLogger().info("Добавьте '{height_expansion}' в hologram.lines для отображения расширения по высоте");
            }
        } else {
            getLogger().info("Система голограмм: отключена");
        }

        // Восстанавливаем границы регионов с учетом сохраненных состояний
        // Задержка в 1 секунду для гарантии полной загрузки миров
        getServer().getScheduler().runTaskLater(this, () -> {
            restoreRegionBorders();
        }, 20L);

        // НОВАЯ задача проверки таймаутов покупок флагов
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (flagProtectionMenu != null) {
                flagProtectionMenu.checkPurchaseTimeouts();
            }
        }, 20L, 20L); // Каждую секунду
    }

    @Override
    public void onDisable() {
        // ВАЖНО: Сохраняем и останавливаем таймеры
        if (regionTimerManager != null) {
            regionTimerManager.shutdown();
        }

        // ВАЖНО: Сохраняем и останавливаем менеджер расширений по высоте
        if (heightExpansionManager != null) {
            heightExpansionManager.shutdown();
        }

        // НОВОЕ: Сохраняем и останавливаем менеджер защитных флагов
        if (flagProtectionManager != null) {
            flagProtectionManager.shutdown();
        }

        // Удаляем все физические границы из красной шерсти
        if (visualizationManager != null) {
            getLogger().info("Восстанавливаем все блоки границ регионов...");
            visualizationManager.removeAllRegionBorders();
            visualizationManager.clearAllVisualizations();
        }

        // Удаляем голограммы
        if (hologramManager != null) {
            hologramManager.removeAllHolograms();
        }

        getLogger().info("RGProtect отключен! Все границы регионов восстановлены.");
    }

    // ... (остальные методы остаются без изменений)

    /**
     * Восстановление границ регионов при загрузке плагина
     */
    private void restoreRegionBorders() {
        getLogger().info("Восстановление границ регионов...");

        int totalRegions = 0;
        int restoredBorders = 0;
        int skippedBorders = 0;

        // Проходим по всем мирам
        for (org.bukkit.World world : getServer().getWorlds()) {
            com.sk89q.worldguard.protection.managers.RegionManager regionManager =
                    protectRegionManager.getWorldGuardRegionManager(world);

            if (regionManager == null) {
                continue;
            }

            try {
                // Получаем все регионы в мире
                java.lang.reflect.Method getRegionsMethod = regionManager.getClass().getMethod("getRegions");
                @SuppressWarnings("unchecked")
                java.util.Map<String, com.sk89q.worldguard.protection.regions.ProtectedRegion> regions =
                        (java.util.Map<String, com.sk89q.worldguard.protection.regions.ProtectedRegion>)
                                getRegionsMethod.invoke(regionManager);

                for (java.util.Map.Entry<String, com.sk89q.worldguard.protection.regions.ProtectedRegion> entry :
                        regions.entrySet()) {
                    String regionId = entry.getKey();
                    com.sk89q.worldguard.protection.regions.ProtectedRegion region = entry.getValue();

                    // Проверяем, является ли это регионом нашего плагина
                    if (regionId.startsWith("rgprotect_")) {
                        totalRegions++;

                        // Проверяем состояние подсветки для региона
                        boolean bordersEnabled = regionMenuManager.isRegionBordersEnabled(regionId);

                        if (bordersEnabled) {
                            // Восстанавливаем границы только если подсветка включена
                            visualizationManager.createRegionBorders(region, world);
                            restoredBorders++;

                            if (getConfig().getBoolean("debug.log-startup-restoration", false)) {
                                getLogger().info("DEBUG: Восстановлены границы для региона " + regionId);
                            }
                        } else {
                            skippedBorders++;
                            if (getConfig().getBoolean("debug.log-startup-restoration", false)) {
                                getLogger().info("DEBUG: Пропущен регион " + regionId + " (подсветка выключена)");
                            }
                        }
                    }
                }

            } catch (Exception e) {
                getLogger().warning("Ошибка при восстановлении границ в мире " + world.getName() + ": " + e.getMessage());
            }
        }

        getLogger().info("Восстановление завершено: " + restoredBorders + " границ восстановлено, " +
                skippedBorders + " пропущено (всего регионов: " + totalRegions + ")");
    }

    private boolean checkDependencies() {
        return getServer().getPluginManager().getPlugin("WorldGuard") != null &&
                getServer().getPluginManager().getPlugin("WorldEdit") != null &&
                getServer().getPluginManager().getPlugin("Vault") != null;
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        economy = rsp.getProvider();
        return economy != null;
    }

    // Геттеры для существующих менеджеров

    public RegionTimerManager getRegionTimerManager() {
        return regionTimerManager;
    }

    public HeightExpansionManager getHeightExpansionManager() {
        return heightExpansionManager;
    }

    public RegionLifetimeMenu getRegionLifetimeMenu() {
        return regionLifetimeMenu;
    }

    public HeightExpansionMenu getHeightExpansionMenu() {
        return heightExpansionMenu;
    }

    public VisualizationManager getVisualizationManager() {
        return visualizationManager;
    }

    public HologramManager getHologramManager() {
        return hologramManager;
    }

    public ProtectRegionManager getProtectRegionManager() {
        return protectRegionManager;
    }

    public RegionMenuManager getRegionMenuManager() {
        return regionMenuManager;
    }

    // НОВЫЕ геттеры для менеджеров защитных флагов

    public FlagProtectionManager getFlagProtectionManager() {
        return flagProtectionManager;
    }

    public FlagProtectionMenu getFlagProtectionMenu() {
        return flagProtectionMenu;
    }

    public net.milkbowl.vault.economy.Economy getEconomy() {
        return economy;
    }

    public static RGProtectPlugin getInstance() {
        return instance;
    }
}