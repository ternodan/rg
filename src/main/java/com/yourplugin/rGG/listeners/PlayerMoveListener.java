package com.yourplugin.rGG.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.entity.Player;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.util.RayTraceResult;

import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.yourplugin.rGG.RGProtectPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerMoveListener implements Listener {

    private final RGProtectPlugin plugin;
    private final Map<UUID, String> lastLookedRegion;

    public PlayerMoveListener(RGProtectPlugin plugin) {
        this.plugin = plugin;
        this.lastLookedRegion = new HashMap<>();
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        // Проверяем только при движении головы
        if (event.getFrom().getYaw() == event.getTo().getYaw() &&
                event.getFrom().getPitch() == event.getTo().getPitch()) {
            return;
        }

        Player player = event.getPlayer();

        // Проверяем, на какой блок смотрит игрок
        RayTraceResult rayTrace = player.rayTraceBlocks(5.0); // Максимальная дистанция 5 блоков

        if (rayTrace == null || rayTrace.getHitBlock() == null) {
            // Игрок не смотрит на блок
            String lastRegion = lastLookedRegion.get(player.getUniqueId());
            if (lastRegion != null) {
                // Убираем визуализацию если игрок перестал смотреть на регион
                plugin.getVisualizationManager().clearVisualization(player);
                lastLookedRegion.remove(player.getUniqueId());
            }
            return;
        }

        Block block = rayTrace.getHitBlock();
        Location location = block.getLocation();

        // Проверяем, есть ли регион в этом месте
        ProtectedRegion region = plugin.getProtectRegionManager().getRegionAt(location);

        if (region == null) {
            // Нет региона
            String lastRegion = lastLookedRegion.get(player.getUniqueId());
            if (lastRegion != null) {
                plugin.getVisualizationManager().clearVisualization(player);
                lastLookedRegion.remove(player.getUniqueId());
            }
            return;
        }

        String regionId = region.getId();
        String lastRegion = lastLookedRegion.get(player.getUniqueId());

        // Если это новый регион или игрок впервые смотрит на регион
        if (lastRegion == null || !lastRegion.equals(regionId)) {
            // Проверяем, является ли блок центральным блоком региона
            if (isCenterBlock(location, region)) {
                // ИСПРАВЛЕНО: Передаем мир игрока для корректного создания границ
                if (!plugin.getVisualizationManager().hasRegionBorders(regionId)) {
                    plugin.getVisualizationManager().createRegionBorders(region, player.getWorld());
                }
                player.sendMessage("§aРегион найден! Границы отмечены красной шерстью.");
                lastLookedRegion.put(player.getUniqueId(), regionId);
            }
        }
    }

    private boolean isCenterBlock(Location blockLocation, ProtectedRegion region) {
        // Вычисляем центр региона на основе его границ
        int regionMinX = region.getMinimumPoint().x();
        int regionMaxX = region.getMaximumPoint().x();
        int regionMinY = region.getMinimumPoint().y();
        int regionMaxY = region.getMaximumPoint().y();
        int regionMinZ = region.getMinimumPoint().z();
        int regionMaxZ = region.getMaximumPoint().z();

        // Центр - это середина между min и max
        int centerX = (regionMinX + regionMaxX) / 2;
        int centerY = (regionMinY + regionMaxY) / 2;
        int centerZ = (regionMinZ + regionMaxZ) / 2;

        return blockLocation.getBlockX() == centerX &&
                blockLocation.getBlockY() == centerY &&
                blockLocation.getBlockZ() == centerZ;
    }
}