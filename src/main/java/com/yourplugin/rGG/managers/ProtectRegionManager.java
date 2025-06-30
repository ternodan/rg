package com.yourplugin.rGG.managers;

import com.yourplugin.rGG.RGProtectPlugin;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.sk89q.worldguard.protection.regions.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.World as WGWorld;

import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.ChatColor;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Менеджер для работы с регионами WorldGuard
 * Предоставляет расширенный API для управления регионами
 *
 * Возможности:
 * - Создание и удаление регионов
 * - Управление владельцами и участниками
 * - Работа с флагами
 * - Поиск и анализ регионов
 * - Расширение и изменение размеров
 * - Проверки пересечений
 * - Статистика и отчеты
 */
public class ProtectRegionManager {

    private final RGProtectPlugin plugin;

    // Кэш для быстрого доступа к RegionManager'ам
    private final Map<String, RegionManager> regionManagerCache = new ConcurrentHashMap<>();

    // Кэш информации о регионах
    private final Map<String, RegionInfo> regionInfoCache = new ConcurrentHashMap<>();

    // Статистика операций
    private final Map<String, Integer> operationStats = new ConcurrentHashMap<>();

    public ProtectRegionManager(RGProtectPlugin plugin) {
        this.plugin = plugin;
        initializeManager();
    }

    /**
     * Внутренний класс для хранения информации о регионе
     */
    public static class RegionInfo {
        public final String regionId;
        public final String worldName;
        public final Set<UUID> owners;
        public final Set<UUID> members;
        public final BlockVector3 minPoint;
        public final BlockVector3 maxPoint;
        public final long cacheTime;

        public RegionInfo(String regionId, String worldName, Set<UUID> owners, Set<UUID> members,
                          BlockVector3 minPoint, BlockVector3 maxPoint) {
            this.regionId = regionId;
            this.worldName = worldName;
            this.owners = new HashSet<>(owners);
            this.members = new HashSet<>(members);
            this.minPoint = minPoint;
            this.maxPoint = maxPoint;
            this.cacheTime = System.currentTimeMillis();
        }

        public boolean isExpired(long maxAge) {
            return System.currentTimeMillis() - cacheTime > maxAge;
        }

        public int getVolume() {
            return (maxPoint.x() - minPoint.x() + 1) *
                    (maxPoint.y() - minPoint.y() + 1) *
                    (maxPoint.z() - minPoint.z() + 1);
        }

        public Location getCenterLocation(World world) {
            double centerX = (minPoint.x() + maxPoint.x()) / 2.0;
            double centerY = (minPoint.y() + maxPoint.y()) / 2.0;
            double centerZ = (minPoint.z() + maxPoint.z()) / 2.0;
            return new Location(world, centerX, centerY, centerZ);
        }
    }

    /**
     * Инициализирует менеджер
     */
    private void initializeManager() {
        try {
            // Проверяем доступность WorldGuard
            if (!isWorldGuardAvailable()) {
                throw new IllegalStateException("WorldGuard не найден!");
            }

            // Инициализируем статистику
            operationStats.put("regions_created", 0);
            operationStats.put("regions_deleted", 0);
            operationStats.put("regions_modified", 0);
            operationStats.put("cache_hits", 0);
            operationStats.put("cache_misses", 0);

            plugin.getLogger().info("ProtectRegionManager инициализирован");

        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка при инициализации ProtectRegionManager: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Проверяет доступность WorldGuard
     * @return true если WorldGuard доступен
     */
    public boolean isWorldGuardAvailable() {
        try {
            WorldGuard.getInstance();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Получает менеджер регионов WorldGuard для указанного мира
     * @param world Мир Bukkit
     * @return RegionManager или null при ошибке
     */
    public RegionManager getWorldGuardRegionManager(World world) {
        if (world == null) {
            plugin.getLogger().warning("Попытка получить RegionManager для null мира");
            return null;
        }

        try {
            // Проверяем кэш
            String worldName = world.getName();
            RegionManager cached = regionManagerCache.get(worldName);
            if (cached != null) {
                operationStats.merge("cache_hits", 1, Integer::sum);
                return cached;
            }

            operationStats.merge("cache_misses", 1, Integer::sum);

            // Получаем RegionManager
            WorldGuard worldGuard = WorldGuard.getInstance();
            RegionContainer container = worldGuard.getPlatform().getRegionContainer();
            WGWorld wgWorld = BukkitAdapter.adapt(world);

            RegionManager regionManager = container.get(wgWorld);

            // Кэшируем результат
            if (regionManager != null) {
                regionManagerCache.put(worldName, regionManager);
            }

            return regionManager;

        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при получении RegionManager для мира " + world.getName() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Получает регион по ID и миру
     * @param world Мир
     * @param regionId ID региона
     * @return ProtectedRegion или null если не найден
     */
    public ProtectedRegion getRegion(World world, String regionId) {
        if (world == null || regionId == null) {
            return null;
        }

        try {
            RegionManager regionManager = getWorldGuardRegionManager(world);
            if (regionManager == null) {
                return null;
            }

            return regionManager.getRegion(regionId);

        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при получении региона " + regionId + " в мире " + world.getName() + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Находит регион по ID во всех мирах
     * @param regionId ID региона
     * @return ProtectedRegion или null если не найден
     */
    public ProtectedRegion findRegionById(String regionId) {
        if (regionId == null) {
            return null;
        }

        for (World world : plugin.getServer().getWorlds()) {
            ProtectedRegion region = getRegion(world, regionId);
            if (region != null) {
                return region;
            }
        }

        return null;
    }

    /**
     * Получает информацию о регионе с кэшированием
     * @param regionId ID региона
     * @return RegionInfo или null если регион не найден
     */
    public RegionInfo getRegionInfo(String regionId) {
        if (regionId == null) {
            return null;
        }

        // Проверяем кэш
        RegionInfo cached = regionInfoCache.get(regionId);
        if (cached != null && !cached.isExpired(30000)) { // 30 секунд кэш
            operationStats.merge("cache_hits", 1, Integer::sum);
            return cached;
        }

        operationStats.merge("cache_misses", 1, Integer::sum);

        // Ищем регион во всех мирах
        for (World world : plugin.getServer().getWorlds()) {
            ProtectedRegion region = getRegion(world, regionId);
            if (region != null) {
                RegionInfo info = createRegionInfo(region, world.getName());
                regionInfoCache.put(regionId, info);
                return info;
            }
        }

        return null;
    }

    /**
     * Создает объект RegionInfo из ProtectedRegion
     * @param region Регион WorldGuard
     * @param worldName Название мира
     * @return RegionInfo
     */
    private RegionInfo createRegionInfo(ProtectedRegion region, String worldName) {
        Set<UUID> owners = new HashSet<>(region.getOwners().getUniqueIds());
        Set<UUID> members = new HashSet<>(region.getMembers().getUniqueIds());

        return new RegionInfo(
                region.getId(),
                worldName,
                owners,
                members,
                region.getMinimumPoint(),
                region.getMaximumPoint()
        );
    }
    /**
     * Создает новый кубический регион
     * @param world Мир
     * @param regionId ID региона
     * @param pos1 Первая позиция
     * @param pos2 Вторая позиция
     * @param owner Владелец региона
     * @return true если регион создан успешно
     */
    public boolean createRegion(World world, String regionId, Location pos1, Location pos2, Player owner) {
        if (world == null || regionId == null || pos1 == null || pos2 == null || owner == null) {
            plugin.getLogger().warning("Попытка создать регион с null параметрами");
            return false;
        }

        try {
            RegionManager regionManager = getWorldGuardRegionManager(world);
            if (regionManager == null) {
                plugin.getLogger().warning("Не удалось получить RegionManager для мира " + world.getName());
                return false;
            }

            // Проверяем, не существует ли уже регион с таким ID
            if (regionManager.hasRegion(regionId)) {
                plugin.getLogger().warning("Регион с ID " + regionId + " уже существует в мире " + world.getName());
                return false;
            }

            // Создаем точки региона
            BlockVector3 min = BlockVector3.at(
                    Math.min(pos1.getBlockX(), pos2.getBlockX()),
                    Math.min(pos1.getBlockY(), pos2.getBlockY()),
                    Math.min(pos1.getBlockZ(), pos2.getBlockZ())
            );

            BlockVector3 max = BlockVector3.at(
                    Math.max(pos1.getBlockX(), pos2.getBlockX()),
                    Math.max(pos1.getBlockY(), pos2.getBlockY()),
                    Math.max(pos1.getBlockZ(), pos2.getBlockZ())
            );

            // Создаем регион
            ProtectedCuboidRegion region = new ProtectedCuboidRegion(regionId, min, max);

            // Устанавливаем владельца
            DefaultDomain owners = new DefaultDomain();
            owners.addPlayer(owner.getUniqueId());
            region.setOwners(owners);

            // Устанавливаем базовые флаги
            setDefaultFlags(region);

            // Добавляем регион в менеджер
            regionManager.addRegion(region);

            // Сохраняем изменения
            try {
                regionManager.save();
            } catch (Exception e) {
                plugin.getLogger().warning("Ошибка при сохранении региона " + regionId + ": " + e.getMessage());
            }

            // Обновляем статистику и кэш
            operationStats.merge("regions_created", 1, Integer::sum);
            invalidateRegionCache(regionId);

            plugin.getLogger().info("Создан регион " + regionId + " для игрока " + owner.getName() + " в мире " + world.getName());
            return true;

        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка при создании региона " + regionId + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Устанавливает флаги по умолчанию для нового региона
     * @param region Регион для настройки
     */
    private void setDefaultFlags(ProtectedRegion region) {
        try {
            // Базовые флаги защиты
            region.setFlag(Flags.BUILD, StateFlag.State.DENY);
            region.setFlag(Flags.INTERACT, StateFlag.State.DENY);
            region.setFlag(Flags.USE, StateFlag.State.DENY);
            region.setFlag(Flags.DAMAGE_ANIMALS, StateFlag.State.DENY);
            region.setFlag(Flags.CHEST_ACCESS, StateFlag.State.DENY);

            // Дополнительные флаги из конфигурации
            if (plugin.getConfig().getBoolean("default-flags.pvp-deny", true)) {
                region.setFlag(Flags.PVP, StateFlag.State.DENY);
            }

            if (plugin.getConfig().getBoolean("default-flags.mob-spawning-deny", true)) {
                region.setFlag(Flags.MOB_SPAWNING, StateFlag.State.DENY);
            }

        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при установке флагов по умолчанию: " + e.getMessage());
        }
    }

    /**
     * Удаляет регион
     * @param world Мир
     * @param regionId ID региона
     * @return true если регион удален успешно
     */
    public boolean deleteRegion(World world, String regionId) {
        if (world == null || regionId == null) {
            return false;
        }

        try {
            RegionManager regionManager = getWorldGuardRegionManager(world);
            if (regionManager == null) {
                return false;
            }

            ProtectedRegion region = regionManager.getRegion(regionId);
            if (region == null) {
                plugin.getLogger().warning("Регион " + regionId + " не найден для удаления");
                return false;
            }

            // Удаляем регион
            regionManager.removeRegion(regionId);

            // Сохраняем изменения
            try {
                regionManager.save();
            } catch (Exception e) {
                plugin.getLogger().warning("Ошибка при сохранении после удаления региона " + regionId + ": " + e.getMessage());
            }

            // Обновляем статистику и кэш
            operationStats.merge("regions_deleted", 1, Integer::sum);
            invalidateRegionCache(regionId);

            plugin.getLogger().info("Удален регион " + regionId + " из мира " + world.getName());
            return true;

        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка при удалении региона " + regionId + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Проверяет, является ли игрок владельцем региона
     * @param region Регион
     * @param player Игрок
     * @return true если игрок является владельцем
     */
    public boolean isOwner(ProtectedRegion region, Player player) {
        if (region == null || player == null) {
            return false;
        }

        return region.getOwners().contains(player.getUniqueId()) ||
                region.getOwners().contains(player.getName().toLowerCase());
    }

    /**
     * Проверяет, является ли игрок участником региона
     * @param region Регион
     * @param player Игрок
     * @return true если игрок является участником
     */
    public boolean isMember(ProtectedRegion region, Player player) {
        if (region == null || player == null) {
            return false;
        }

        return region.getMembers().contains(player.getUniqueId()) ||
                region.getMembers().contains(player.getName().toLowerCase());
    }

    /**
     * Проверяет, имеет ли игрок доступ к региону (владелец или участник)
     * @param region Регион
     * @param player Игрок
     * @return true если игрок имеет доступ
     */
    public boolean hasAccess(ProtectedRegion region, Player player) {
        return isOwner(region, player) || isMember(region, player);
    }

    /**
     * Сохраняет изменения региона
     * @param world Мир
     * @param regionId ID региона
     */
    private void saveRegionChanges(World world, String regionId) {
        try {
            RegionManager regionManager = getWorldGuardRegionManager(world);
            if (regionManager != null) {
                regionManager.save();
                operationStats.merge("regions_modified", 1, Integer::sum);
                invalidateRegionCache(regionId);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при сохранении изменений региона " + regionId + ": " + e.getMessage());
        }
    }

    /**
     * Аннулирует кэш для региона
     * @param regionId ID региона
     */
    private void invalidateRegionCache(String regionId) {
        regionInfoCache.remove(regionId);
    }
    /**
     * Добавляет владельца к региону
     * @param world Мир
     * @param regionId ID региона
     * @param player Игрок
     * @return true если владелец добавлен успешно
     */
    public boolean addOwner(World world, String regionId, Player player) {
        if (world == null || regionId == null || player == null) {
            plugin.getLogger().warning("Попытка добавить владельца с null параметрами");
            return false;
        }

        try {
            ProtectedRegion region = getRegion(world, regionId);
            if (region == null) {
                plugin.getLogger().warning("Регион " + regionId + " не найден для добавления владельца");
                return false;
            }

            // Проверяем, не является ли игрок уже владельцем
            if (isOwner(region, player)) {
                plugin.getLogger().info("Игрок " + player.getName() + " уже является владельцем региона " + regionId);
                return true;
            }

            DefaultDomain owners = region.getOwners();
            owners.addPlayer(player.getUniqueId());
            region.setOwners(owners);

            // Сохраняем изменения
            saveRegionChanges(world, regionId);

            plugin.getLogger().info("Игрок " + player.getName() + " удален из участников региона " + regionId);
            return true;

        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при удалении участника " + player.getName() + " из региона " + regionId + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Удаляет участника из региона по UUID
     * @param world Мир
     * @param regionId ID региона
     * @param playerUUID UUID игрока
     * @return true если участник удален успешно
     */
    public boolean removeMemberByUUID(World world, String regionId, UUID playerUUID) {
        if (world == null || regionId == null || playerUUID == null) {
            return false;
        }

        try {
            ProtectedRegion region = getRegion(world, regionId);
            if (region == null) {
                return false;
            }

            DefaultDomain members = region.getMembers();
            members.removePlayer(playerUUID);
            region.setMembers(members);

            saveRegionChanges(world, regionId);

            String playerName = plugin.getServer().getOfflinePlayer(playerUUID).getName();
            plugin.getLogger().info("Игрок " + (playerName != null ? playerName : playerUUID.toString()) +
                    " удален из участников региона " + regionId);
            return true;

        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при удалении участника по UUID из региона " + regionId + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Получает список владельцев региона
     * @param world Мир
     * @param regionId ID региона
     * @return Список UUID владельцев
     */
    public Set<UUID> getOwners(World world, String regionId) {
        if (world == null || regionId == null) {
            return new HashSet<>();
        }

        try {
            ProtectedRegion region = getRegion(world, regionId);
            if (region != null) {
                return new HashSet<>(region.getOwners().getUniqueIds());
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при получении владельцев региона " + regionId + ": " + e.getMessage());
        }

        return new HashSet<>();
    }

    /**
     * Получает список участников региона
     * @param world Мир
     * @param regionId ID региона
     * @return Список UUID участников
     */
    public Set<UUID> getMembers(World world, String regionId) {
        if (world == null || regionId == null) {
            return new HashSet<>();
        }

        try {
            ProtectedRegion region = getRegion(world, regionId);
            if (region != null) {
                return new HashSet<>(region.getMembers().getUniqueIds());
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при получении участников региона " + regionId + ": " + e.getMessage());
        }

        return new HashSet<>();
    }

    /**
     * Получает имя основного владельца региона
     * @param region Регион
     * @return Имя владельца или "Unknown"
     */
    public String getPrimaryOwnerName(ProtectedRegion region) {
        if (region == null) {
            return "Unknown";
        }

        try {
            // Сначала пробуем получить по UUID
            Set<UUID> ownerUUIDs = region.getOwners().getUniqueIds();
            if (!ownerUUIDs.isEmpty()) {
                UUID ownerUUID = ownerUUIDs.iterator().next();
                String ownerName = plugin.getServer().getOfflinePlayer(ownerUUID).getName();
                if (ownerName != null) {
                    return ownerName;
                }
            }

            // Затем по именам (старый формат)
            Set<String> ownerNames = region.getOwners().getPlayers();
            if (!ownerNames.isEmpty()) {
                return ownerNames.iterator().next();
            }

        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при получении имени владельца региона " + region.getId() + ": " + e.getMessage());
        }

        return "Unknown";
    }

    /**
     * Получает регионы игрока в мире
     * @param world Мир
     * @param player Игрок
     * @param includeMembers Включать регионы где игрок является участником
     * @return Список регионов
     */
    public List<ProtectedRegion> getPlayerRegions(World world, Player player, boolean includeMembers) {
        List<ProtectedRegion> playerRegions = new ArrayList<>();

        if (world == null || player == null) {
            return playerRegions;
        }

        try {
            RegionManager regionManager = getWorldGuardRegionManager(world);
            if (regionManager == null) {
                return playerRegions;
            }

            for (ProtectedRegion region : regionManager.getRegions().values()) {
                if (isOwner(region, player) || (includeMembers && isMember(region, player))) {
                    playerRegions.add(region);
                }
            }

        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при получении регионов игрока " + player.getName() + ": " + e.getMessage());
        }

        return playerRegions;
    }

    /**
     * Подсчитывает количество регионов игрока
     * @param player Игрок
     * @param includeMembers Включать регионы где игрок является участником
     * @return Количество регионов
     */
    public int getPlayerRegionCount(Player player, boolean includeMembers) {
        if (player == null) {
            return 0;
        }

        int count = 0;
        for (World world : plugin.getServer().getWorlds()) {
            count += getPlayerRegions(world, player, includeMembers).size();
        }

        return count;
    }

    /**
     * Проверяет, достиг ли игрок лимита регионов
     * @param player Игрок
     * @return true если лимит достигнут
     */
    public boolean hasReachedRegionLimit(Player player) {
        if (player == null) {
            return true;
        }

        int limit = getRegionLimitForPlayer(player);
        if (limit <= 0) {
            return false; // Без лимита
        }

        int currentCount = getPlayerRegionCount(player, false);
        return currentCount >= limit;
    }

    /**
     * Получает лимит регионов для игрока на основе прав доступа
     * @param player Игрок
     * @return Лимит регионов для игрока (-1 если без лимита)
     */
    private int getRegionLimitForPlayer(Player player) {
        if (player == null) {
            return 0;
        }

        // Проверяем права на неограниченные регионы
        if (player.hasPermission("rgprotect.regions.unlimited")) {
            return -1; // Без лимита
        }

        // Проверяем специальные лимиты по правам (от большего к меньшему)
        for (int limit = 100; limit >= 1; limit--) {
            if (player.hasPermission("rgprotect.regions.limit." + limit)) {
                return limit;
            }
        }

        // Возвращаем лимит по умолчанию из конфигурации
        return plugin.getConfig().getInt("region-limits.default", 3);
    }

    /**
     * Получает все UUID связанные с регионом (владельцы + участники)
     * @param world Мир
     * @param regionId ID региона
     * @return Set всех UUID
     */
    public Set<UUID> getAllRegionMembers(World world, String regionId) {
        Set<UUID> allMembers = new HashSet<>();
        allMembers.addAll(getOwners(world, regionId));
        allMembers.addAll(getMembers(world, regionId));
        return allMembers;
    }

    /**
     * Получает имена владельцев региона
     * @param world Мир
     * @param regionId ID региона
     * @return Список имен владельцев
     */
    public List<String> getOwnerNames(World world, String regionId) {
        List<String> ownerNames = new ArrayList<>();

        try {
            Set<UUID> ownerUUIDs = getOwners(world, regionId);
            for (UUID uuid : ownerUUIDs) {
                String name = plugin.getServer().getOfflinePlayer(uuid).getName();
                if (name != null) {
                    ownerNames.add(name);
                } else {
                    ownerNames.add(uuid.toString());
                }
            }

            // Также добавляем имена из строковых записей (старый формат)
            ProtectedRegion region = getRegion(world, regionId);
            if (region != null) {
                ownerNames.addAll(region.getOwners().getPlayers());
            }

        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при получении имен владельцев региона " + regionId + ": " + e.getMessage());
        }

        return ownerNames;
    }

    /**
     * Получает имена участников региона
     * @param world Мир
     * @param regionId ID региона
     * @return Список имен участников
     */
    public List<String> getMemberNames(World world, String regionId) {
        List<String> memberNames = new ArrayList<>();

        try {
            Set<UUID> memberUUIDs = getMembers(world, regionId);
            for (UUID uuid : memberUUIDs) {
                String name = plugin.getServer().getOfflinePlayer(uuid).getName();
                if (name != null) {
                    memberNames.add(name);
                } else {
                    memberNames.add(uuid.toString());
                }
            }

            // Также добавляем имена из строковых записей (старый формат)
            ProtectedRegion region = getRegion(world, regionId);
            if (region != null) {
                memberNames.addAll(region.getMembers().getPlayers());
            }

        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при получении имен участников региона " + regionId + ": " + e.getMessage());
        }

        return memberNames;
    }

    /**
     * Получает UUID основного владельца региона
     * @param region Регион
     * @return UUID владельца или null
     */
    public UUID getPrimaryOwnerUUID(ProtectedRegion region) {
        if (region == null) {
            return null;
        }

        try {
            Set<UUID> ownerUUIDs = region.getOwners().getUniqueIds();
            if (!ownerUUIDs.isEmpty()) {
                return ownerUUIDs.iterator().next();
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при получении UUID владельца региона " + region.getId() + ": " + e.getMessage());
        }

        return null;
    }

    /**
     * Проверяет, является ли игрок единственным владельцем региона
     * @param region Регион
     * @param player Игрок
     * @return true если игрок единственный владелец
     */
    public boolean isSoleOwner(ProtectedRegion region, Player player) {
        if (region == null || player == null) {
            return false;
        }

        try {
            Set<UUID> ownerUUIDs = region.getOwners().getUniqueIds();
            Set<String> ownerNames = region.getOwners().getPlayers();

            return (ownerUUIDs.size() == 1 && ownerUUIDs.contains(player.getUniqueId())) ||
                    (ownerNames.size() == 1 && ownerNames.contains(player.getName().toLowerCase()) && ownerUUIDs.isEmpty());

        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при проверке единственного владельца: " + e.getMessage());
            return false;
        }
    }

    /**
     * Передает право владения регионом другому игроку
     * @param world Мир
     * @param regionId ID региона
     * @param currentOwner Текущий владелец
     * @param newOwner Новый владелец
     * @return true если право владения передано успешно
     */
    public boolean transferOwnership(World world, String regionId, Player currentOwner, Player newOwner) {
        if (world == null || regionId == null || currentOwner == null || newOwner == null) {
            plugin.getLogger().warning("Попытка передать право владения с null параметрами");
            return false;
        }

        try {
            ProtectedRegion region = getRegion(world, regionId);
            if (region == null) {
                plugin.getLogger().warning("Регион " + regionId + " не найден для передачи права владения");
                return false;
            }

            // Проверяем, является ли текущий игрок владельцем
            if (!isOwner(region, currentOwner)) {
                plugin.getLogger().warning("Игрок " + currentOwner.getName() + " не является владельцем региона " + regionId);
                return false;
            }

            // Удаляем текущего владельца
            DefaultDomain owners = region.getOwners();
            owners.removePlayer(currentOwner.getUniqueId());
            owners.removePlayer(currentOwner.getName().toLowerCase());

            // Добавляем нового владельца
            owners.addPlayer(newOwner.getUniqueId());
            region.setOwners(owners);

            // Удаляем нового владельца из участников если он там был
            DefaultDomain members = region.getMembers();
            members.removePlayer(newOwner.getUniqueId());
            members.removePlayer(newOwner.getName().toLowerCase());
            region.setMembers(members);

            // Сохраняем изменения
            saveRegionChanges(world, regionId);

            plugin.getLogger().info("Право владения регионом " + regionId + " передано от " +
                    currentOwner.getName() + " к " + newOwner.getName());
            return true;

        } catch (Exception e) {
            plugin.getLogger().severe("Ошибка при передаче права владения регионом " + regionId + ": " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    /**
     * Очищает всех владельцев и участников региона
     * @param world Мир
     * @param regionId ID региона
     * @return true если очистка прошла успешно
     */
    public boolean clearAllMembers(World world, String regionId) {
        if (world == null || regionId == null) {
            return false;
        }

        try {
            ProtectedRegion region = getRegion(world, regionId);
            if (region == null) {
                return false;
            }

            // Очищаем владельцев и участников
            region.setOwners(new DefaultDomain());
            region.setMembers(new DefaultDomain());

            saveRegionChanges(world, regionId);

            plugin.getLogger().info("Очищены все владельцы и участники региона " + regionId);
            return true;

        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при очистке участников региона " + regionId + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Получает регионы игрока по UUID в мире
     * @param world Мир
     * @param playerUUID UUID игрока
     * @param includeMembers Включать регионы где игрок является участником
     * @return Список регионов
     */
    public List<ProtectedRegion> getPlayerRegionsByUUID(World world, UUID playerUUID, boolean includeMembers) {
        List<ProtectedRegion> playerRegions = new ArrayList<>();

        if (world == null || playerUUID == null) {
            return playerRegions;
        }

        try {
            RegionManager regionManager = getWorldGuardRegionManager(world);
            if (regionManager == null) {
                return playerRegions;
            }

            for (ProtectedRegion region : regionManager.getRegions().values()) {
                boolean isOwner = region.getOwners().contains(playerUUID);
                boolean isMember = region.getMembers().contains(playerUUID);

                if (isOwner || (includeMembers && isMember)) {
                    playerRegions.add(region);
                }
            }

        } catch (Exception e) {
            plugin.getLogger().warning("Ошибка при получении регионов игрока по UUID " + playerUUID + ": " + e.getMessage());
        }

        return playerRegions;
    }

    /**
     * Получает все регионы игрока во всех мирах
     * @param player Игрок
     * @param includeMembers Включать регионы где игрок является участником
     * @return Map: имя мира -> список регионов
     */
    public Map<String, List<ProtectedRegion>> getAllPlayerRegions(Player player, boolean includeMembers) {
        Map<String, List<ProtectedRegion>> allRegions = new HashMap<>();

        if (player == null) {
            return allRegions;
        }

        for (World world : plugin.getServer().getWorlds()) {
            List<ProtectedRegion> worldRegions = getPlayerRegions(world, player, includeMembers);
            if (!worldRegions.isEmpty()) {
                allRegions.put(world.getName(), worldRegions);
            }
        }

        return allRegions;
    }

    /**
     * Получает все регионы игрока во всех мирах по UUID
     * @param playerUUID UUID игрока
     * @param includeMembers Включать регионы где игрок является участником
     * @return Map: имя мира -> список регионов
     */
    public Map<String, List<ProtectedRegion>> getAllPlayerRegionsByUUID(UUID playerUUID, boolean includeMembers) {
        Map<String, List<ProtectedRegion>> allRegions = new HashMap<>();

        if (playerUUID == null) {
            return allRegions;
        }

        for (World world : plugin.getServer().getWorlds()) {
            List<ProtectedRegion> worldRegions = getPlayerRegionsByUUID(world, playerUUID, includeMembers);
            if (!worldRegions.isEmpty()) {
                allRegions.put(world.getName(), worldRegions);
            }
        }

        return allRegions;
    }

    /**
     * Подсчитывает количество регионов игрока по UUID
     * @param playerUUID UUID игрока
     * @param includeMembers Включать регионы где игрок является участником
     * @return Количество регионов
     */
    public int getPlayerRegionCountByUUID(UUID playerUUID, boolean includeMembers) {
        if (playerUUID == null) {
            return 0;
        }

        int count = 0;
        for (World world : plugin.getServer().getWorlds()) {
            count += getPlayerRegionsByUUID(world, playerUUID, includeMembers).size();
        }

        return count;
    }

    /**
     * Получает оставшееся количество регионов которые может создать игрок
     * @param player Игрок
     * @return Количество оставшихся регионов (-1 если без лимита)
     */
    public int getRemainingRegionCount(Player player) {
        if (player == null) {
            return 0;
        }

        int limit = getRegionLimitForPlayer(player);
        if (limit <= 0) {
            return -1; // Без лимита
        }

        int currentCount = getPlayerRegionCount(player, false);
        return Math.max(0, limit - currentCount);
    }няем изменения
    saveRegionChanges(world, regionId);

            plugin.getLogger().info("Игрок " + player.getName() + " добавлен как владелец региона " + regionId);
            return true;

} catch (Exception e) {
        plugin.getLogger().warning("Ошибка при добавлении владельца " + player.getName() + " к региону " + regionId + ": " + e.getMessage());
        e.printStackTrace();
            return false;
                    }
                    }

/**
 * Добавляет владельца к региону по UUID
 * @param world Мир
 * @param regionId ID региона
 * @param playerUUID UUID игрока
 * @return true если владелец добавлен успешно
 */
public boolean addOwnerByUUID(World world, String regionId, UUID playerUUID) {
    if (world == null || regionId == null || playerUUID == null) {
        return false;
    }

    try {
        ProtectedRegion region = getRegion(world, regionId);
        if (region == null) {
            return false;
        }

        DefaultDomain owners = region.getOwners();
        owners.addPlayer(playerUUID);
        region.setOwners(owners);

        saveRegionChanges(world, regionId);

        String playerName = plugin.getServer().getOfflinePlayer(playerUUID).getName();
        plugin.getLogger().info("Игрок " + (playerName != null ? playerName : playerUUID.toString()) +
                " добавлен как владелец региона " + regionId);
        return true;

    } catch (Exception e) {
        plugin.getLogger().warning("Ошибка при добавлении владельца по UUID к региону " + regionId + ": " + e.getMessage());
        return false;
    }
}

/**
 * Удаляет владельца из региона
 * @param world Мир
 * @param regionId ID региона
 * @param player Игрок
 * @return true если владелец удален успешно
 */
public boolean removeOwner(World world, String regionId, Player player) {
    if (world == null || regionId == null || player == null) {
        plugin.getLogger().warning("Попытка удалить владельца с null параметрами");
        return false;
    }

    try {
        ProtectedRegion region = getRegion(world, regionId);
        if (region == null) {
            plugin.getLogger().warning("Регион " + regionId + " не найден для удаления владельца");
            return false;
        }

        // Проверяем, является ли игрок владельцем
        if (!isOwner(region, player)) {
            plugin.getLogger().info("Игрок " + player.getName() + " не является владельцем региона " + regionId);
            return true;
        }

        DefaultDomain owners = region.getOwners();
        owners.removePlayer(player.getUniqueId());
        owners.removePlayer(player.getName().toLowerCase());
        region.setOwners(owners);

        // Сохраняем изменения
        saveRegionChanges(world, regionId);

        plugin.getLogger().info("Игрок " + player.getName() + " удален из владельцев региона " + regionId);
        return true;

    } catch (Exception e) {
        plugin.getLogger().warning("Ошибка при удалении владельца " + player.getName() + " из региона " + regionId + ": " + e.getMessage());
        e.printStackTrace();
        return false;
    }
}

/**
 * Удаляет владельца из региона по UUID
 * @param world Мир
 * @param regionId ID региона
 * @param playerUUID UUID игрока
 * @return true если владелец удален успешно
 */
public boolean removeOwnerByUUID(World world, String regionId, UUID playerUUID) {
    if (world == null || regionId == null || playerUUID == null) {
        return false;
    }

    try {
        ProtectedRegion region = getRegion(world, regionId);
        if (region == null) {
            return false;
        }

        DefaultDomain owners = region.getOwners();
        owners.removePlayer(playerUUID);
        region.setOwners(owners);

        saveRegionChanges(world, regionId);

        String playerName = plugin.getServer().getOfflinePlayer(playerUUID).getName();
        plugin.getLogger().info("Игрок " + (playerName != null ? playerName : playerUUID.toString()) +
                " удален из владельцев региона " + regionId);
        return true;

    } catch (Exception e) {
        plugin.getLogger().warning("Ошибка при удалении владельца по UUID из региона " + regionId + ": " + e.getMessage());
        return false;
    }
}

/**
 * Добавляет участника к региону
 * @param world Мир
 * @param regionId ID региона
 * @param player Игрок
 * @return true если участник добавлен успешно
 */
public boolean addMember(World world, String regionId, Player player) {
    if (world == null || regionId == null || player == null) {
        plugin.getLogger().warning("Попытка добавить участника с null параметрами");
        return false;
    }

    try {
        ProtectedRegion region = getRegion(world, regionId);
        if (region == null) {
            plugin.getLogger().warning("Регион " + regionId + " не найден для добавления участника");
            return false;
        }

        // Проверяем, не является ли игрок уже участником или владельцем
        if (isMember(region, player) || isOwner(region, player)) {
            plugin.getLogger().info("Игрок " + player.getName() + " уже имеет доступ к региону " + regionId);
            return true;
        }

        DefaultDomain members = region.getMembers();
        members.addPlayer(player.getUniqueId());
        region.setMembers(members);

        // Сохраняем изменения
        saveRegionChanges(world, regionId);

        plugin.getLogger().info("Игрок " + player.getName() + " добавлен как участник региона " + regionId);
        return true;

    } catch (Exception e) {
        plugin.getLogger().warning("Ошибка при добавлении участника " + player.getName() + " к региону " + regionId + ": " + e.getMessage());
        e.printStackTrace();
        return false;
    }
}

/**
 * Добавляет участника к региону по UUID
 * @param world Мир
 * @param regionId ID региона
 * @param playerUUID UUID игрока
 * @return true если участник добавлен успешно
 */
public boolean addMemberByUUID(World world, String regionId, UUID playerUUID) {
    if (world == null || regionId == null || playerUUID == null) {
        return false;
    }

    try {
        ProtectedRegion region = getRegion(world, regionId);
        if (region == null) {
            return false;
        }

        DefaultDomain members = region.getMembers();
        members.addPlayer(playerUUID);
        region.setMembers(members);

        saveRegionChanges(world, regionId);

        String playerName = plugin.getServer().getOfflinePlayer(playerUUID).getName();
        plugin.getLogger().info("Игрок " + (playerName != null ? playerName : playerUUID.toString()) +
                " добавлен как участник региона " + regionId);
        return true;

    } catch (Exception e) {
        plugin.getLogger().warning("Ошибка при добавлении участника по UUID к региону " + regionId + ": " + e.getMessage());
        return false;
    }
}

/**
 * Удаляет участника из региона
 * @param world Мир
 * @param regionId ID региона
 * @param player Игрок
 * @return true если участник удален успешно
 */
public boolean removeMember(World world, String regionId, Player player) {
    if (world == null || regionId == null || player == null) {
        plugin.getLogger().warning("Попытка удалить участника с null параметрами");
        return false;
    }

    try {
        ProtectedRegion region = getRegion(world, regionId);
        if (region == null) {
            plugin.getLogger().warning("Регион " + regionId + " не найден для удаления участника");
            return false;
        }

        // Проверяем, является ли игрок участником
        if (!isMember(region, player)) {
            plugin.getLogger().info("Игрок " + player.getName() + " не является участником региона " + regionId);
            return true;
        }

        DefaultDomain members = region.getMembers();
        members.removePlayer(player.getUniqueId());
        members.removePlayer(player.getName().toLowerCase());
        region.setMembers(members);

        // Сохраняем изменения
        saveRegionChanges(world, regionId);

        plugin.getLogger().info("Игрок " + player.getName() + " удален из участников региона " + regionId);
        return true;

    } catch (Exception e) {
        plugin.getLogger().warning("Ошибка при удалении участника " + player.getName() + " из региона " + regionId + ": " + e.getMessage());
        e.printStackTrace();
        return false;
    }
}
/**
 * Добавляет владельца к региону
 * @param world Мир
 * @param regionId ID региона
 * @param player Игрок
 * @return true если владелец добавлен успешно
 */
public boolean addOwner(World world, String regionId, Player player) {
    if (world == null || regionId == null || player == null) {
        plugin.getLogger().warning("Попытка добавить владельца с null параметрами");
        return false;
    }

    try {
        ProtectedRegion region = getRegion(world, regionId);
        if (region == null) {
            plugin.getLogger().warning("Регион " + regionId + " не найден для добавления владельца");
            return false;
        }

        // Проверяем, не является ли игрок уже владельцем
        if (isOwner(region, player)) {
            plugin.getLogger().info("Игрок " + player.getName() + " уже является владельцем региона " + regionId);
            return true;
        }

        DefaultDomain owners = region.getOwners();
        owners.addPlayer(player.getUniqueId());
        region.setOwners(owners);

        // Сохраняем изменения
        saveRegionChanges(world, regionId);

        plugin.getLogger().info("Игрок " + player.getName() + " удален из участников региона " + regionId);
        return true;

    } catch (Exception e) {
        plugin.getLogger().warning("Ошибка при удалении участника " + player.getName() + " из региона " + regionId + ": " + e.getMessage());
        e.printStackTrace();
        return false;
    }
}

/**
 * Удаляет участника из региона по UUID
 * @param world Мир
 * @param regionId ID региона
 * @param playerUUID UUID игрока
 * @return true если участник удален успешно
 */
public boolean removeMemberByUUID(World world, String regionId, UUID playerUUID) {
    if (world == null || regionId == null || playerUUID == null) {
        return false;
    }

    try {
        ProtectedRegion region = getRegion(world, regionId);
        if (region == null) {
            return false;
        }

        DefaultDomain members = region.getMembers();
        members.removePlayer(playerUUID);
        region.setMembers(members);

        saveRegionChanges(world, regionId);

        String playerName = plugin.getServer().getOfflinePlayer(playerUUID).getName();
        plugin.getLogger().info("Игрок " + (playerName != null ? playerName : playerUUID.toString()) +
                " удален из участников региона " + regionId);
        return true;

    } catch (Exception e) {
        plugin.getLogger().warning("Ошибка при удалении участника по UUID из региона " + regionId + ": " + e.getMessage());
        return false;
    }
}

/**
 * Получает список владельцев региона
 * @param world Мир
 * @param regionId ID региона
 * @return Список UUID владельцев
 */
public Set<UUID> getOwners(World world, String regionId) {
    if (world == null || regionId == null) {
        return new HashSet<>();
    }

    try {
        ProtectedRegion region = getRegion(world, regionId);
        if (region != null) {
            return new HashSet<>(region.getOwners().getUniqueIds());
        }
    } catch (Exception e) {
        plugin.getLogger().warning("Ошибка при получении владельцев региона " + regionId + ": " + e.getMessage());
    }

    return new HashSet<>();
}

/**
 * Получает список участников региона
 * @param world Мир
 * @param regionId ID региона
 * @return Список UUID участников
 */
public Set<UUID> getMembers(World world, String regionId) {
    if (world == null || regionId == null) {
        return new HashSet<>();
    }

    try {
        ProtectedRegion region = getRegion(world, regionId);
        if (region != null) {
            return new HashSet<>(region.getMembers().getUniqueIds());
        }
    } catch (Exception e) {
        plugin.getLogger().warning("Ошибка при получении участников региона " + regionId + ": " + e.getMessage());
    }

    return new HashSet<>();
}

/**
 * Получает имя основного владельца региона
 * @param region Регион
 * @return Имя владельца или "Unknown"
 */
public String getPrimaryOwnerName(ProtectedRegion region) {
    if (region == null) {
        return "Unknown";
    }

    try {
        // Сначала пробуем получить по UUID
        Set<UUID> ownerUUIDs = region.getOwners().getUniqueIds();
        if (!ownerUUIDs.isEmpty()) {
            UUID ownerUUID = ownerUUIDs.iterator().next();
            String ownerName = plugin.getServer().getOfflinePlayer(ownerUUID).getName();
            if (ownerName != null) {
                return ownerName;
            }
        }

        // Затем по именам (старый формат)
        Set<String> ownerNames = region.getOwners().getPlayers();
        if (!ownerNames.isEmpty()) {
            return ownerNames.iterator().next();
        }

    } catch (Exception e) {
        plugin.getLogger().warning("Ошибка при получении имени владельца региона " + region.getId() + ": " + e.getMessage());
    }

    return "Unknown";
}

/**
 * Получает регионы игрока в мире
 * @param world Мир
 * @param player Игрок
 * @param includeMembers Включать регионы где игрок является участником
 * @return Список регионов
 */
public List<ProtectedRegion> getPlayerRegions(World world, Player player, boolean includeMembers) {
    List<ProtectedRegion> playerRegions = new ArrayList<>();

    if (world == null || player == null) {
        return playerRegions;
    }

    try {
        RegionManager regionManager = getWorldGuardRegionManager(world);
        if (regionManager == null) {
            return playerRegions;
        }

        for (ProtectedRegion region : regionManager.getRegions().values()) {
            if (isOwner(region, player) || (includeMembers && isMember(region, player))) {
                playerRegions.add(region);
            }
        }

    } catch (Exception e) {
        plugin.getLogger().warning("Ошибка при получении регионов игрока " + player.getName() + ": " + e.getMessage());
    }

    return playerRegions;
}

/**
 * Подсчитывает количество регионов игрока
 * @param player Игрок
 * @param includeMembers Включать регионы где игрок является участником
 * @return Количество регионов
 */
public int getPlayerRegionCount(Player player, boolean includeMembers) {
    if (player == null) {
        return 0;
    }

    int count = 0;
    for (World world : plugin.getServer().getWorlds()) {
        count += getPlayerRegions(world, player, includeMembers).size();
    }

    return count;
}

/**
 * Проверяет, достиг ли игрок лимита регионов
 * @param player Игрок
 * @return true если лимит достигнут
 */
public boolean hasReachedRegionLimit(Player player) {
    if (player == null) {
        return true;
    }

    int limit = getRegionLimitForPlayer(player);
    if (limit <= 0) {
        return false; // Без лимита
    }

    int currentCount = getPlayerRegionCount(player, false);
    return currentCount >= limit;
}

/**
 * Получает лимит регионов для игрока на основе прав доступа
 * @param player Игрок
 * @return Лимит регионов для игрока (-1 если без лимита)
 */
private int getRegionLimitForPlayer(Player player) {
    if (player == null) {
        return 0;
    }

    // Проверяем права на неограниченные регионы
    if (player.hasPermission("rgprotect.regions.unlimited")) {
        return -1; // Без лимита
    }

    // Проверяем специальные лимиты по правам (от большего к меньшему)
    for (int limit = 100; limit >= 1; limit--) {
        if (player.hasPermission("rgprotect.regions.limit." + limit)) {
            return limit;
        }
    }

    // Возвращаем лимит по умолчанию из конфигурации
    return plugin.getConfig().getInt("region-limits.default", 3);
}

/**
 * Получает все UUID связанные с регионом (владельцы + участники)
 * @param world Мир
 * @param regionId ID региона
 * @return Set всех UUID
 */
public Set<UUID> getAllRegionMembers(World world, String regionId) {
    Set<UUID> allMembers = new HashSet<>();
    allMembers.addAll(getOwners(world, regionId));
    allMembers.addAll(getMembers(world, regionId));
    return allMembers;
}

/**
 * Получает имена владельцев региона
 * @param world Мир
 * @param regionId ID региона
 * @return Список имен владельцев
 */
public List<String> getOwnerNames(World world, String regionId) {
    List<String> ownerNames = new ArrayList<>();

    try {
        Set<UUID> ownerUUIDs = getOwners(world, regionId);
        for (UUID uuid : ownerUUIDs) {
            String name = plugin.getServer().getOfflinePlayer(uuid).getName();
            if (name != null) {
                ownerNames.add(name);
            } else {
                ownerNames.add(uuid.toString());
            }
        }

        // Также добавляем имена из строковых записей (старый формат)
        ProtectedRegion region = getRegion(world, regionId);
        if (region != null) {
            ownerNames.addAll(region.getOwners().getPlayers());
        }

    } catch (Exception e) {
        plugin.getLogger().warning("Ошибка при получении имен владельцев региона " + regionId + ": " + e.getMessage());
    }

    return ownerNames;
}

/**
 * Получает имена участников региона
 * @param world Мир
 * @param regionId ID региона
 * @return Список имен участников
 */
public List<String> getMemberNames(World world, String regionId) {
    List<String> memberNames = new ArrayList<>();

    try {
        Set<UUID> memberUUIDs = getMembers(world, regionId);
        for (UUID uuid : memberUUIDs) {
            String name = plugin.getServer().getOfflinePlayer(uuid).getName();
            if (name != null) {
                memberNames.add(name);
            } else {
                memberNames.add(uuid.toString());
            }
        }

        // Также добавляем имена из строковых записей (старый формат)
        ProtectedRegion region = getRegion(world, regionId);
        if (region != null) {
            memberNames.addAll(region.getMembers().getPlayers());
        }

    } catch (Exception e) {
        plugin.getLogger().warning("Ошибка при получении имен участников региона " + regionId + ": " + e.getMessage());
    }

    return memberNames;
}

/**
 * Получает UUID основного владельца региона
 * @param region Регион
 * @return UUID владельца или null
 */
public UUID getPrimaryOwnerUUID(ProtectedRegion region) {
    if (region == null) {
        return null;
    }

    try {
        Set<UUID> ownerUUIDs = region.getOwners().getUniqueIds();
        if (!ownerUUIDs.isEmpty()) {
            return ownerUUIDs.iterator().next();
        }
    } catch (Exception e) {
        plugin.getLogger().warning("Ошибка при получении UUID владельца региона " + region.getId() + ": " + e.getMessage());
    }

    return null;
}

/**
 * Проверяет, является ли игрок единственным владельцем региона
 * @param region Регион
 * @param player Игрок
 * @return true если игрок единственный владелец
 */
public boolean isSoleOwner(ProtectedRegion region, Player player) {
    if (region == null || player == null) {
        return false;
    }

    try {
        Set<UUID> ownerUUIDs = region.getOwners().getUniqueIds();
        Set<String> ownerNames = region.getOwners().getPlayers();

        return (ownerUUIDs.size() == 1 && ownerUUIDs.contains(player.getUniqueId())) ||
                (ownerNames.size() == 1 && ownerNames.contains(player.getName().toLowerCase()) && ownerUUIDs.isEmpty());

    } catch (Exception e) {
        plugin.getLogger().warning("Ошибка при проверке единственного владельца: " + e.getMessage());
        return false;
    }
}

/**
 * Передает право владения регионом другому игроку
 * @param world Мир
 * @param regionId ID региона
 * @param currentOwner Текущий владелец
 * @param newOwner Новый владелец
 * @return true если право владения передано успешно
 */
public boolean transferOwnership(World world, String regionId, Player currentOwner, Player newOwner) {
    if (world == null || regionId == null || currentOwner == null || newOwner == null) {
        plugin.getLogger().warning("Попытка передать право владения с null параметрами");
        return false;
    }

    try {
        ProtectedRegion region = getRegion(world, regionId);
        if (region == null) {
            plugin.getLogger().warning("Регион " + regionId + " не найден для передачи права владения");
            return false;
        }

        // Проверяем, является ли текущий игрок владельцем
        if (!isOwner(region, currentOwner)) {
            plugin.getLogger().warning("Игрок " + currentOwner.getName() + " не является владельцем региона " + regionId);
            return false;
        }

        // Удаляем текущего владельца
        DefaultDomain owners = region.getOwners();
        owners.removePlayer(currentOwner.getUniqueId());
        owners.removePlayer(currentOwner.getName().toLowerCase());

        // Добавляем нового владельца
        owners.addPlayer(newOwner.getUniqueId());
        region.setOwners(owners);

        // Удаляем нового владельца из участников если он там был
        DefaultDomain members = region.getMembers();
        members.removePlayer(newOwner.getUniqueId());
        members.removePlayer(newOwner.getName().toLowerCase());
        region.setMembers(members);

        // Сохраняем изменения
        saveRegionChanges(world, regionId);

        plugin.getLogger().info("Право владения регионом " + regionId + " передано от " +
                currentOwner.getName() + " к " + newOwner.getName());
        return true;

    } catch (Exception e) {
        plugin.getLogger().severe("Ошибка при передаче права владения регионом " + regionId + ": " + e.getMessage());
        e.printStackTrace();
        return false;
    }
}

/**
 * Очищает всех владельцев и участников региона
 * @param world Мир
 * @param regionId ID региона
 * @return true если очистка прошла успешно
 */
public boolean clearAllMembers(World world, String regionId) {
    if (world == null || regionId == null) {
        return false;
    }

    try {
        ProtectedRegion region = getRegion(world, regionId);
        if (region == null) {
            return false;
        }

        // Очищаем владельцев и участников
        region.setOwners(new DefaultDomain());
        region.setMembers(new DefaultDomain());

        saveRegionChanges(world, regionId);

        plugin.getLogger().info("Очищены все владельцы и участники региона " + regionId);
        return true;

    } catch (Exception e) {
        plugin.getLogger().warning("Ошибка при очистке участников региона " + regionId + ": " + e.getMessage());
        return false;
    }
}

/**
 * Получает регионы игрока по UUID в мире
 * @param world Мир
 * @param playerUUID UUID игрока
 * @param includeMembers Включать регионы где игрок является участником
 * @return Список регионов
 */
public List<ProtectedRegion> getPlayerRegionsByUUID(World world, UUID playerUUID, boolean includeMembers) {
    List<ProtectedRegion> playerRegions = new ArrayList<>();

    if (world == null || playerUUID == null) {
        return playerRegions;
    }

    try {
        RegionManager regionManager = getWorldGuardRegionManager(world);
        if (regionManager == null) {
            return playerRegions;
        }

        for (ProtectedRegion region : regionManager.getRegions().values()) {
            boolean isOwner = region.getOwners().contains(playerUUID);
            boolean isMember = region.getMembers().contains(playerUUID);

            if (isOwner || (includeMembers && isMember)) {
                playerRegions.add(region);
            }
        }

    } catch (Exception e) {
        plugin.getLogger().warning("Ошибка при получении регионов игрока по UUID " + playerUUID + ": " + e.getMessage());
    }

    return playerRegions;
}

/**
 * Получает все регионы игрока во всех мирах
 * @param player Игрок
 * @param includeMembers Включать регионы где игрок является участником
 * @return Map: имя мира -> список регионов
 */
public Map<String, List<ProtectedRegion>> getAllPlayerRegions(Player player, boolean includeMembers) {
    Map<String, List<ProtectedRegion>> allRegions = new HashMap<>();

    if (player == null) {
        return allRegions;
    }

    for (World world : plugin.getServer().getWorlds()) {
        List<ProtectedRegion> worldRegions = getPlayerRegions(world, player, includeMembers);
        if (!worldRegions.isEmpty()) {
            allRegions.put(world.getName(), worldRegions);
        }
    }

    return allRegions;
}

/**
 * Получает все регионы игрока во всех мирах по UUID
 * @param playerUUID UUID игрока
 * @param includeMembers Включать регионы где игрок является участником
 * @return Map: имя мира -> список регионов
 */
public Map<String, List<ProtectedRegion>> getAllPlayerRegionsByUUID(UUID playerUUID, boolean includeMembers) {
    Map<String, List<ProtectedRegion>> allRegions = new HashMap<>();

    if (playerUUID == null) {
        return allRegions;
    }

    for (World world : plugin.getServer().getWorlds()) {
        List<ProtectedRegion> worldRegions = getPlayerRegionsByUUID(world, playerUUID, includeMembers);
        if (!worldRegions.isEmpty()) {
            allRegions.put(world.getName(), worldRegions);
        }
    }

    return allRegions;
}

/**
 * Подсчитывает количество регионов игрока по UUID
 * @param playerUUID UUID игрока
 * @param includeMembers Включать регионы где игрок является участником
 * @return Количество регионов
 */
public int getPlayerRegionCountByUUID(UUID playerUUID, boolean includeMembers) {
    if (playerUUID == null) {
        return 0;
    }

    int count = 0;
    for (World world : plugin.getServer().getWorlds()) {
        count += getPlayerRegionsByUUID(world, playerUUID, includeMembers).size();
    }

    return count;
}

/**
 * Получает оставшееся количество регионов которые может создать игрок
 * @param player Игрок
 * @return Количество оставшихся регионов (-1 если без лимита)
 */
public int getRemainingRegionCount(Player player) {
    if (player == null) {
        return 0;
    }

    int limit = getRegionLimitForPlayer(player);
    if (limit <= 0) {
        return -1; // Без лимита
    }

    int currentCount = getPlayerRegionCount(player, false);
    return Math.max(0, limit - currentCount);
}няем изменения
saveRegionChanges(world, regionId);

            plugin.getLogger().info("Игрок " + player.getName() + " добавлен как владелец региона " + regionId);
        return true;

        } catch (Exception e) {
        plugin.getLogger().warning("Ошибка при добавлении владельца " + player.getName() + " к региону " + regionId + ": " + e.getMessage());
        e.printStackTrace();
            return false;
                    }
                    }

/**
 * Добавляет владельца к региону по UUID
 * @param world Мир
 * @param regionId ID региона
 * @param playerUUID UUID игрока
 * @return true если владелец добавлен успешно
 */
public boolean addOwnerByUUID(World world, String regionId, UUID playerUUID) {
    if (world == null || regionId == null || playerUUID == null) {
        return false;
    }

    try {
        ProtectedRegion region = getRegion(world, regionId);
        if (region == null) {
            return false;
        }

        DefaultDomain owners = region.getOwners();
        owners.addPlayer(playerUUID);
        region.setOwners(owners);

        saveRegionChanges(world, regionId);

        String playerName = plugin.getServer().getOfflinePlayer(playerUUID).getName();
        plugin.getLogger().info("Игрок " + (playerName != null ? playerName : playerUUID.toString()) +
                " добавлен как владелец региона " + regionId);
        return true;

    } catch (Exception e) {
        plugin.getLogger().warning("Ошибка при добавлении владельца по UUID к региону " + regionId + ": " + e.getMessage());
        return false;
    }
}

/**
 * Удаляет владельца из региона
 * @param world Мир
 * @param regionId ID региона
 * @param player Игрок
 * @return true если владелец удален успешно
 */
public boolean removeOwner(World world, String regionId, Player player) {
    if (world == null || regionId == null || player == null) {
        plugin.getLogger().warning("Попытка удалить владельца с null параметрами");
        return false;
    }

    try {
        ProtectedRegion region = getRegion(world, regionId);
        if (region == null) {
            plugin.getLogger().warning("Регион " + regionId + " не найден для удаления владельца");
            return false;
        }

        // Проверяем, является ли игрок владельцем
        if (!isOwner(region, player)) {
            plugin.getLogger().info("Игрок " + player.getName() + " не является владельцем региона " + regionId);
            return true;
        }

        DefaultDomain owners = region.getOwners();
        owners.removePlayer(player.getUniqueId());
        owners.removePlayer(player.getName().toLowerCase());
        region.setOwners(owners);

        // Сохраняем изменения
        saveRegionChanges(world, regionId);

        plugin.getLogger().info("Игрок " + player.getName() + " удален из владельцев региона " + regionId);
        return true;

    } catch (Exception e) {
        plugin.getLogger().warning("Ошибка при удалении владельца " + player.getName() + " из региона " + regionId + ": " + e.getMessage());
        e.printStackTrace();
        return false;
    }
}

/**
 * Удаляет владельца из региона по UUID
 * @param world Мир
 * @param regionId ID региона
 * @param playerUUID UUID игрока
 * @return true если владелец удален успешно
 */
public boolean removeOwnerByUUID(World world, String regionId, UUID playerUUID) {
    if (world == null || regionId == null || playerUUID == null) {
        return false;
    }

    try {
        ProtectedRegion region = getRegion(world, regionId);
        if (region == null) {
            return false;
        }

        DefaultDomain owners = region.getOwners();
        owners.removePlayer(playerUUID);
        region.setOwners(owners);

        saveRegionChanges(world, regionId);

        String playerName = plugin.getServer().getOfflinePlayer(playerUUID).getName();
        plugin.getLogger().info("Игрок " + (playerName != null ? playerName : playerUUID.toString()) +
                " удален из владельцев региона " + regionId);
        return true;

    } catch (Exception e) {
        plugin.getLogger().warning("Ошибка при удалении владельца по UUID из региона " + regionId + ": " + e.getMessage());
        return false;
    }
}

/**
 * Добавляет участника к региону
 * @param world Мир
 * @param regionId ID региона
 * @param player Игрок
 * @return true если участник добавлен успешно
 */
public boolean addMember(World world, String regionId, Player player) {
    if (world == null || regionId == null || player == null) {
        plugin.getLogger().warning("Попытка добавить участника с null параметрами");
        return false;
    }

    try {
        ProtectedRegion region = getRegion(world, regionId);
        if (region == null) {
            plugin.getLogger().warning("Регион " + regionId + " не найден для добавления участника");
            return false;
        }

        // Проверяем, не является ли игрок уже участником или владельцем
        if (isMember(region, player) || isOwner(region, player)) {
            plugin.getLogger().info("Игрок " + player.getName() + " уже имеет доступ к региону " + regionId);
            return true;
        }

        DefaultDomain members = region.getMembers();
        members.addPlayer(player.getUniqueId());
        region.setMembers(members);

        // Сохраняем изменения
        saveRegionChanges(world, regionId);

        plugin.getLogger().info("Игрок " + player.getName() + " добавлен как участник региона " + regionId);
        return true;

    } catch (Exception e) {
        plugin.getLogger().warning("Ошибка при добавлении участника " + player.getName() + " к региону " + regionId + ": " + e.getMessage());
        e.printStackTrace();
        return false;
    }
}

/**
 * Добавляет участника к региону по UUID
 * @param world Мир
 * @param regionId ID региона
 * @param playerUUID UUID игрока
 * @return true если участник добавлен успешно
 */
public boolean addMemberByUUID(World world, String regionId, UUID playerUUID) {
    if (world == null || regionId == null || playerUUID == null) {
        return false;
    }

    try {
        ProtectedRegion region = getRegion(world, regionId);
        if (region == null) {
            return false;
        }

        DefaultDomain members = region.getMembers();
        members.addPlayer(playerUUID);
        region.setMembers(members);

        saveRegionChanges(world, regionId);

        String playerName = plugin.getServer().getOfflinePlayer(playerUUID).getName();
        plugin.getLogger().info("Игрок " + (playerName != null ? playerName : playerUUID.toString()) +
                " добавлен как участник региона " + regionId);
        return true;

    } catch (Exception e) {
        plugin.getLogger().warning("Ошибка при добавлении участника по UUID к региону " + regionId + ": " + e.getMessage());
        return false;
    }
}

/**
 * Удаляет участника из региона
 * @param world Мир
 * @param regionId ID региона
 * @param player Игрок
 * @return true если участник удален успешно
 */
public boolean removeMember(World world, String regionId, Player player) {
    if (world == null || regionId == null || player == null) {
        plugin.getLogger().warning("Попытка удалить участника с null параметрами");
        return false;
    }

    try {
        ProtectedRegion region = getRegion(world, regionId);
        if (region == null) {
            plugin.getLogger().warning("Регион " + regionId + " не найден для удаления участника");
            return false;
        }

        // Проверяем, является ли игрок участником
        if (!isMember(region, player)) {
            plugin.getLogger().info("Игрок " + player.getName() + " не является участником региона " + regionId);
            return true;
        }

        DefaultDomain members = region.getMembers();
        members.removePlayer(player.getUniqueId());
        members.removePlayer(player.getName().toLowerCase());
        region.setMembers(members);

        // Сохраняем изменения
        saveRegionChanges(world, regionId);

        plugin.getLogger().info("Игрок " + player.getName() + " удален из участников региона " + regionId);
        return true;

    } catch (Exception e) {
        plugin.getLogger().warning("Ошибка при удалении участника " + player.getName() + " из региона " + regionId + ": " + e.getMessage());
        e.printStackTrace();
        return false;
    }
}
/**
 * Очищает кэш
 */
public void clearCache() {
    regionManagerCache.clear();
    regionInfoCache.clear();
    plugin.getLogger().info("Кэш ProtectRegionManager очищен");
}

/**
 * Получает статистику операций
 * @return Map со статистикой
 */
public Map<String, Integer> getOperationStatistics() {
    return new HashMap<>(operationStats);
}

/**
 * Получает общую статистику менеджера
 * @return Строка со статистикой
 */
public String getManagerStatistics() {
    StringBuilder stats = new StringBuilder();
    stats.append("=== Статистика ProtectRegionManager ===\n");
    stats.append("Кэш RegionManager'ов: ").append(regionManagerCache.size()).append("\n");
    stats.append("Кэш информации о регионах: ").append(regionInfoCache.size()).append("\n");

    for (Map.Entry<String, Integer> entry : operationStats.entrySet()) {
        stats.append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
    }

    return stats.toString();
}

/**
 * Проверяет здоровье менеджера
 * @return Отчет о проверке
 */
public String performHealthCheck() {
    StringBuilder report = new StringBuilder();
    report.append("=== Проверка здоровья ProtectRegionManager ===\n");

    // Проверка WorldGuard
    if (isWorldGuardAvailable()) {
        report.append("✓ WorldGuard доступен\n");
    } else {
        report.append("✗ WorldGuard недоступен\n");
    }

    // Проверка совместимости версии
    if (isWorldGuardVersionCompatible()) {
        report.append("✓ Версия WorldGuard совместима\n");
    } else {
        report.append("✗ Проблемы совместимости с WorldGuard\n");
    }

    // Проверка миров
    int totalWorlds = plugin.getServer().getWorlds().size();
    int worldsWithRegions = 0;
    int totalRegionsCount = 0;

    for (World world : plugin.getServer().getWorlds()) {
        RegionManager rm = getWorldGuardRegionManager(world);
        if (rm != null) {
            int regionCount = rm.getRegions().size();
            totalRegionsCount += regionCount;
            if (regionCount > 0) {
                worldsWithRegions++;
            }
        }
    }

    report.append("Миров всего: ").append(totalWorlds).append("\n");
    report.append("Миров с регионами: ").append(worldsWithRegions).append("/").append(totalWorlds).append("\n");
    report.append("Всего регионов: ").append(totalRegionsCount).append("\n");

    // Статистика кэша
    long cacheHits = operationStats.getOrDefault("cache_hits", 0);
    long cacheMisses = operationStats.getOrDefault("cache_misses", 0);
    long totalCacheRequests = cacheHits + cacheMisses;

    if (totalCacheRequests > 0) {
        double hitRate = (double) cacheHits / totalCacheRequests * 100;
        report.append("Эффективность кэша: ").append(String.format("%.1f%%", hitRate));
        report.append(" (").append(cacheHits).append("/").append(totalCacheRequests).append(")\n");
    } else {
        report.append("Кэш не использовался\n");
    }

    // Статистика операций
    int regionsCreated = operationStats.getOrDefault("regions_created", 0);
    int regionsDeleted = operationStats.getOrDefault("regions_deleted", 0);
    int regionsModified = operationStats.getOrDefault("regions_modified", 0);

    report.append("Создано регионов: ").append(regionsCreated).append("\n");
    report.append("Удалено регионов: ").append(regionsDeleted).append("\n");
    report.append("Изменено регионов: ").append(regionsModified).append("\n");

    return report.toString();
}

/**
 * Получает детальную информацию о регионе
 * @param world Мир
 * @param regionId ID региона
 * @return Строка с информацией
 */
public String getRegionDetailedInfo(World world, String regionId) {
    try {
        ProtectedRegion region = getRegion(world, regionId);
        if (region == null) {
            return "Регион не найден";
        }

        StringBuilder info = new StringBuilder();
        info.append("§6=== Информация о регионе ").append(regionId).append(" ===\n");
        info.append("§eМир: §f").append(world.getName()).append("\n");

        // Границы
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        info.append("§eГраницы: §f").append(min.x()).append(",").append(min.y()).append(",").append(min.z())
                .append(" до ").append(max.x()).append(",").append(max.y()).append(",").append(max.z()).append("\n");

        // Размер
        int sizeX = max.x() - min.x() + 1;
        int sizeY = max.y() - min.y() + 1;
        int sizeZ = max.z() - min.z() + 1;
        int volume = sizeX * sizeY * sizeZ;
        info.append("§eРазмер: §f").append(sizeX).append("×").append(sizeY).append("×").append(sizeZ)
                .append(" (объем: ").append(volume).append(")\n");

        // Владельцы
        Set<UUID> ownerUUIDs = region.getOwners().getUniqueIds();
        Set<String> ownerNames = region.getOwners().getPlayers();
        if (!ownerUUIDs.isEmpty() || !ownerNames.isEmpty()) {
            info.append("§eВладельцы: §f");
            List<String> allOwners = new ArrayList<>();

            // Добавляем владельцев по UUID
            for (UUID uuid : ownerUUIDs) {
                String name = plugin.getServer().getOfflinePlayer(uuid).getName();
                allOwners.add(name != null ? name : uuid.toString());
            }

            // Добавляем владельцев по именам
            allOwners.addAll(ownerNames);

            info.append(String.join(", ", allOwners)).append("\n");
        } else {
            info.append("§eВладельцы: §cНет\n");
        }

        // Участники
        Set<UUID> memberUUIDs = region.getMembers().getUniqueIds();
        Set<String> memberNames = region.getMembers().getPlayers();
        if (!memberUUIDs.isEmpty() || !memberNames.isEmpty()) {
            info.append("§eУчастники: §f");
            List<String> allMembers = new ArrayList<>();

            // Добавляем участников по UUID
            for (UUID uuid : memberUUIDs) {
                String name = plugin.getServer().getOfflinePlayer(uuid).getName();
                allMembers.add(name != null ? name : uuid.toString());
            }

            // Добавляем участников по именам
            allMembers.addAll(memberNames);

            info.append(String.join(", ", allMembers)).append("\n");
        } else {
            info.append("§eУчастники: §7Нет\n");
        }

        // Приоритет
        info.append("§eПриоритет: §f").append(region.getPriority()).append("\n");

        // Флаги
        if (!region.getFlags().isEmpty()) {
            info.append("§eФлаги: §f").append(region.getFlags().size()).append(" установлено\n");
        } else {
            info.append("§eФлаги: §7Не установлены\n");
        }

        // Статус активности
        boolean isActive = isRegionActive(region);
        info.append("§eСтатус: ").append(isActive ? "§aАктивный" : "§cНеактивный").append("\n");

        return info.toString();

    } catch (Exception e) {
        plugin.getLogger().warning("Ошибка при получении информации о регионе: " + e.getMessage());
        return "Ошибка при получении информации: " + e.getMessage();
    }
}

/**
 * Выполняет диагностику и исправление проблем
 * @return Отчет о выполненных исправлениях
 */
public String performMaintenanceAndRepair() {
    StringBuilder report = new StringBuilder();
    report.append("=== Обслуживание и ремонт ProtectRegionManager ===\n");

    int totalIssuesFixed = 0;

    try {
        // Очистка кэша
        int cacheSize = regionInfoCache.size() + regionManagerCache.size();
        clearCache();
        report.append("Очищен кэш (").append(cacheSize).append(" записей)\n");
        totalIssuesFixed++;

        // Удаление устаревших записей кэша
        int removedExpired = 0;
        regionInfoCache.entrySet().removeIf(entry -> {
            boolean expired = entry.getValue().isExpired(300000); // 5 минут
            return expired;
        });

        if (removedExpired > 0) {
            report.append("Удалено устаревших записей кэша: ").append(removedExpired).append("\n");
            totalIssuesFixed++;
        }

        // Проверка доступности WorldGuard
        if (!isWorldGuardAvailable()) {
            report.append("ПРЕДУПРЕЖДЕНИЕ: WorldGuard недоступен!\n");
        } else if (!isWorldGuardVersionCompatible()) {
            report.append("ПРЕДУПРЕЖДЕНИЕ: Проблемы совместимости с WorldGuard!\n");
        }

        // Сброс больших счетчиков статистики
        int resetCounters = 0;
        for (Map.Entry<String, Integer> entry : operationStats.entrySet()) {
            if (entry.getValue() > 10000) {
                operationStats.put(entry.getKey(), entry.getValue() / 2);
                resetCounters++;
            }
        }

        if (resetCounters > 0) {
            report.append("Сброшены большие счетчики статистики: ").append(resetCounters).append("\n");
            totalIssuesFixed++;
        }

    } catch (Exception e) {
        report.append("ОШИБКА при обслуживании: ").append(e.getMessage()).append("\n");
        plugin.getLogger().warning("Ошибка при обслуживании ProtectRegionManager: " + e.getMessage());
    }

    report.append("\nОбслуживание завершено. Исправлено проблем: ").append(totalIssuesFixed).append("\n");

    return report.toString();
}

/**
 * Получает версию API
 * @return Строка с версией
 */
public String getAPIVersion() {
    return "1.0.0";
}

/**
 * Проверяет совместимость с версией WorldGuard
 * @return true если версия совместима
 */
public boolean isWorldGuardVersionCompatible() {
    try {
        // Попробуем вызвать основные методы API
        WorldGuard.getInstance();
        WorldGuard.getInstance().getPlatform().getRegionContainer();
        return true;
    } catch (Exception e) {
        plugin.getLogger().warning("Проблема совместимости с WorldGuard: " + e.getMessage());
        return false;
    }
}

/**
 * Очищает неактивные регионы (удаляет регионы без владельцев)
 * @param world Мир
 * @return Количество удаленных регионов
 */
public int cleanupInactiveRegions(World world) {
    if (world == null) {
        return 0;
    }

    List<ProtectedRegion> inactiveRegions = getInactiveRegions(world);
    int removedCount = 0;

    for (ProtectedRegion region : inactiveRegions) {
        if (deleteRegion(world, region.getId())) {
            removedCount++;
            plugin.getLogger().info("Удален неактивный регион: " + region.getId());
        }
    }

    plugin.getLogger().info("Очистка завершена. Удалено неактивных регионов: " + removedCount);
    return removedCount;
}

/**
 * Получает список регионов поблизости от указанной позиции
 * @param world Мир
 * @param center Центральная позиция
 * @param radius Радиус поиска
 * @return Список регионов в радиусе
 */
public List<ProtectedRegion> getNearbyRegions(World world, Location center, int radius) {
    List<ProtectedRegion> nearbyRegions = new ArrayList<>();

    if (world == null || center == null || radius <= 0) {
        return nearbyRegions;
    }

    try {
        RegionManager regionManager = getWorldGuardRegionManager(world);
        if (regionManager == null) {
            return nearbyRegions;
        }

        BlockVector3 centerPoint = BlockVector3.at(center.getBlockX(), center.getBlockY(), center.getBlockZ());

        for (ProtectedRegion region : regionManager.getRegions().values()) {
            // Вычисляем центр региона
            BlockVector3 regionCenter = region.getMinimumPoint().add(region.getMaximumPoint()).divide(2);

            // Проверяем расстояние
            double distance = centerPoint.distance(regionCenter);
            if (distance <= radius) {
                nearbyRegions.add(region);
            }
        }

        // Сортируем по расстоянию
        nearbyRegions.sort((r1, r2) -> {
            BlockVector3 center1 = r1.getMinimumPoint().add(r1.getMaximumPoint()).divide(2);
            BlockVector3 center2 = r2.getMinimumPoint().add(r2.getMaximumPoint()).divide(2);
            double dist1 = centerPoint.distance(center1);
            double dist2 = centerPoint.distance(center2);
            return Double.compare(dist1, dist2);
        });

    } catch (Exception e) {
        plugin.getLogger().warning("Ошибка при поиске регионов поблизости: " + e.getMessage());
    }

    return nearbyRegions;
}

/**
 * Получает расстояние между двумя регионами
 * @param region1 Первый регион
 * @param region2 Второй регион
 * @return Расстояние между центрами регионов
 */
public double getDistanceBetweenRegions(ProtectedRegion region1, ProtectedRegion region2) {
    if (region1 == null || region2 == null) {
        return -1;
    }

    try {
        BlockVector3 center1 = region1.getMinimumPoint().add(region1.getMaximumPoint()).divide(2);
        BlockVector3 center2 = region2.getMinimumPoint().add(region2.getMaximumPoint()).divide(2);

        return center1.distance(center2);

    } catch (Exception e) {
        plugin.getLogger().warning("Ошибка при вычислении расстояния между регионами: " + e.getMessage());
        return -1;
    }
}

/**
 * Проверяет, пересекаются ли два региона
 * @param region1 Первый регион
 * @param region2 Второй регион
 * @return true если регионы пересекаются
 */
public boolean doRegionsOverlap(ProtectedRegion region1, ProtectedRegion region2) {
    if (region1 == null || region2 == null) {
        return false;
    }

    try {
        return region1.intersects(region2);
    } catch (Exception e) {
        plugin.getLogger().warning("Ошибка при проверке пересечения регионов: " + e.getMessage());
        return false;
    }
}

/**
 * Получает количество активных голограмм
 * @return Количество голограмм
 */
public int getHologramCount() {
    return regionInfoCache.size();
}

/**
 * Проверяет, существует ли голограмма для региона
 * @param regionName ID региона
 * @return true если голограмма существует
 */
public boolean hasHologram(String regionName) {
    return regionInfoCache.containsKey(regionName);
}

/**
 * Получает отчет о состоянии всех регионов
 * @return Подробный отчет
 */
public String generateFullReport() {
    StringBuilder report = new StringBuilder();
    report.append("=== Полный отчет ProtectRegionManager ===\n");
    report.append("Время генерации: ").append(new java.util.Date()).append("\n\n");

    // Статистика по мирам
    for (World world : plugin.getServer().getWorlds()) {
        try {
            RegionManager rm = getWorldGuardRegionManager(world);
            if (rm != null && !rm.getRegions().isEmpty()) {
                report.append("=== Мир: ").append(world.getName()).append(" ===\n");
                report.append("Регионов: ").append(rm.getRegions().size()).append("\n");

                // Неактивные регионы
                List<ProtectedRegion> inactive = getInactiveRegions(world);
                if (!inactive.isEmpty()) {
                    report.append("Неактивных регионов: ").append(inactive.size()).append("\n");
                }

                report.append("\n");
            }
        } catch (Exception e) {
            report.append("Ошибка при анализе мира ").append(world.getName()).append(": ").append(e.getMessage()).append("\n");
        }
    }

    // Общая статистика менеджера
    report.append(getManagerStatistics()).append("\n");

    // Проверка здоровья
    report.append(performHealthCheck());

    return report.toString();
}

/**
 * Выполняет очистку ресурсов при выключении
 */
public void shutdown() {
    plugin.getLogger().info("ProtectRegionManager: Остановка...");

    try {
        // Сохраняем важную статистику
        Map<String, Integer> finalStats = new HashMap<>(operationStats);
        plugin.getLogger().info("Финальная статистика операций: " + finalStats);

        // Очищаем кэш
        clearCache();

        // Очищаем статистику
        operationStats.clear();

        plugin.getLogger().info("ProtectRegionManager остановлен");

    } catch (Exception e) {
        plugin.getLogger().severe("Ошибка при остановке ProtectRegionManager: " + e.getMessage());
        e.printStackTrace();
    }
}
