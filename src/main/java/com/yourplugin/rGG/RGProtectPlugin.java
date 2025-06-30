package com.yourplugin.rGG;

import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;

import com.yourplugin.rGG.managers.HologramManager;
import com.yourplugin.rGG.managers.RegionTimerManager;
import com.yourplugin.rGG.managers.HeightExpansionManager;
import com.yourplugin.rGG.managers.FlagProtectionManager;
import com.yourplugin.rGG.managers.ProtectRegionManager;

import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.RegionContainer;

import java.util.logging.Logger;

/**
 * Главный класс плагина RGProtect
 * Управляет регионами WorldGuard с дополнительными функциями:
 * - Голограммы над регионами
 * - Таймеры жизни регионов
 * - Расширение по высоте
 * - Защитные флаги
 */
public class RGProtectPlugin extends JavaPlugin implements Listener {

    // Менеджеры плагина
    private HologramManager hologramManager;
    private RegionTimerManager regionTimerManager;
    private HeightExpansionManager heightExpansionManager;
    private FlagProtectionManager flagProtectionManager;
    private ProtectRegionManager protectRegionManager;

    // Логгер плагина
    private Logger logger;

    @Override
    public void onEnable() {
        logger = getLogger();
        logger.info("Запуск RGProtect плагина...");

        try {
            // Проверяем наличие зависимостей
            if (!checkDependencies()) {
                logger.severe("Отсутствуют необходимые зависимости! Плагин отключается.");
                getServer().getPluginManager().disablePlugin(this);
                return;
            }

            // Загружаем конфигурацию
            loadConfiguration();

            // Инициализируем менеджеры в правильном порядке
            initializeManagers();

            // Регистрируем события и команды
            registerEventsAndCommands();

            logger.info("RGProtect плагин успешно включен!");
            logger.info("Версия: " + getDescription().getVersion());
            logger.info("Автор: " + getDescription().getAuthors());

        } catch (Exception e) {
            logger.severe("Критическая ошибка при запуске плагина: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        logger.info("Остановка RGProtect плагина...");

        try {
            // Останавливаем менеджеры в обратном порядке
            shutdownManagers();

            logger.info("RGProtect плагин остановлен");

        } catch (Exception e) {
            logger.severe("Ошибка при остановке плагина: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Проверяет наличие необходимых зависимостей
     * @return true если все зависимости найдены
     */
    private boolean checkDependencies() {
        // Проверяем WorldGuard
        if (getServer().getPluginManager().getPlugin("WorldGuard") == null) {
            logger.severe("WorldGuard не найден! Плагин требует WorldGuard для работы.");
            return false;
        }

        // Проверяем WorldEdit
        if (getServer().getPluginManager().getPlugin("WorldEdit") == null) {
            logger.severe("WorldEdit не найден! Плагин требует WorldEdit для работы.");
            return false;
        }

        logger.info("Все зависимости найдены: WorldGuard, WorldEdit");
        return true;
    }

    /**
     * Загружает и проверяет конфигурацию плагина
     */
    private void loadConfiguration() {
        // Сохраняем дефолтную конфигурацию если файл не существует
        saveDefaultConfig();

        // Проверяем конфигурацию и устанавливаем значения по умолчанию
        validateAndSetDefaults();

        logger.info("Конфигурация загружена");
    }

    /**
     * Проверяет конфигурацию и устанавливает значения по умолчанию
     */
    private void validateAndSetDefaults() {
        boolean configChanged = false;

        // Настройки голограмм
        if (!getConfig().contains("hologram.enabled")) {
            getConfig().set("hologram.enabled", true);
            configChanged = true;
        }

        if (!getConfig().contains("hologram.height-offset")) {
            getConfig().set("hologram.height-offset", 1.5);
            configChanged = true;
        }

        if (!getConfig().contains("hologram.update-interval")) {
            getConfig().set("hologram.update-interval", 20);
            configChanged = true;
        }

        if (!getConfig().contains("hologram.lines")) {
            getConfig().set("hologram.lines", java.util.Arrays.asList(
                    "&6Регион игрока: &e{player}",
                    "&7Создан: &f{date}",
                    "&7Время жизни: &f{timer}",
                    "&7Расширение ↕: &f{height_expansion}",
                    "&dФлаги: &f{flag_protection}"
            ));
            configChanged = true;
        }

        // Настройки отладки
        if (!getConfig().contains("debug.log-hologram-operations")) {
            getConfig().set("debug.log-hologram-operations", false);
            configChanged = true;
        }

        if (!getConfig().contains("debug.log-stack-traces")) {
            getConfig().set("debug.log-stack-traces", false);
            configChanged = true;
        }

        // Настройки расширения регионов
        if (!getConfig().contains("region-expansion.base-size.x")) {
            getConfig().set("region-expansion.base-size.x", 3);
            configChanged = true;
        }

        if (!getConfig().contains("region-expansion.base-size.y")) {
            getConfig().set("region-expansion.base-size.y", 3);
            configChanged = true;
        }

        if (!getConfig().contains("region-expansion.base-size.z")) {
            getConfig().set("region-expansion.base-size.z", 3);
            configChanged = true;
        }

        if (!getConfig().contains("region-expansion.max-level")) {
            getConfig().set("region-expansion.max-level", 10);
            configChanged = true;
        }

        // Настройки защитных флагов
        if (!getConfig().contains("flag-protection.enabled")) {
            getConfig().set("flag-protection.enabled", true);
            configChanged = true;
        }

        if (!getConfig().contains("flag-protection.flags.pvp")) {
            getConfig().set("flag-protection.flags.pvp.enabled", false);
            getConfig().set("flag-protection.flags.pvp.description", "Защита от PvP");
            configChanged = true;
        }

        if (!getConfig().contains("flag-protection.flags.build")) {
            getConfig().set("flag-protection.flags.build.enabled", true);
            getConfig().set("flag-protection.flags.build.description", "Защита от строительства");
            configChanged = true;
        }

        if (!getConfig().contains("flag-protection.flags.interact")) {
            getConfig().set("flag-protection.flags.interact.enabled", true);
            getConfig().set("flag-protection.flags.interact.description", "Защита от взаимодействия");
            configChanged = true;
        }

        // Сохраняем конфигурацию если были изменения
        if (configChanged) {
            saveConfig();
            logger.info("Конфигурация обновлена значениями по умолчанию");
        }
    }

    /**
     * Инициализирует все менеджеры плагина
     */
    private void initializeManagers() {
        logger.info("Инициализация менеджеров...");

        try {
            // Инициализируем базовые менеджеры
            protectRegionManager = new ProtectRegionManager(this);
            logger.info("ProtectRegionManager инициализирован");

            regionTimerManager = new RegionTimerManager(this);
            logger.info("RegionTimerManager инициализирован");

            heightExpansionManager = new HeightExpansionManager(this);
            logger.info("HeightExpansionManager инициализирован");

            flagProtectionManager = new FlagProtectionManager(this);
            logger.info("FlagProtectionManager инициализирован");

            // Голограммы инициализируем последними
            if (getConfig().getBoolean("hologram.enabled", true)) {
                hologramManager = new HologramManager(this);
                logger.info("HologramManager инициализирован");
            } else {
                logger.info("HologramManager отключен в конфигурации");
            }

            logger.info("Все менеджеры успешно инициализированы");

        } catch (Exception e) {
            logger.severe("Ошибка при инициализации менеджеров: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Регистрирует события и команды
     */
    private void registerEventsAndCommands() {
        // Регистрируем этот класс как слушатель событий
        getServer().getPluginManager().registerEvents(this, this);

        logger.info("События и команды зарегистрированы");
    }

    /**
     * Останавливает все менеджеры
     */
    private void shutdownManagers() {
        logger.info("Остановка менеджеров...");

        // Останавливаем голограммы первыми
        if (hologramManager != null) {
            try {
                hologramManager.shutdown();
                logger.info("HologramManager остановлен");
            } catch (Exception e) {
                logger.warning("Ошибка при остановке HologramManager: " + e.getMessage());
            }
        }

        // Останавливаем остальные менеджеры
        if (flagProtectionManager != null) {
            try {
                // flagProtectionManager.shutdown(); // Метод будет добавлен в FlagProtectionManager
                logger.info("FlagProtectionManager остановлен");
            } catch (Exception e) {
                logger.warning("Ошибка при остановке FlagProtectionManager: " + e.getMessage());
            }
        }

        if (heightExpansionManager != null) {
            try {
                // heightExpansionManager.shutdown(); // Метод будет добавлен в HeightExpansionManager
                logger.info("HeightExpansionManager остановлен");
            } catch (Exception e) {
                logger.warning("Ошибка при остановке HeightExpansionManager: " + e.getMessage());
            }
        }

        if (regionTimerManager != null) {
            try {
                // regionTimerManager.shutdown(); // Метод будет добавлен в RegionTimerManager
                logger.info("RegionTimerManager остановлен");
            } catch (Exception e) {
                logger.warning("Ошибка при остановке RegionTimerManager: " + e.getMessage());
            }
        }

        if (protectRegionManager != null) {
            try {
                // protectRegionManager.shutdown(); // Метод будет добавлен в ProtectRegionManager
                logger.info("ProtectRegionManager остановлен");
            } catch (Exception e) {
                logger.warning("Ошибка при остановке ProtectRegionManager: " + e.getMessage());
            }
        }

        logger.info("Все менеджеры остановлены");
    }

    // ===== ГЕТТЕРЫ ДЛЯ МЕНЕДЖЕРОВ =====

    /**
     * Получает менеджер голограмм
     * @return HologramManager или null если отключен
     */
    public HologramManager getHologramManager() {
        return hologramManager;
    }

    /**
     * Получает менеджер таймеров регионов
     * @return RegionTimerManager или null если не инициализирован
     */
    public RegionTimerManager getRegionTimerManager() {
        return regionTimerManager;
    }

    /**
     * Получает менеджер расширения по высоте
     * @return HeightExpansionManager или null если не инициализирован
     */
    public HeightExpansionManager getHeightExpansionManager() {
        return heightExpansionManager;
    }

    /**
     * Получает менеджер защитных флагов
     * @return FlagProtectionManager или null если не инициализирован
     */
    public FlagProtectionManager getFlagProtectionManager() {
        return flagProtectionManager;
    }

    /**
     * Получает менеджер защищенных регионов
     * @return ProtectRegionManager или null если не инициализирован
     */
    public ProtectRegionManager getProtectRegionManager() {
        return protectRegionManager;
    }

    // ===== ОБРАБОТКА КОМАНД =====

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("rgprotect") || command.getName().equalsIgnoreCase("rgp")) {
            return handleRGProtectCommand(sender, args);
        }
        return false;
    }

    /**
     * Обрабатывает команды плагина
     * @param sender Отправитель команды
     * @param args Аргументы команды
     * @return true если команда обработана
     */
    private boolean handleRGProtectCommand(CommandSender sender, String[] args) {
        // Проверяем права доступа
        if (!sender.hasPermission("rgprotect.admin")) {
            sender.sendMessage("§cУ вас нет прав для использования этой команды!");
            return true;
        }

        if (args.length == 0) {
            sendHelpMessage(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "help":
            case "?":
                sendHelpMessage(sender);
                break;

            case "reload":
                reloadPlugin(sender);
                break;

            case "info":
            case "status":
                sendPluginInfo(sender);
                break;

            case "hologram":
                handleHologramCommand(sender, args);
                break;

            case "debug":
                handleDebugCommand(sender, args);
                break;

            default:
                sender.sendMessage("§cНеизвестная команда. Используйте /rgp help для справки.");
                break;
        }

        return true;
    }

    /**
     * Отправляет справочное сообщение
     * @param sender Получатель сообщения
     */
    private void sendHelpMessage(CommandSender sender) {
        sender.sendMessage("§6=== RGProtect Команды ===");
        sender.sendMessage("§e/rgp help §7- Показать эту справку");
        sender.sendMessage("§e/rgp reload §7- Перезагрузить плагин");
        sender.sendMessage("§e/rgp info §7- Информация о плагине");
        sender.sendMessage("§e/rgp hologram <subcommand> §7- Управление голограммами");
        sender.sendMessage("§e/rgp debug <subcommand> §7- Отладочные команды");
    }

    /**
     * Перезагружает плагин
     * @param sender Отправитель команды
     */
    private void reloadPlugin(CommandSender sender) {
        sender.sendMessage("§eПерезагрузка RGProtect...");

        try {
            // Останавливаем менеджеры
            shutdownManagers();

            // Перезагружаем конфигурацию
            reloadConfig();
            loadConfiguration();

            // Запускаем менеджеры заново
            initializeManagers();

            sender.sendMessage("§aRGProtect успешно перезагружен!");

        } catch (Exception e) {
            sender.sendMessage("§cОшибка при перезагрузке: " + e.getMessage());
            logger.severe("Ошибка при перезагрузке плагина: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Отправляет информацию о плагине
     * @param sender Получатель информации
     */
    private void sendPluginInfo(CommandSender sender) {
        sender.sendMessage("§6=== Информация о RGProtect ===");
        sender.sendMessage("§eВерсия: §f" + getDescription().getVersion());
        sender.sendMessage("§eАвторы: §f" + String.join(", ", getDescription().getAuthors()));
        sender.sendMessage("§eСостояние:");

        // Информация о менеджерах
        sender.sendMessage("  §7- ProtectRegionManager: §" + (protectRegionManager != null ? "a✓" : "c✗"));
        sender.sendMessage("  §7- RegionTimerManager: §" + (regionTimerManager != null ? "a✓" : "c✗"));
        sender.sendMessage("  §7- HeightExpansionManager: §" + (heightExpansionManager != null ? "a✓" : "c✗"));
        sender.sendMessage("  §7- FlagProtectionManager: §" + (flagProtectionManager != null ? "a✓" : "c✗"));
        sender.sendMessage("  §7- HologramManager: §" + (hologramManager != null ? "a✓" : "c✗"));

        // Статистика голограмм
        if (hologramManager != null) {
            sender.sendMessage("§eГолограммы: §f" + hologramManager.getStatistics());
        }
    }

    /**
     * Обрабатывает команды голограмм
     * @param sender Отправитель команды
     * @param args Аргументы команды
     */
    private void handleHologramCommand(CommandSender sender, String[] args) {
        if (hologramManager == null) {
            sender.sendMessage("§cГолограммы отключены!");
            return;
        }

        if (args.length < 2) {
            sender.sendMessage("§cИспользование: /rgp hologram <repair|info|stats|clear>");
            return;
        }

        String subCommand = args[1].toLowerCase();

        switch (subCommand) {
            case "repair":
                int repaired = hologramManager.repairDamagedHolograms();
                sender.sendMessage("§aИсправлено голограмм: " + repaired);
                break;

            case "info":
                if (args.length < 3) {
                    sender.sendMessage("§cИспользование: /rgp hologram info <regionName>");
                    return;
                }
                String regionName = args[2];
                String info = hologramManager.getHologramInfo(regionName);
                sender.sendMessage("§6Информация о голограмме:");
                for (String line : info.split("\n")) {
                    sender.sendMessage("§f" + line);
                }
                break;

            case "stats":
                sender.sendMessage("§6Статистика голограмм:");
                sender.sendMessage("§f" + hologramManager.getStatistics());
                break;

            case "clear":
                hologramManager.removeAllHolograms();
                sender.sendMessage("§aВсе голограммы удалены!");
                break;

            default:
                sender.sendMessage("§cНеизвестная подкоманда. Доступны: repair, info, stats, clear");
                break;
        }
    }

    /**
     * Обрабатывает отладочные команды
     * @param sender Отправитель команды
     * @param args Аргументы команды
     */
    private void handleDebugCommand(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage("§cИспользование: /rgp debug <hologram|diagnostics>");
            return;
        }

        String subCommand = args[1].toLowerCase();

        switch (subCommand) {
            case "hologram":
                if (hologramManager != null) {
                    String debugInfo = hologramManager.getDebugInfo();
                    sender.sendMessage("§6Отладочная информация голограмм:");
                    for (String line : debugInfo.split("\n")) {
                        sender.sendMessage("§f" + line);
                    }
                } else {
                    sender.sendMessage("§cГолограммы отключены!");
                }
                break;

            case "diagnostics":
                if (hologramManager != null) {
                    String diagnostics = hologramManager.performDiagnostics();
                    sender.sendMessage("§6Диагностика системы голограмм:");
                    for (String line : diagnostics.split("\n")) {
                        sender.sendMessage("§f" + line);
                    }
                } else {
                    sender.sendMessage("§cГолограммы отключены!");
                }
                break;

            default:
                sender.sendMessage("§cНеизвестная отладочная команда. Доступны: hologram, diagnostics");
                break;
        }
    }

    // ===== УТИЛИТАРНЫЕ МЕТОДЫ =====

    /**
     * Проверяет, является ли отправитель игроком
     * @param sender Отправитель команды
     * @return Player или null если не игрок
     */
    public Player getPlayerFromSender(CommandSender sender) {
        if (sender instanceof Player) {
            return (Player) sender;
        }
        return null;
    }

    /**
     * Получает версию плагина
     * @return Строка с версией
     */
    public String getPluginVersion() {
        return getDescription().getVersion();
    }

    /**
     * Проверяет, включен ли режим отладки
     * @return true если отладка включена
     */
    public boolean isDebugMode() {
        return getConfig().getBoolean("debug.log-hologram-operations", false);
    }

    /**
     * Проверяет, нужно ли выводить stack trace
     * @return true если нужно выводить stack trace
     */
    public boolean shouldLogStackTraces() {
        return getConfig().getBoolean("debug.log-stack-traces", false);
    }
}