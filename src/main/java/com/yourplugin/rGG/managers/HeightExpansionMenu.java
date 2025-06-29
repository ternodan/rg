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

public class HeightExpansionMenu {

    private final RGProtectPlugin plugin;
    private final Map<UUID, String> openHeightMenus;

    public HeightExpansionMenu(RGProtectPlugin plugin) {
        this.plugin = plugin;
        this.openHeightMenus = new HashMap<>();
    }

    /**
     * Открывает меню временного расширения по высоте
     */
    public void openHeightExpansionMenu(Player player, ProtectedRegion region) {
        String regionId = region.getId();

        // Создаем инвентарь
        String title = ChatColor.translateAlternateColorCodes('&',
                plugin.getConfig().getString("height-expansion-menu.title", "&d&lВременное расширение ↕"));
        int size = plugin.getConfig().getInt("height-expansion-menu.size", 27);

        Inventory menu = Bukkit.createInventory(null, size, title);

        // Добавляем информационную кнопку
        addInfoButton(menu, region);

        // Добавляем кнопки времени расширения из конфига
        addTimeExpansionButtons(menu, player, region);

        // Добавляем кнопку отключения расширения (если активно)
        if (plugin.getHeightExpansionManager().hasHeightExpansion(regionId)) {
            addDisableButton(menu);
        }

        // Добавляем кнопку возврата
        addBackButton(menu);

        // Добавляем декоративные элементы
        if (plugin.getConfig().getBoolean("height-expansion-menu.items.filler.enabled", true)) {
            addFillerItems(menu);
        }

        // Открываем меню
        player.openInventory(menu);
        openHeightMenus.put(player.getUniqueId(), regionId);

        plugin.getLogger().info("Игрок " + player.getName() + " открыл меню расширения по высоте региона " + regionId);
    }

    /**
     * Добавление информационной кнопки
     */
    private void addInfoButton(Inventory menu, ProtectedRegion region) {
        int slot = plugin.getConfig().getInt("height-expansion-menu.items.info.slot", 4);
        String materialName = plugin.getConfig().getString("height-expansion-menu.items.info.material", "ELYTRA");

        Material material;
        try {
            material = Material.valueOf(materialName);
        } catch (IllegalArgumentException e) {
            material = Material.ELYTRA;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        String name = plugin.getConfig().getString("height-expansion-menu.items.info.name", "&b&lВременное расширение");
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));

        List<String> lore = new ArrayList<>();
        List<String> configLore = plugin.getConfig().getStringList("height-expansion-menu.items.info.lore");

        String currentHeight = plugin.getHeightExpansionManager().getCurrentHeightString(region.getId());
        String maxHeight = plugin.getHeightExpansionManager().getMaxHeightString(region.getId());

        for (String line : configLore) {
            String processedLine = line
                    .replace("{region}", region.getId())
                    .replace("{current_height}", currentHeight)
                    .replace("{max_height}", maxHeight);
            lore.add(ChatColor.translateAlternateColorCodes('&', processedLine));
        }

        meta.setLore(lore);
        item.setItemMeta(meta);
        menu.setItem(slot, item);
    }

    /**
     * Добавление кнопок расширения времени
     */
    private void addTimeExpansionButtons(Inventory menu, Player player, ProtectedRegion region) {
        if (!plugin.getConfig().contains("height-expansion-menu.time-options")) {
            // Если в конфиге нет настроек, используем стандартные
            addDefaultTimeButton(menu, 11, 1, 500, Material.IRON_INGOT);
            addDefaultTimeButton(menu, 14, 3, 1200, Material.GOLD_INGOT);
            addDefaultTimeButton(menu, 15, 6, 2000, Material.DIAMOND);
            return;
        }

        // Загружаем кнопки из конфига
        for (String key : plugin.getConfig().getConfigurationSection("height-expansion-menu.time-options").getKeys(false)) {
            String path = "height-expansion-menu.time-options." + key;

            int slot = plugin.getConfig().getInt(path + ".slot");

            // ИСПРАВЛЕНО: Проверяем наличие параметра seconds в первую очередь
            int timeInSeconds;
            if (plugin.getConfig().contains(path + ".seconds")) {
                timeInSeconds = plugin.getConfig().getInt(path + ".seconds");
            } else if (plugin.getConfig().contains(path + ".hours")) {
                timeInSeconds = plugin.getConfig().getInt(path + ".hours") * 3600;
            } else {
                plugin.getLogger().warning("Не найдено время для опции " + key + " в height-expansion-menu");
                continue;
            }

            double price = plugin.getConfig().getDouble(path + ".price");
            String materialName = plugin.getConfig().getString(path + ".material", "IRON_INGOT");
            String displayName = plugin.getConfig().getString(path + ".name", "&a+{time}");
            List<String> lore = plugin.getConfig().getStringList(path + ".lore");

            Material material;
            try {
                material = Material.valueOf(materialName);
            } catch (IllegalArgumentException e) {
                material = Material.IRON_INGOT;
            }

            ItemStack item = new ItemStack(material);
            ItemMeta meta = item.getItemMeta();

            // Форматируем время для отображения
            String timeDisplay = formatTimeForDisplay(timeInSeconds);

            // Форматируем название
            String formattedName = displayName
                    .replace("{time}", timeDisplay)
                    .replace("{seconds}", String.valueOf(timeInSeconds))
                    .replace("{price}", formatPrice(price));
            meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', formattedName));

            // Форматируем описание
            List<String> formattedLore = new ArrayList<>();
            for (String line : lore) {
                String formattedLine = line
                        .replace("{time}", timeDisplay)
                        .replace("{seconds}", String.valueOf(timeInSeconds))
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
     * НОВЫЙ метод для форматирования времени для отображения
     */
    private String formatTimeForDisplay(int seconds) {
        if (seconds < 60) {
            return seconds + " секунд";
        } else if (seconds < 3600) {
            int minutes = seconds / 60;
            if (seconds % 60 == 0) {
                return minutes + " минут";
            } else {
                return minutes + " минут " + (seconds % 60) + " секунд";
            }
        } else {
            int hours = seconds / 3600;
            int remainingMinutes = (seconds % 3600) / 60;
            if (remainingMinutes == 0) {
                return hours + " час" + getHoursSuffix(hours);
            } else {
                return hours + " час" + getHoursSuffix(hours) + " " + remainingMinutes + " минут";
            }
        }
    }

    /**
     * НОВЫЙ метод для правильных окончаний часов
     */
    private String getHoursSuffix(int hours) {
        if (hours % 10 == 1 && hours % 100 != 11) {
            return "";
        } else if ((hours % 10 >= 2 && hours % 10 <= 4) && (hours % 100 < 10 || hours % 100 >= 20)) {
            return "а";
        } else {
            return "ов";
        }
    }

    /**
     * Добавление стандартной кнопки времени
     */
    private void addDefaultTimeButton(Inventory menu, int slot, int hours, double price, Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        meta.setDisplayName(ChatColor.GREEN + "+" + hours + " час" + (hours > 1 ? "а" : ""));

        List<String> lore = new ArrayList<>();
        lore.add("");
        lore.add(ChatColor.GRAY + "Расширить регион по высоте");
        lore.add(ChatColor.GRAY + "на " + ChatColor.YELLOW + hours + " час" + (hours > 1 ? "а" : ""));
        lore.add("");
        lore.add(ChatColor.GRAY + "Цена: " + ChatColor.GOLD + formatPrice(price) + " монет");
        lore.add("");
        lore.add(ChatColor.YELLOW + "Нажмите для покупки!");

        meta.setLore(lore);
        item.setItemMeta(meta);
        menu.setItem(slot, item);
    }

    /**
     * Добавление кнопки отключения расширения
     */
    private void addDisableButton(Inventory menu) {
        int slot = plugin.getConfig().getInt("height-expansion-menu.items.disable.slot", 13);
        String materialName = plugin.getConfig().getString("height-expansion-menu.items.disable.material", "BARRIER");

        Material material;
        try {
            material = Material.valueOf(materialName);
        } catch (IllegalArgumentException e) {
            material = Material.BARRIER;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        String name = plugin.getConfig().getString("height-expansion-menu.items.disable.name", "&c&lОтключить расширение");
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));

        List<String> lore = new ArrayList<>();
        List<String> configLore = plugin.getConfig().getStringList("height-expansion-menu.items.disable.lore");

        for (String line : configLore) {
            lore.add(ChatColor.translateAlternateColorCodes('&', line));
        }

        meta.setLore(lore);
        item.setItemMeta(meta);
        menu.setItem(slot, item);
    }

    /**
     * Добавление кнопки возврата
     */
    private void addBackButton(Inventory menu) {
        int slot = plugin.getConfig().getInt("height-expansion-menu.items.back.slot", 22);
        String materialName = plugin.getConfig().getString("height-expansion-menu.items.back.material", "ARROW");

        Material material;
        try {
            material = Material.valueOf(materialName);
        } catch (IllegalArgumentException e) {
            material = Material.ARROW;
        }

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        String name = plugin.getConfig().getString("height-expansion-menu.items.back.name", "&c&lНазад");
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', name));

        List<String> lore = new ArrayList<>();
        List<String> configLore = plugin.getConfig().getStringList("height-expansion-menu.items.back.lore");

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
        String materialName = plugin.getConfig().getString("height-expansion-menu.items.filler.material", "PURPLE_STAINED_GLASS_PANE");
        String name = plugin.getConfig().getString("height-expansion-menu.items.filler.name", "&r");
        List<Integer> slots = plugin.getConfig().getIntegerList("height-expansion-menu.items.filler.slots");

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
        String regionId = openHeightMenus.get(player.getUniqueId());
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
        int backSlot = plugin.getConfig().getInt("height-expansion-menu.items.back.slot", 22);
        if (slot == backSlot) {
            // Возвращаемся в основное меню
            player.closeInventory();
            plugin.getRegionMenuManager().openRegionMenu(player, region);
            return true;
        }

        // Проверяем слот кнопки отключения
        int disableSlot = plugin.getConfig().getInt("height-expansion-menu.items.disable.slot", 13);
        if (slot == disableSlot) {
            handleDisableExpansion(player, regionId);
            return true;
        }

        // Проверяем слоты кнопок расширения времени
        if (plugin.getConfig().contains("height-expansion-menu.time-options")) {
            for (String key : plugin.getConfig().getConfigurationSection("height-expansion-menu.time-options").getKeys(false)) {
                String path = "height-expansion-menu.time-options." + key;
                int buttonSlot = plugin.getConfig().getInt(path + ".slot");

                if (slot == buttonSlot) {
                    // ИСПРАВЛЕНИЕ: Правильно читаем время и передаем параметры
                    int timeInSeconds;
                    if (plugin.getConfig().contains(path + ".seconds")) {
                        timeInSeconds = plugin.getConfig().getInt(path + ".seconds");
                    } else if (plugin.getConfig().contains(path + ".hours")) {
                        timeInSeconds = plugin.getConfig().getInt(path + ".hours") * 3600;
                    } else {
                        plugin.getLogger().warning("Не найдено время для опции " + key);
                        return true;
                    }

                    double price = plugin.getConfig().getDouble(path + ".price");

                    handleTimeExpansion(player, region, timeInSeconds, price);
                    return true;
                }
            }
        }

        return true; // Блокируем все клики в меню
    }

    /**
     * ИСПРАВЛЕННАЯ обработка покупки расширения времени
     */
    private void handleTimeExpansion(Player player, ProtectedRegion region, int durationSeconds, double price) {
        String regionId = region.getId();

        plugin.getLogger().info("=== НАЧАЛО ОБРАБОТКИ РАСШИРЕНИЯ ===");
        plugin.getLogger().info("Игрок: " + player.getName());
        plugin.getLogger().info("Регион: " + regionId);
        plugin.getLogger().info("Время в секундах: " + durationSeconds);
        plugin.getLogger().info("Цена: " + price);

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

        // КРИТИЧЕСКОЕ: Проверяем, что регион существует ДО операции
        ProtectedRegion checkRegion = findRegionById(regionId);
        if (checkRegion == null) {
            plugin.getLogger().severe("ОШИБКА: Регион " + regionId + " не найден ДО операции расширения!");
            player.sendMessage(ChatColor.RED + "Ошибка: регион не найден!");
            player.closeInventory();
            return;
        }

        plugin.getLogger().info("Регион найден, границы ДО операции: " +
                checkRegion.getMinimumPoint() + " -> " + checkRegion.getMaximumPoint());

        // Списываем деньги
        net.milkbowl.vault.economy.EconomyResponse response = plugin.getEconomy().withdrawPlayer(player, price);
        if (!response.transactionSuccess()) {
            player.sendMessage(ChatColor.RED + "Ошибка при списании денег: " + response.errorMessage);
            return;
        }

        plugin.getLogger().info("Деньги списаны успешно");

        // Проверяем было ли активно расширение до покупки
        boolean wasActive = plugin.getHeightExpansionManager() != null &&
                plugin.getHeightExpansionManager().hasHeightExpansion(regionId);
        plugin.getLogger().info("Было активно расширение ДО операции: " + wasActive);

        // Сохраняем состояние подсветки ДО активации
        boolean bordersEnabled = plugin.getRegionMenuManager().isRegionBordersEnabled(regionId);
        plugin.getLogger().info("Состояние подсветки ДО активации: " + bordersEnabled);

        // КРИТИЧЕСКОЕ: Активируем/продлеваем расширение
        // ИСПРАВЛЕНИЕ: Используем новый метод activateHeightExpansionSeconds
        boolean success = false;

        try {
            success = plugin.getHeightExpansionManager().activateHeightExpansionSeconds(regionId, durationSeconds);
            plugin.getLogger().info("Результат activateHeightExpansionSeconds: " + success);
        } catch (Exception e) {
            plugin.getLogger().severe("ИСКЛЮЧЕНИЕ при активации расширения: " + e.getMessage());
            e.printStackTrace();
            success = false;
        }

        if (success) {
            // КРИТИЧЕСКОЕ: Проверяем, что регион все еще существует ПОСЛЕ операции
            ProtectedRegion afterRegion = findRegionById(regionId);
            if (afterRegion == null) {
                plugin.getLogger().severe("КРИТИЧЕСКАЯ ОШИБКА: Регион " + regionId + " ИСЧЕЗ после операции расширения!");

                // Возвращаем деньги
                plugin.getEconomy().depositPlayer(player, price);
                player.sendMessage(ChatColor.RED + "Критическая ошибка: регион исчез! Деньги возвращены.");
                player.sendMessage(ChatColor.RED + "Пожалуйста, обратитесь к администратору!");
                player.closeInventory();

                // Отправляем уведомление администраторам
                for (Player admin : Bukkit.getOnlinePlayers()) {
                    if (admin.hasPermission("rgprotect.admin")) {
                        admin.sendMessage(ChatColor.DARK_RED + "[RGProtect] КРИТИЧЕСКАЯ ОШИБКА: Регион " + regionId + " исчез при расширении по высоте!");
                    }
                }

                return;
            }

            plugin.getLogger().info("Регион найден ПОСЛЕ операции, границы: " +
                    afterRegion.getMinimumPoint() + " -> " + afterRegion.getMaximumPoint());

            // Проверяем состояние границ
            boolean hasBorders = plugin.getVisualizationManager().hasRegionBorders(regionId);
            plugin.getLogger().info("Есть ли границы ПОСЛЕ операции: " + hasBorders);

            String messageKey = wasActive ? "messages.height-expansion-extended" : "messages.height-expansion-activated";
            String message = plugin.getConfig().getString(messageKey,
                    "&a✅ Регион временно расширен до максимальной высоты на {time}!");

            // ИСПРАВЛЕНИЕ: Правильно форматируем время для отображения
            String timeText = formatTimeForDisplay(durationSeconds);
            message = message.replace("{time}", timeText);

            player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
            player.sendMessage(ChatColor.GRAY + "Списано: " + formatPrice(price) + " монет");

            // Проверяем и восстанавливаем границы если нужно
            if (bordersEnabled && !hasBorders) {
                plugin.getLogger().warning("ПРОБЛЕМА: Подсветка включена, но границ нет! Пересоздаем...");

                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    try {
                        ProtectedRegion finalRegion = findRegionById(regionId);
                        if (finalRegion != null) {
                            plugin.getLogger().info("Принудительное пересоздание границ...");
                            plugin.getVisualizationManager().removeRegionBorders(regionId);
                            plugin.getVisualizationManager().createRegionBorders(finalRegion, player.getWorld());

                            boolean hasAfterRecreate = plugin.getVisualizationManager().hasRegionBorders(regionId);
                            plugin.getLogger().info("Границы после принудительного пересоздания: " + hasAfterRecreate);
                        }
                    } catch (Exception e) {
                        plugin.getLogger().severe("Ошибка при принудительном пересоздании: " + e.getMessage());
                    }
                }, 20L);
            }

            // Обновляем голограмму после успешного расширения
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                try {
                    String ownerName = getRegionOwnerName(afterRegion);
                    plugin.getHologramManager().updateHologram(regionId, ownerName);
                    plugin.getLogger().info("Голограмма обновлена после расширения по высоте");
                } catch (Exception e) {
                    plugin.getLogger().warning("Ошибка при обновлении голограммы: " + e.getMessage());
                }
            }, 25L);

            // Закрываем меню
            player.closeInventory();

            // БЕЗОПАСНОЕ повторное открытие меню
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                try {
                    // Получаем обновленный регион
                    ProtectedRegion updatedRegion = findRegionById(regionId);
                    if (updatedRegion != null) {
                        plugin.getLogger().info("Открываем обновленное меню для региона " + regionId);
                        openHeightExpansionMenu(player, updatedRegion);
                    } else {
                        plugin.getLogger().warning("Не удалось найти регион для повторного открытия меню");
                        player.sendMessage(ChatColor.YELLOW + "Расширение активировано, но меню не может быть открыто повторно.");

                        // Открываем основное меню как запасной вариант
                        ProtectedRegion mainRegion = findRegionById(regionId);
                        if (mainRegion != null) {
                            plugin.getRegionMenuManager().openRegionMenu(player, mainRegion);
                        }
                    }
                } catch (Exception e) {
                    plugin.getLogger().severe("Ошибка при повторном открытии меню: " + e.getMessage());
                    player.sendMessage(ChatColor.YELLOW + "Расширение активировано, но произошла ошибка при открытии меню.");
                }
            }, 30L);

            // Звуковой эффект
            player.playSound(player.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);

            plugin.getLogger().info("Игрок " + player.getName() + " активировал/продлил расширение по высоте региона " +
                    regionId + " на " + timeText + " за " + price + " монет");

            plugin.getLogger().info("=== КОНЕЦ ОБРАБОТКИ РАСШИРЕНИЯ (УСПЕХ) ===");
        } else {
            // Возвращаем деньги при ошибке
            plugin.getEconomy().depositPlayer(player, price);
            player.sendMessage(ChatColor.RED + "Ошибка при активации расширения по высоте!");
            player.sendMessage(ChatColor.YELLOW + "Ваши " + formatPrice(price) + " монет возвращены.");

            // Проверяем, существует ли еще регион
            ProtectedRegion checkRegionAfter = findRegionById(regionId);
            if (checkRegionAfter == null) {
                player.sendMessage(ChatColor.DARK_RED + "КРИТИЧЕСКАЯ ОШИБКА: Регион был потерян!");
                player.sendMessage(ChatColor.RED + "Пожалуйста, обратитесь к администратору!");
                player.closeInventory();

                // Логируем критическую ошибку
                plugin.getLogger().severe("КРИТИЧЕСКАЯ ОШИБКА: Регион " + regionId + " был потерян при попытке расширения!");
            } else {
                // Регион существует, можно попробовать снова
                player.sendMessage(ChatColor.YELLOW + "Попробуйте еще раз позже.");
            }

            plugin.getLogger().severe("Не удалось активировать расширение по высоте для региона " + regionId);
            plugin.getLogger().info("=== КОНЕЦ ОБРАБОТКИ РАСШИРЕНИЯ (ОШИБКА) ===");
        }
    }

    /**
     * ИСПРАВЛЕННАЯ обработка отключения расширения
     */
    private void handleDisableExpansion(Player player, String regionId) {
        boolean success = plugin.getHeightExpansionManager().disableHeightExpansion(regionId);

        if (success) {
            String message = plugin.getConfig().getString("messages.height-expansion-disabled",
                    "&e⚡ Временное расширение по высоте отключено. Регион вернулся к обычной высоте.");
            player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));

            // ИСПРАВЛЕНИЕ: Обновляем голограмму после отключения
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                try {
                    ProtectedRegion region = findRegionById(regionId);
                    if (region != null) {
                        String ownerName = getRegionOwnerName(region);
                        plugin.getHologramManager().updateHologram(regionId, ownerName);
                        plugin.getLogger().info("Голограмма обновлена после отключения расширения");
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Ошибка при обновлении голограммы: " + e.getMessage());
                }
            }, 10L);

            // Возвращаемся в основное меню с задержкой
            player.closeInventory();
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                try {
                    ProtectedRegion region = findRegionById(regionId);
                    if (region != null) {
                        plugin.getRegionMenuManager().openRegionMenu(player, region);
                    } else {
                        player.sendMessage(ChatColor.RED + "Ошибка: регион не найден после отключения расширения!");
                        plugin.getLogger().severe("Регион " + regionId + " исчез после отключения расширения!");
                    }
                } catch (Exception e) {
                    plugin.getLogger().severe("Ошибка при открытии основного меню: " + e.getMessage());
                    player.sendMessage(ChatColor.RED + "Ошибка при открытии меню региона!");
                }
            }, 15L);

            plugin.getLogger().info("Игрок " + player.getName() + " отключил расширение по высоте региона " + regionId);
        } else {
            player.sendMessage(ChatColor.RED + "Ошибка при отключении расширения!");
            plugin.getLogger().warning("Не удалось отключить расширение для региона " + regionId);
        }
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
        openHeightMenus.remove(player.getUniqueId());
    }

    /**
     * Проверка, открыто ли меню у игрока
     */
    public boolean hasOpenMenu(Player player) {
        return openHeightMenus.containsKey(player.getUniqueId());
    }

    /**
     * Получение ID региона для открытого меню
     */
    public String getOpenMenuRegionId(Player player) {
        return openHeightMenus.get(player.getUniqueId());
    }

    /**
     * ДОБАВЛЕННЫЙ вспомогательный метод для получения имени владельца региона
     */
    private String getRegionOwnerName(ProtectedRegion region) {
        if (!region.getOwners().getUniqueIds().isEmpty()) {
            java.util.UUID ownerUUID = region.getOwners().getUniqueIds().iterator().next();
            String ownerName = plugin.getServer().getOfflinePlayer(ownerUUID).getName();
            return ownerName != null ? ownerName : "Unknown";
        }

        if (!region.getOwners().getPlayers().isEmpty()) {
            return region.getOwners().getPlayers().iterator().next();
        }

        return "Unknown";
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