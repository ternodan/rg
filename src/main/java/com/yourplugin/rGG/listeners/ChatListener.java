package com.yourplugin.rGG.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.entity.Player;

import com.yourplugin.rGG.RGProtectPlugin;

public class ChatListener implements Listener {

    private final RGProtectPlugin plugin;

    public ChatListener(RGProtectPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage();

        // ИСПРАВЛЕНИЕ: Проверяем есть ли ожидающее удаление без выполнения операций с блоками
        if (plugin.getRegionMenuManager().hasPendingDeletion(player)) {
            // Отменяем обычный чат если это команда удаления
            if (message.equalsIgnoreCase("УДАЛИТЬ") || message.equalsIgnoreCase("DELETE") || message.equalsIgnoreCase("YES") ||
                    message.equalsIgnoreCase("ДА") || message.equalsIgnoreCase("CONFIRM") ||
                    message.equalsIgnoreCase("ОТМЕНА") || message.equalsIgnoreCase("CANCEL") || message.equalsIgnoreCase("NO") ||
                    message.equalsIgnoreCase("НЕТ")) {

                event.setCancelled(true);

                // Выполняем обработку подтверждения СИНХРОННО в главном потоке
                org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                    plugin.getRegionMenuManager().handleChatConfirmation(player, message);
                });

                if (plugin.getConfig().getBoolean("debug.log-menu-actions", true)) {
                    plugin.getLogger().info("DEBUG CHAT: Обработано подтверждение удаления от игрока " + player.getName() + ": " + message);
                }
            }
        }
    }
}