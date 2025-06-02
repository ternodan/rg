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

public class RegionLifetimeMenu {

    private final RGProtectPlugin plugin;
    private final Map<UUID, String> openLifetimeMenus;

    public RegionLifetimeMenu(RGProtectPlugin plugin) {
        this.plugin = plugin;
        this.openLifetimeMenus = new HashMap<>();
    }

    /**
     * Открывает меню продления времени жизни
     */
    public void openLifetimeMenu(Player player, ProtectedRegion region) {
        String regionId = region.getId();

        // Проверяем, есть ли таймер у региона
        if (!plugin.getRegionTimerManager().hasTimer(regionId)) {
            player.sendMessage(ChatColor.RED + "У этого региона нет активного таймера!");
            return;
        }

        // Создаем инвентарь
        String title = ChatColor.translateAlternateColorCodes('&',
                plugin.getConfig().getString("lifetime-menu.title", "&5&lПродление времени жизни"));
        int size = plugin.getConfig().getInt("lifetime-menu.size", 27);

        Inventory menu = Bukkit.createInventory(null, size, title);

        // Получаем оставшееся время
        String formattedTime = plugin.getRegionTimerManager().getFormattedRemainingTime(regionId);

        // Добавляем информационную кнопку
        addInfoButton(menu, region, formattedTime);

        // Добавляем кнопки продления времени из конфига
        addTimeExtensionButtons(menu, player, region);

        // Добавляем кнопку возврата
        addBackButton(menu);

        // Добавляем декоративные элементы
        if (plugin.getConfig().getBoolean("lifetime-menu.items.filler.enabled", true)) {
            addFillerItems(menu);
        }

        // Открываем меню
        player.openInventory(menu);
        openLifetimeMenus.put(player.getUniqueId(), regionId);

        plugin.getLogger().info("Игрок " + player.getName() + " открыл меню времени жизни региона " + regionId);
    }

    /**
     * Добавление информационной кнопки
     */
    private void addInfoButton(Inventory menu, ProtectedRegion region, String remainingTime) {
        int slot = plugin.getConfig().getInt("lifetime-menu.items.info.slot", 4);
        String materialName = plugin.getConfig().getString("lifetime-menu.items.info.material", "CLOCK");

        Material material;
        try {
            material = Material.valueOf(materialName);
        } catch (IllegalArgumentException e) {
            material = Material.CLOCK;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        String name = plugin.getConfig().getString("lifetime-menu.items.info.name", "&b&lВремя жизни региона");
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));

        List<String> lore = new ArrayList<>();
        List<String> configLore = plugin.getConfig().getStringList("lifetime-menu.items.info.lore");

        for (String line : configLore) {
            String processedLine = line
                    .replace("{time}", remainingTime)
                    .replace("{region}", region.getId());
            lore.add(ChatColor.translateAlternateColorCodes('&', processedLine));
        }

        meta.setLore(lore);
        item.setItemMeta(meta);
        menu.setItem(slot, item);
    }

    /**
     * Добавление кнопок продления времени
     */
    private void addTimeExtensionButtons(Inventory menu, Player player, ProtectedRegion region) {
        // Получаем список кнопок из конфига
        if (!plugin.getConfig().contains("lifetime-menu.time-options")) {
            // Если в конфиге нет настроек, используем стандартные
            addDefaultTimeButton(menu, 11, 60, 1000, Material.IRON_INGOT);
            addDefaultTimeButton(menu, 13, 180, 2500, Material.GOLD_INGOT);
            addDefaultTimeButton(menu, 15, 360, 5000, Material.DIAMOND);
            return;
        }

        // Загружаем кнопки из конфига
        for (String key : plugin.getConfig().getConfigurationSection("lifetime-menu.time-options").getKeys(false)) {
            String path = "lifetime-menu.time-options." + key;

            int slot = plugin.getConfig().getInt(path + ".slot");
            int minutes = plugin.getConfig().getInt(path + ".minutes");
            double price = plugin.getConfig().getDouble(path + ".price");
            String materialName = plugin.getConfig().getString(path + ".material", "IRON_INGOT");
            String displayName = plugin.getConfig().getString(path + ".name", "&a+{minutes} минут");
            List<String> lore = plugin.getConfig().getStringList(path + ".lore");

            Material material;
            try {
                material = Material.valueOf(materialName);
            } catch (IllegalArgumentException e) {
                material = Material.IRON_INGOT;
            }

            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();

            // Форматируем название
            String formattedName = displayName
                    .replace("{minutes}", String.valueOf(minutes))
                    .replace("{hours}", String.valueOf(minutes / 60))
                    .replace("{price}", formatPrice(price));
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', formattedName));

            // Форматируем описание
            List<String> formattedLore = new ArrayList<>();
            for (String line : lore) {
                String formattedLine = line
                        .replace("{minutes}", String.valueOf(minutes))
                        .replace("{hours}", String.valueOf(minutes / 60))
                        .replace("{price}", formatPrice(price));
                formattedLore.add(ChatColor.translateAlternateColorCodes('&', formattedLine));
            }

            // Проверяем, может ли игрок позволить себе это
            if (plugin.getEconomy() != null) {
                double balance = plugin.getEconomy().getBalance(player);
                if (balance < price) {
                    formattedLore.add("");
                    formattedLore.add(ChatColor.RED + "Недостаточно денег!");
                    item.setType(Material.BARRIER);
                }
            }

            meta.setLore(formattedLore);
            item.setItemMeta(meta);
            menu.setItem(slot, item);
        }
    }

    /**
     * Добавление стандартной кнопки времени
     */
    private void addDefaultTimeButton(Inventory menu, int slot, int minutes, double price, Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        String hours = minutes >= 60 ? (minutes / 60) + " час" : "";
        String displayTime = hours.isEmpty() ? minutes + " минут" : hours;

        meta.setDisplayName(ChatColor.GREEN + "+" + displayTime);

        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add(ChatColor.GRAY + "Продлить время жизни региона");
        lore.add(ChatColor.GRAY + "на " + ChatColor.YELLOW + displayTime);
        lore.add("");
        lore.add(ChatColor.GRAY + "Цена: " + ChatColor.GOLD + formatPrice(price) + " монет");
        lore.add("");
        lore.add(ChatColor.YELLOW + "Нажмите для покупки!");

        meta.setLore(lore);
        item.setItemMeta(meta);
        menu.setItem(slot, item);
    }

    /**
     * Добавление кнопки возврата
     */
    private void addBackButton(Inventory menu) {
        int slot = plugin.getConfig().getInt("lifetime-menu.items.back.slot", 22);
        String materialName = plugin.getConfig().getString("lifetime-menu.items.back.material", "ARROW");

        Material material;
        try {
            material = Material.valueOf(materialName);
        } catch (IllegalArgumentException e) {
            material = Material.ARROW;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        String name = plugin.getConfig().getString("lifetime-menu.items.back.name", "&c&lНазад");
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));

        List<String> lore = new ArrayList<>();
        List<String> configLore = plugin.getConfig().getStringList("lifetime-menu.items.back.lore");

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
        String materialName = plugin.getConfig().getString("lifetime-menu.items.filler.material", "PURPLE_STAINED_GLASS_PANE");
        String name = plugin.getConfig().getString("lifetime-menu.items.filler.name", "&r");
        List<Integer> slots = plugin.getConfig().getIntegerList("lifetime-menu.items.filler.slots");

        Material material;
        try {
            material = Material.valueOf(materialName);
        } catch (IllegalArgumentException e) {
            material = Material.PURPLE_STAINED_GLASS_PANE;
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
        String regionId = openLifetimeMenus.get(player.getUniqueId());
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
        int backSlot = plugin.getConfig().getInt("lifetime-menu.items.back.slot", 22);
        if (slot == backSlot) {
            // Возвращаемся в основное меню
            player.closeInventory();
            plugin.getRegionMenuManager().openRegionMenu(player, region);
            return true;
        }

        // Проверяем слоты кнопок продления времени
        if (plugin.getConfig().contains("lifetime-menu.time-options")) {
            for (String key : plugin.getConfig().getConfigurationSection("lifetime-menu.time-options").getKeys(false)) {
                String path = "lifetime-menu.time-options." + key;
                int buttonSlot = plugin.getConfig().getInt(path + ".slot");

                if (slot == buttonSlot) {
                    int minutes = plugin.getConfig().getInt(path + ".minutes");
                    double price = plugin.getConfig().getDouble(path + ".price");

                    handleTimeExtension(player, region, minutes, price);
                    return true;
                }
            }
        }

        return true; // Блокируем все клики в меню
    }

    /**
     * Обработка покупки продления времени
     */
    private void handleTimeExtension(Player player, ProtectedRegion region, int minutes, double price) {
        // Проверяем экономику
        if (plugin.getEconomy() == null) {
            player.sendMessage(ChatColor.RED + "Экономика не настроена!");
            return;
        }

        // Проверяем баланс
        double balance = plugin.getEconomy().getBalance(player);
        if (balance < price) {
            player.sendMessage(ChatColor.RED + "Недостаточно денег! Нужно: " + formatPrice(price) +
                    ", у вас: " + formatPrice(balance));
            return;
        }

        // Списываем деньги
        net.milkbowl.vault.economy.EconomyResponse response = plugin.getEconomy().withdrawPlayer(player, price);
        if (!response.transactionSuccess()) {
            player.sendMessage(ChatColor.RED + "Ошибка при списании денег: " + response.errorMessage);
            return;
        }

        // Продлеваем время
        String regionId = region.getId();
        if (plugin.getRegionTimerManager().extendRegionTime(regionId, minutes)) {
            player.sendMessage(ChatColor.GREEN + "✅ Время жизни региона успешно продлено на " +
                    formatTime(minutes) + "!");
            player.sendMessage(ChatColor.GRAY + "Списано: " + formatPrice(price) + " монет");

            // Обновляем голограмму
            plugin.getHologramManager().updateHologram(regionId, getRegionOwnerName(region));

            // Закрываем меню и открываем заново для обновления
            player.closeInventory();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                openLifetimeMenu(player, region);
            }, 1L);

            // Звуковой эффект
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);

            plugin.getLogger().info("Игрок " + player.getName() + " продлил время жизни региона " +
                    regionId + " на " + minutes + " минут за " + price + " монет");
        } else {
            // Возвращаем деньги при ошибке
            plugin.getEconomy().depositPlayer(player, price);
            player.sendMessage(ChatColor.RED + "Ошибка при продлении времени жизни региона!");
        }
    }

    /**
     * Форматирование времени
     */
    private String formatTime(int minutes) {
        if (minutes >= 60) {
            int hours = minutes / 60;
            int mins = minutes % 60;
            if (mins > 0) {
                return hours + " час. " + mins + " мин.";
            } else {
                return hours + " час.";
            }
        }
        return minutes + " мин.";
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
        openLifetimeMenus.remove(player.getUniqueId());
    }

    /**
     * Проверка, открыто ли меню у игрока
     */
    public boolean hasOpenMenu(Player player) {
        return openLifetimeMenus.containsKey(player.getUniqueId());
    }

    /**
     * Получение ID региона для открытого меню
     */
    public String getOpenMenuRegionId(Player player) {
        return openLifetimeMenus.get(player.getUniqueId());
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