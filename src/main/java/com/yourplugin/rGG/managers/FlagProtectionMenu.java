package com.yourplugin.rGG.managers;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.ChatColor;

import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.yourplugin.rGG.RGProtectPlugin;

import java.util.*;

public class FlagProtectionMenu {

    private final RGProtectPlugin plugin;
    private final Map<UUID, String> openFlagMenus;
    // Для обработки покупки флагов
    private final Map<UUID, FlagPurchaseData> pendingFlagPurchases;

    public FlagProtectionMenu(RGProtectPlugin plugin) {
        this.plugin = plugin;
        this.openFlagMenus = new HashMap<>();
        this.pendingFlagPurchases = new HashMap<>();
    }

    /**
     * Класс для хранения данных о покупке флага
     */
    public static class FlagPurchaseData {
        public String regionId;
        public String flagName;
        public long durationSeconds;
        public double cost;
        public long expirationTime; // Время истечения запроса

        public FlagPurchaseData(String regionId, String flagName, long durationSeconds, double cost) {
            this.regionId = regionId;
            this.flagName = flagName;
            this.durationSeconds = durationSeconds;
            this.cost = cost;
            this.expirationTime = System.currentTimeMillis() + 15000; // 15 секунд
        }
    }

    /**
     * Открывает меню защиты региона
     */
    public void openFlagProtectionMenu(Player player, ProtectedRegion region) {
        String regionId = region.getId();

        // Создаем инвентарь
        String title = ChatColor.translateAlternateColorCodes('&',
                plugin.getConfig().getString("flag-protection-menu.title", "&6&lЗащита региона"));
        int size = plugin.getConfig().getInt("flag-protection-menu.size", 54);

        Inventory menu = Bukkit.createInventory(null, size, title);

        // Добавляем информационную кнопку
        addInfoButton(menu, region);

        // Добавляем кнопки флагов из конфига
        addFlagButtons(menu, player, region);

        // Добавляем кнопку возврата
        addBackButton(menu);

        // Добавляем декоративные элементы
        if (plugin.getConfig().getBoolean("flag-protection-menu.items.filler.enabled", true)) {
            addFillerItems(menu);
        }

        // Открываем меню
        player.openInventory(menu);
        openFlagMenus.put(player.getUniqueId(), regionId);

        plugin.getLogger().info("Игрок " + player.getName() + " открыл меню защиты региона " + regionId);
    }

    /**
     * Добавление информационной кнопки
     */
    private void addInfoButton(Inventory menu, ProtectedRegion region) {
        int slot = plugin.getConfig().getInt("flag-protection-menu.items.info.slot", 4);
        String materialName = plugin.getConfig().getString("flag-protection-menu.items.info.material", "SHIELD");

        Material material;
        try {
            material = Material.valueOf(materialName);
        } catch (IllegalArgumentException e) {
            material = Material.SHIELD;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        String name = plugin.getConfig().getString("flag-protection-menu.items.info.name", "&b&lЗащита региона");
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));

        List<String> lore = new ArrayList<>();
        List<String> configLore = plugin.getConfig().getStringList("flag-protection-menu.items.info.lore");

        for (String line : configLore) {
            String processedLine = line.replace("{region}", region.getId());
            lore.add(ChatColor.translateAlternateColorCodes('&', processedLine));
        }

        meta.setLore(lore);
        item.setItemMeta(meta);
        menu.setItem(slot, item);
    }

    /**
     * Добавление кнопок флагов
     */
    private void addFlagButtons(Inventory menu, Player player, ProtectedRegion region) {
        String regionId = region.getId();

        if (!plugin.getConfig().contains("flag-protection.flags")) {
            plugin.getLogger().warning("Флаги не настроены в конфиге!");
            return;
        }

        // Загружаем кнопки флагов из конфига
        for (String flagKey : plugin.getConfig().getConfigurationSection("flag-protection.flags").getKeys(false)) {
            String path = "flag-protection.flags." + flagKey;

            int slot = plugin.getConfig().getInt(path + ".slot");
            String materialName = plugin.getConfig().getString(path + ".material", "BARRIER");
            String flagName = plugin.getConfig().getString(path + ".name", flagKey);
            List<String> lore = plugin.getConfig().getStringList(path + ".lore");
            double pricePerHour = plugin.getConfig().getDouble(path + ".price-per-hour", 1000.0);

            Material material;
            try {
                material = Material.valueOf(materialName);
            } catch (IllegalArgumentException e) {
                material = Material.BARRIER;
            }

            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();

            // Проверяем активность флага
            boolean isActive = plugin.getFlagProtectionManager().isFlagActive(regionId, flagKey);
            String remainingTime = plugin.getFlagProtectionManager().getFormattedRemainingTime(regionId, flagKey);

            // Форматируем название
            String displayName = flagName;
            if (isActive) {
                displayName = ChatColor.GREEN + "✅ " + flagName + " (Активен)";
            } else {
                displayName = ChatColor.GRAY + "❌ " + flagName + " (Неактивен)";
            }
            meta.setDisplayName(displayName);

            // Форматируем описание
            List<String> formattedLore = new ArrayList<>();
            for (String line : lore) {
                String formattedLine = line
                        .replace("{price}", formatPrice(pricePerHour))
                        .replace("{status}", isActive ? "Активен" : "Неактивен")
                        .replace("{remaining_time}", isActive ? remainingTime : "");
                formattedLore.add(ChatColor.translateAlternateColorCodes('&', formattedLine));
            }

            if (isActive) {
                formattedLore.add("");
                formattedLore.add(ChatColor.GREEN + "⏰ Осталось: " + remainingTime);
                formattedLore.add("");
                formattedLore.add(ChatColor.YELLOW + "Нажмите для продления!");
            } else {
                formattedLore.add("");
                formattedLore.add(ChatColor.GRAY + "💰 Цена: " + formatPrice(pricePerHour) + "/час");
                formattedLore.add(ChatColor.GRAY + "⏱ Минимум: 5 минут");
                formattedLore.add("");
                formattedLore.add(ChatColor.YELLOW + "Нажмите для активации!");
            }

            meta.setLore(formattedLore);
            item.setItemMeta(meta);
            menu.setItem(slot, item);
        }
    }

    /**
     * Добавление кнопки возврата
     */
    private void addBackButton(Inventory menu) {
        int slot = plugin.getConfig().getInt("flag-protection-menu.items.back.slot", 49);
        String materialName = plugin.getConfig().getString("flag-protection-menu.items.back.material", "ARROW");

        Material material;
        try {
            material = Material.valueOf(materialName);
        } catch (IllegalArgumentException e) {
            material = Material.ARROW;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        String name = plugin.getConfig().getString("flag-protection-menu.items.back.name", "&c&lНазад");
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));

        List<String> lore = new ArrayList<>();
        List<String> configLore = plugin.getConfig().getStringList("flag-protection-menu.items.back.lore");

        for (String line : configLore) {
            lore.add(ChatColor.translateAlternateColorCodes('&', line));
        }

        meta.setLore(lore);
        item.setItemMeta(meta);
        menu.setItem(slot, item);
    }

    /**
     * Добавление декоративных элементов
     */
    private void addFillerItems(Inventory menu) {
        String materialName = plugin.getConfig().getString("flag-protection-menu.items.filler.material", "BLACK_STAINED_GLASS_PANE");
        String name = plugin.getConfig().getString("flag-protection-menu.items.filler.name", "&r");
        List<Integer> slots = plugin.getConfig().getIntegerList("flag-protection-menu.items.filler.slots");

        Material material;
        try {
            material = Material.valueOf(materialName);
        } catch (IllegalArgumentException e) {
            material = Material.BLACK_STAINED_GLASS_PANE;
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
     * Обработка клика в меню
     */
    public boolean handleMenuClick(Player player, int slot, ItemStack clickedItem) {
        String regionId = openFlagMenus.get(player.getUniqueId());
        if (regionId == null) {
            return false;
        }

        // Находим регион
        ProtectedRegion region = findRegionById(regionId);
        if (region == null) {
            player.sendMessage(ChatColor.RED + "Регион не найден!");
            player.closeInventory();
            return true;
        }

        // Проверяем слот кнопки возврата
        int backSlot = plugin.getConfig().getInt("flag-protection-menu.items.back.slot", 49);
        if (slot == backSlot) {
            // Возвращаемся в основное меню
            player.closeInventory();
            plugin.getRegionMenuManager().openRegionMenu(player, region);
            return true;
        }

        // Проверяем слоты кнопок флагов
        if (plugin.getConfig().contains("flag-protection.flags")) {
            for (String flagKey : plugin.getConfig().getConfigurationSection("flag-protection.flags").getKeys(false)) {
                String path = "flag-protection.flags." + flagKey;
                int buttonSlot = plugin.getConfig().getInt(path + ".slot");

                if (slot == buttonSlot) {
                    handleFlagClick(player, region, flagKey);
                    return true;
                }
            }
        }

        return true; // Блокируем все клики в меню
    }

    /**
     * Обработка клика по флагу
     */
    private void handleFlagClick(Player player, ProtectedRegion region, String flagKey) {
        String regionId = region.getId();
        String flagName = plugin.getConfig().getString("flag-protection.flags." + flagKey + ".name", flagKey);

        // Проверяем права доступа
        if (!canPlayerManageFlags(player, region)) {
            player.sendMessage(ChatColor.RED + "У вас нет прав на управление флагами этого региона!");
            return;
        }

        player.closeInventory();

        // Отправляем инструкции по вводу времени
        player.sendMessage("");
        player.sendMessage(ChatColor.GOLD + "=== АРЕНДА ФЛАГА ===");
        player.sendMessage(ChatColor.YELLOW + "Флаг: " + ChatColor.WHITE + flagName);
        player.sendMessage("");
        player.sendMessage(ChatColor.GREEN + "Введите время аренды в чат:");
        player.sendMessage(ChatColor.GRAY + "Формат: 1ч3м2с (1 час 3 минуты 2 секунды)");
        player.sendMessage(ChatColor.GRAY + "Примеры: 1ч, 30м, 1ч30м, 2ч15м30с");
        player.sendMessage("");
        player.sendMessage(ChatColor.YELLOW + "Минимальное время: " + ChatColor.WHITE + "5 минут");

        double pricePerHour = plugin.getConfig().getDouble("flag-protection.flags." + flagKey + ".price-per-hour", 1000.0);
        player.sendMessage(ChatColor.YELLOW + "Цена: " + ChatColor.WHITE + formatPrice(pricePerHour) + " за час");
        player.sendMessage("");
        player.sendMessage(ChatColor.GRAY + "У вас есть 30 секунд для ввода времени.");

        // Сохраняем состояние для обработки в чате
        FlagPurchaseData purchaseData = new FlagPurchaseData(regionId, flagKey, 0, 0);
        purchaseData.expirationTime = System.currentTimeMillis() + 30000; // 30 секунд на ввод времени
        pendingFlagPurchases.put(player.getUniqueId(), purchaseData);

        plugin.getLogger().info("Игрок " + player.getName() + " начал процесс покупки флага " + flagKey);
    }

    /**
     * Обработка сообщения в чате для покупки флага
     */
    public boolean handleFlagPurchaseChat(Player player, String message) {
        FlagPurchaseData purchaseData = pendingFlagPurchases.get(player.getUniqueId());
        if (purchaseData == null) {
            return false;
        }

        // Проверяем таймаут
        if (System.currentTimeMillis() > purchaseData.expirationTime) {
            pendingFlagPurchases.remove(player.getUniqueId());
            player.sendMessage(ChatColor.RED + "⏰ Время ожидания истекло. Операция отменена.");
            return true;
        }

        message = message.trim();

        // Этап 1: Ввод времени
        if (purchaseData.durationSeconds == 0) {
            return handleTimeInput(player, message, purchaseData);
        }
        // Этап 2: Подтверждение покупки
        else {
            return handlePurchaseConfirmation(player, message, purchaseData);
        }
    }

    /**
     * Обработка ввода времени
     */
    private boolean handleTimeInput(Player player, String message, FlagPurchaseData purchaseData) {
        long durationSeconds = plugin.getFlagProtectionManager().parseTimeString(message);

        if (durationSeconds == -1) {
            player.sendMessage(ChatColor.RED + "❌ Неверный формат времени!");
            player.sendMessage(ChatColor.YELLOW + "Попробуйте еще раз. Пример: 1ч30м");
            player.sendMessage(ChatColor.GRAY + "Минимум: 5 минут (5м)");
            return true;
        }

        // Рассчитываем стоимость
        double cost = plugin.getFlagProtectionManager().calculateFlagCost(purchaseData.flagName, durationSeconds);
        String timeFormatted = plugin.getFlagProtectionManager().formatTime(durationSeconds);
        String flagDisplayName = plugin.getConfig().getString("flag-protection.flags." + purchaseData.flagName + ".name", purchaseData.flagName);

        // Обновляем данные покупки
        purchaseData.durationSeconds = durationSeconds;
        purchaseData.cost = cost;
        purchaseData.expirationTime = System.currentTimeMillis() + 15000; // 15 секунд на подтверждение

        // Отправляем подтверждение
        player.sendMessage("");
        player.sendMessage(ChatColor.GOLD + "=== ПОДТВЕРЖДЕНИЕ ПОКУПКИ ===");
        player.sendMessage(ChatColor.YELLOW + "Флаг: " + ChatColor.WHITE + flagDisplayName);
        player.sendMessage(ChatColor.YELLOW + "Время аренды: " + ChatColor.WHITE + timeFormatted);
        player.sendMessage(ChatColor.YELLOW + "Стоимость: " + ChatColor.WHITE + formatPrice(cost) + " монет");
        player.sendMessage("");

        // Проверяем баланс
        if (plugin.getEconomy() != null) {
            double balance = plugin.getEconomy().getBalance(player);
            if (balance < cost) {
                player.sendMessage(ChatColor.RED + "❌ Недостаточно денег!");
                player.sendMessage(ChatColor.YELLOW + "У вас: " + ChatColor.WHITE + formatPrice(balance) + " монет");
                player.sendMessage(ChatColor.YELLOW + "Нужно: " + ChatColor.WHITE + formatPrice(cost) + " монет");
                pendingFlagPurchases.remove(player.getUniqueId());
                return true;
            }
        }

        player.sendMessage(ChatColor.GREEN + "Согласны на покупку?");
        player.sendMessage(ChatColor.WHITE + "ДА" + ChatColor.GRAY + " - подтвердить покупку");
        player.sendMessage(ChatColor.WHITE + "НЕТ" + ChatColor.GRAY + " - отменить покупку");
        player.sendMessage("");
        player.sendMessage(ChatColor.GRAY + "У вас есть 15 секунд для ответа.");

        return true;
    }

    /**
     * Обработка подтверждения покупки
     */
    private boolean handlePurchaseConfirmation(Player player, String message, FlagPurchaseData purchaseData) {
        String cleanMessage = message.trim().toUpperCase();

        if (cleanMessage.equals("ДА") || cleanMessage.equals("YES") || cleanMessage.equals("Y")) {
            return processFlagPurchase(player, purchaseData);
        } else if (cleanMessage.equals("НЕТ") || cleanMessage.equals("NO") || cleanMessage.equals("N")) {
            player.sendMessage(ChatColor.YELLOW + "Покупка отменена.");
            pendingFlagPurchases.remove(player.getUniqueId());
            return true;
        } else {
            player.sendMessage(ChatColor.RED + "Напишите ДА или НЕТ для подтверждения.");
            return true;
        }
    }

    /**
     * Обработка покупки флага
     */
    private boolean processFlagPurchase(Player player, FlagPurchaseData purchaseData) {
        try {
            // Проверяем экономику
            if (plugin.getEconomy() == null) {
                player.sendMessage(ChatColor.RED + "Экономика не настроена!");
                pendingFlagPurchases.remove(player.getUniqueId());
                return true;
            }

            // Списываем деньги
            net.milkbowl.vault.economy.EconomyResponse response = plugin.getEconomy().withdrawPlayer(player, purchaseData.cost);
            if (!response.transactionSuccess()) {
                player.sendMessage(ChatColor.RED + "Ошибка при списании денег: " + response.errorMessage);
                pendingFlagPurchases.remove(player.getUniqueId());
                return true;
            }

            // Активируем флаг
            boolean success = plugin.getFlagProtectionManager().activateFlag(
                    purchaseData.regionId,
                    purchaseData.flagName,
                    purchaseData.durationSeconds,
                    player.getName()
            );

            if (success) {
                String flagDisplayName = plugin.getConfig().getString("flag-protection.flags." + purchaseData.flagName + ".name", purchaseData.flagName);
                String timeFormatted = plugin.getFlagProtectionManager().formatTime(purchaseData.durationSeconds);

                player.sendMessage("");
                player.sendMessage(ChatColor.GREEN + "✅ Флаг успешно активирован!");
                player.sendMessage(ChatColor.YELLOW + "Флаг: " + ChatColor.WHITE + flagDisplayName);
                player.sendMessage(ChatColor.YELLOW + "Время: " + ChatColor.WHITE + timeFormatted);
                player.sendMessage(ChatColor.YELLOW + "Списано: " + ChatColor.WHITE + formatPrice(purchaseData.cost) + " монет");
                player.sendMessage("");

                // Звуковой эффект
                player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);

                plugin.getLogger().info("Игрок " + player.getName() + " активировал флаг " + purchaseData.flagName +
                        " для региона " + purchaseData.regionId + " на " + timeFormatted);
            } else {
                // Возвращаем деньги при ошибке
                plugin.getEconomy().depositPlayer(player, purchaseData.cost);
                player.sendMessage(ChatColor.RED + "Ошибка при активации флага!");
                player.sendMessage(ChatColor.YELLOW + "Деньги возвращены: " + formatPrice(purchaseData.cost) + " монет");
            }

            pendingFlagPurchases.remove(player.getUniqueId());
            return true;

        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка при покупке флага: " + e.getMessage());
            e.printStackTrace();

            // Возвращаем деньги при ошибке
            if (plugin.getEconomy() != null) {
                plugin.getEconomy().depositPlayer(player, purchaseData.cost);
            }

            player.sendMessage(ChatColor.RED + "Произошла ошибка при покупке флага!");
            pendingFlagPurchases.remove(player.getUniqueId());
            return true;
        }
    }

    /**
     * Проверка таймаутов покупок
     */
    public void checkPurchaseTimeouts() {
        long currentTime = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, FlagPurchaseData>> iterator = pendingFlagPurchases.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<UUID, FlagPurchaseData> entry = iterator.next();
            FlagPurchaseData purchaseData = entry.getValue();

            if (currentTime > purchaseData.expirationTime) {
                Player player = plugin.getServer().getPlayer(entry.getKey());
                if (player != null && player.isOnline()) {
                    player.sendMessage(ChatColor.RED + "⏰ Время ожидания истекло. Операция отменена.");
                }
                iterator.remove();
            }
        }
    }

    /**
     * Проверка прав на управление флагами
     */
    private boolean canPlayerManageFlags(Player player, ProtectedRegion region) {
        return player.hasPermission("rgprotect.admin") ||
                region.getOwners().contains(player.getUniqueId()) ||
                region.getOwners().contains(player.getName());
    }

    /**
     * Форматирование цены
     */
    private String formatPrice(double price) {
        if (price == (long) price) {
            return String.valueOf((long) price);
        } else {
            return String.valueOf(price);
        }
    }

    /**
     * Закрытие меню для игрока
     */
    public void closeMenuForPlayer(Player player) {
        openFlagMenus.remove(player.getUniqueId());
    }

    /**
     * Проверка, открыто ли меню у игрока
     */
    public boolean hasOpenMenu(Player player) {
        return openFlagMenus.containsKey(player.getUniqueId());
    }

    /**
     * Получение ID региона для открытого меню
     */
    public String getOpenMenuRegionId(Player player) {
        return openFlagMenus.get(player.getUniqueId());
    }

    /**
     * Проверка, есть ли ожидающая покупка флага
     */
    public boolean hasPendingFlagPurchase(Player player) {
        return pendingFlagPurchases.containsKey(player.getUniqueId());
    }

    /**
     * Очистка ожидающей покупки
     */
    public void clearPendingFlagPurchase(Player player) {
        pendingFlagPurchases.remove(player.getUniqueId());
    }

    // Вспомогательные методы

    private ProtectedRegion findRegionById(String regionId) {
        for (org.bukkit.World world : plugin.getServer().getWorlds()) {
            try {
                com.sk89q.worldguard.protection.managers.RegionManager regionManager =
                        plugin.getProtectRegionManager().getWorldGuardRegionManager(world);
                if (regionManager != null) {
                    return regionManager.getRegion(regionId);
                }
            } catch (Exception e) {
                // Игнорируем
            }
        }
        return null;
    }
}