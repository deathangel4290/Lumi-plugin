package com.legendaryweapons.guis;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.rituals.RitualBlock;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

public class WeaponGiveGUI implements Listener {
   private final LegendaryWeapons plugin;

   public WeaponGiveGUI(LegendaryWeapons plugin) {
      this.plugin = plugin;
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
   }

   public void open(Player player) {
      List<String> keys = new ArrayList<>(this.plugin.getWeaponManager().getWeaponKeys());
      int needed = keys.size() + 1;
      int rows = Math.max(1, (int)Math.ceil(needed / 9.0));
      int size = Math.min(54, rows * 9);
      WeaponGiveGUI.Holder holder = new WeaponGiveGUI.Holder();
      Inventory inventory = Bukkit.createInventory(holder, size, Component.text("Legendary Weapons", NamedTextColor.DARK_PURPLE));
      holder.inventory = inventory;
      int slot = 0;

      for (String key : keys) {
         ItemStack weapon = this.plugin.getWeaponManager().getWeapon(key);
         if (weapon != null && !weapon.getType().isAir() && slot < size - 1) {
            inventory.setItem(slot++, weapon);
         }
      }

      ItemStack ritual = RitualBlock.getRitualBlockItem();
      ritual.editMeta(
         meta -> meta.lore(
            List.of((TextComponent)Component.text("Click for a ritual block", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false))
         )
      );
      inventory.setItem(size - 1, ritual);
      player.openInventory(inventory);
   }

   @EventHandler
   public void onDrag(InventoryDragEvent event) {
      if (event.getInventory().getHolder() instanceof WeaponGiveGUI.Holder) {
         event.setCancelled(true);
      }
   }

   @EventHandler
   public void onClick(InventoryClickEvent event) {
      if (event.getInventory().getHolder() instanceof WeaponGiveGUI.Holder) {
         event.setCancelled(true);
         if (event.getWhoClicked() instanceof Player player) {
            if (event.getClickedInventory() != null && event.getClickedInventory().getHolder() instanceof WeaponGiveGUI.Holder) {
               ItemStack clicked = event.getCurrentItem();
               if (clicked != null && !clicked.getType().isAir()) {
                  ItemStack give = clicked.clone();
                  player.getInventory().addItem(new ItemStack[]{give}).values().forEach(left -> player.getWorld().dropItem(player.getLocation(), left));
                  player.sendActionBar(
                     Component.text("Received ", NamedTextColor.GRAY)
                        .append(
                           (Component)(give.hasItemMeta() && give.getItemMeta().hasDisplayName()
                              ? give.getItemMeta().displayName()
                              : Component.text(give.getType().name(), NamedTextColor.WHITE))
                        )
                  );
               }
            }
         }
      }
   }

   private static final class Holder implements InventoryHolder {
      private Inventory inventory;

      public Inventory getInventory() {
         return this.inventory;
      }
   }
}
