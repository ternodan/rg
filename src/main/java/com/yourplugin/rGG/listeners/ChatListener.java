package com.yourplugin.rGG.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.entity.Player;
import org.bukkit.ChatColor;

import com.yourplugin.rGG.RGProtectPlugin;

public class ChatListener implements Listener {

    private final RGProtectPlugin plugin;

    public ChatListener(RGProtectPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        String message = event.getMessage().trim(); // Убираем лишние пробелы

        // Логируем для отладки
        plugin.getLogger().info("DEBUG CHAT: Игрок " + player.getName() + " написал: '" + message + "'");
        plugin.getLogger().info("DEBUG CHAT: Есть ожидающее удаление: " + plugin.getRegionMenuManager().hasPendingDeletion(player));

        // ИСПРАВЛЕНИЕ: Проверяем есть ли ожидающее удаление
        if (plugin.getRegionMenuManager().hasPendingDeletion(player)) {
            plugin.getLogger().info("DEBUG CHAT: Обрабатываем команду удаления: '" + message + "'");

            // Проверяем команды подтверждения (более широкий список)
            boolean isConfirmCommand = message.equalsIgnoreCase("УДАЛИТЬ") ||
                    message.equalsIgnoreCase("DELETE") ||
                    message.equalsIgnoreCase("YES") ||
                    message.equalsIgnoreCase("ДА") ||
                    message.equalsIgnoreCase("CONFIRM") ||
                    message.equalsIgnoreCase("Y") ||
                    message.equalsIgnoreCase("Д");

            boolean isCancelCommand = message.equalsIgnoreCase("ОТМЕНА") ||
                    message.equalsIgnoreCase("CANCEL") ||
                    message.equalsIgnoreCase("NO") ||
                    message.equalsIgnoreCase("НЕТ") ||
                    message.equalsIgnoreCase("N") ||
                    message.equalsIgnoreCase("Н");

            if (isConfirmCommand || isCancelCommand) {
                // Отменяем чат
                event.setCancelled(true);

                plugin.getLogger().info("DEBUG CHAT: Команда распознана, отменяем чат и выполняем в главном потоке");

                // ИСПРАВЛЕНИЕ: Выполняем в главном потоке с дополнительной проверкой
                org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        // Дополнительная проверка что игрок все еще онлайн
                        if (!player.isOnline()) {
                            plugin.getLogger().warning("DEBUG CHAT: Игрок " + player.getName() + " больше не онлайн");
                            return;
                        }

                        // Дополнительная проверка что у игрока есть ожидающее удаление
                        if (!plugin.getRegionMenuManager().hasPendingDeletion(player)) {
                            plugin.getLogger().warning("DEBUG CHAT: У игрока " + player.getName() + " больше нет ожидающего удаления");
                            return;
                        }

                        plugin.getLogger().info("DEBUG CHAT: Вызываем handleChatConfirmation...");
                        plugin.getRegionMenuManager().handleChatConfirmation(player, message);

                        plugin.getLogger().info("DEBUG CHAT: handleChatConfirmation выполнен успешно");

                    } catch (Exception e) {
                        plugin.getLogger().severe("КРИТИЧЕСКАЯ ОШИБКА при обработке подтверждения удаления: " + e.getMessage());
                        e.printStackTrace();

                        // Уведомляем игрока об ошибке
                        player.sendMessage(ChatColor.RED + "Произошла ошибка при обработке команды!");
                        player.sendMessage(ChatColor.YELLOW + "Попробуйте снова или обратитесь к администратору.");

                        // Очищаем ожидающее удаление
                        plugin.getRegionMenuManager().clearPendingDeletion(player);
                    }
                });
            } else {
                plugin.getLogger().info("DEBUG CHAT: Сообщение не является командой удаления, игнорируем");
            }
        }
    }
}