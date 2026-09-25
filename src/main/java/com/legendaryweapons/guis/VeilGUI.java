package com.legendaryweapons.guis;

import com.legendaryweapons.LegendaryWeapons;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

public class VeilGUI implements Listener {
   private static final int PAGE_SIZE = 45;
   private static final int SLOT_PREV = 45;
   private static final int SLOT_INFO = 49;
   private static final int SLOT_NEXT = 53;
   private final LegendaryWeapons plugin;
   private final BiConsumer<Player, String> onChoose;

   public VeilGUI(LegendaryWeapons plugin, BiConsumer<Player, String> onChoose) {
      this.plugin = plugin;
      this.onChoose = onChoose;
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
   }

   public boolean allowOnlineTargets() {
      ConfigurationSection abilities = this.plugin.getConfigManager().getWeaponAbilities("veil-of-mimicry");
      return abilities == null || abilities.getBoolean("allow-online-targets", true);
   }

   private List<Entry<UUID, String>> candidates(Player viewer) {
      boolean allowOnline = this.allowOnlineTargets();
      List<Entry<UUID, String>> out = new ArrayList<>();

      for (Entry<UUID, String> entry : this.plugin.getPlayerRosterManager().entries()) {
         if (!entry.getKey().equals(viewer.getUniqueId()) && (allowOnline || Bukkit.getPlayer(entry.getKey()) == null)) {
            out.add(entry);
         }
      }

      return out;
   }

   public int candidateCount(Player viewer) {
      return this.candidates(viewer).size();
   }

   public int excludedOnlineCount(Player viewer) {
      if (this.allowOnlineTargets()) {
         return 0;
      } else {
         int count = 0;

         for (Entry<UUID, String> entry : this.plugin.getPlayerRosterManager().entries()) {
            if (!entry.getKey().equals(viewer.getUniqueId()) && Bukkit.getPlayer(entry.getKey()) != null) {
               count++;
            }
         }

         return count;
      }
   }

   public void open(Player player, int page) {
      List<Entry<UUID, String>> all = this.candidates(player);
      int pages = Math.max(1, (int)Math.ceil(all.size() / 45.0));
      int clamped = Math.max(0, Math.min(page, pages - 1));
      VeilGUI.Holder holder = new VeilGUI.Holder(clamped);
      Inventory inventory = Bukkit.createInventory(holder, 54, Component.text("Veil of Mimicry", NamedTextColor.DARK_PURPLE));
      holder.inventory = inventory;
      int from = clamped * 45;
      int to = Math.min(all.size(), from + 45);

      for (int i = from; i < to; i++) {
         Entry<UUID, String> entry = all.get(i);
         inventory.setItem(i - from, this.headFor(entry.getKey(), entry.getValue()));
      }

      if (clamped > 0) {
         inventory.setItem(45, this.navItem("Previous page"));
      }

      if (clamped < pages - 1) {
         inventory.setItem(53, this.navItem("Next page"));
      }

      ItemStack info = new ItemStack(Material.BOOK);
      info.editMeta(
         meta -> {
            meta.displayName(Component.text("Choose a face", NamedTextColor.LIGHT_PURPLE).decoration(TextDecoration.ITALIC, false));
            meta.lore(
               List.of(
                  (TextComponent)Component.text(all.size() + " seen players", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                  (TextComponent)Component.text("Page " + (clamped + 1) + " of " + pages, NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false),
                  Component.empty(),
                  (TextComponent)Component.text("The morph fails if anyone sees you.", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
               )
            );
         }
      );
      inventory.setItem(49, info);
      player.openInventory(inventory);
   }

   private ItemStack headFor(UUID id, String name) {
      ItemStack head = new ItemStack(Material.PLAYER_HEAD);
      SkullMeta meta = (SkullMeta)head.getItemMeta();
      if (meta != null) {
         meta.setOwningPlayer(Bukkit.getOfflinePlayer(id));
         meta.displayName(Component.text(name, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
         meta.lore(List.of((TextComponent)Component.text("Click to wear this face", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)));
         head.setItemMeta(meta);
      }

      return head;
   }

   private ItemStack navItem(String label) {
      ItemStack item = new ItemStack(Material.ARROW);
      item.editMeta(meta -> meta.displayName(Component.text(label, NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)));
      return item;
   }

   @EventHandler
   public void onInventoryDrag(InventoryDragEvent event) {
      if (event.getInventory().getHolder() instanceof VeilGUI.Holder) {
         event.setCancelled(true);
      }
   }

   @EventHandler
   public void onInventoryClick(InventoryClickEvent event) {
      if (event.getInventory().getHolder() instanceof VeilGUI.Holder holder) {
         event.setCancelled(true);
         if (event.getWhoClicked() instanceof Player player) {
            int slot = event.getRawSlot();
            if (slot >= 0 && slot < 54) {
               ItemStack clicked = event.getCurrentItem();
               if (clicked != null && !clicked.getType().isAir()) {
                  if (slot == 45 && clicked.getType() == Material.ARROW) {
                     this.open(player, holder.page - 1);
                  } else if (slot == 53 && clicked.getType() == Material.ARROW) {
                     this.open(player, holder.page + 1);
                  } else if (clicked.getType() == Material.PLAYER_HEAD) {
                     SkullMeta meta = (SkullMeta)clicked.getItemMeta();
                     if (meta != null && meta.hasDisplayName()) {
                        String chosen = PlainTextComponentSerializer.plainText().serialize(meta.displayName());
                        player.closeInventory();
                        this.onChoose.accept(player, chosen);
                     }
                  }
               }
            }
         }
      }
   }

   private static final class Holder implements InventoryHolder {
      private final int page;
      private Inventory inventory;

      private Holder(int page) {
         this.page = page;
      }

      public Inventory getInventory() {
         return this.inventory;
      }
   }
}
