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

import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.yourplugin.rGG.RGProtectPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.io.File;
import java.io.IOException;

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

    public RegionMenuManager(RGProtectPlugin plugin) {
        this.plugin = plugin;
        this.openMenus = new HashMap<>();
        this.pendingDeletions = new HashMap<>();
        this.pendingDeletionTimeouts = new HashMap<>();
        this.regionBordersEnabled = new HashMap<>();

        // Загружаем сохраненные состояния подсветки
        loadBordersState();
    }

    /**
     * Загрузка состояний подсветки из файла
     */
    private void loadBordersState() {
        bordersStateFile = new File(plugin.getDataFolder(), "borders-state.yml");

        if (!bordersStateFile.exists()) {
            try {
                bordersStateFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().warning("Не удалось создать файл borders-state.yml: " + e.getMessage());
            }
        }

        bordersStateConfig = YamlConfiguration.loadConfiguration(bordersStateFile);

        // Загружаем все сохраненные состояния
        if (bordersStateConfig.contains("regions")) {
            for (String regionId : bordersStateConfig.getConfigurationSection("regions").getKeys(false)) {
                boolean enabled = bordersStateConfig.getBoolean("regions." + regionId + ".borders-enabled", true);
                regionBordersEnabled.put(regionId, enabled);

                if (plugin.getConfig().getBoolean("debug.log-borders-state", false)) {
                    plugin.getLogger().info("DEBUG: Загружено состояние подсветки для региона " + regionId + ": " + enabled);
                }
            }
        }
    }

    /**
     * Сохранение состояний подсветки в файл
     */
    private void saveBordersState() {
        for (Map.Entry<String, Boolean> entry : regionBordersEnabled.entrySet()) {
            bordersStateConfig.set("regions." + entry.getKey() + ".borders-enabled", entry.getValue());
        }

        try {
            bordersStateConfig.save(bordersStateFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить состояния подсветки: " + e.getMessage());
        }
    }

    /**
     * Получение состояния подсветки для региона
     */
    public boolean isRegionBordersEnabled(String regionId) {
        // По умолчанию подсветка включена для новых регионов
        return regionBordersEnabled.getOrDefault(regionId, true);
    }

    /**
     * Установка состояния подсветки для региона
     */
    public void setRegionBordersEnabled(String regionId, boolean enabled) {
        regionBordersEnabled.put(regionId, enabled);
        saveBordersState();
    }

    /**
     * Создание таймаута для ожидающего удаления
     */
    private void createDeletionTimeout(Player player, String regionId) {
        // Отменяем предыдущий таймаут если есть
        BukkitTask oldTask = pendingDeletionTimeouts.remove(player.getUniqueId());
        if (oldTask != null) {
            oldTask.cancel();
        }

        // Создаем новый таймаут на 60 секунд
        BukkitTask timeoutTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (pendingDeletions.containsKey(player.getUniqueId())) {
                plugin.getLogger().info("DEBUG TIMEOUT: Таймаут ожидающего удаления для игрока " + player.getName());

                pendingDeletions.remove(player.getUniqueId());
                pendingDeletionTimeouts.remove(player.getUniqueId());

                if (player.isOnline()) {
                    player.sendMessage(ChatColor.YELLOW + "⏰ Время ожидания подтверждения удаления истекло.");
                    player.sendMessage(ChatColor.GRAY + "Операция удаления отменена.");
                }
            }
        }, 20L * 60); // 60 секунд

        pendingDeletionTimeouts.put(player.getUniqueId(), timeoutTask);

        plugin.getLogger().info("DEBUG TIMEOUT: Создан таймаут на 60 секунд для удаления региона " + regionId);
    }
    /**
     * Открытие меню региона для игрока с новыми кнопками
     */
    public void openRegionMenu(Player player, ProtectedRegion region) {
        if (!plugin.getConfig().getBoolean("region-expansion.enabled", true)) {
            player.sendMessage(ChatColor.RED + "Меню регионов отключено!");
            return;
        }

        // Проверяем права доступа к региону
        if (!canPlayerAccessRegion(player, region)) {
            player.sendMessage(ChatColor.RED + "У вас нет доступа к этому региону!");
            return;
        }

        // Сбрасываем состояние подтверждения удаления при открытии нового меню
        clearPendingDeletion(player);

        // Создаем инвентарь
        String title = ChatColor.translateAlternateColorCodes('&',
                plugin.getConfig().getString("menu.title", "&6&lМеню региона"));
        int size = plugin.getConfig().getInt("menu.size", 27);

        Inventory menu = Bukkit.createInventory(null, size, title);

        // Получаем информацию о регионе
        String ownerName = getRegionOwnerName(region);
        int currentLevel = getRegionExpansionLevel(region);
        String currentSize = getCurrentRegionSizeString(region);
        String nextSize = getNextRegionSizeString(region, currentLevel);
        int maxLevel = plugin.getConfig().getInt("region-expansion.max-level", 10);
        double price = getExpansionPrice(currentLevel + 1);

        // Добавляем кнопку расширения
        addExpandButton(menu, region, currentLevel, currentSize, nextSize, maxLevel, price);

        // Добавляем информационную кнопку
        addInfoButton(menu, region, ownerName, currentSize);

        // Добавляем кнопку переключения подсветки
        addBordersToggleButton(menu, region);

        // Добавляем кнопку времени жизни
        addLifetimeButton(menu, region);

        // Добавляем кнопку временного расширения по высоте
        addHeightExpansionButton(menu, region);

        // Добавляем кнопку удаления
        addDeleteButton(menu, player, region);

        // Добавляем кнопку закрытия
        addCloseButton(menu);

        // Добавляем декоративные элементы если включены
        if (plugin.getConfig().getBoolean("menu.items.filler.enabled", true)) {
            addFillerItems(menu);
        }

        // Открываем меню игроку
        player.openInventory(menu);
        openMenus.put(player.getUniqueId(), region.getId());

        plugin.getLogger().info("Игрок " + player.getName() + " открыл меню региона " + region.getId());
    }

    /**
     * Добавляет кнопку расширения региона
     */
    private void addExpandButton(Inventory menu, ProtectedRegion region, int currentLevel,
                                 String currentSize, String nextSize, int maxLevel, double price) {
        int slot = plugin.getConfig().getInt("menu.items.expand.slot", 13);
        String materialName = plugin.getConfig().getString("menu.items.expand.material", "EMERALD");

        Material material;
        try {
            material = Material.valueOf(materialName);
        } catch (IllegalArgumentException e) {
            material = Material.EMERALD;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

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
        String materialName = plugin.getConfig().getString("menu.items.info.material", "BOOK");

        Material material;
        try {
            material = Material.valueOf(materialName);
        } catch (IllegalArgumentException e) {
            material = Material.BOOK;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

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
        boolean bordersEnabled = isRegionBordersEnabled(region.getId());

        String materialName = bordersEnabled ?
                plugin.getConfig().getString("menu.items.borders-toggle.material-enabled", "GLOWSTONE") :
                plugin.getConfig().getString("menu.items.borders-toggle.material-disabled", "REDSTONE_LAMP");

        Material material;
        try {
            material = Material.valueOf(materialName);
        } catch (IllegalArgumentException e) {
            material = bordersEnabled ? Material.GLOWSTONE : Material.REDSTONE_LAMP;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

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
     * Добавление кнопки времени жизни
     */
    private void addLifetimeButton(Inventory menu, ProtectedRegion region) {
        int slot = plugin.getConfig().getInt("menu.items.lifetime.slot", 24);

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

        Material material;
        try {
            material = Material.valueOf(materialName);
        } catch (IllegalArgumentException e) {
            material = hasTimer ? Material.CLOCK : Material.BARRIER;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
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
            return;
        }

        int slot = plugin.getConfig().getInt("menu.items.height-expansion.slot", 16);

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

        Material material;
        try {
            material = Material.valueOf(materialName);
        } catch (IllegalArgumentException e) {
            material = hasExpansion ? Material.ELYTRA : Material.FEATHER;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
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
        String materialName = plugin.getConfig().getString("menu.items.close.material", "BARRIER");

        Material material;
        try {
            material = Material.valueOf(materialName);
        } catch (IllegalArgumentException e) {
            material = Material.BARRIER;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

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
            return;
        }

        int slot = plugin.getConfig().getInt("menu.items.delete.slot", 22);
        String materialName = plugin.getConfig().getString("menu.items.delete.material", "TNT");

        Material material;
        try {
            material = Material.valueOf(materialName);
        } catch (IllegalArgumentException e) {
            material = Material.TNT;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

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

        Material material;
        try {
            material = Material.valueOf(materialName);
        } catch (IllegalArgumentException e) {
            material = Material.GRAY_STAINED_GLASS_PANE;
        }

        ItemStack filler = new ItemStack(material);
        ItemMeta meta = filler.getItemMeta();
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));
        filler.setItemMeta(meta);

        for (int slot : slots) {
            if (slot >= 0 && slot < menu.getSize()) {
                menu.setItem(slot, filler);
            }
        }
    }

    /**
     * Обработчик кликов с поддержкой всех кнопок
     */
    public boolean handleMenuClick(Player player, int slot, ItemStack clickedItem) {
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

        // Получаем слоты кнопок
        int expandSlot = plugin.getConfig().getInt("menu.items.expand.slot", 13);
        int closeSlot = plugin.getConfig().getInt("menu.items.close.slot", 15);
        int infoSlot = plugin.getConfig().getInt("menu.items.info.slot", 11);
        int deleteSlot = plugin.getConfig().getInt("menu.items.delete.slot", 22);
        int bordersToggleSlot = plugin.getConfig().getInt("menu.items.borders-toggle.slot", 20);
        int lifetimeSlot = plugin.getConfig().getInt("menu.items.lifetime.slot", 24);
        int heightExpansionSlot = plugin.getConfig().getInt("menu.items.height-expansion.slot", 16);

        if (slot == expandSlot) {
            handleExpandClick(player, region);
        } else if (slot == closeSlot) {
            clearPendingDeletion(player);
            player.closeInventory();
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
        }

        return true;
    }

    /**
     * Обработка клика по информационной кнопке
     */
    private void handleInfoClick(Player player, ProtectedRegion region) {
        String regionId = region.getId();

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
    }
    /**
     * Обработка клика по кнопке переключения подсветки
     */
    private void handleBordersToggleClick(Player player, ProtectedRegion region) {
        String regionId = region.getId();
        boolean currentState = isRegionBordersEnabled(regionId);
        boolean newState = !currentState;

        setRegionBordersEnabled(regionId, newState);

        if (newState) {
            plugin.getVisualizationManager().createRegionBorders(region, player.getWorld());
            String message = plugin.getConfig().getString("messages.borders-enabled",
                    "&a✅ Подсветка границ включена! Границы отмечены красной шерстью.");
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
        } else {
            plugin.getVisualizationManager().removeRegionBorders(regionId);
            String message = plugin.getConfig().getString("messages.borders-disabled",
                    "&e⚡ Подсветка границ выключена! Границы удалены.");
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
        }

        player.closeInventory();
        Bukkit.getScheduler().runTaskLater(plugin, () -> openRegionMenu(player, region), 1L);
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
            plugin.getRegionLifetimeMenu().openLifetimeMenu(player, region);
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
            plugin.getHeightExpansionMenu().openHeightExpansionMenu(player, region);
        } else {
            player.sendMessage(ChatColor.RED + "Меню расширения по высоте недоступно!");
        }
    }
    /**
     * Обработка расширения с проверкой коллизий
     */
    private void handleExpandClick(Player player, ProtectedRegion region) {
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
                if (updatedRegion != null) {
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
        try {
            org.bukkit.World world = findWorldForRegion(region.getId());
            if (world == null) return;

            Object regionManager = plugin.getProtectRegionManager().getWorldGuardRegionManager(world);
            if (regionManager == null) return;

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

            com.sk89q.worldedit.math.BlockVector3 newMin = com.sk89q.worldedit.math.BlockVector3.at(
                    centerX - radiusX, centerY - radiusY, centerZ - radiusZ);
            com.sk89q.worldedit.math.BlockVector3 newMax = com.sk89q.worldedit.math.BlockVector3.at(
                    centerX + radiusX, centerY + radiusY, centerZ + radiusZ);

            com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion testRegion =
                    new com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion("test", newMin, newMax);

            // Получаем все регионы
            java.lang.reflect.Method getRegionsMethod = regionManager.getClass().getMethod("getRegions");
            @SuppressWarnings("unchecked")
            java.util.Map<String, com.sk89q.worldguard.protection.regions.ProtectedRegion> regions =
                    (java.util.Map<String, com.sk89q.worldguard.protection.regions.ProtectedRegion>)
                            getRegionsMethod.invoke(regionManager);

            java.util.List<String> conflictingOwners = new java.util.ArrayList<>();
            for (com.sk89q.worldguard.protection.regions.ProtectedRegion existingRegion : regions.values()) {
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
        }
    }

    /**
     * Обработка клика по кнопке удаления с таймаутом
     */
    private void handleDeleteClick(Player player, ProtectedRegion region) {
        String regionId = region.getId();

        plugin.getLogger().info("DEBUG DELETE CLICK: Игрок " + player.getName() + " кликнул удаление региона " + regionId);

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
                if (timeoutTask != null) {
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
        String regionId = region.getId();
        String ownerName = getRegionOwnerName(region);

        plugin.getLogger().info("DEBUG DELETE: Начало удаления региона " + regionId + " владельца " + ownerName);

        try {
            org.bukkit.World regionWorld = null;
            Object regionManager = null;

            plugin.getLogger().info("DEBUG DELETE: Поиск мира для региона...");

            // Ищем мир региона
            for (org.bukkit.World world : plugin.getServer().getWorlds()) {
                Object rm = plugin.getProtectRegionManager().getWorldGuardRegionManager(world);
                if (rm != null) {
                    try {
                        java.lang.reflect.Method getRegionMethod = rm.getClass().getMethod("getRegion", String.class);
                        ProtectedRegion testRegion = (ProtectedRegion) getRegionMethod.invoke(rm, regionId);
                        if (testRegion != null) {
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

            // Удаляем визуализацию
            plugin.getVisualizationManager().removeRegionBorders(regionId);

            // Удаляем голограмму
            plugin.getHologramManager().removeHologram(regionId);

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
            regionBordersEnabled.remove(regionId);
            bordersStateConfig.set("regions." + regionId, null);
            saveBordersState();

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
    // Вспомогательные методы для удаления
    private void removeCenterBlockDirectly(ProtectedRegion region, org.bukkit.World world) {
        try {
            int centerX = (region.getMinimumPoint().x() + region.getMaximumPoint().x()) / 2;
            int centerY = (region.getMinimumPoint().y() + region.getMaximumPoint().y()) / 2;
            int centerZ = (region.getMinimumPoint().z() + region.getMaximumPoint().z()) / 2;

            org.bukkit.Location centerLoc = new org.bukkit.Location(world, centerX, centerY, centerZ);
            org.bukkit.block.Block centerBlock = centerLoc.getBlock();

            Material protectMaterial;
            try {
                protectMaterial = Material.valueOf(
                        plugin.getConfig().getString("protect-block.material", "DIAMOND_BLOCK"));
            } catch (IllegalArgumentException e) {
                protectMaterial = Material.DIAMOND_BLOCK;
            }

            if (centerBlock.getType() == protectMaterial) {
                centerBlock.setType(Material.AIR);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при удалении центрального блока: " + e.getMessage());
        }
    }

    private void giveProtectBlockBackDirectly(Player player, String ownerName) {
        try {
            Material blockType;
            try {
                blockType = Material.valueOf(
                        plugin.getConfig().getString("protect-block.material", "DIAMOND_BLOCK"));
            } catch (IllegalArgumentException e) {
                blockType = Material.DIAMOND_BLOCK;
            }

            ItemStack protectBlock = new ItemStack(blockType, 1);
            ItemMeta meta = protectBlock.getItemMeta();

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
            }

            protectBlock.setItemMeta(meta);

            if (player.getInventory().firstEmpty() != -1) {
                player.getInventory().addItem(protectBlock);
                player.sendMessage(ChatColor.GREEN + "Блок привата возвращен в инвентарь!");
            } else {
                player.getWorld().dropItemNaturally(player.getLocation(), protectBlock);
                player.sendMessage(ChatColor.YELLOW + "Блок привата выпал на землю - инвентарь полон!");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при возврате блока: " + e.getMessage());
        }
    }
    /**
     * Расширяет регион до указанного уровня
     */
    private boolean expandRegion(ProtectedRegion region, int level) {
        try {
            if (!(region instanceof com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion)) {
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
            int baseX, baseY, baseZ;

            if (hasHeightExpansion) {
                // Используем размеры из конфига как базовые
                baseX = plugin.getConfig().getInt("region-expansion.base-size.x", 3);
                baseY = plugin.getConfig().getInt("region-expansion.base-size.y", 3);
                baseZ = plugin.getConfig().getInt("region-expansion.base-size.z", 3);
                plugin.getLogger().info("DEBUG EXPAND: Базовые размеры из конфига: " + baseX + "x" + baseY + "x" + baseZ);
            } else {
                // Обычный регион - вычисляем текущие размеры
                baseX = plugin.getConfig().getInt("region-expansion.base-size.x", 3);
                baseY = plugin.getConfig().getInt("region-expansion.base-size.y", 3);
                baseZ = plugin.getConfig().getInt("region-expansion.base-size.z", 3);
                plugin.getLogger().info("DEBUG EXPAND: Обычный регион, базовые размеры: " + baseX + "x" + baseY + "x" + baseZ);
            }

            // Вычисляем новые размеры по ширине (X/Z)
            int newSizeX = baseX + (level * 2);
            int newSizeZ = baseZ + (level * 2);

            // Высота зависит от состояния расширения
            int newMinY, newMaxY;

            if (hasHeightExpansion) {
                // Регион расширен по высоте - сохраняем расширенную высоту
                org.bukkit.World world = findWorldForRegion(regionId);
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

            com.sk89q.worldedit.math.BlockVector3 newMin = com.sk89q.worldedit.math.BlockVector3.at(
                    centerX - radiusX, newMinY, centerZ - radiusZ);
            com.sk89q.worldedit.math.BlockVector3 newMax = com.sk89q.worldedit.math.BlockVector3.at(
                    centerX + radiusX, newMaxY, centerZ + radiusZ);

            plugin.getLogger().info("DEBUG EXPAND: Новые границы региона:");
            plugin.getLogger().info("DEBUG EXPAND: Центр: " + centerX + "," + centerZ);
            plugin.getLogger().info("DEBUG EXPAND: Новые размеры: " + newSizeX + "x" + (newMaxY - newMinY + 1) + "x" + newSizeZ);
            plugin.getLogger().info("DEBUG EXPAND: Новые границы: " + newMin + " -> " + newMax);

            // Создаем новый регион с новыми размерами
            com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion newRegion =
                    new com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion(
                            region.getId(), newMin, newMax);

            // Копируем ВСЕ параметры региона
            newRegion.setOwners(region.getOwners());
            newRegion.setMembers(region.getMembers());
            newRegion.setFlags(region.getFlags());
            newRegion.setPriority(region.getPriority());

            org.bukkit.World world = findWorldForRegion(region.getId());
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
                        .getMethod("addRegion", com.sk89q.worldguard.protection.regions.ProtectedRegion.class);
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
                if (hadBordersEnabled) {
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
                            .getMethod("addRegion", com.sk89q.worldguard.protection.regions.ProtectedRegion.class);
                    addRegionMethod.invoke(regionManager, region);

                    java.lang.reflect.Method saveMethod = regionManager.getClass().getMethod("save");
                    saveMethod.invoke(regionManager);

                    plugin.getLogger().info("DEBUG EXPAND: Оригинальный регион восстановлен после ошибки");
                } catch (Exception restoreEx) {
                    plugin.getLogger().severe("DEBUG EXPAND: КРИТИЧЕСКАЯ ОШИБКА: Не удалось восстановить оригинальный регион: " + restoreEx.getMessage());
                }

                return false;
            }

        } catch (Exception e) {
            plugin.getLogger().severe("DEBUG EXPAND: Ошибка при расширении региона: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
    /**
     * Вспомогательный метод для получения планируемого размера
     */
    private String getPlannedRegionSizeString(ProtectedRegion region, int newLevel) {
        int baseX = plugin.getConfig().getInt("region-expansion.base-size.x", 3);
        int baseY = plugin.getConfig().getInt("region-expansion.base-size.y", 3);
        int baseZ = plugin.getConfig().getInt("region-expansion.base-size.z", 3);

        int newSizeX = baseX + (newLevel * 2);
        int newSizeY = baseY + (newLevel * 2);
        int newSizeZ = baseZ + (newLevel * 2);

        return newSizeX + "x" + newSizeY + "x" + newSizeZ;
    }

    /**
     * Вспомогательные методы для проверки пересечений и владельцев
     */
    private boolean hasRegionIntersection(com.sk89q.worldguard.protection.regions.ProtectedRegion region1,
                                          com.sk89q.worldguard.protection.regions.ProtectedRegion region2) {
        com.sk89q.worldedit.math.BlockVector3 min1 = region1.getMinimumPoint();
        com.sk89q.worldedit.math.BlockVector3 max1 = region1.getMaximumPoint();
        com.sk89q.worldedit.math.BlockVector3 min2 = region2.getMinimumPoint();
        com.sk89q.worldedit.math.BlockVector3 max2 = region2.getMaximumPoint();

        return !(max1.x() < min2.x() || min1.x() > max2.x() ||
                max1.y() < min2.y() || min1.y() > max2.y() ||
                max1.z() < min2.z() || min1.z() > max2.z());
    }

    private String getRegionOwnerName(com.sk89q.worldguard.protection.regions.ProtectedRegion region) {
        if (!region.getOwners().getUniqueIds().isEmpty()) {
            java.util.UUID ownerUUID = region.getOwners().getUniqueIds().iterator().next();
            String ownerName = plugin.getServer().getOfflinePlayer(ownerUUID).getName();
            return ownerName != null ? ownerName : "Неизвестно";
        }
        if (!region.getOwners().getPlayers().isEmpty()) {
            return region.getOwners().getPlayers().iterator().next();
        }
        return "Неизвестно";
    }

    private boolean isPlayerOwner(com.sk89q.worldguard.protection.regions.ProtectedRegion region, String playerName) {
        java.util.UUID playerUUID = getPlayerUUID(playerName);
        if (playerUUID == null) {
            return false;
        }
        return region.getOwners().contains(playerUUID) || region.getOwners().contains(playerName);
    }

    private java.util.UUID getPlayerUUID(String playerName) {
        try {
            org.bukkit.entity.Player onlinePlayer = plugin.getServer().getPlayer(playerName);
            if (onlinePlayer != null) {
                return onlinePlayer.getUniqueId();
            } else {
                return plugin.getServer().getOfflinePlayer(playerName).getUniqueId();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private String formatPrice(double price) {
        return price == (long) price ? String.valueOf((long) price) : String.valueOf(price);
    }

    private int getRegionExpansionLevel(ProtectedRegion region) {
        int baseX = plugin.getConfig().getInt("region-expansion.base-size.x", 3);
        int baseY = plugin.getConfig().getInt("region-expansion.base-size.y", 3);
        int baseZ = plugin.getConfig().getInt("region-expansion.base-size.z", 3);

        int currentX = region.getMaximumPoint().x() - region.getMinimumPoint().x() + 1;
        int currentY = region.getMaximumPoint().y() - region.getMinimumPoint().y() + 1;
        int currentZ = region.getMaximumPoint().z() - region.getMinimumPoint().z() + 1;

        int levelX = (currentX - baseX) / 2;
        int levelY = (currentY - baseY) / 2;
        int levelZ = (currentZ - baseZ) / 2;

        return Math.max(0, Math.min(Math.min(levelX, levelY), levelZ));
    }

    private double getExpansionPrice(int level) {
        return plugin.getConfig().getDouble("region-expansion.prices." + level, -1);
    }

    private String getCurrentRegionSizeString(ProtectedRegion region) {
        int sizeX = region.getMaximumPoint().x() - region.getMinimumPoint().x() + 1;
        int sizeY = region.getMaximumPoint().y() - region.getMinimumPoint().y() + 1;
        int sizeZ = region.getMaximumPoint().z() - region.getMinimumPoint().z() + 1;
        return sizeX + "x" + sizeY + "x" + sizeZ;
    }

    private String getNextRegionSizeString(ProtectedRegion region, int currentLevel) {
        int maxLevel = plugin.getConfig().getInt("region-expansion.max-level", 10);
        if (currentLevel >= maxLevel) return "Максимум";

        int baseX = plugin.getConfig().getInt("region-expansion.base-size.x", 3);
        int baseY = plugin.getConfig().getInt("region-expansion.base-size.y", 3);
        int baseZ = plugin.getConfig().getInt("region-expansion.base-size.z", 3);

        int nextLevel = currentLevel + 1;
        int nextSizeX = baseX + (nextLevel * 2);
        int nextSizeY = baseY + (nextLevel * 2);
        int nextSizeZ = baseZ + (nextLevel * 2);

        return nextSizeX + "x" + nextSizeY + "x" + nextSizeZ;
    }

    private boolean canPlayerAccessRegion(Player player, ProtectedRegion region) {
        return region.getOwners().contains(player.getUniqueId()) ||
                region.getOwners().contains(player.getName()) ||
                region.getMembers().contains(player.getUniqueId()) ||
                region.getMembers().contains(player.getName()) ||
                player.hasPermission("rgprotect.admin");
    }

    private boolean canPlayerDeleteRegion(Player player, ProtectedRegion region) {
        return player.hasPermission("rgprotect.admin") ||
                region.getOwners().contains(player.getUniqueId()) ||
                region.getOwners().contains(player.getName());
    }

    private ProtectedRegion findRegionById(String regionId) {
        for (org.bukkit.World world : plugin.getServer().getWorlds()) {
            try {
                Object regionManager = plugin.getProtectRegionManager().getWorldGuardRegionManager(world);
                if (regionManager != null) {
                    java.lang.reflect.Method getRegionMethod = regionManager.getClass().getMethod("getRegion", String.class);
                    ProtectedRegion region = (ProtectedRegion) getRegionMethod.invoke(regionManager, regionId);
                    if (region != null) return region;
                }
            } catch (Exception e) {
                // Игнорируем ошибки
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
                    if (region != null) return world;
                }
            } catch (Exception e) {
                // Игнорируем ошибки
            }
        }
        return null;
    }

    // Публичные методы для управления меню
    public void closeMenuForPlayer(Player player) {
        openMenus.remove(player.getUniqueId());
        plugin.getLogger().info("DEBUG CLOSE: Игрок " + player.getName() + " убран из openMenus");
        plugin.getLogger().info("DEBUG CLOSE: Ожидающее удаление сохранено: " + pendingDeletions.containsKey(player.getUniqueId()));
    }

    public boolean hasOpenMenu(Player player) {
        return openMenus.containsKey(player.getUniqueId());
    }

    public String getOpenMenuRegionId(Player player) {
        return openMenus.get(player.getUniqueId());
    }

    public boolean hasPendingDeletion(Player player) {
        boolean result = pendingDeletions.containsKey(player.getUniqueId());
        plugin.getLogger().info("DEBUG PENDING CHECK: Игрок " + player.getName() + " имеет ожидающее удаление: " + result);
        return result;
    }

    /**
     * Метод для принудительной очистки ожидающего удаления с отменой таймаута
     */
    public void clearPendingDeletion(Player player) {
        String regionId = pendingDeletions.remove(player.getUniqueId());

        // Отменяем таймаут
        BukkitTask timeoutTask = pendingDeletionTimeouts.remove(player.getUniqueId());
        if (timeoutTask != null) {
            timeoutTask.cancel();
            plugin.getLogger().info("DEBUG CLEAR: Отменен таймаут для игрока " + player.getName());
        }

        if (regionId != null) {
            plugin.getLogger().info("DEBUG CLEAR: Очищено ожидающее удаление региона " + regionId + " для игрока " + player.getName());
        } else {
            plugin.getLogger().info("DEBUG CLEAR: У игрока " + player.getName() + " не было ожидающего удаления для очистки");
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
    }
}