package com.yourplugin.rGG.commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.Material;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.yourplugin.rGG.RGProtectPlugin;

import java.util.List;
import java.util.ArrayList;

public class RGProtectCommand implements CommandExecutor {

    private final RGProtectPlugin plugin;

    public RGProtectCommand(RGProtectPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.GOLD + "=== RGProtect ===");
            sender.sendMessage(ChatColor.YELLOW + "/rgprotect get <игрок> - получить блок привата");
            sender.sendMessage(ChatColor.YELLOW + "/rgprotect info - информация о плагине");
            sender.sendMessage(ChatColor.YELLOW + "/rgprotect reload - перезагрузить конфиг");
            sender.sendMessage(ChatColor.YELLOW + "/rgprotect test - тестировать границы региона");
            sender.sendMessage(ChatColor.YELLOW + "/rgprotect debug - глубокая диагностика");
            sender.sendMessage(ChatColor.YELLOW + "/rgprotect borders - проверить все границы");
            sender.sendMessage(ChatColor.YELLOW + "/rgprotect checkground - проверить размещение на земле");
            sender.sendMessage(ChatColor.YELLOW + "/rgprotect testheight - тестировать расширение по высоте");
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "get":
                return handleGetCommand(sender, args);
            case "info":
                return handleInfoCommand(sender);
            case "reload":
                return handleReloadCommand(sender);
            case "test":
                return handleTestCommand(sender);
            case "debug":
                return handleDebugCommand(sender);
            case "borders":
                return handleBordersCommand(sender);
            case "checkground":
                return handleCheckGroundCommand(sender);
            case "testheight":
                return handleTestHeightCommand(sender);
            default:
                sender.sendMessage(ChatColor.RED + "Неизвестная команда! Используйте /rgprotect для справки.");
                return true;
        }
    }

    private boolean handleGetCommand(CommandSender sender, String[] args) {
        if (!sender.hasPermission("rgprotect.get")) {
            sender.sendMessage(ChatColor.RED + "У вас нет прав на эту команду!");
            return true;
        }

        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Эта команда доступна только игрокам!");
            return true;
        }

        Player player = (Player) sender;
        String targetPlayer = args.length > 1 ? args[1] : player.getName();

        // Создаем блок привата
        Material blockType = Material.valueOf(plugin.getConfig().getString("protect-block.material", "DIAMOND_BLOCK"));
        ItemStack protectBlock = new ItemStack(blockType, 1);
        ItemMeta meta = protectBlock.getItemMeta();

        String displayName = plugin.getConfig().getString("protect-block.display-name", "&aБлок привата")
                .replace("{player}", targetPlayer);
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', displayName));

        List<String> lore = plugin.getConfig().getStringList("protect-block.lore");
        if (!lore.isEmpty()) {
            List<String> newLore = new ArrayList<>();
            for (String line : lore) {
                newLore.add(ChatColor.translateAlternateColorCodes('&',
                        line.replace("{player}", targetPlayer)));
            }
            // Добавляем скрытый тег
            newLore.add(ChatColor.DARK_GRAY + "RGProtect:" + targetPlayer);
            meta.setLore(newLore);
        }

        protectBlock.setItemMeta(meta);
        player.getInventory().addItem(protectBlock);

        player.sendMessage(ChatColor.GREEN + "Блок привата для " + targetPlayer + " выдан!");
        return true;
    }

    private boolean handleInfoCommand(CommandSender sender) {
        sender.sendMessage(ChatColor.GOLD + "=== RGProtect Информация ===");
        sender.sendMessage(ChatColor.YELLOW + "Версия: " + plugin.getDescription().getVersion());
        sender.sendMessage(ChatColor.YELLOW + "Автор: " + plugin.getDescription().getAuthors());

        if (sender.hasPermission("rgprotect.admin")) {
            sender.sendMessage(ChatColor.GRAY + "--- Статистика ---");
            sender.sendMessage(ChatColor.GRAY + "Голограммы: " + plugin.getHologramManager().getHologramCount());
            sender.sendMessage(ChatColor.GRAY + "Активные визуализации: " + plugin.getVisualizationManager().getActiveVisualizationCount());
            sender.sendMessage(ChatColor.GRAY + "Регионы с границами: " + plugin.getVisualizationManager().getRegionBordersCount());

            if (plugin.getEconomy() != null) {
                sender.sendMessage(ChatColor.GRAY + "Экономика: " + plugin.getEconomy().getName());
            } else {
                sender.sendMessage(ChatColor.RED + "Экономика: не настроена");
            }
        }

        return true;
    }

    private boolean handleReloadCommand(CommandSender sender) {
        if (!sender.hasPermission("rgprotect.reload")) {
            sender.sendMessage(ChatColor.RED + "У вас нет прав на эту команду!");
            return true;
        }

        plugin.reloadConfig();
        sender.sendMessage(ChatColor.GREEN + "Конфигурация перезагружена!");
        return true;
    }
    /**
     * Команда для тестирования границ региона
     */
    private boolean handleTestCommand(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Эта команда доступна только игрокам!");
            return true;
        }

        if (!sender.hasPermission("rgprotect.admin")) {
            sender.sendMessage(ChatColor.RED + "У вас нет прав на эту команду!");
            return true;
        }

        Player player = (Player) sender;
        Location location = player.getLocation();

        // Проверяем, есть ли регион в этом месте
        ProtectedRegion region = plugin.getProtectRegionManager().getRegionAt(location);

        if (region == null) {
            player.sendMessage(ChatColor.RED + "Регион не найден в вашей позиции!");
            return true;
        }

        player.sendMessage(ChatColor.GOLD + "=== Тестирование границ региона (НА ЗЕМЛЕ) ===");
        player.sendMessage(ChatColor.YELLOW + "ID региона: " + region.getId());
        player.sendMessage(ChatColor.YELLOW + "Размер: " + getCurrentRegionSizeString(region));
        player.sendMessage(ChatColor.YELLOW + "Мир: " + player.getWorld().getName());

        // Принудительно пересоздаем границы
        plugin.getVisualizationManager().removeRegionBorders(region.getId());
        player.sendMessage(ChatColor.GRAY + "Старые границы удалены...");

        plugin.getVisualizationManager().createRegionBorders(region, player.getWorld());
        player.sendMessage(ChatColor.GREEN + "Новые границы созданы НА ЗЕМЛЕ!");

        // Показываем информацию о границах
        if (plugin.getVisualizationManager().hasRegionBorders(region.getId())) {
            player.sendMessage(ChatColor.GREEN + "✅ Границы успешно созданы и сохранены");
        } else {
            player.sendMessage(ChatColor.RED + "❌ Ошибка: границы не были созданы");
        }

        return true;
    }

    /**
     * Команда для глубокой диагностики
     */
    private boolean handleDebugCommand(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Эта команда доступна только игрокам!");
            return true;
        }

        if (!sender.hasPermission("rgprotect.admin")) {
            sender.sendMessage(ChatColor.RED + "У вас нет прав на эту команду!");
            return true;
        }

        Player player = (Player) sender;
        Location location = player.getLocation();
        World world = player.getWorld();

        player.sendMessage(ChatColor.GOLD + "=== ПОЛНАЯ ДИАГНОСТИКА ===");

        // 1. Проверяем настройки плагина
        player.sendMessage(ChatColor.YELLOW + "1. Настройки плагина:");
        player.sendMessage("   visualization.enabled: " + plugin.getConfig().getBoolean("visualization.enabled"));
        player.sendMessage("   physical-borders.enabled: " + plugin.getConfig().getBoolean("visualization.physical-borders.enabled"));
        player.sendMessage("   border material: " + plugin.getConfig().getString("visualization.physical-borders.material"));
        player.sendMessage("   max-ground-search-depth: " + plugin.getConfig().getInt("advanced.max-ground-search-depth", 50));

        // 2. Проверяем мир
        player.sendMessage(ChatColor.YELLOW + "2. Информация о мире:");
        player.sendMessage("   Название: " + world.getName());
        player.sendMessage("   Тип: " + world.getEnvironment());
        player.sendMessage("   Доступен: " + (world != null));
        player.sendMessage("   Минимальная высота: " + world.getMinHeight());
        player.sendMessage("   Максимальная высота: " + world.getMaxHeight());

        // 3. Проверяем WorldGuard
        player.sendMessage(ChatColor.YELLOW + "3. WorldGuard:");
        Object regionManager = plugin.getProtectRegionManager().getWorldGuardRegionManager(world);
        player.sendMessage("   RegionManager: " + (regionManager != null ? "Найден" : "НЕ НАЙДЕН"));

        // 4. Проверяем регион в текущей позиции
        player.sendMessage(ChatColor.YELLOW + "4. Регион в текущей позиции:");
        ProtectedRegion region = plugin.getProtectRegionManager().getRegionAt(location);
        if (region != null) {
            player.sendMessage("   ID: " + region.getId());
            player.sendMessage("   Размер: " + getCurrentRegionSizeString(region));
            player.sendMessage("   Границы: " + region.getMinimumPoint() + " -> " + region.getMaximumPoint());
            player.sendMessage("   Есть сохраненные границы: " + plugin.getVisualizationManager().hasRegionBorders(region.getId()));
        } else {
            player.sendMessage("   Регион НЕ НАЙДЕН");
        }

        // 5. Проверяем землю под ногами игрока
        player.sendMessage(ChatColor.YELLOW + "5. Анализ земли под вами:");
        int playerX = location.getBlockX();
        int playerY = location.getBlockY();
        int playerZ = location.getBlockZ();

        // Ищем землю вниз
        boolean foundGround = false;
        int groundY = -1;
        for (int y = playerY; y >= world.getMinHeight(); y--) {
            Block testBlock = world.getBlockAt(playerX, y, playerZ);
            if (isSolidBlock(testBlock)) {
                foundGround = true;
                groundY = y;
                break;
            }
        }

        if (foundGround) {
            player.sendMessage("   ✅ Земля найдена на высоте Y=" + groundY);
            player.sendMessage("   📏 Расстояние до земли: " + (playerY - groundY) + " блоков");
            Block groundBlock = world.getBlockAt(playerX, groundY, playerZ);
            player.sendMessage("   🧱 Материал земли: " + groundBlock.getType());
        } else {
            player.sendMessage("   ❌ Земля НЕ НАЙДЕНА до минимальной высоты " + world.getMinHeight());
        }

        // 6. Проверяем блоки границ вокруг игрока
        player.sendMessage(ChatColor.YELLOW + "6. Блоки границ вокруг вас:");
        int borderCount = 0;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    Block block = world.getBlockAt(playerX + dx, playerY + dy, playerZ + dz);
                    if (block.getType() == Material.RED_WOOL) {
                        borderCount++;
                        // Проверяем, на земле ли этот блок
                        Block blockBelow = world.getBlockAt(playerX + dx, playerY + dy - 1, playerZ + dz);
                        String groundStatus = isSolidBlock(blockBelow) ? "НА ЗЕМЛЕ" : "ВИСИТ";
                        player.sendMessage("   🔴 Красная шерсть: " + (playerX + dx) + "," + (playerY + dy) + "," + (playerZ + dz) + " - " + groundStatus);
                    }
                }
            }
        }
        player.sendMessage("   Всего найдено блоков границ: " + borderCount);

        player.sendMessage(ChatColor.GOLD + "=== КОНЕЦ ДИАГНОСТИКИ ===");

        return true;
    }
    /**
     * НОВАЯ команда для проверки размещения границ на земле
     */
    private boolean handleCheckGroundCommand(CommandSender sender) {
        if (!sender.hasPermission("rgprotect.admin")) {
            sender.sendMessage(ChatColor.RED + "У вас нет прав на эту команду!");
            return true;
        }

        sender.sendMessage(ChatColor.GOLD + "=== ПРОВЕРКА РАЗМЕЩЕНИЯ НА ЗЕМЛЕ ===");

        if (sender instanceof Player) {
            Player player = (Player) sender;
            World world = player.getWorld();

            // Проверяем все регионы в мире игрока
            Object regionManager = plugin.getProtectRegionManager().getWorldGuardRegionManager(world);
            if (regionManager != null) {
                try {
                    java.lang.reflect.Method getRegionsMethod = regionManager.getClass().getMethod("getRegions");
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, ProtectedRegion> regions = (java.util.Map<String, ProtectedRegion>) getRegionsMethod.invoke(regionManager);

                    sender.sendMessage(ChatColor.YELLOW + "Проверяем " + regions.size() + " регионов в мире " + world.getName() + ":");

                    int totalRegions = 0;
                    int regionsWithBorders = 0;
                    int regionsOnGround = 0;
                    int regionsFloating = 0;

                    for (java.util.Map.Entry<String, ProtectedRegion> entry : regions.entrySet()) {
                        String regionId = entry.getKey();
                        ProtectedRegion region = entry.getValue();
                        totalRegions++;

                        boolean hasBorders = plugin.getVisualizationManager().hasRegionBorders(regionId);
                        if (hasBorders) {
                            regionsWithBorders++;

                            // Проверяем, на земле ли границы
                            boolean onGround = checkIfRegionBordersOnGround(world, region);
                            if (onGround) {
                                regionsOnGround++;
                                sender.sendMessage("   " + ChatColor.GREEN + "✅ " + regionId + " - границы НА ЗЕМЛЕ");
                            } else {
                                regionsFloating++;
                                sender.sendMessage("   " + ChatColor.RED + "❌ " + regionId + " - границы ВИСЯТ!");
                            }
                        } else {
                            sender.sendMessage("   " + ChatColor.GRAY + "⚪ " + regionId + " - нет границ");
                        }
                    }

                    sender.sendMessage("");
                    sender.sendMessage(ChatColor.GOLD + "=== РЕЗУЛЬТАТЫ ===");
                    sender.sendMessage(ChatColor.YELLOW + "Всего регионов: " + totalRegions);
                    sender.sendMessage(ChatColor.YELLOW + "С границами: " + regionsWithBorders);
                    sender.sendMessage(ChatColor.GREEN + "НА ЗЕМЛЕ: " + regionsOnGround);
                    sender.sendMessage(ChatColor.RED + "ВИСЯЩИХ: " + regionsFloating);

                    if (regionsFloating > 0) {
                        sender.sendMessage("");
                        sender.sendMessage(ChatColor.RED + "⚠️ Обнаружены висящие границы!");
                        sender.sendMessage(ChatColor.YELLOW + "Используйте /rgprotect test в регионе для пересоздания границ НА ЗЕМЛЕ");
                    } else if (regionsWithBorders > 0) {
                        sender.sendMessage("");
                        sender.sendMessage(ChatColor.GREEN + "✅ Все границы корректно размещены НА ЗЕМЛЕ!");
                    }

                } catch (Exception e) {
                    sender.sendMessage(ChatColor.RED + "Ошибка при проверке регионов: " + e.getMessage());
                }
            }
        } else {
            sender.sendMessage(ChatColor.RED + "Эта команда доступна только игрокам!");
        }

        return true;
    }

    /**
     * НОВАЯ команда для тестирования расширения по высоте
     */
    private boolean handleTestHeightCommand(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(ChatColor.RED + "Эта команда доступна только игрокам!");
            return true;
        }

        if (!sender.hasPermission("rgprotect.admin")) {
            sender.sendMessage(ChatColor.RED + "У вас нет прав на эту команду!");
            return true;
        }

        Player player = (Player) sender;
        Location location = player.getLocation();

        // Проверяем, есть ли регион в этом месте
        ProtectedRegion region = plugin.getProtectRegionManager().getRegionAt(location);

        if (region == null) {
            player.sendMessage(ChatColor.RED + "Регион не найден в вашей позиции!");
            return true;
        }

        String regionId = region.getId();

        player.sendMessage(ChatColor.GOLD + "=== Тестирование расширения по высоте ===");
        player.sendMessage(ChatColor.YELLOW + "ID региона: " + regionId);
        player.sendMessage(ChatColor.YELLOW + "Мир: " + player.getWorld().getName());

        // Показываем текущую высоту
        String currentHeight = plugin.getHeightExpansionManager().getCurrentHeightString(regionId);
        String maxHeight = plugin.getHeightExpansionManager().getMaxHeightString(regionId);

        player.sendMessage(ChatColor.YELLOW + "Текущая высота: " + currentHeight);
        player.sendMessage(ChatColor.YELLOW + "Максимальная высота мира: " + maxHeight);

        // Проверяем состояние расширения
        boolean hasExpansion = plugin.getHeightExpansionManager().hasHeightExpansion(regionId);
        player.sendMessage(ChatColor.YELLOW + "Активно расширение: " + (hasExpansion ? ChatColor.GREEN + "ДА" : ChatColor.RED + "НЕТ"));

        if (hasExpansion) {
            String remainingTime = plugin.getHeightExpansionManager().getFormattedRemainingTime(regionId);
            player.sendMessage(ChatColor.YELLOW + "Оставшееся время: " + remainingTime);
        }

        // Показываем границы региона
        player.sendMessage(ChatColor.GRAY + "Границы региона:");
        player.sendMessage(ChatColor.GRAY + "  X: " + region.getMinimumPoint().x() + " -> " + region.getMaximumPoint().x());
        player.sendMessage(ChatColor.GRAY + "  Y: " + region.getMinimumPoint().y() + " -> " + region.getMaximumPoint().y());
        player.sendMessage(ChatColor.GRAY + "  Z: " + region.getMinimumPoint().z() + " -> " + region.getMaximumPoint().z());

        return true;
    }

    /**
     * НОВЫЙ метод для проверки размещения границ региона на земле
     */
    private boolean checkIfRegionBordersOnGround(World world, ProtectedRegion region) {
        int minX = region.getMinimumPoint().x();
        int maxX = region.getMaximumPoint().x();
        int minZ = region.getMinimumPoint().z();
        int maxZ = region.getMaximumPoint().z();

        // Проверяем несколько ключевых точек границы
        int[] checkX = {minX, maxX, (minX + maxX) / 2};
        int[] checkZ = {minZ, maxZ, (minZ + maxZ) / 2};

        int totalChecks = 0;
        int onGroundChecks = 0;

        for (int x : checkX) {
            for (int z : checkZ) {
                // Ищем красную шерсть в этой позиции
                for (int y = world.getMaxHeight() - 1; y >= world.getMinHeight(); y--) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() == Material.RED_WOOL) {
                        totalChecks++;
                        // Проверяем, есть ли под ней твердый блок
                        Block blockBelow = world.getBlockAt(x, y - 1, z);
                        if (isSolidBlock(blockBelow)) {
                            onGroundChecks++;
                        }
                        break; // Нашли блок границы, переходим к следующей позиции
                    }
                }
            }
        }

        // Считаем, что границы на земле, если хотя бы 70% проверенных блоков на земле
        return totalChecks > 0 && ((double) onGroundChecks / totalChecks) >= 0.7;
    }
    /**
     * Команда для проверки всех границ
     */
    private boolean handleBordersCommand(CommandSender sender) {
        if (!sender.hasPermission("rgprotect.admin")) {
            sender.sendMessage(ChatColor.RED + "У вас нет прав на эту команду!");
            return true;
        }

        sender.sendMessage(ChatColor.GOLD + "=== Информация о границах ===");
        sender.sendMessage(ChatColor.YELLOW + "Всего регионов с границами: " + plugin.getVisualizationManager().getRegionBordersCount());

        if (sender instanceof Player) {
            Player player = (Player) sender;
            World world = player.getWorld();

            Object regionManager = plugin.getProtectRegionManager().getWorldGuardRegionManager(world);
            if (regionManager != null) {
                try {
                    java.lang.reflect.Method getRegionsMethod = regionManager.getClass().getMethod("getRegions");
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, ProtectedRegion> regions = (java.util.Map<String, ProtectedRegion>) getRegionsMethod.invoke(regionManager);

                    sender.sendMessage(ChatColor.YELLOW + "Регионы в мире " + world.getName() + ": " + regions.size());

                    for (java.util.Map.Entry<String, ProtectedRegion> entry : regions.entrySet()) {
                        String regionId = entry.getKey();
                        ProtectedRegion region = entry.getValue();

                        boolean hasBorders = plugin.getVisualizationManager().hasRegionBorders(regionId);
                        String status = hasBorders ? ChatColor.GREEN + "✅" : ChatColor.RED + "❌";

                        sender.sendMessage("   " + status + " " + regionId + " (" + getCurrentRegionSizeString(region) + ")");
                    }

                } catch (Exception e) {
                    sender.sendMessage(ChatColor.RED + "Ошибка при получении регионов: " + e.getMessage());
                }
            }
        }

        return true;
    }

    /**
     * Вспомогательный метод для проверки твердого блока
     */
    private boolean isSolidBlock(Block block) {
        Material type = block.getType();

        // Исключаем воздух и жидкости
        if (type == Material.AIR ||
                type == Material.CAVE_AIR ||
                type == Material.VOID_AIR ||
                type == Material.WATER ||
                type == Material.LAVA) {
            return false;
        }

        // Исключаем растения и нетвердые блоки
        String typeName = type.toString();
        if (typeName.contains("GRASS") ||
                typeName.contains("FLOWER") ||
                typeName.contains("SAPLING") ||
                type == Material.TORCH ||
                type == Material.REDSTONE_TORCH ||
                type == Material.SNOW ||
                type == Material.POWDER_SNOW) {
            return false;
        }

        return true;
    }

    /**
     * Получает строку с текущим размером региона
     */
    private String getCurrentRegionSizeString(ProtectedRegion region) {
        int sizeX = region.getMaximumPoint().x() - region.getMinimumPoint().x() + 1;
        int sizeY = region.getMaximumPoint().y() - region.getMinimumPoint().y() + 1;
        int sizeZ = region.getMaximumPoint().z() - region.getMinimumPoint().z() + 1;

        return sizeX + "x" + sizeY + "x" + sizeZ;
    }
}