package com.legendaryweapons.guis;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.managers.KeybindManager;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

public class BindsGUI implements Listener {
   private final LegendaryWeapons plugin;
   private final KeybindManager keybindManager;

   public BindsGUI(LegendaryWeapons plugin) {
      this.plugin = plugin;
      this.keybindManager = plugin.getKeybindManager();
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
   }

   public void open(@NotNull Player player) {
      int size = 27;
      String title = "Bind Weapon Ability";
      Inventory bindsInventory = Bukkit.createInventory(player, size, title);
      ItemStack border = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
      ItemMeta borderMeta = border.getItemMeta();
      borderMeta.setDisplayName(" ");
      border.setItemMeta(borderMeta);

      for (int i = 0; i < size; i++) {
         if (i < 9 || i > 17 || i % 9 == 0 || i % 9 == 8) {
            bindsInventory.setItem(i, border);
         }
      }

      String currentBind = this.keybindManager.getKeybind(player);
      bindsInventory.setItem(11, this.createBindItem(Material.DIAMOND_SWORD, "LEFT_CLICK", "Left Click", currentBind));
      bindsInventory.setItem(12, this.createBindItem(Material.GOLDEN_APPLE, "RIGHT_CLICK", "Right Click", currentBind));
      bindsInventory.setItem(14, this.createBindItem(Material.TOTEM_OF_UNDYING, "SWAP_HANDS", "Swap Hands (F)", currentBind));
      bindsInventory.setItem(15, this.createBindItem(Material.DROPPER, "DROP_ITEM", "Drop Item (Q)", currentBind));
      bindsInventory.setItem(13, this.createUnbindItem(currentBind));
      player.openInventory(bindsInventory);
   }

   private ItemStack createUnbindItem(String currentBind) {
      ItemStack item = new ItemStack(Material.BARRIER);
      ItemMeta meta = item.getItemMeta();
      meta.setDisplayName("§cUnbind");
      List<String> lore = new ArrayList<>(Arrays.asList("§7Clear your ability bind.", "§7Weapon abilities stay usable", "§7through their own controls."));
      if (currentBind == null || currentBind.equalsIgnoreCase("NONE")) {
         lore.add(" ");
         lore.add("§e(Nothing bound)");
      }

      meta.setLore(lore);
      item.setItemMeta(meta);
      return item;
   }

   private ItemStack createBindItem(Material material, String action, String displayName, String currentBind) {
      ItemStack item = new ItemStack(material);
      ItemMeta meta = item.getItemMeta();
      meta.setDisplayName("§a" + displayName);
      List<String> lore = new ArrayList<>(Arrays.asList("§7Trigger your weapon's ability", "§7with " + displayName + "."));
      if (action.equalsIgnoreCase(currentBind)) {
         meta.addEnchant(Enchantment.UNBREAKING, 1, true);
         meta.addItemFlags(new ItemFlag[]{ItemFlag.HIDE_ENCHANTS});
         lore.add(" ");
         lore.add("§e(Selected)");
      }

      meta.setLore(lore);
      item.setItemMeta(meta);
      return item;
   }

   @EventHandler
   public void onInventoryClick(InventoryClickEvent event) {
      if (event.getView().getTitle().equals("Bind Weapon Ability")) {
         event.setCancelled(true);
         Player player = (Player)event.getWhoClicked();
         ItemStack clickedItem = event.getCurrentItem();
         if (clickedItem != null && clickedItem.getType() != Material.BLACK_STAINED_GLASS_PANE) {
            if (clickedItem.getType() == Material.BARRIER) {
               if (this.keybindManager.removeKeybind(player)) {
                  player.sendMessage("§aAbility bind cleared.");
               } else {
                  player.sendMessage("§7You don't have an ability bind set.");
               }

               player.closeInventory();
               return;
            }

            String action = this.getActionFromMaterial(clickedItem.getType());
            if (action != null) {
               this.keybindManager.setKeybind(player, action);
               player.sendMessage("§aAbility bind set to " + action + ".");
               player.closeInventory();
            }
         }
      }
   }

   private String getActionFromMaterial(Material material) {
      if (material == Material.DIAMOND_SWORD) {
         return "LEFT_CLICK";
      } else if (material == Material.GOLDEN_APPLE) {
         return "RIGHT_CLICK";
      } else if (material == Material.TOTEM_OF_UNDYING) {
         return "SWAP_HANDS";
      } else {
         return material == Material.DROPPER ? "DROP_ITEM" : null;
      }
   }
}
