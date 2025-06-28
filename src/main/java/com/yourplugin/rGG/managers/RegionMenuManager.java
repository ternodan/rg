package com.yourplugin.rGG.managers;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldedit.math.BlockVector3;
import com.yourplugin.rGG.RGProtectPlugin;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.io.File;
import java.io.IOException;

/**
 * Полностью переписанный менеджер меню регионов
 * Исправлены все критические ошибки и добавлена улучшенная обработка
 */
public class RegionMenuManager {

    private final RGProtectPlugin plugin;

    // Хранение открытых меню для отслеживания
    private final Map<UUID, String> openMenus;

    // Хранение состояния подтверждения удаления
    private final Map<UUID, String> pendingDeletions;

    // Хранение таймаутов для ожидающих удалений
    private final Map<UUID, BukkitTask> pendingDeletionTimeouts;

    // Хранение состояния подсветки регионов
    private final Map<String, Boolean> regionBordersEnabled;

    // Файл для сохранения состояний подсветки
    private File bordersStateFile;
    private FileConfiguration bordersStateConfig;

    // Кэш для быстрого доступа к регионам
    private final Map<String, ProtectedRegion> regionCache;

    // Статистика использования
    private final Map<String, Long> lastMenuOpenTime;

    public RegionMenuManager(RGProtectPlugin plugin) {
        this.plugin = plugin;
        this.openMenus = new ConcurrentHashMap<>();
        this.pendingDeletions = new ConcurrentHashMap<>();
        this.pendingDeletionTimeouts = new ConcurrentHashMap<>();
        this.regionBordersEnabled = new ConcurrentHashMap<>();
        this.regionCache = new ConcurrentHashMap<>();
        this.lastMenuOpenTime = new ConcurrentHashMap<>();

        // Инициализируем систему сохранения состояний
        initializeBordersState();

        // Загружаем сохраненные состояния подсветки
        loadBordersState();

        // Запускаем задачу очистки кэша
        startCacheCleanupTask();

        plugin.getLogger().info("RegionMenuManager успешно инициализирован");
    }

    /**
     * Инициализация системы сохранения состояний подсветки
     */
    private void initializeBordersState() {
        try {
            // Создаем папку плагина если её нет
            if (!plugin.getDataFolder().exists()) {
                if (plugin.getDataFolder().mkdirs()) {
                    plugin.getLogger().info("Создана папка плагина: " + plugin.getDataFolder().getPath());
                }
            }

            bordersStateFile = new File(plugin.getDataFolder(), "borders-state.yml");

            if (!bordersStateFile.exists()) {
                try {
                    if (bordersStateFile.createNewFile()) {
                        plugin.getLogger().info("Создан файл состояний подсветки: borders-state.yml");
                    }
                } catch (IOException e) {
                    plugin.getLogger().severe("Не удалось создать файл borders-state.yml: " + e.getMessage());
                    e.printStackTrace();
                }
            }

            bordersStateConfig = YamlConfiguration.loadConfiguration(bordersStateFile);
            plugin.getLogger().info("Система сохранения состояний подсветки инициализирована");

        } catch (Exception e) {
            plugin.getLogger().severe("Критическая ошибка при инициализации файла состояний: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Загрузка состояний подсветки из файла
     */
    private void loadBordersState() {
        if (bordersStateConfig == null) {
            plugin.getLogger().warning("bordersStateConfig равен null при загрузке состояний");
            return;
        }

        try {
            int loadedStates = 0;

            // Загружаем все сохраненные состояния
            if (bordersStateConfig.contains("regions")) {
                Set<String> regionIds = bordersStateConfig.getConfigurationSection("regions").getKeys(false);

                for (String regionId : regionIds) {
                    try {
                        String path = "regions." + regionId + ".borders-enabled";
                        boolean enabled = bordersStateConfig.getBoolean(path, true);
                        regionBordersEnabled.put(regionId, enabled);
                        loadedStates++;

                        if (plugin.getConfig().getBoolean("debug.log-borders-state", false)) {
                            plugin.getLogger().info("DEBUG: Загружено состояние подсветки для региона " +
                                    regionId + ": " + enabled);
                        }
                    } catch (Exception e) {
                        plugin.getLogger().warning("Ошибка при загрузке состояния для региона " +
                                regionId + ": " + e.getMessage());
                    }
                }
            }

            plugin.getLogger().info("Загружено состояний подсветки: " + loadedStates);

        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка при загрузке состояний подсветки: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Сохранение состояний подсветки в файл
     */
    private void saveBordersState() {
        if (bordersStateConfig == null) {
            plugin.getLogger().warning("bordersStateConfig равен null при сохранении");
            return;
        }

        try {
            // Сохраняем все состояния
            for (Map.Entry<String, Boolean> entry : regionBordersEnabled.entrySet()) {
                String regionId = entry.getKey();
                Boolean enabled = entry.getValue();

                if (regionId != null && enabled != null) {
                    bordersStateConfig.set("regions." + regionId + ".borders-enabled", enabled);
                }
            }

            // Записываем в файл
            bordersStateConfig.save(bordersStateFile);

            if (plugin.getConfig().getBoolean("debug.log-borders-state", false)) {
                plugin.getLogger().info("DEBUG: Сохранено " + regionBordersEnabled.size() + " состояний подсветки");
            }

        } catch (IOException e) {
            plugin.getLogger().severe("Не удалось сохранить состояния подсветки: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            plugin.getLogger().severe("Критическая ошибка при сохранении состояний: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Запуск задачи периодической очистки кэша
     */
    private void startCacheCleanupTask() {
        Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            try {
                cleanupCache();
            } catch (Exception e) {
                plugin.getLogger().warning("Ошибка при очистке кэша: " + e.getMessage());
            }
        }, 20L * 300, 20L * 300); // Каждые 5 минут
    }

    /**
     * Очистка устаревших записей из кэша
     */
    private void cleanupCache() {
        long currentTime = System.currentTimeMillis();
        long cacheLifetime = 300000; // 5 минут

        // Очищаем устаревшие записи времени открытия меню
        lastMenuOpenTime.entrySet().removeIf(entry ->
                currentTime - entry.getValue() > cacheLifetime);

        // Очищаем кэш регионов (они могут измениться)
        if (regionCache.size() > 100) { // Ограничиваем размер кэша
            regionCache.clear();
        }
    }

    // ===== МЕТОДЫ ДЛЯ РАБОТЫ С СОСТОЯНИЕМ ПОДСВЕТКИ =====

    /**
     * Получение состояния подсветки для региона
     * @param regionId ID региона
     * @return true если подсветка включена, false если выключена
     */
    public boolean isRegionBordersEnabled(String regionId) {
        if (regionId == null || regionId.trim().isEmpty()) {
            plugin.getLogger().warning("Попытка получить состояние подсветки для null/пустого regionId");
            return true; // По умолчанию включена
        }

        // По умолчанию подсветка включена для новых регионов
        Boolean enabled = regionBordersEnabled.get(regionId);
        return enabled != null ? enabled : true;
    }

    /**
     * Установка состояния подсветки для региона
     * @param regionId ID региона
     * @param enabled true для включения, false для выключения
     */
    public void setRegionBordersEnabled(String regionId, boolean enabled) {
        if (regionId == null || regionId.trim().isEmpty()) {
            plugin.getLogger().warning("Попытка установить состояние подсветки для null/пустого regionId");
            return;
        }

        regionBordersEnabled.put(regionId, enabled);
        saveBordersState();

        if (plugin.getConfig().getBoolean("debug.log-borders-state", false)) {
            plugin.getLogger().info("DEBUG: Состояние подсветки для региона " + regionId +
                    " установлено в: " + enabled);
        }
    }

    /**
     * Удаление состояния подсветки для региона (при удалении региона)
     * @param regionId ID региона
     */
    public void removeRegionBordersState(String regionId) {
        if (regionId == null || regionId.trim().isEmpty()) {
            return;
        }

        regionBordersEnabled.remove(regionId);

        if (bordersStateConfig != null) {
            bordersStateConfig.set("regions." + regionId, null);
            saveBordersState();
        }

        if (plugin.getConfig().getBoolean("debug.log-borders-state", false)) {
            plugin.getLogger().info("DEBUG: Удалено состояние подсветки для региона " + regionId);
        }
    }

    // ===== МЕТОДЫ ДЛЯ РАБОТЫ С ТАЙМАУТАМИ УДАЛЕНИЯ =====

    /**
     * Создание таймаута для ожидающего удаления
     * @param player Игрок
     * @param regionId ID региона для удаления
     */
    private void createDeletionTimeout(Player player, String regionId) {
        if (player == null || regionId == null) {
            plugin.getLogger().warning("Попытка создать таймаут удаления с null параметрами");
            return;
        }

        UUID playerId = player.getUniqueId();

        // Отменяем предыдущий таймаут если есть
        BukkitTask oldTask = pendingDeletionTimeouts.remove(playerId);
        if (oldTask != null && !oldTask.isCancelled()) {
            oldTask.cancel();
            plugin.getLogger().info("DEBUG TIMEOUT: Отменен предыдущий таймаут для игрока " + player.getName());
        }

        // Создаем новый таймаут на 60 секунд
        BukkitTask timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            try {
                handleDeletionTimeout(playerId, regionId, player.getName());
            } catch (Exception e) {
                plugin.getLogger().severe("Ошибка при обработке таймаута удаления: " + e.getMessage());
                e.printStackTrace();
            }
        }, 20L * 60); // 60 секунд

        pendingDeletionTimeouts.put(playerId, timeoutTask);

        plugin.getLogger().info("DEBUG TIMEOUT: Создан таймаут на 60 секунд для удаления региона " +
                regionId + " игроком " + player.getName());
    }

    /**
     * Обработка истечения таймаута удаления
     * @param playerId UUID игрока
     * @param regionId ID региона
     * @param playerName Имя игрока для логирования
     */
    private void handleDeletionTimeout(UUID playerId, String regionId, String playerName) {
        if (pendingDeletions.containsKey(playerId)) {
            plugin.getLogger().info("DEBUG TIMEOUT: Таймаут ожидающего удаления для игрока " + playerName);

            pendingDeletions.remove(playerId);
            pendingDeletionTimeouts.remove(playerId);

            // Уведомляем игрока если он онлайн
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.sendMessage(ChatColor.YELLOW + "⏰ Время ожидания подтверждения удаления истекло.");
                player.sendMessage(ChatColor.GRAY + "Операция удаления отменена.");
            }
        }
    }

    /**
     * Принудительная очистка ожидающего удаления с отменой таймаута
     * @param player Игрок
     */
    public void clearPendingDeletion(Player player) {
        if (player == null) {
            return;
        }

        UUID playerId = player.getUniqueId();
        String regionId = pendingDeletions.remove(playerId);

        // Отменяем таймаут
        BukkitTask timeoutTask = pendingDeletionTimeouts.remove(playerId);
        if (timeoutTask != null && !timeoutTask.isCancelled()) {
            timeoutTask.cancel();
            plugin.getLogger().info("DEBUG CLEAR: Отменен таймаут для игрока " + player.getName());
        }

        if (regionId != null) {
            plugin.getLogger().info("DEBUG CLEAR: Очищено ожидающее удаление региона " + regionId +
                    " для игрока " + player.getName());
        }
    }
    // ===== ОСНОВНЫЕ МЕТОДЫ РАБОТЫ С МЕНЮ =====

    /**
     * Открытие меню региона для игрока
     * @param player Игрок
     * @param region Регион
     */
    public void openRegionMenu(Player player, ProtectedRegion region) {
        if (player == null) {
            plugin.getLogger().warning("Попытка открыть меню для null игрока");
            return;
        }

        if (region == null) {
            player.sendMessage(ChatColor.RED + "Ошибка: регион не найден!");
            plugin.getLogger().warning("Попытка открыть меню для null региона игроку " + player.getName());
            return;
        }

        // Проверяем настройки
        if (!plugin.getConfig().getBoolean("region-expansion.enabled", true)) {
            player.sendMessage(ChatColor.RED + "Меню регионов отключено!");
            return;
        }

        // Проверяем права доступа к региону
        if (!canPlayerAccessRegion(player, region)) {
            player.sendMessage(ChatColor.RED + "У вас нет доступа к этому региону!");
            return;
        }

        try {
            // Сбрасываем состояние подтверждения удаления при открытии нового меню
            clearPendingDeletion(player);

            // Создаем инвентарь
            String title = ChatColor.translateAlternateColorCodes('&',
                    plugin.getConfig().getString("menu.title", "&6&lМеню региона"));
            int size = plugin.getConfig().getInt("menu.size", 27);

            // Проверяем корректность размера
            if (size < 9 || size > 54 || size % 9 != 0) {
                size = 27; // Фолбэк на стандартный размер
                plugin.getLogger().warning("Некорректный размер меню в конфиге, используется 27");
            }

            Inventory menu = Bukkit.createInventory(null, size, title);

            // Получаем информацию о регионе
            String ownerName = getRegionOwnerName(region);
            int currentLevel = getRegionExpansionLevel(region);
            String currentSize = getCurrentRegionSizeString(region);
            String nextSize = getNextRegionSizeString(region, currentLevel);
            int maxLevel = plugin.getConfig().getInt("region-expansion.max-level", 10);
            double price = getExpansionPrice(currentLevel + 1);

            // Добавляем все кнопки
            addExpandButton(menu, region, currentLevel, currentSize, nextSize, maxLevel, price);
            addInfoButton(menu, region, ownerName, currentSize);
            addBordersToggleButton(menu, region);
            addFlagProtectionButton(menu, region);
            addLifetimeButton(menu, region);
            addHeightExpansionButton(menu, region);
            addDeleteButton(menu, player, region);
            addCloseButton(menu);

            // Добавляем декоративные элементы если включены
            if (plugin.getConfig().getBoolean("menu.items.filler.enabled", true)) {
                addFillerItems(menu);
            }

            // Открываем меню игроку
            player.openInventory(menu);
            openMenus.put(player.getUniqueId(), region.getId());
            lastMenuOpenTime.put(region.getId(), System.currentTimeMillis());

            plugin.getLogger().info("Игрок " + player.getName() + " открыл меню региона " + region.getId());

        } catch (Exception e) {
            plugin.getLogger().severe("Критическая ошибка при открытии меню для игрока " +
                    player.getName() + ": " + e.getMessage());
            e.printStackTrace();
            player.sendMessage(ChatColor.RED + "Произошла ошибка при открытии меню!");
        }
    }

    /**
     * Проверка прав доступа игрока к региону
     * @param player Игрок
     * @param region Регион
     * @return true если доступ есть
     */
    private boolean canPlayerAccessRegion(Player player, ProtectedRegion region) {
        if (player == null || region == null) {
            return false;
        }

        // Админы могут всегда
        if (player.hasPermission("rgprotect.admin")) {
            return true;
        }

        UUID playerId = player.getUniqueId();
        String playerName = player.getName();

        // Владелец может всегда
        if (region.getOwners().contains(playerId) || region.getOwners().contains(playerName)) {
            return true;
        }

        // Члены региона могут (если добавлены)
        if (region.getMembers().contains(playerId) || region.getMembers().contains(playerName)) {
            return true;
        }

        return false;
    }

    /**
     * Проверка прав на удаление региона
     * @param player Игрок
     * @param region Регион
     * @return true если может удалить
     */
    private boolean canPlayerDeleteRegion(Player player, ProtectedRegion region) {
        if (player == null || region == null) {
            return false;
        }

        // Админы могут удалять любые приваты
        if (player.hasPermission("rgprotect.admin")) {
            return true;
        }

        UUID playerId = player.getUniqueId();
        String playerName = player.getName();

        // Только владелец может удалять
        return region.getOwners().contains(playerId) || region.getOwners().contains(playerName);
    }

    // ===== МЕТОДЫ ДОБАВЛЕНИЯ КНОПОК В МЕНЮ =====

    /**
     * Добавляет кнопку расширения региона
     */
    private void addExpandButton(Inventory menu, ProtectedRegion region, int currentLevel,
                                 String currentSize, String nextSize, int maxLevel, double price) {
        int slot = plugin.getConfig().getInt("menu.items.expand.slot", 13);
        if (slot < 0 || slot >= menu.getSize()) {
            plugin.getLogger().warning("Некорректный слот для кнопки расширения: " + slot);
            return;
        }

        String materialName = plugin.getConfig().getString("menu.items.expand.material", "EMERALD");
        Material material = getMaterialSafely(materialName, Material.EMERALD);

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            plugin.getLogger().warning("Не удалось получить ItemMeta для кнопки расширения");
            return;
        }

        String name = plugin.getConfig().getString("menu.items.expand.name", "&a&lРасширить регион");
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));

        List<String> lore = new ArrayList<>();
        List<String> configLore = plugin.getConfig().getStringList("menu.items.expand.lore");

        for (String line : configLore) {
            String processedLine = line
                    .replace("{current_size}", currentSize)
                    .replace("{next_size}", nextSize)
                    .replace("{level}", String.valueOf(currentLevel))
                    .replace("{max_level}", String.valueOf(maxLevel))
                    .replace("{price}", formatPrice(price));

            lore.add(ChatColor.translateAlternateColorCodes('&', processedLine));
        }

        // Проверяем ограничения
        if (currentLevel >= maxLevel) {
            lore.add("");
            lore.add(ChatColor.RED + "Достигнут максимальный уровень!");
            item.setType(Material.BARRIER);
        } else if (price < 0) {
            lore.add("");
            lore.add(ChatColor.RED + "Ошибка конфигурации цены!");
            item.setType(Material.BARRIER);
        }

        meta.setLore(lore);
        item.setItemMeta(meta);
        menu.setItem(slot, item);
    }

    /**
     * Добавляет информационную кнопку
     */
    private void addInfoButton(Inventory menu, ProtectedRegion region, String ownerName, String size) {
        int slot = plugin.getConfig().getInt("menu.items.info.slot", 11);
        if (slot < 0 || slot >= menu.getSize()) {
            plugin.getLogger().warning("Некорректный слот для информационной кнопки: " + slot);
            return;
        }

        String materialName = plugin.getConfig().getString("menu.items.info.material", "BOOK");
        Material material = getMaterialSafely(materialName, Material.BOOK);

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        String name = plugin.getConfig().getString("menu.items.info.name", "&b&lИнформация о регионе");
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));

        List<String> lore = new ArrayList<>();
        List<String> configLore = plugin.getConfig().getStringList("menu.items.info.lore");

        String currentDate = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));

        for (String line : configLore) {
            String processedLine = line
                    .replace("{owner}", ownerName)
                    .replace("{size}", size)
                    .replace("{date}", currentDate)
                    .replace("{region_id}", region.getId());

            lore.add(ChatColor.translateAlternateColorCodes('&', processedLine));
        }

        meta.setLore(lore);
        item.setItemMeta(meta);
        menu.setItem(slot, item);
    }

    /**
     * Добавление кнопки переключения подсветки
     */
    private void addBordersToggleButton(Inventory menu, ProtectedRegion region) {
        int slot = plugin.getConfig().getInt("menu.items.borders-toggle.slot", 20);
        if (slot < 0 || slot >= menu.getSize()) {
            plugin.getLogger().warning("Некорректный слот для кнопки подсветки: " + slot);
            return;
        }

        boolean bordersEnabled = isRegionBordersEnabled(region.getId());

        String materialName = bordersEnabled ?
                plugin.getConfig().getString("menu.items.borders-toggle.material-enabled", "GLOWSTONE") :
                plugin.getConfig().getString("menu.items.borders-toggle.material-disabled", "REDSTONE_LAMP");

        Material material = getMaterialSafely(materialName,
                bordersEnabled ? Material.GLOWSTONE : Material.REDSTONE_LAMP);

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        String name = bordersEnabled ?
                plugin.getConfig().getString("menu.items.borders-toggle.name-enabled", "&e&lПодсветка границ: &a&lВКЛ") :
                plugin.getConfig().getString("menu.items.borders-toggle.name-disabled", "&e&lПодсветка границ: &c&lВЫКЛ");
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));

        List<String> lore = new ArrayList<>();
        List<String> configLore = bordersEnabled ?
                plugin.getConfig().getStringList("menu.items.borders-toggle.lore-enabled") :
                plugin.getConfig().getStringList("menu.items.borders-toggle.lore-disabled");

        for (String line : configLore) {
            lore.add(ChatColor.translateAlternateColorCodes('&', line));
        }

        meta.setLore(lore);
        item.setItemMeta(meta);
        menu.setItem(slot, item);
    }
    /**
     * Добавление кнопки защиты региона (флаги)
     */
    private void addFlagProtectionButton(Inventory menu, ProtectedRegion region) {
        if (!plugin.getConfig().getBoolean("flag-protection.enabled", true)) {
            return; // Система отключена
        }

        int slot = plugin.getConfig().getInt("menu.items.flag-protection.slot", 12);
        if (slot < 0 || slot >= menu.getSize()) {
            plugin.getLogger().warning("Некорректный слот для кнопки защиты: " + slot);
            return;
        }

        String materialName = plugin.getConfig().getString("menu.items.flag-protection.material", "SHIELD");
        Material material = getMaterialSafely(materialName, Material.SHIELD);

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        String name = plugin.getConfig().getString("menu.items.flag-protection.name", "&e&lЗащита региона");
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));

        List<String> lore = new ArrayList<>();
        List<String> configLore = plugin.getConfig().getStringList("menu.items.flag-protection.lore");

        for (String line : configLore) {
            lore.add(ChatColor.translateAlternateColorCodes('&', line));
        }

        // Показываем активные флаги
        String regionId = region.getId();
        if (plugin.getFlagProtectionManager() != null) {
            boolean hasActiveFlags = false;
            if (plugin.getConfig().contains("flag-protection.flags")) {
                for (String flagKey : plugin.getConfig().getConfigurationSection("flag-protection.flags").getKeys(false)) {
                    if (plugin.getFlagProtectionManager().isFlagActive(regionId, flagKey)) {
                        if (!hasActiveFlags) {
                            lore.add("");
                            lore.add(ChatColor.GREEN + "Активные флаги:");
                            hasActiveFlags = true;
                        }
                        String flagName = plugin.getConfig().getString("flag-protection.flags." + flagKey + ".name", flagKey);
                        String remainingTime = plugin.getFlagProtectionManager().getFormattedRemainingTime(regionId, flagKey);
                        lore.add(ChatColor.WHITE + "• " + flagName + " (" + remainingTime + ")");
                    }
                }
            }

            if (!hasActiveFlags) {
                lore.add("");
                lore.add(ChatColor.GRAY + "Нет активных флагов");
            }
        }

        lore.add("");
        lore.add(ChatColor.YELLOW + "Нажмите для управления!");

        meta.setLore(lore);
        item.setItemMeta(meta);
        menu.setItem(slot, item);
    }

    /**
     * Добавление кнопки времени жизни
     */
    private void addLifetimeButton(Inventory menu, ProtectedRegion region) {
        int slot = plugin.getConfig().getInt("menu.items.lifetime.slot", 24);
        if (slot < 0 || slot >= menu.getSize()) {
            plugin.getLogger().warning("Некорректный слот для кнопки времени жизни: " + slot);
            return;
        }

        // Проверка с null safety
        boolean hasTimer = plugin.getRegionTimerManager() != null &&
                plugin.getRegionTimerManager().hasTimer(region.getId());

        String materialName = hasTimer ?
                plugin.getConfig().getString("menu.items.lifetime.material-active", "CLOCK") :
                plugin.getConfig().getString("menu.items.lifetime.material-inactive", "BARRIER");
        String name = hasTimer ?
                plugin.getConfig().getString("menu.items.lifetime.name-active", "&5&lВремя жизни региона") :
                plugin.getConfig().getString("menu.items.lifetime.name-inactive", "&7&lТаймер отключен");
        List<String> configLore = hasTimer ?
                plugin.getConfig().getStringList("menu.items.lifetime.lore-active") :
                plugin.getConfig().getStringList("menu.items.lifetime.lore-inactive");

        Material material = getMaterialSafely(materialName, hasTimer ? Material.CLOCK : Material.BARRIER);

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));

        List<String> lore = new ArrayList<>();
        for (String line : configLore) {
            String processedLine = line;
            if (hasTimer && plugin.getRegionTimerManager() != null) {
                String remainingTime = plugin.getRegionTimerManager().getFormattedRemainingTime(region.getId());
                processedLine = processedLine.replace("{time}", remainingTime);
            }
            lore.add(ChatColor.translateAlternateColorCodes('&', processedLine));
        }

        meta.setLore(lore);
        item.setItemMeta(meta);
        menu.setItem(slot, item);
    }

    /**
     * Добавление кнопки временного расширения по высоте
     */
    private void addHeightExpansionButton(Inventory menu, ProtectedRegion region) {
        if (!plugin.getConfig().getBoolean("height-expansion.enabled", true)) {
            return; // Система отключена
        }

        int slot = plugin.getConfig().getInt("menu.items.height-expansion.slot", 16);
        if (slot < 0 || slot >= menu.getSize()) {
            plugin.getLogger().warning("Некорректный слот для кнопки расширения по высоте: " + slot);
            return;
        }

        // Проверка с null safety
        boolean hasExpansion = plugin.getHeightExpansionManager() != null &&
                plugin.getHeightExpansionManager().hasHeightExpansion(region.getId());

        String materialName = hasExpansion ?
                plugin.getConfig().getString("menu.items.height-expansion.material-active", "ELYTRA") :
                plugin.getConfig().getString("menu.items.height-expansion.material-inactive", "FEATHER");
        String name = hasExpansion ?
                plugin.getConfig().getString("menu.items.height-expansion.name-active", "&d&lВременное расширение ↕") :
                plugin.getConfig().getString("menu.items.height-expansion.name-inactive", "&7&lРасширение по высоте");
        List<String> configLore = hasExpansion ?
                plugin.getConfig().getStringList("menu.items.height-expansion.lore-active") :
                plugin.getConfig().getStringList("menu.items.height-expansion.lore-inactive");

        Material material = getMaterialSafely(materialName, hasExpansion ? Material.ELYTRA : Material.FEATHER);

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));

        List<String> lore = new ArrayList<>();
        for (String line : configLore) {
            String processedLine = line;
            if (hasExpansion && plugin.getHeightExpansionManager() != null) {
                String remainingTime = plugin.getHeightExpansionManager().getFormattedRemainingTime(region.getId());
                processedLine = processedLine.replace("{time}", remainingTime);
            }
            lore.add(ChatColor.translateAlternateColorCodes('&', processedLine));
        }

        meta.setLore(lore);
        item.setItemMeta(meta);
        menu.setItem(slot, item);
    }

    /**
     * Добавляет кнопку закрытия
     */
    private void addCloseButton(Inventory menu) {
        int slot = plugin.getConfig().getInt("menu.items.close.slot", 15);
        if (slot < 0 || slot >= menu.getSize()) {
            plugin.getLogger().warning("Некорректный слот для кнопки закрытия: " + slot);
            return;
        }

        String materialName = plugin.getConfig().getString("menu.items.close.material", "BARRIER");
        Material material = getMaterialSafely(materialName, Material.BARRIER);

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        String name = plugin.getConfig().getString("menu.items.close.name", "&c&lЗакрыть меню");
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));

        List<String> lore = new ArrayList<>();
        List<String> configLore = plugin.getConfig().getStringList("menu.items.close.lore");
        for (String line : configLore) {
            lore.add(ChatColor.translateAlternateColorCodes('&', line));
        }

        meta.setLore(lore);
        item.setItemMeta(meta);
        menu.setItem(slot, item);
    }

    /**
     * Добавляет кнопку удаления региона
     */
    private void addDeleteButton(Inventory menu, Player player, ProtectedRegion region) {
        if (!canPlayerDeleteRegion(player, region)) {
            return; // Нет прав на удаление
        }

        int slot = plugin.getConfig().getInt("menu.items.delete.slot", 22);
        if (slot < 0 || slot >= menu.getSize()) {
            plugin.getLogger().warning("Некорректный слот для кнопки удаления: " + slot);
            return;
        }

        String materialName = plugin.getConfig().getString("menu.items.delete.material", "TNT");
        Material material = getMaterialSafely(materialName, Material.TNT);

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return;

        String name = plugin.getConfig().getString("menu.items.delete.name", "&c&lУдалить регион");
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));

        List<String> lore = new ArrayList<>();
        List<String> configLore = plugin.getConfig().getStringList("menu.items.delete.lore");
        for (String line : configLore) {
            lore.add(ChatColor.translateAlternateColorCodes('&', line));
        }

        meta.setLore(lore);
        item.setItemMeta(meta);
        menu.setItem(slot, item);
    }

    /**
     * Добавляет декоративные элементы
     */
    private void addFillerItems(Inventory menu) {
        String materialName = plugin.getConfig().getString("menu.items.filler.material", "GRAY_STAINED_GLASS_PANE");
        String name = plugin.getConfig().getString("menu.items.filler.name", "&r");
        List<Integer> slots = plugin.getConfig().getIntegerList("menu.items.filler.slots");

        Material material = getMaterialSafely(materialName, Material.GRAY_STAINED_GLASS_PANE);

        ItemStack filler = new ItemStack(material);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
            filler.setItemMeta(meta);
        }

        for (int slot : slots) {
            if (slot >= 0 && slot < menu.getSize()) {
                menu.setItem(slot, filler);
            }
        }
    }

    /**
     * Безопасное получение материала с фолбэком
     * @param materialName Название материала
     * @param fallback Материал по умолчанию
     * @return Материал
     */
    private Material getMaterialSafely(String materialName, Material fallback) {
        if (materialName == null || materialName.trim().isEmpty()) {
            return fallback;
        }

        try {
            return Material.valueOf(materialName.toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Неверный материал в конфиге: " + materialName +
                    ", используется " + fallback.name());
            return fallback;
        }
    }
    // ===== ОБРАБОТЧИКИ КЛИКОВ =====

    /**
     * Главный обработчик кликов в меню
     * @param player Игрок
     * @param slot Слот клика
     * @param clickedItem Предмет на который кликнули
     * @return true если клик обработан
     */
    public boolean handleMenuClick(Player player, int slot, ItemStack clickedItem) {
        if (player == null) {
            plugin.getLogger().warning("Обработка клика для null игрока");
            return false;
        }

        plugin.getLogger().info("DEBUG MENU: Игрок " + player.getName() + " нажал слот " + slot);

        String regionId = openMenus.get(player.getUniqueId());
        if (regionId == null) {
            plugin.getLogger().warning("DEBUG MENU: У игрока " + player.getName() + " нет открытого меню!");
            return false;
        }

        ProtectedRegion region = findRegionById(regionId);
        if (region == null) {
            player.sendMessage(ChatColor.RED + "Регион не найден!");
            player.closeInventory();
            return true;
        }

        try {
            // Получаем слоты кнопок из конфига
            int expandSlot = plugin.getConfig().getInt("menu.items.expand.slot", 13);
            int closeSlot = plugin.getConfig().getInt("menu.items.close.slot", 15);
            int infoSlot = plugin.getConfig().getInt("menu.items.info.slot", 11);
            int deleteSlot = plugin.getConfig().getInt("menu.items.delete.slot", 22);
            int bordersToggleSlot = plugin.getConfig().getInt("menu.items.borders-toggle.slot", 20);
            int lifetimeSlot = plugin.getConfig().getInt("menu.items.lifetime.slot", 24);
            int heightExpansionSlot = plugin.getConfig().getInt("menu.items.height-expansion.slot", 16);
            int flagProtectionSlot = plugin.getConfig().getInt("menu.items.flag-protection.slot", 12);

            // Обрабатываем клики по соответствующим слотам
            if (slot == expandSlot) {
                handleExpandClick(player, region);
            } else if (slot == closeSlot) {
                handleCloseClick(player);
            } else if (slot == infoSlot) {
                handleInfoClick(player, region);
            } else if (slot == deleteSlot) {
                handleDeleteClick(player, region);
            } else if (slot == bordersToggleSlot) {
                handleBordersToggleClick(player, region);
            } else if (slot == lifetimeSlot) {
                handleLifetimeClick(player, region);
            } else if (slot == heightExpansionSlot) {
                handleHeightExpansionClick(player, region);
            } else if (slot == flagProtectionSlot) {
                handleFlagProtectionClick(player, region);
            } else {
                // Клик по неизвестному слоту - просто блокируем
                plugin.getLogger().info("DEBUG MENU: Клик по неизвестному слоту " + slot);
            }

            return true;

        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка при обработке клика в меню: " + e.getMessage());
            e.printStackTrace();
            player.sendMessage(ChatColor.RED + "Произошла ошибка при обработке клика!");
            return true;
        }
    }

    /**
     * Обработка клика по кнопке закрытия
     */
    private void handleCloseClick(Player player) {
        clearPendingDeletion(player);
        player.closeInventory();
        plugin.getLogger().info("DEBUG MENU: Игрок " + player.getName() + " закрыл меню");
    }

    /**
     * Обработка клика по информационной кнопке
     */
    private void handleInfoClick(Player player, ProtectedRegion region) {
        String regionId = region.getId();

        try {
            player.sendMessage(ChatColor.GOLD + "=== Информация о регионе ===");
            player.sendMessage(ChatColor.YELLOW + "ID: " + ChatColor.WHITE + regionId);
            player.sendMessage(ChatColor.YELLOW + "Владелец: " + ChatColor.WHITE + getRegionOwnerName(region));
            player.sendMessage(ChatColor.YELLOW + "Размер: " + ChatColor.WHITE + getCurrentRegionSizeString(region));
            player.sendMessage(ChatColor.YELLOW + "Уровень: " + ChatColor.WHITE + getRegionExpansionLevel(region));
            player.sendMessage(ChatColor.YELLOW + "Подсветка: " + ChatColor.WHITE +
                    (isRegionBordersEnabled(regionId) ? ChatColor.GREEN + "Включена" : ChatColor.RED + "Выключена"));

            // Показываем информацию о таймере с null проверкой
            if (plugin.getRegionTimerManager() != null && plugin.getRegionTimerManager().hasTimer(regionId)) {
                String timeLeft = plugin.getRegionTimerManager().getFormattedRemainingTime(regionId);
                player.sendMessage(ChatColor.YELLOW + "Время жизни: " + ChatColor.WHITE + timeLeft);
            } else {
                player.sendMessage(ChatColor.YELLOW + "Время жизни: " + ChatColor.GRAY + "Нет таймера");
            }

            // Показываем информацию о расширении по высоте с null проверкой
            if (plugin.getHeightExpansionManager() != null && plugin.getHeightExpansionManager().hasHeightExpansion(regionId)) {
                String heightTime = plugin.getHeightExpansionManager().getFormattedRemainingTime(regionId);
                player.sendMessage(ChatColor.YELLOW + "Расширение по высоте: " + ChatColor.WHITE + heightTime);
            } else {
                player.sendMessage(ChatColor.YELLOW + "Расширение по высоте: " + ChatColor.GRAY + "Неактивно");
            }

            // Показываем информацию об активных флагах
            if (plugin.getFlagProtectionManager() != null) {
                boolean hasActiveFlags = false;
                if (plugin.getConfig().contains("flag-protection.flags")) {
                    for (String flagKey : plugin.getConfig().getConfigurationSection("flag-protection.flags").getKeys(false)) {
                        if (plugin.getFlagProtectionManager().isFlagActive(regionId, flagKey)) {
                            if (!hasActiveFlags) {
                                player.sendMessage(ChatColor.YELLOW + "Активные флаги:");
                                hasActiveFlags = true;
                            }
                            String flagName = plugin.getConfig().getString("flag-protection.flags." + flagKey + ".name", flagKey);
                            String remainingTime = plugin.getFlagProtectionManager().getFormattedRemainingTime(regionId, flagKey);
                            player.sendMessage(ChatColor.WHITE + "  • " + flagName + ": " + remainingTime);
                        }
                    }
                }

                if (!hasActiveFlags) {
                    player.sendMessage(ChatColor.YELLOW + "Защитные флаги: " + ChatColor.GRAY + "Неактивны");
                }
            }

        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка при показе информации о регионе: " + e.getMessage());
            player.sendMessage(ChatColor.RED + "Ошибка при получении информации о регионе!");
        }
    }

    /**
     * Обработка клика по кнопке переключения подсветки
     */
    private void handleBordersToggleClick(Player player, ProtectedRegion region) {
        String regionId = region.getId();

        try {
            boolean currentState = isRegionBordersEnabled(regionId);
            boolean newState = !currentState;

            setRegionBordersEnabled(regionId, newState);

            if (newState) {
                // Включаем подсветку
                if (plugin.getVisualizationManager() != null) {
                    plugin.getVisualizationManager().createRegionBorders(region, player.getWorld());
                }

                String message = plugin.getConfig().getString("messages.borders-enabled",
                        "&a✅ Подсветка границ включена! Границы отмечены красной шерстью.");
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
            } else {
                // Выключаем подсветку
                if (plugin.getVisualizationManager() != null) {
                    plugin.getVisualizationManager().removeRegionBorders(regionId);
                }

                String message = plugin.getConfig().getString("messages.borders-disabled",
                        "&e⚡ Подсветка границ выключена! Границы удалены.");
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
            }

            // Перезапускаем меню с обновленной информацией
            player.closeInventory();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    openRegionMenu(player, region);
                }
            }, 1L);

        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка при переключении подсветки: " + e.getMessage());
            player.sendMessage(ChatColor.RED + "Ошибка при переключении подсветки границ!");
        }
    }

    /**
     * Обработка клика по кнопке защиты региона (флаги)
     */
    private void handleFlagProtectionClick(Player player, ProtectedRegion region) {
        if (!plugin.getConfig().getBoolean("flag-protection.enabled", true)) {
            player.sendMessage(ChatColor.RED + "Система защитных флагов отключена!");
            return;
        }

        player.closeInventory();

        // Проверка с null safety
        if (plugin.getFlagProtectionMenu() != null) {
            try {
                plugin.getFlagProtectionMenu().openFlagProtectionMenu(player, region);
            } catch (Exception e) {
                plugin.getLogger().severe("Ошибка при открытии меню флагов: " + e.getMessage());
                player.sendMessage(ChatColor.RED + "Ошибка при открытии меню защиты региона!");
            }
        } else {
            player.sendMessage(ChatColor.RED + "Меню защиты региона недоступно!");
        }
    }

    /**
     * Обработка клика по кнопке времени жизни
     */
    private void handleLifetimeClick(Player player, ProtectedRegion region) {
        String regionId = region.getId();

        // Проверка с null safety
        if (plugin.getRegionTimerManager() == null || !plugin.getRegionTimerManager().hasTimer(regionId)) {
            player.sendMessage(ChatColor.RED + "У этого региона нет активного таймера!");
            player.sendMessage(ChatColor.GRAY + "Таймеры применяются только к новым регионам.");
            return;
        }

        player.closeInventory();

        // Проверка с null safety
        if (plugin.getRegionLifetimeMenu() != null) {
            try {
                plugin.getRegionLifetimeMenu().openLifetimeMenu(player, region);
            } catch (Exception e) {
                plugin.getLogger().severe("Ошибка при открытии меню времени жизни: " + e.getMessage());
                player.sendMessage(ChatColor.RED + "Ошибка при открытии меню времени жизни!");
            }
        } else {
            player.sendMessage(ChatColor.RED + "Меню времени жизни недоступно!");
        }
    }

    /**
     * Обработка клика по кнопке временного расширения по высоте
     */
    private void handleHeightExpansionClick(Player player, ProtectedRegion region) {
        if (!plugin.getConfig().getBoolean("height-expansion.enabled", true)) {
            player.sendMessage(ChatColor.RED + "Временное расширение по высоте отключено!");
            return;
        }

        player.closeInventory();

        // Проверка с null safety
        if (plugin.getHeightExpansionMenu() != null) {
            try {
                plugin.getHeightExpansionMenu().openHeightExpansionMenu(player, region);
            } catch (Exception e) {
                plugin.getLogger().severe("Ошибка при открытии меню расширения по высоте: " + e.getMessage());
                player.sendMessage(ChatColor.RED + "Ошибка при открытии меню расширения по высоте!");
            }
        } else {
            player.sendMessage(ChatColor.RED + "Меню расширения по высоте недоступно!");
        }
    }
    /**
     * Обработка клика по кнопке расширения с проверкой коллизий
     */
    private void handleExpandClick(Player player, ProtectedRegion region) {
        if (player == null || region == null) {
            plugin.getLogger().warning("handleExpandClick вызван с null параметрами");
            return;
        }

        String regionId = region.getId();
        int currentLevel = getRegionExpansionLevel(region);
        int maxLevel = plugin.getConfig().getInt("region-expansion.max-level", 10);

        if (currentLevel >= maxLevel) {
            player.sendMessage("");
            player.sendMessage(ChatColor.RED + "❌ Максимальный уровень достигнут!");
            player.sendMessage(ChatColor.YELLOW + "Текущий уровень: " + ChatColor.WHITE + currentLevel + "/" + maxLevel);
            player.sendMessage(ChatColor.GRAY + "Обратитесь к администратору для увеличения лимита");
            player.sendMessage("");
            return;
        }

        int nextLevel = currentLevel + 1;
        double price = getExpansionPrice(nextLevel);

        if (price < 0) {
            player.sendMessage(ChatColor.RED + "Ошибка: цена расширения не настроена в конфиге!");
            return;
        }

        plugin.getLogger().info("РАСШИРЕНИЕ: " + player.getName() + " пытается расширить " + regionId +
                " с уровня " + currentLevel + " до " + nextLevel);

        // КРИТИЧЕСКИ ВАЖНО: Проверяем коллизии ПЕРЕД списанием денег
        if (!plugin.getProtectRegionManager().canExpandRegion(region, nextLevel, player.getName())) {
            handleExpansionCollision(player, region, nextLevel);
            return;
        }

        plugin.getLogger().info("РАСШИРЕНИЕ: Проверка коллизий пройдена");

        // Проверяем экономику
        if (plugin.getEconomy() == null) {
            player.sendMessage(ChatColor.RED + "Экономика не настроена!");
            return;
        }

        double balance = plugin.getEconomy().getBalance(player);
        if (balance < price) {
            player.sendMessage("");
            player.sendMessage(ChatColor.RED + "❌ Недостаточно денег!");
            player.sendMessage(ChatColor.YELLOW + "Нужно: " + ChatColor.WHITE + formatPrice(price) + " монет");
            player.sendMessage(ChatColor.YELLOW + "У вас: " + ChatColor.WHITE + formatPrice(balance) + " монет");
            player.sendMessage(ChatColor.GRAY + "Не хватает: " + formatPrice(price - balance) + " монет");
            player.sendMessage("");
            return;
        }

        // Списываем деньги только ПОСЛЕ всех проверок
        net.milkbowl.vault.economy.EconomyResponse response = plugin.getEconomy().withdrawPlayer(player, price);
        if (!response.transactionSuccess()) {
            player.sendMessage(ChatColor.RED + "Ошибка при списании денег: " + response.errorMessage);
            return;
        }

        plugin.getLogger().info("РАСШИРЕНИЕ: Деньги списаны, начинаем расширение");

        // Выполняем расширение
        if (expandRegion(region, nextLevel)) {
            player.sendMessage("");
            player.sendMessage(ChatColor.GREEN + "✅ Регион успешно расширен!");
            player.sendMessage(ChatColor.YELLOW + "Новый уровень: " + ChatColor.WHITE + nextLevel + "/" + maxLevel);
            player.sendMessage(ChatColor.YELLOW + "Списано: " + ChatColor.WHITE + formatPrice(price) + " монет");
            player.sendMessage("");

            plugin.getLogger().info("РАСШИРЕНИЕ: Регион " + regionId + " успешно расширен до уровня " + nextLevel);

            player.closeInventory();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                ProtectedRegion updatedRegion = findRegionById(regionId);
                if (updatedRegion != null && player.isOnline()) {
                    openRegionMenu(player, updatedRegion);
                }
            }, 1L);
        } else {
            // Возвращаем деньги при ошибке расширения
            plugin.getEconomy().depositPlayer(player, price);
            player.sendMessage("");
            player.sendMessage(ChatColor.RED + "❌ Ошибка при расширении региона!");
            player.sendMessage(ChatColor.YELLOW + "Деньги возвращены: " + formatPrice(price) + " монет");
            player.sendMessage(ChatColor.GRAY + "Попробуйте еще раз или обратитесь к администратору");
            player.sendMessage("");

            plugin.getLogger().severe("РАСШИРЕНИЕ: Ошибка при расширении " + regionId + " - деньги возвращены");
        }
    }

    /**
     * Метод для обработки коллизий при расширении
     */
    private void handleExpansionCollision(Player player, ProtectedRegion region, int newLevel) {
        if (player == null || region == null) {
            plugin.getLogger().warning("handleExpansionCollision вызван с null параметрами");
            return;
        }

        String regionId = region.getId();

        player.sendMessage("");
        player.sendMessage(ChatColor.RED + "❌ Невозможно расширить регион!");
        player.sendMessage(ChatColor.YELLOW + "Причина: " + ChatColor.WHITE + "Пересечение с соседними регионами");
        player.sendMessage("");

        // Показываем текущие и планируемые размеры
        String currentSize = getCurrentRegionSizeString(region);
        String plannedSize = getPlannedRegionSizeString(region, newLevel);

        player.sendMessage(ChatColor.GRAY + "📏 Текущий размер: " + ChatColor.WHITE + currentSize);
        player.sendMessage(ChatColor.GRAY + "📏 Планируемый размер: " + ChatColor.WHITE + plannedSize);
        player.sendMessage("");

        // Анализируем конкретные пересечения
        analyzeExpansionCollisions(player, region, newLevel);

        player.sendMessage(ChatColor.YELLOW + "💡 Возможные решения:");
        player.sendMessage(ChatColor.GRAY + "   • Договоритесь с соседями о границах");
        player.sendMessage(ChatColor.GRAY + "   • Найдите новое место для большего региона");
        player.sendMessage(ChatColor.GRAY + "   • Обратитесь к администратору");
        player.sendMessage("");

        plugin.getLogger().info("РАСШИРЕНИЕ: Коллизия для " + regionId + " до уровня " + newLevel);
    }

    /**
     * Метод для детального анализа коллизий при расширении
     */
    private void analyzeExpansionCollisions(Player player, ProtectedRegion region, int newLevel) {
        if (player == null || region == null) {
            return;
        }

        try {
            World world = findWorldForRegion(region.getId());
            if (world == null) {
                plugin.getLogger().warning("Не удалось найти мир для региона " + region.getId());
                return;
            }

            Object regionManager = plugin.getProtectRegionManager().getWorldGuardRegionManager(world);
            if (regionManager == null) {
                plugin.getLogger().warning("Не удалось получить RegionManager для мира " + world.getName());
                return;
            }

            // Вычисляем новые границы
            int baseX = plugin.getConfig().getInt("region-expansion.base-size.x", 3);
            int baseY = plugin.getConfig().getInt("region-expansion.base-size.y", 3);
            int baseZ = plugin.getConfig().getInt("region-expansion.base-size.z", 3);

            int newSizeX = baseX + (newLevel * 2);
            int newSizeY = baseY + (newLevel * 2);
            int newSizeZ = baseZ + (newLevel * 2);

            int centerX = (region.getMinimumPoint().x() + region.getMaximumPoint().x()) / 2;
            int centerY = (region.getMinimumPoint().y() + region.getMaximumPoint().y()) / 2;
            int centerZ = (region.getMinimumPoint().z() + region.getMaximumPoint().z()) / 2;

            int radiusX = (newSizeX - 1) / 2;
            int radiusY = (newSizeY - 1) / 2;
            int radiusZ = (newSizeZ - 1) / 2;

            BlockVector3 newMin = BlockVector3.at(centerX - radiusX, centerY - radiusY, centerZ - radiusZ);
            BlockVector3 newMax = BlockVector3.at(centerX + radiusX, centerY + radiusY, centerZ + radiusZ);

            ProtectedCuboidRegion testRegion = new ProtectedCuboidRegion("test", newMin, newMax);

            // Получаем все регионы с помощью рефлексии
            java.lang.reflect.Method getRegionsMethod = regionManager.getClass().getMethod("getRegions");
            @SuppressWarnings("unchecked")
            Map<String, ProtectedRegion> regions = (Map<String, ProtectedRegion>) getRegionsMethod.invoke(regionManager);

            List<String> conflictingOwners = new ArrayList<>();
            for (ProtectedRegion existingRegion : regions.values()) {
                // Пропускаем сам регион
                if (existingRegion.getId().equals(region.getId())) {
                    continue;
                }

                if (hasRegionIntersection(testRegion, existingRegion)) {
                    String ownerName = getRegionOwnerName(existingRegion);

                    if (!isPlayerOwner(existingRegion, player.getName())) {
                        if (!conflictingOwners.contains(ownerName)) {
                            conflictingOwners.add(ownerName);
                        }
                    }
                }
            }

            if (!conflictingOwners.isEmpty()) {
                player.sendMessage(ChatColor.RED + "🚫 Конфликт с регионами игроков:");
                for (String owner : conflictingOwners) {
                    player.sendMessage(ChatColor.RED + "   • " + ChatColor.WHITE + owner);
                }
                player.sendMessage("");
            }

        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка в analyzeExpansionCollisions: " + e.getMessage());
            e.printStackTrace();
        }
    }
    /**
     * Обработка клика по кнопке удаления с таймаутом
     */
    private void handleDeleteClick(Player player, ProtectedRegion region) {
        if (player == null || region == null) {
            plugin.getLogger().warning("handleDeleteClick вызван с null параметрами");
            return;
        }

        String regionId = region.getId();

        plugin.getLogger().info("DEBUG DELETE CLICK: Игрок " + player.getName() + " кликнул удаление региона " + regionId);

        // Проверяем права на удаление
        if (!canPlayerDeleteRegion(player, region)) {
            player.sendMessage(ChatColor.RED + "У вас нет прав на удаление этого региона!");
            return;
        }

        // Сохраняем состояние ожидающего удаления
        pendingDeletions.put(player.getUniqueId(), regionId);

        // Создаем таймаут
        createDeletionTimeout(player, regionId);

        plugin.getLogger().info("DEBUG DELETE CLICK: Сохранено ожидающее удаление с таймаутом");

        // Отправляем сообщения игроку
        player.sendMessage("");
        player.sendMessage(ChatColor.RED + "⚠ ВНИМАНИЕ! ⚠");
        player.sendMessage(ChatColor.YELLOW + "Вы действительно хотите удалить регион " +
                ChatColor.WHITE + regionId + ChatColor.YELLOW + "?");
        player.sendMessage(ChatColor.RED + "Это действие нельзя будет отменить!");
        player.sendMessage("");
        player.sendMessage(ChatColor.GREEN + "Напишите " + ChatColor.WHITE + "УДАЛИТЬ" +
                ChatColor.GREEN + " для подтверждения");
        player.sendMessage(ChatColor.RED + "Напишите " + ChatColor.WHITE + "ОТМЕНА" +
                ChatColor.RED + " для отмены");
        player.sendMessage(ChatColor.GRAY + "У вас есть 60 секунд на подтверждение.");
        player.sendMessage("");

        // Закрываем меню
        player.closeInventory();

        plugin.getLogger().info("DEBUG DELETE CLICK: Меню закрыто, ожидаем подтверждения в чате");
    }

    /**
     * Метод обработки подтверждения в чате
     */
    public void handleChatConfirmation(Player player, String message) {
        if (player == null || message == null) {
            plugin.getLogger().warning("handleChatConfirmation вызван с null параметрами");
            return;
        }

        plugin.getLogger().info("DEBUG CONFIRMATION: Начало обработки для игрока " + player.getName() + " с сообщением '" + message + "'");

        String regionId = pendingDeletions.get(player.getUniqueId());
        if (regionId == null) {
            plugin.getLogger().warning("DEBUG CONFIRMATION: У игрока " + player.getName() + " нет ожидающего удаления!");
            return;
        }

        plugin.getLogger().info("DEBUG CONFIRMATION: Найдено ожидающее удаление региона: " + regionId);

        // Очищаем сообщение от лишних символов
        String cleanMessage = message.trim().toUpperCase();
        plugin.getLogger().info("DEBUG CONFIRMATION: Очищенное сообщение: '" + cleanMessage + "'");

        if (cleanMessage.equals("УДАЛИТЬ") || cleanMessage.equals("DELETE") ||
                cleanMessage.equals("YES") || cleanMessage.equals("ДА") ||
                cleanMessage.equals("CONFIRM") || cleanMessage.equals("Y") || cleanMessage.equals("Д")) {

            plugin.getLogger().info("DEBUG CONFIRMATION: Подтверждение удаления получено, выполняем удаление...");

            ProtectedRegion region = findRegionById(regionId);
            if (region == null) {
                plugin.getLogger().severe("DEBUG CONFIRMATION: РЕГИОН НЕ НАЙДЕН: " + regionId);
                player.sendMessage(ChatColor.RED + "Ошибка: регион не найден!");
                clearPendingDeletion(player);
                return;
            }

            plugin.getLogger().info("DEBUG CONFIRMATION: Регион найден: " + region.getId());

            // Проверяем права на удаление
            if (!canPlayerDeleteRegion(player, region)) {
                plugin.getLogger().warning("DEBUG CONFIRMATION: У игрока нет прав на удаление региона");
                player.sendMessage(ChatColor.RED + "У вас нет прав на удаление этого региона!");
                clearPendingDeletion(player);
                return;
            }

            plugin.getLogger().info("DEBUG CONFIRMATION: Права проверены, начинаем удаление...");

            try {
                // Сначала очищаем состояние, потом удаляем
                pendingDeletions.remove(player.getUniqueId());

                // Отменяем таймаут
                BukkitTask timeoutTask = pendingDeletionTimeouts.remove(player.getUniqueId());
                if (timeoutTask != null && !timeoutTask.isCancelled()) {
                    timeoutTask.cancel();
                    plugin.getLogger().info("DEBUG CONFIRMATION: Отменен таймаут при подтверждении удаления");
                }

                plugin.getLogger().info("DEBUG CONFIRMATION: Вызываем deleteRegionDirectly...");
                deleteRegionDirectly(player, region);

                plugin.getLogger().info("DEBUG CONFIRMATION: Удаление завершено успешно");

                // Подтверждение игроку
                player.sendMessage("");
                player.sendMessage(ChatColor.GREEN + "✅ Регион " + regionId + " успешно удален!");
                player.sendMessage("");

            } catch (Exception e) {
                plugin.getLogger().severe("КРИТИЧЕСКАЯ ОШИБКА при удалении региона " + regionId + ": " + e.getMessage());
                e.printStackTrace();
                player.sendMessage(ChatColor.RED + "Произошла критическая ошибка при удалении региона!");
                player.sendMessage(ChatColor.YELLOW + "Обратитесь к администратору. Ошибка: " + e.getMessage());
            }

        } else if (cleanMessage.equals("ОТМЕНА") || cleanMessage.equals("CANCEL") ||
                cleanMessage.equals("NO") || cleanMessage.equals("НЕТ") ||
                cleanMessage.equals("N") || cleanMessage.equals("Н")) {

            plugin.getLogger().info("DEBUG CONFIRMATION: Отмена удаления получена");

            String cancelMessage = plugin.getConfig().getString("messages.region-deletion-cancelled",
                    "&7Удаление региона отменено.");
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', cancelMessage));
            clearPendingDeletion(player);

            plugin.getLogger().info("DEBUG CONFIRMATION: Удаление отменено");
        } else {
            plugin.getLogger().info("DEBUG CONFIRMATION: Неизвестная команда: '" + cleanMessage + "'");
        }
    }

    /**
     * Метод прямого удаления региона с подробным логированием
     */
    public void deleteRegionDirectly(Player player, ProtectedRegion region) {
        if (player == null || region == null) {
            plugin.getLogger().warning("deleteRegionDirectly вызван с null параметрами");
            return;
        }

        String regionId = region.getId();
        String ownerName = getRegionOwnerName(region);

        plugin.getLogger().info("DEBUG DELETE: Начало удаления региона " + regionId + " владельца " + ownerName);

        try {
            World regionWorld = null;
            Object regionManager = null;

            plugin.getLogger().info("DEBUG DELETE: Поиск мира для региона...");

            // Ищем мир региона
            for (World world : plugin.getServer().getWorlds()) {
                Object rm = plugin.getProtectRegionManager().getWorldGuardRegionManager(world);
                if (rm != null) {
                    try {
                        java.lang.reflect.Method getRegionMethod = rm.getClass().getMethod("getRegion", String.class);
                        ProtectedRegion testRegion = (ProtectedRegion) getRegionMethod.invoke(rm, regionId);
                        if (testRegion != null && testRegion.getId().equals(regionId)) {
                            regionWorld = world;
                            regionManager = rm;
                            plugin.getLogger().info("DEBUG DELETE: Регион найден в мире: " + world.getName());
                            break;
                        }
                    } catch (Exception e) {
                        // Игнорируем
                    }
                }
            }

            if (regionWorld == null || regionManager == null) {
                plugin.getLogger().severe("DEBUG DELETE: КРИТИЧЕСКАЯ ОШИБКА - не удалось найти мир региона!");
                player.sendMessage(ChatColor.RED + "Ошибка: не удалось найти мир региона!");
                return;
            }

            plugin.getLogger().info("DEBUG DELETE: Удаляем визуализацию и голограмму...");

            // Удаляем визуализацию с null проверкой
            if (plugin.getVisualizationManager() != null) {
                plugin.getVisualizationManager().removeRegionBorders(regionId);
            }

            // Удаляем голограмму с null проверкой
            if (plugin.getHologramManager() != null) {
                plugin.getHologramManager().removeHologram(regionId);
            }

            // Удаляем таймер если есть
            if (plugin.getRegionTimerManager() != null && plugin.getRegionTimerManager().hasTimer(regionId)) {
                plugin.getRegionTimerManager().removeRegionTimer(regionId);
                plugin.getLogger().info("DEBUG DELETE: Таймер региона удален");
            }

            // Отключаем расширение по высоте если есть
            if (plugin.getHeightExpansionManager() != null && plugin.getHeightExpansionManager().hasHeightExpansion(regionId)) {
                plugin.getHeightExpansionManager().disableHeightExpansion(regionId);
                plugin.getLogger().info("DEBUG DELETE: Расширение по высоте отключено");
            }

            // Отключаем защитные флаги если есть
            if (plugin.getFlagProtectionManager() != null) {
                // Проходим по всем флагам и отключаем их
                if (plugin.getConfig().contains("flag-protection.flags")) {
                    for (String flagKey : plugin.getConfig().getConfigurationSection("flag-protection.flags").getKeys(false)) {
                        if (plugin.getFlagProtectionManager().isFlagActive(regionId, flagKey)) {
                            plugin.getFlagProtectionManager().deactivateFlag(regionId, flagKey);
                        }
                    }
                }
                plugin.getLogger().info("DEBUG DELETE: Защитные флаги отключены");
            }

            plugin.getLogger().info("DEBUG DELETE: Удаляем центральный блок...");

            // Удаляем центральный блок
            removeCenterBlockDirectly(region, regionWorld);

            plugin.getLogger().info("DEBUG DELETE: Удаляем регион из WorldGuard...");

            // Удаляем регион из WorldGuard
            java.lang.reflect.Method removeRegionMethod = regionManager.getClass()
                    .getMethod("removeRegion", String.class);
            removeRegionMethod.invoke(regionManager, regionId);

            java.lang.reflect.Method saveMethod = regionManager.getClass().getMethod("save");
            saveMethod.invoke(regionManager);

            plugin.getLogger().info("DEBUG DELETE: Регион удален из WorldGuard");

            // Возвращаем блок игроку
            plugin.getLogger().info("DEBUG DELETE: Возвращаем блок привата...");
            giveProtectBlockBackDirectly(player, ownerName);

            // Очищаем состояние подсветки
            plugin.getLogger().info("DEBUG DELETE: Очищаем состояние подсветки...");
            removeRegionBordersState(regionId);

            plugin.getLogger().info("DEBUG DELETE: Удаление региона " + regionId + " ЗАВЕРШЕНО УСПЕШНО");

            String deleteMessage = plugin.getConfig().getString("messages.region-deleted",
                    "&aРегион успешно удален!");
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', deleteMessage));

        } catch (Exception e) {
            plugin.getLogger().severe("КРИТИЧЕСКАЯ ОШИБКА при удалении региона " + regionId + ": " + e.getMessage());
            e.printStackTrace();
            player.sendMessage(ChatColor.RED + "Произошла критическая ошибка при удалении региона!");
            throw new RuntimeException("Ошибка удаления региона", e);
        }
    }
    /**
     * Вспомогательный метод для удаления центрального блока
     */
    private void removeCenterBlockDirectly(ProtectedRegion region, World world) {
        if (region == null || world == null) {
            plugin.getLogger().warning("removeCenterBlockDirectly вызван с null параметрами");
            return;
        }

        try {
            int centerX = (region.getMinimumPoint().x() + region.getMaximumPoint().x()) / 2;
            int centerY = (region.getMinimumPoint().y() + region.getMaximumPoint().y()) / 2;
            int centerZ = (region.getMinimumPoint().z() + region.getMaximumPoint().z()) / 2;

            Location centerLoc = new Location(world, centerX, centerY, centerZ);
            Block centerBlock = centerLoc.getBlock();

            Material protectMaterial = getMaterialSafely(
                    plugin.getConfig().getString("protect-block.material", "DIAMOND_BLOCK"),
                    Material.DIAMOND_BLOCK);

            if (centerBlock.getType() == protectMaterial) {
                centerBlock.setType(Material.AIR);
                plugin.getLogger().info("DEBUG DELETE: Центральный блок удален");
            } else {
                plugin.getLogger().warning("DEBUG DELETE: Центральный блок не является блоком привата: " + centerBlock.getType());
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при удалении центрального блока: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Вспомогательный метод для возврата блока привата игроку
     */
    private void giveProtectBlockBackDirectly(Player player, String ownerName) {
        if (player == null) {
            plugin.getLogger().warning("giveProtectBlockBackDirectly вызван с null игроком");
            return;
        }

        if (ownerName == null || ownerName.trim().isEmpty()) {
            ownerName = player.getName();
        }

        try {
            Material blockType = getMaterialSafely(
                    plugin.getConfig().getString("protect-block.material", "DIAMOND_BLOCK"),
                    Material.DIAMOND_BLOCK);

            ItemStack protectBlock = new ItemStack(blockType, 1);
            ItemMeta meta = protectBlock.getItemMeta();
            if (meta == null) {
                plugin.getLogger().warning("Не удалось получить ItemMeta для блока привата");
                return;
            }

            String displayName = plugin.getConfig().getString("protect-block.display-name", "&aБлок привата")
                    .replace("{player}", ownerName);
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', displayName));

            List<String> lore = plugin.getConfig().getStringList("protect-block.lore");
            if (!lore.isEmpty()) {
                List<String> newLore = new ArrayList<>();
                for (String line : lore) {
                    newLore.add(ChatColor.translateAlternateColorCodes('&',
                            line.replace("{player}", ownerName)));
                }
                newLore.add(ChatColor.DARK_GRAY + "RGProtect:" + ownerName);
                meta.setLore(newLore);
            } else {
                // Добавляем базовую информацию если лор не настроен
                List<String> defaultLore = new ArrayList<>();
                defaultLore.add(ChatColor.GRAY + "Владелец: " + ChatColor.WHITE + ownerName);
                defaultLore.add(ChatColor.DARK_GRAY + "RGProtect:" + ownerName);
                meta.setLore(defaultLore);
            }

            protectBlock.setItemMeta(meta);

            if (player.getInventory().firstEmpty() != -1) {
                player.getInventory().addItem(protectBlock);
                player.sendMessage(ChatColor.GREEN + "Блок привата возвращен в инвентарь!");
            } else {
                player.getWorld().dropItemNaturally(player.getLocation(), protectBlock);
                player.sendMessage(ChatColor.YELLOW + "Блок привата выпал на землю - инвентарь полон!");
            }

            plugin.getLogger().info("DEBUG DELETE: Блок привата возвращен игроку " + player.getName());

        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при возврате блока: " + e.getMessage());
            e.printStackTrace();
            player.sendMessage(ChatColor.RED + "Ошибка при возврате блока привата!");
        }
    }

    /**
     * Расширяет регион до указанного уровня
     */
    private boolean expandRegion(ProtectedRegion region, int level) {
        if (region == null || level < 0) {
            plugin.getLogger().warning("expandRegion вызван с некорректными параметрами");
            return false;
        }

        try {
            if (!(region instanceof ProtectedCuboidRegion)) {
                plugin.getLogger().warning("Регион не является кубоидом: " + region.getClass().getSimpleName());
                return false;
            }

            String regionId = region.getId();

            // Проверяем расширение по высоте
            boolean hasHeightExpansion = plugin.getHeightExpansionManager() != null &&
                    plugin.getHeightExpansionManager().hasHeightExpansion(regionId);
            boolean hadBordersEnabled = isRegionBordersEnabled(regionId);

            plugin.getLogger().info("DEBUG EXPAND: Регион " + regionId + " расширен по высоте: " + hasHeightExpansion);
            plugin.getLogger().info("DEBUG EXPAND: Подсветка включена: " + hadBordersEnabled);

            // Получаем ОРИГИНАЛЬНЫЕ размеры базового региона
            int baseX = plugin.getConfig().getInt("region-expansion.base-size.x", 3);
            int baseY = plugin.getConfig().getInt("region-expansion.base-size.y", 3);
            int baseZ = plugin.getConfig().getInt("region-expansion.base-size.z", 3);

            plugin.getLogger().info("DEBUG EXPAND: Базовые размеры из конфига: " + baseX + "x" + baseY + "x" + baseZ);

            // Вычисляем новые размеры по ширине (X/Z)
            int newSizeX = baseX + (level * 2);
            int newSizeZ = baseZ + (level * 2);

            // Высота зависит от состояния расширения
            int newMinY, newMaxY;

            if (hasHeightExpansion) {
                // Регион расширен по высоте - сохраняем расширенную высоту
                World world = findWorldForRegion(regionId);
                if (world != null) {
                    newMinY = world.getMinHeight();
                    newMaxY = world.getMaxHeight() - 1;
                    plugin.getLogger().info("DEBUG EXPAND: Сохраняем расширенную высоту: " + newMinY + " -> " + newMaxY);
                } else {
                    // Фолбэк на текущие границы
                    newMinY = region.getMinimumPoint().y();
                    newMaxY = region.getMaximumPoint().y();
                }
            } else {
                // Обычный регион - используем базовую высоту
                int newSizeY = baseY + (level * 2);
                int centerY = (region.getMinimumPoint().y() + region.getMaximumPoint().y()) / 2;
                int radiusY = (newSizeY - 1) / 2;
                newMinY = centerY - radiusY;
                newMaxY = centerY + radiusY;
                plugin.getLogger().info("DEBUG EXPAND: Обычная высота: " + newMinY + " -> " + newMaxY);
            }

            // Вычисляем центр на основе ТЕКУЩИХ границ региона
            int centerX = (region.getMinimumPoint().x() + region.getMaximumPoint().x()) / 2;
            int centerZ = (region.getMinimumPoint().z() + region.getMaximumPoint().z()) / 2;

            int radiusX = (newSizeX - 1) / 2;
            int radiusZ = (newSizeZ - 1) / 2;

            BlockVector3 newMin = BlockVector3.at(centerX - radiusX, newMinY, centerZ - radiusZ);
            BlockVector3 newMax = BlockVector3.at(centerX + radiusX, newMaxY, centerZ + radiusZ);

            plugin.getLogger().info("DEBUG EXPAND: Новые границы региона:");
            plugin.getLogger().info("DEBUG EXPAND: Центр: " + centerX + "," + centerZ);
            plugin.getLogger().info("DEBUG EXPAND: Новые размеры: " + newSizeX + "x" + (newMaxY - newMinY + 1) + "x" + newSizeZ);
            plugin.getLogger().info("DEBUG EXPAND: Новые границы: " + newMin + " -> " + newMax);

            // Создаем новый регион с новыми размерами
            ProtectedCuboidRegion newRegion = new ProtectedCuboidRegion(region.getId(), newMin, newMax);

            // Копируем ВСЕ параметры региона
            newRegion.setOwners(region.getOwners());
            newRegion.setMembers(region.getMembers());
            newRegion.setFlags(region.getFlags());
            newRegion.setPriority(region.getPriority());

            World world = findWorldForRegion(region.getId());
            if (world == null) {
                plugin.getLogger().severe("DEBUG EXPAND: Мир не найден для региона " + regionId);
                return false;
            }

            Object regionManager = plugin.getProtectRegionManager().getWorldGuardRegionManager(world);
            if (regionManager == null) {
                plugin.getLogger().severe("DEBUG EXPAND: RegionManager не найден");
                return false;
            }

            try {
                // АТОМАРНАЯ замена региона
                java.lang.reflect.Method removeRegionMethod = regionManager.getClass()
                        .getMethod("removeRegion", String.class);
                java.lang.reflect.Method addRegionMethod = regionManager.getClass()
                        .getMethod("addRegion", ProtectedRegion.class);
                java.lang.reflect.Method saveMethod = regionManager.getClass().getMethod("save");

                // Удаляем старый регион
                removeRegionMethod.invoke(regionManager, region.getId());
                plugin.getLogger().info("DEBUG EXPAND: Старый регион удален");

                // Добавляем новый регион
                addRegionMethod.invoke(regionManager, newRegion);
                plugin.getLogger().info("DEBUG EXPAND: Новый регион добавлен");

                // Сохраняем изменения
                saveMethod.invoke(regionManager);
                plugin.getLogger().info("DEBUG EXPAND: Изменения сохранены");

                // Правильно обрабатываем границы для всех типов регионов
                if (hadBordersEnabled && plugin.getVisualizationManager() != null) {
                    plugin.getLogger().info("DEBUG EXPAND: Подсветка включена - обновляем границы");

                    // Удаляем старые границы
                    plugin.getVisualizationManager().removeRegionBorders(regionId);
                    plugin.getLogger().info("DEBUG EXPAND: Старые границы удалены");

                    // Пересоздаем границы для НОВОГО региона
                    plugin.getVisualizationManager().createRegionBorders(newRegion, world);
                    plugin.getLogger().info("DEBUG EXPAND: ✅ Новые границы созданы для расширенного региона");

                    // Проверяем результат
                    boolean hasBorders = plugin.getVisualizationManager().hasRegionBorders(regionId);
                    plugin.getLogger().info("DEBUG EXPAND: Границы после пересоздания: " + hasBorders);
                } else {
                    plugin.getLogger().info("DEBUG EXPAND: Подсветка выключена - границы не создаем");
                }

                plugin.getLogger().info("DEBUG EXPAND: Регион " + regionId + " успешно расширен до уровня " + level);
                return true;

            } catch (Exception e) {
                plugin.getLogger().severe("DEBUG EXPAND: Ошибка при замене региона: " + e.getMessage());
                e.printStackTrace();

                // Пытаемся восстановить оригинальный регион
                try {
                    java.lang.reflect.Method addRegionMethod = regionManager.getClass()
                            .getMethod("addRegion", ProtectedRegion.class);
                    addRegionMethod.invoke(regionManager, region);

                    java.lang.reflect.Method saveMethod = regionManager.getClass().getMethod("save");
                    saveMethod.invoke(regionManager);

                    plugin.getLogger().info("DEBUG EXPAND: Оригинальный регион восстановлен после ошибки");
                } catch (Exception restoreEx) {
                    plugin.getLogger().severe("DEBUG EXPAND: КРИТИЧЕСКАЯ ОШИБКА: Не удалось восстановить оригинальный регион: " + restoreEx.getMessage());
                    restoreEx.printStackTrace();
                }

                return false;
            }

        } catch (Exception e) {
            plugin.getLogger().severe("DEBUG EXPAND: Ошибка при расширении региона: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    // ===== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ =====

    /**
     * Вспомогательный метод для получения планируемого размера
     */
    private String getPlannedRegionSizeString(ProtectedRegion region, int newLevel) {
        if (region == null || newLevel < 0) {
            return "Неизвестно";
        }

        int baseX = plugin.getConfig().getInt("region-expansion.base-size.x", 3);
        int baseY = plugin.getConfig().getInt("region-expansion.base-size.y", 3);
        int baseZ = plugin.getConfig().getInt("region-expansion.base-size.z", 3);

        int newSizeX = baseX + (newLevel * 2);
        int newSizeY = baseY + (newLevel * 2);
        int newSizeZ = baseZ + (newLevel * 2);

        return newSizeX + "x" + newSizeY + "x" + newSizeZ;
    }

    /**
     * Проверка пересечений регионов
     */
    private boolean hasRegionIntersection(ProtectedRegion region1, ProtectedRegion region2) {
        if (region1 == null || region2 == null) {
            return false;
        }

        BlockVector3 min1 = region1.getMinimumPoint();
        BlockVector3 max1 = region1.getMaximumPoint();
        BlockVector3 min2 = region2.getMinimumPoint();
        BlockVector3 max2 = region2.getMaximumPoint();

        return !(max1.x() < min2.x() || min1.x() > max2.x() ||
                max1.y() < min2.y() || min1.y() > max2.y() ||
                max1.z() < min2.z() || min1.z() > max2.z());
    }

    /**
     * Получение имени владельца региона
     */
    private String getRegionOwnerName(ProtectedRegion region) {
        if (region == null) {
            return "Неизвестно";
        }

        try {
            if (!region.getOwners().getUniqueIds().isEmpty()) {
                UUID ownerUUID = region.getOwners().getUniqueIds().iterator().next();
                String ownerName = plugin.getServer().getOfflinePlayer(ownerUUID).getName();
                return ownerName != null ? ownerName : "Неизвестно";
            }
            if (!region.getOwners().getPlayers().isEmpty()) {
                return region.getOwners().getPlayers().iterator().next();
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при получении имени владельца региона: " + e.getMessage());
        }

        return "Неизвестно";
    }

    /**
     * Проверка, является ли игрок владельцем региона
     */
    private boolean isPlayerOwner(ProtectedRegion region, String playerName) {
        if (region == null || playerName == null || playerName.trim().isEmpty()) {
            return false;
        }

        try {
            UUID playerUUID = getPlayerUUID(playerName);
            if (playerUUID == null) {
                return false;
            }
            return region.getOwners().contains(playerUUID) || region.getOwners().contains(playerName);
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при проверке владельца региона: " + e.getMessage());
            return false;
        }
    }

    /**
     * Получение UUID игрока по имени
     */
    private UUID getPlayerUUID(String playerName) {
        if (playerName == null || playerName.trim().isEmpty()) {
            return null;
        }

        try {
            Player onlinePlayer = plugin.getServer().getPlayer(playerName);
            if (onlinePlayer != null) {
                return onlinePlayer.getUniqueId();
            } else {
                return plugin.getServer().getOfflinePlayer(playerName).getUniqueId();
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при получении UUID игрока " + playerName + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Форматирование цены
     */
    private String formatPrice(double price) {
        if (price < 0) {
            return "Ошибка";
        }
        return price == (long) price ? String.valueOf((long) price) : String.valueOf(price);
    }

    /**
     * Получение уровня расширения региона
     */
    private int getRegionExpansionLevel(ProtectedRegion region) {
        if (region == null) {
            return 0;
        }

        try {
            int baseX = plugin.getConfig().getInt("region-expansion.base-size.x", 3);
            int baseY = plugin.getConfig().getInt("region-expansion.base-size.y", 3);
            int baseZ = plugin.getConfig().getInt("region-expansion.base-size.z", 3);

            int currentX = region.getMaximumPoint().x() - region.getMinimumPoint().x() + 1;
            int currentY = region.getMaximumPoint().y() - region.getMinimumPoint().y() + 1;
            int currentZ = region.getMaximumPoint().z() - region.getMinimumPoint().z() + 1;

            // Проверяем расширение по высоте
            String regionId = region.getId();
            boolean hasHeightExpansion = plugin.getHeightExpansionManager() != null &&
                    plugin.getHeightExpansionManager().hasHeightExpansion(regionId);

            int levelX = (currentX - baseX) / 2;
            int levelZ = (currentZ - baseZ) / 2;

            // Если регион расширен по высоте, не учитываем Y в расчете уровня
            if (hasHeightExpansion) {
                return Math.max(0, Math.min(levelX, levelZ));
            } else {
                int levelY = (currentY - baseY) / 2;
                return Math.max(0, Math.min(Math.min(levelX, levelY), levelZ));
            }

        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при вычислении уровня расширения: " + e.getMessage());
            return 0;
        }
    }

    /**
     * Получение цены расширения для уровня
     */
    private double getExpansionPrice(int level) {
        if (level < 1) {
            return -1;
        }

        try {
            return plugin.getConfig().getDouble("region-expansion.prices." + level, -1);
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при получении цены для уровня " + level + ": " + e.getMessage());
            return -1;
        }
    }

    /**
     * Получение текущего размера региона в виде строки
     */
    private String getCurrentRegionSizeString(ProtectedRegion region) {
        if (region == null) {
            return "Неизвестно";
        }

        try {
            int sizeX = region.getMaximumPoint().x() - region.getMinimumPoint().x() + 1;
            int sizeY = region.getMaximumPoint().y() - region.getMinimumPoint().y() + 1;
            int sizeZ = region.getMaximumPoint().z() - region.getMinimumPoint().z() + 1;
            return sizeX + "x" + sizeY + "x" + sizeZ;
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при получении размера региона: " + e.getMessage());
            return "Ошибка";
        }
    }

    /**
     * Получение следующего размера региона в виде строки
     */
    private String getNextRegionSizeString(ProtectedRegion region, int currentLevel) {
        if (region == null || currentLevel < 0) {
            return "Неизвестно";
        }

        try {
            int maxLevel = plugin.getConfig().getInt("region-expansion.max-level", 10);
            if (currentLevel >= maxLevel) {
                return "Максимум";
            }

            int baseX = plugin.getConfig().getInt("region-expansion.base-size.x", 3);
            int baseY = plugin.getConfig().getInt("region-expansion.base-size.y", 3);
            int baseZ = plugin.getConfig().getInt("region-expansion.base-size.z", 3);

            int nextLevel = currentLevel + 1;
            int nextSizeX = baseX + (nextLevel * 2);
            int nextSizeY = baseY + (nextLevel * 2);
            int nextSizeZ = baseZ + (nextLevel * 2);

            return nextSizeX + "x" + nextSizeY + "x" + nextSizeZ;

        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при вычислении следующего размера: " + e.getMessage());
            return "Ошибка";
        }
    }

    /**
     * Поиск региона по ID во всех мирах
     */
    private ProtectedRegion findRegionById(String regionId) {
        if (regionId == null || regionId.trim().isEmpty()) {
            return null;
        }

        for (World world : plugin.getServer().getWorlds()) {
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
                // Игнорируем ошибки поиска
            }
        }
        return null;
    }

    /**
     * Поиск мира для региона
     */
    private World findWorldForRegion(String regionId) {
        if (regionId == null || regionId.trim().isEmpty()) {
            return null;
        }

        for (World world : plugin.getServer().getWorlds()) {
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
                // Игнорируем ошибки поиска
            }
        }
        return null;
    }
    // ===== ПУБЛИЧНЫЕ МЕТОДЫ УПРАВЛЕНИЯ МЕНЮ =====

    /**
     * Закрытие меню для игрока
     */
    public void closeMenuForPlayer(Player player) {
        if (player == null) {
            plugin.getLogger().warning("closeMenuForPlayer вызван с null игроком");
            return;
        }

        UUID playerId = player.getUniqueId();
        String regionId = openMenus.remove(playerId);

        if (regionId != null) {
            plugin.getLogger().info("DEBUG CLOSE: Игрок " + player.getName() + " убран из openMenus для региона " + regionId);
        } else {
            plugin.getLogger().info("DEBUG CLOSE: Игрок " + player.getName() + " не имел открытого меню");
        }

        // НЕ очищаем pendingDeletions здесь - это может прервать процесс удаления
        boolean hasPending = pendingDeletions.containsKey(playerId);
        plugin.getLogger().info("DEBUG CLOSE: Ожидающее удаление сохранено: " + hasPending);
    }

    /**
     * Проверка наличия открытого меню у игрока
     */
    public boolean hasOpenMenu(Player player) {
        if (player == null) {
            return false;
        }
        return openMenus.containsKey(player.getUniqueId());
    }

    /**
     * Получение ID региона открытого меню
     */
    public String getOpenMenuRegionId(Player player) {
        if (player == null) {
            return null;
        }
        return openMenus.get(player.getUniqueId());
    }

    /**
     * Проверка наличия ожидающего удаления
     */
    public boolean hasPendingDeletion(Player player) {
        if (player == null) {
            return false;
        }

        boolean result = pendingDeletions.containsKey(player.getUniqueId());
        plugin.getLogger().info("DEBUG PENDING CHECK: Игрок " + player.getName() + " имеет ожидающее удаление: " + result);
        return result;
    }

    /**
     * Получение ID региона ожидающего удаления
     */
    public String getPendingDeletionRegionId(Player player) {
        if (player == null) {
            return null;
        }
        return pendingDeletions.get(player.getUniqueId());
    }

    /**
     * Обновление меню для игрока (если оно открыто)
     */
    public void refreshMenuForPlayer(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        String regionId = openMenus.get(player.getUniqueId());
        if (regionId != null) {
            ProtectedRegion region = findRegionById(regionId);
            if (region != null) {
                plugin.getLogger().info("DEBUG REFRESH: Обновляем меню для игрока " + player.getName());

                // Закрываем текущее меню
                player.closeInventory();

                // Открываем обновленное меню через тик
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (player.isOnline()) {
                        openRegionMenu(player, region);
                    }
                }, 1L);
            } else {
                plugin.getLogger().warning("DEBUG REFRESH: Регион " + regionId + " не найден для обновления меню");
                openMenus.remove(player.getUniqueId());
                player.closeInventory();
            }
        }
    }

    /**
     * Принудительное закрытие всех меню (для перезагрузки плагина)
     */
    public void closeAllMenus() {
        plugin.getLogger().info("DEBUG CLOSE_ALL: Закрываем все открытые меню (" + openMenus.size() + ")");

        for (UUID playerId : new HashSet<>(openMenus.keySet())) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.closeInventory();
                player.sendMessage(ChatColor.YELLOW + "Меню региона закрыто из-за перезагрузки плагина.");
            }
        }

        openMenus.clear();

        // Очищаем таймауты удаления
        for (BukkitTask task : pendingDeletionTimeouts.values()) {
            if (task != null && !task.isCancelled()) {
                task.cancel();
            }
        }
        pendingDeletionTimeouts.clear();

        // Уведомляем игроков с ожидающими удалениями
        for (UUID playerId : new HashSet<>(pendingDeletions.keySet())) {
            Player player = plugin.getServer().getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.sendMessage(ChatColor.RED + "Ожидающее удаление отменено из-за перезагрузки плагина.");
            }
        }
        pendingDeletions.clear();

        plugin.getLogger().info("DEBUG CLOSE_ALL: Все меню закрыты");
    }

    /**
     * Получение статистики менеджера
     */
    public void printStatistics() {
        plugin.getLogger().info("=== Статистика RegionMenuManager ===");
        plugin.getLogger().info("Открытых меню: " + openMenus.size());
        plugin.getLogger().info("Ожидающих удаления: " + pendingDeletions.size());
        plugin.getLogger().info("Активных таймаутов: " + pendingDeletionTimeouts.size());
        plugin.getLogger().info("Сохраненных состояний подсветки: " + regionBordersEnabled.size());
        plugin.getLogger().info("Кэшированных регионов: " + regionCache.size());
        plugin.getLogger().info("Записей времени открытия: " + lastMenuOpenTime.size());

        if (plugin.getConfig().getBoolean("debug.detailed-statistics", false)) {
            plugin.getLogger().info("--- Детальная статистика ---");

            plugin.getLogger().info("Открытые меню:");
            for (Map.Entry<UUID, String> entry : openMenus.entrySet()) {
                Player player = plugin.getServer().getPlayer(entry.getKey());
                String playerName = player != null ? player.getName() : "OFFLINE";
                plugin.getLogger().info("  " + playerName + " -> " + entry.getValue());
            }

            plugin.getLogger().info("Ожидающие удаления:");
            for (Map.Entry<UUID, String> entry : pendingDeletions.entrySet()) {
                Player player = plugin.getServer().getPlayer(entry.getKey());
                String playerName = player != null ? player.getName() : "OFFLINE";
                plugin.getLogger().info("  " + playerName + " -> " + entry.getValue());
            }
        }
    }

    /**
     * Отладочный метод для вывода состояния ожидающих удалений
     */
    public void debugPendingDeletions() {
        plugin.getLogger().info("DEBUG PENDING: Всего ожидающих удалений: " + pendingDeletions.size());
        for (Map.Entry<UUID, String> entry : pendingDeletions.entrySet()) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            String playerName = player != null ? player.getName() : "OFFLINE";
            plugin.getLogger().info("DEBUG PENDING: " + playerName + " (" + entry.getKey() + ") -> " + entry.getValue());
        }

        plugin.getLogger().info("DEBUG TIMEOUTS: Всего активных таймаутов: " + pendingDeletionTimeouts.size());
        for (Map.Entry<UUID, BukkitTask> entry : pendingDeletionTimeouts.entrySet()) {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            String playerName = player != null ? player.getName() : "OFFLINE";
            BukkitTask task = entry.getValue();
            boolean isActive = task != null && !task.isCancelled();
            plugin.getLogger().info("DEBUG TIMEOUTS: " + playerName + " -> активен: " + isActive);
        }
    }

    /**
     * Проверка целостности данных менеджера
     */
    public boolean validateIntegrity() {
        boolean hasErrors = false;

        // Проверяем соответствие таймаутов и ожидающих удалений
        for (UUID playerId : pendingDeletions.keySet()) {
            if (!pendingDeletionTimeouts.containsKey(playerId)) {
                plugin.getLogger().warning("INTEGRITY: Найдено ожидающее удаление без таймаута для игрока " + playerId);
                hasErrors = true;
            }
        }

        // Проверяем наличие отмененных таймаутов
        for (Map.Entry<UUID, BukkitTask> entry : pendingDeletionTimeouts.entrySet()) {
            BukkitTask task = entry.getValue();
            if (task != null && task.isCancelled()) {
                plugin.getLogger().warning("INTEGRITY: Найден отмененный таймаут для игрока " + entry.getKey());
                hasErrors = true;
            }
        }

        // Проверяем существование регионов для открытых меню
        for (Map.Entry<UUID, String> entry : openMenus.entrySet()) {
            String regionId = entry.getValue();
            ProtectedRegion region = findRegionById(regionId);
            if (region == null) {
                Player player = plugin.getServer().getPlayer(entry.getKey());
                String playerName = player != null ? player.getName() : "OFFLINE";
                plugin.getLogger().warning("INTEGRITY: Игрок " + playerName + " имеет открытое меню для несуществующего региона " + regionId);
                hasErrors = true;
            }
        }

        if (hasErrors) {
            plugin.getLogger().warning("INTEGRITY: Обнаружены проблемы целостности данных!");
        } else {
            plugin.getLogger().info("INTEGRITY: Проверка целостности пройдена успешно");
        }

        return !hasErrors;
    }

    /**
     * Автоматическое исправление проблем целостности
     */
    public void fixIntegrityIssues() {
        plugin.getLogger().info("INTEGRITY FIX: Начинаем исправление проблем целостности");

        int fixedIssues = 0;

        // Исправляем ожидающие удаления без таймаутов
        for (UUID playerId : new HashSet<>(pendingDeletions.keySet())) {
            if (!pendingDeletionTimeouts.containsKey(playerId)) {
                plugin.getLogger().info("INTEGRITY FIX: Удаляем ожидающее удаление без таймаута для " + playerId);
                pendingDeletions.remove(playerId);
                fixedIssues++;
            }
        }

        // Удаляем отмененные таймауты
        for (UUID playerId : new HashSet<>(pendingDeletionTimeouts.keySet())) {
            BukkitTask task = pendingDeletionTimeouts.get(playerId);
            if (task != null && task.isCancelled()) {
                plugin.getLogger().info("INTEGRITY FIX: Удаляем отмененный таймаут для " + playerId);
                pendingDeletionTimeouts.remove(playerId);
                pendingDeletions.remove(playerId); // На всякий случай
                fixedIssues++;
            }
        }

        // Закрываем меню для несуществующих регионов
        for (UUID playerId : new HashSet<>(openMenus.keySet())) {
            String regionId = openMenus.get(playerId);
            ProtectedRegion region = findRegionById(regionId);
            if (region == null) {
                Player player = plugin.getServer().getPlayer(playerId);
                plugin.getLogger().info("INTEGRITY FIX: Закрываем меню для несуществующего региона " + regionId);

                openMenus.remove(playerId);
                if (player != null && player.isOnline()) {
                    player.closeInventory();
                    player.sendMessage(ChatColor.RED + "Меню закрыто: регион больше не существует.");
                }
                fixedIssues++;
            }
        }

        plugin.getLogger().info("INTEGRITY FIX: Исправлено проблем: " + fixedIssues);
    }

    // ===== МЕТОДЫ ОЧИСТКИ И ОБСЛУЖИВАНИЯ =====

    /**
     * Очистка ресурсов при выключении плагина
     */
    public void shutdown() {
        plugin.getLogger().info("RegionMenuManager: Начинаем процедуру выключения");

        try {
            // Закрываем все меню
            closeAllMenus();

            // Сохраняем состояния подсветки
            saveBordersState();

            // Очищаем все коллекции
            openMenus.clear();
            pendingDeletions.clear();
            pendingDeletionTimeouts.clear();
            regionBordersEnabled.clear();
            regionCache.clear();
            lastMenuOpenTime.clear();

            plugin.getLogger().info("RegionMenuManager: Выключение завершено успешно");

        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка при выключении RegionMenuManager: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Перезагрузка менеджера (для команды reload)
     */
    public void reload() {
        plugin.getLogger().info("RegionMenuManager: Начинаем перезагрузку");

        try {
            // Закрываем все активные меню
            closeAllMenus();

            // Перезагружаем состояния подсветки
            loadBordersState();

            // Очищаем кэш
            regionCache.clear();
            lastMenuOpenTime.clear();

            plugin.getLogger().info("RegionMenuManager: Перезагрузка завершена успешно");

        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка при перезагрузке RegionMenuManager: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Ручная очистка кэша
     */
    public void clearCache() {
        int regionCacheSize = regionCache.size();
        int timeRecordsSize = lastMenuOpenTime.size();

        regionCache.clear();
        lastMenuOpenTime.clear();

        plugin.getLogger().info("CACHE: Очищен кэш регионов (" + regionCacheSize + " записей) и времени открытия (" + timeRecordsSize + " записей)");
    }

    /**
     * Получение размера кэша
     */
    public int getCacheSize() {
        return regionCache.size() + lastMenuOpenTime.size();
    }

    /**
     * Проверка здоровья менеджера
     */
    public boolean isHealthy() {
        try {
            // Проверяем файлы
            if (bordersStateFile != null && !bordersStateFile.exists()) {
                plugin.getLogger().warning("HEALTH: Файл состояний подсветки не существует");
                return false;
            }

            if (bordersStateConfig == null) {
                plugin.getLogger().warning("HEALTH: Конфигурация состояний подсветки не загружена");
                return false;
            }

            // Проверяем целостность данных
            if (!validateIntegrity()) {
                plugin.getLogger().warning("HEALTH: Проблемы целостности данных");
                return false;
            }

            // Проверяем размер кэша
            if (regionCache.size() > 1000) {
                plugin.getLogger().warning("HEALTH: Кэш регионов слишком большой: " + regionCache.size());
                return false;
            }

            return true;

        } catch (Exception e) {
            plugin.getLogger().severe("HEALTH: Ошибка при проверке здоровья: " + e.getMessage());
            return false;
        }
    }
    // ===== СЛУЖЕБНЫЕ И УТИЛИТАРНЫЕ МЕТОДЫ =====

    /**
     * Получение информации о состоянии менеджера для команд
     */
    public String getStatusInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("§6=== Состояние RegionMenuManager ===\n");
        sb.append("§eОткрытых меню: §f").append(openMenus.size()).append("\n");
        sb.append("§eОжидающих удаления: §f").append(pendingDeletions.size()).append("\n");
        sb.append("§eАктивных таймаутов: §f").append(pendingDeletionTimeouts.size()).append("\n");
        sb.append("§eСостояний подсветки: §f").append(regionBordersEnabled.size()).append("\n");
        sb.append("§eКэшированных регионов: §f").append(regionCache.size()).append("\n");
        sb.append("§eЗаписей времени: §f").append(lastMenuOpenTime.size()).append("\n");
        sb.append("§eЗдоровье системы: ").append(isHealthy() ? "§aОК" : "§cПроблемы").append("\n");
        sb.append("§6=====================================");

        return sb.toString();
    }

    /**
     * Экспорт настроек подсветки для бэкапа
     */
    public Map<String, Boolean> exportBordersSettings() {
        return new HashMap<>(regionBordersEnabled);
    }

    /**
     * Импорт настроек подсветки из бэкапа
     */
    public void importBordersSettings(Map<String, Boolean> settings) {
        if (settings == null) {
            plugin.getLogger().warning("Попытка импорта null настроек подсветки");
            return;
        }

        regionBordersEnabled.clear();
        regionBordersEnabled.putAll(settings);
        saveBordersState();

        plugin.getLogger().info("Импортировано " + settings.size() + " настроек подсветки");
    }

    /**
     * Принудительная синхронизация с файлом состояний
     */
    public void forceSyncBordersState() {
        try {
            if (bordersStateFile == null || bordersStateConfig == null) {
                plugin.getLogger().warning("Не удается синхронизировать - файлы не инициализированы");
                return;
            }

            // Перезагружаем из файла
            bordersStateConfig = YamlConfiguration.loadConfiguration(bordersStateFile);

            // Очищаем текущие настройки
            regionBordersEnabled.clear();

            // Загружаем из файла
            if (bordersStateConfig.contains("regions")) {
                Set<String> regionIds = bordersStateConfig.getConfigurationSection("regions").getKeys(false);
                for (String regionId : regionIds) {
                    boolean enabled = bordersStateConfig.getBoolean("regions." + regionId + ".borders-enabled", true);
                    regionBordersEnabled.put(regionId, enabled);
                }
            }

            plugin.getLogger().info("Принудительная синхронизация состояний подсветки завершена");

        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка при принудительной синхронизации: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Получение детальной информации о регионе для отладки
     */
    public String getRegionDebugInfo(String regionId) {
        if (regionId == null || regionId.trim().isEmpty()) {
            return "§cНеверный ID региона";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("§6=== Отладка региона ").append(regionId).append(" ===\n");

        ProtectedRegion region = findRegionById(regionId);
        if (region == null) {
            sb.append("§cРегион не найден!\n");
        } else {
            sb.append("§eВладелец: §f").append(getRegionOwnerName(region)).append("\n");
            sb.append("§eРазмер: §f").append(getCurrentRegionSizeString(region)).append("\n");
            sb.append("§eУровень: §f").append(getRegionExpansionLevel(region)).append("\n");
            sb.append("§eМир: §f").append(findWorldForRegion(regionId)).append("\n");
        }

        sb.append("§eПодсветка: ").append(isRegionBordersEnabled(regionId) ? "§aВКЛ" : "§cВЫКЛ").append("\n");

        // Проверяем дополнительные системы
        if (plugin.getRegionTimerManager() != null) {
            boolean hasTimer = plugin.getRegionTimerManager().hasTimer(regionId);
            sb.append("§eТаймер: ").append(hasTimer ? "§aАктивен" : "§7Неактивен").append("\n");
        }

        if (plugin.getHeightExpansionManager() != null) {
            boolean hasHeight = plugin.getHeightExpansionManager().hasHeightExpansion(regionId);
            sb.append("§eВысота: ").append(hasHeight ? "§aРасширен" : "§7Обычная").append("\n");
        }

        // Проверяем открытые меню
        boolean hasOpenMenu = openMenus.containsValue(regionId);
        sb.append("§eОткрытое меню: ").append(hasOpenMenu ? "§aДа" : "§7Нет").append("\n");

        sb.append("§6=====================================");

        return sb.toString();
    }

    /**
     * Массовое обновление состояний подсветки
     */
    public void bulkUpdateBordersState(Map<String, Boolean> updates) {
        if (updates == null || updates.isEmpty()) {
            return;
        }

        int updated = 0;
        for (Map.Entry<String, Boolean> entry : updates.entrySet()) {
            String regionId = entry.getKey();
            Boolean enabled = entry.getValue();

            if (regionId != null && enabled != null) {
                regionBordersEnabled.put(regionId, enabled);
                updated++;
            }
        }

        if (updated > 0) {
            saveBordersState();
            plugin.getLogger().info("Массово обновлено " + updated + " состояний подсветки");
        }
    }

    /**
     * Получение списка всех регионов с подсветкой
     */
    public Set<String> getRegionsWithBorders() {
        return regionBordersEnabled.entrySet().stream()
                .filter(Map.Entry::getValue)
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Получение списка всех регионов без подсветки
     */
    public Set<String> getRegionsWithoutBorders() {
        return regionBordersEnabled.entrySet().stream()
                .filter(entry -> !entry.getValue())
                .map(Map.Entry::getKey)
                .collect(java.util.stream.Collectors.toSet());
    }

    /**
     * Проверка, является ли менеджер инициализированным
     */
    public boolean isInitialized() {
        return bordersStateFile != null && bordersStateConfig != null;
    }

    /**
     * Получение версии данных для совместимости
     */
    public String getDataVersion() {
        return "1.0.0"; // Версия формата данных RegionMenuManager
    }

    /**
     * Проверка совместимости версий данных
     */
    public boolean isDataVersionCompatible(String version) {
        if (version == null) return false;
        return "1.0.0".equals(version); // Пока поддерживаем только текущую версию
    }

    /**
     * Логирование важных событий для аудита
     */
    private void logAuditEvent(String event, Player player, String regionId) {
        if (plugin.getConfig().getBoolean("audit.enabled", false)) {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String playerName = player != null ? player.getName() : "SYSTEM";
            String message = String.format("[AUDIT] %s | %s | %s | %s", timestamp, event, playerName, regionId);
            plugin.getLogger().info(message);
        }
    }

    /**
     * Финальная проверка перед закрытием
     */
    private void performFinalChecks() {
        plugin.getLogger().info("RegionMenuManager: Выполняем финальные проверки");

        // Проверяем несохраненные изменения
        if (!regionBordersEnabled.isEmpty()) {
            saveBordersState();
            plugin.getLogger().info("Сохранены финальные изменения состояний подсветки");
        }

        // Проверяем активные задачи
        int activeTasks = 0;
        for (BukkitTask task : pendingDeletionTimeouts.values()) {
            if (task != null && !task.isCancelled()) {
                activeTasks++;
            }
        }

        if (activeTasks > 0) {
            plugin.getLogger().warning("Обнаружено " + activeTasks + " активных задач при закрытии");
        }

        plugin.getLogger().info("RegionMenuManager: Финальные проверки завершены");
    }

    /**
     * Метод для принудительной очистки ресурсов (замена deprecated finalize)
     */
    public void cleanup() {
        try {
            performFinalChecks();
            shutdown();
        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка при очистке RegionMenuManager: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ===== ГЕТТЕРЫ ДЛЯ ИНТЕГРАЦИИ С ДРУГИМИ КОМПОНЕНТАМИ =====

    public Map<UUID, String> getOpenMenus() {
        return new HashMap<>(openMenus);
    }

    public Map<UUID, String> getPendingDeletions() {
        return new HashMap<>(pendingDeletions);
    }

    public Map<String, Boolean> getRegionBordersEnabled() {
        return new HashMap<>(regionBordersEnabled);
    }

    public File getBordersStateFile() {
        return bordersStateFile;
    }

    public FileConfiguration getBordersStateConfig() {
        return bordersStateConfig;
    }

} // Конец класса RegionMenuManager