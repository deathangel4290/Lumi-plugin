package com.lumiweapons.guis;

import com.lumiweapons.LumiWeapons;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Typed input for a menu: the menu closes, the player types the value in chat (it is not sent to
 * anyone), and the menu opens again. "cancel" goes back without changing anything.
 */
public final class ChatPrompt implements Listener {
   private static final long TIMEOUT_MILLIS = 45000L;
   private static ChatPrompt instance;
   private final LumiWeapons plugin;
   private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

   /** `answer` returns null when the input was used, or an error to show (the prompt then asks again). */
   private record Pending(Function<String, String> answer, Runnable done, long at) {
   }

   public ChatPrompt(LumiWeapons plugin) {
      this.plugin = plugin;
      instance = this;
   }

   public static void ask(Player player, String question, String current, Function<String, String> answer, Runnable done) {
      if (instance == null) {
         return;
      }

      player.closeInventory();
      instance.pending.put(player.getUniqueId(), new Pending(answer, done, System.currentTimeMillis()));
      player.sendMessage(Component.empty());
      player.sendMessage(Component.text("✦ ", Menu.ACCENT).append(Component.text(question, NamedTextColor.WHITE)));
      if (current != null) {
         player.sendMessage(Component.text("  Now: ", Menu.TEXT).append(Component.text(current, Menu.GOLD)));
      }

      player.sendMessage(Component.text("  Type it in chat, or ", Menu.TEXT).append(Component.text("cancel", NamedTextColor.YELLOW)).append(Component.text(".", Menu.TEXT)));
   }

   /** Drops a waiting prompt (the player moved on to another menu). */
   public static void cancel(Player player) {
      if (instance != null) {
         instance.pending.remove(player.getUniqueId());
      }
   }

   @EventHandler(priority = EventPriority.LOWEST)
   public void onChat(AsyncChatEvent event) {
      Player player = event.getPlayer();
      Pending waiting = this.pending.get(player.getUniqueId());
      if (waiting == null) {
         return;
      }

      event.setCancelled(true);
      if (System.currentTimeMillis() - waiting.at() > TIMEOUT_MILLIS) {
         this.pending.remove(player.getUniqueId());
         event.setCancelled(false);                       // stale: let the message through as normal chat
         return;
      }

      String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
      this.plugin.getServer().getScheduler().runTask(this.plugin, () -> {
         if (this.pending.get(player.getUniqueId()) != waiting || !player.isOnline()) {
            return;
         }

         if (text.equalsIgnoreCase("cancel")) {
            this.pending.remove(player.getUniqueId());
            waiting.done().run();
            return;
         }

         String error = waiting.answer().apply(text);
         if (error != null) {
            player.sendMessage(Component.text("✦ ", Menu.ACCENT).append(Component.text(error + " Try again, or type cancel.", Menu.BAD)));
            this.pending.put(player.getUniqueId(), new Pending(waiting.answer(), waiting.done(), System.currentTimeMillis()));
            return;
         }

         this.pending.remove(player.getUniqueId());
         waiting.done().run();
      });
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      this.pending.remove(event.getPlayer().getUniqueId());
   }
}
