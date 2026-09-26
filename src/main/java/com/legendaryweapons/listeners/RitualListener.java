package com.legendaryweapons.listeners;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.guis.RitualGUI;
import com.legendaryweapons.items.CustomItems;
import com.legendaryweapons.managers.HudManager;
import com.legendaryweapons.managers.ProtectionManager;
import com.legendaryweapons.managers.RitualManager;
import com.legendaryweapons.rituals.Ritual;
import com.legendaryweapons.rituals.RitualBlock;
import com.legendaryweapons.rituals.RitualRecipes;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class RitualListener implements Listener {
   private final LegendaryWeapons plugin;
   private final RitualManager ritualManager;

   public RitualListener(LegendaryWeapons plugin) {
      this.plugin = plugin;
      this.ritualManager = plugin.getRitualManager();
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onBlockPlace(BlockPlaceEvent event) {
      if (RitualBlock.isRitualBlock(event.getItemInHand())) {
         this.ritualManager.addRitualBlock(event.getBlock().getLocation());
         this.plugin.getTutorialManager().giveTutorialBook(event.getPlayer());
         this.plugin.getSoundManager().world(event.getBlock().getLocation(), Sound.BLOCK_END_PORTAL_FRAME_FILL, 1.0F, 0.8F);
      }
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onBlockBreak(BlockBreakEvent event) {
      if (!ProtectionManager.isProbing() && this.ritualManager.isRitualBlock(event.getBlock().getLocation())) {
         this.ritualManager.removeRitualBlock(event.getBlock().getLocation());
         // Give the altar back rather than a plain enchanting table.
         event.setDropItems(false);
         event.getBlock().getWorld().dropItemNaturally(event.getBlock().getLocation().add(0.5, 0.5, 0.5), RitualBlock.getRitualBlockItem());
      }
   }

   @EventHandler
   public void onPlayerInteract(PlayerInteractEvent event) {
      if (event.getClickedBlock() != null && event.getHand() == EquipmentSlot.HAND && event.getAction().isRightClick()
         && this.ritualManager.isRitualBlock(event.getClickedBlock().getLocation())) {
         event.setCancelled(true);
         Location location = event.getClickedBlock().getLocation();
         Player player = event.getPlayer();
         Ritual active = this.ritualManager.getRitualAtLocation(location);
         if (active != null) {
            HudManager.message(
               player,
               Component.text(active.getDisplayName().replaceAll("§.", "") + " ", NamedTextColor.LIGHT_PURPLE)
                  .append(Component.text(active.getFormattedTimeRemaining() + " remaining", NamedTextColor.GRAY))
            );
         } else {
            this.plugin.getGuiManager().openRitualGUI(player, location);
         }
      }
   }

   @EventHandler
   public void onInventoryClick(InventoryClickEvent event) {
      if (event.getInventory().getHolder() instanceof RitualGUI gui && event.getWhoClicked() instanceof Player player) {
         if (event.getClick() == ClickType.DOUBLE_CLICK) {
            event.setCancelled(true);
            return;
         }

         boolean top = event.getRawSlot() >= 0 && event.getRawSlot() < gui.getInventory().getSize();
         if (top && !RitualGUI.isIngredientSlot(event.getRawSlot())) {
            event.setCancelled(true);
            if (event.getRawSlot() == RitualGUI.BUTTON_SLOT) {
               this.begin(player, gui);
            } else if (event.getRawSlot() == RitualGUI.RECIPES_SLOT) {
               player.closeInventory();
               this.plugin.getGuiManager().getRecipesGUI().open(player);
            }
         }

         this.plugin.getServer().getScheduler().runTask(this.plugin, () -> gui.update(player));
      }
   }

   @EventHandler
   public void onInventoryDrag(InventoryDragEvent event) {
      if (event.getInventory().getHolder() instanceof RitualGUI gui && event.getWhoClicked() instanceof Player player) {
         for (int raw : event.getRawSlots()) {
            if (raw < gui.getInventory().getSize() && !RitualGUI.isIngredientSlot(raw)) {
               event.setCancelled(true);
               return;
            }
         }

         this.plugin.getServer().getScheduler().runTask(this.plugin, () -> gui.update(player));
      }
   }

   private void begin(Player player, RitualGUI gui) {
      Inventory inv = gui.getInventory();
      List<ItemStack> items = gui.ingredients();
      RitualRecipes recipes = this.plugin.getRitualRecipes();
      RitualRecipes.Match match = recipes.match(items);
      if (match != null) {
         List<ItemStack> consumed = new ArrayList<>();

         for (int i = 0; i < match.entries().size(); i++) {
            ItemStack source = items.get(match.slotForEntry()[i]);
            ItemStack taken = source.clone();
            taken.setAmount(match.entries().get(i).amount());
            consumed.add(taken);
         }

         if (this.ritualManager.startRitual(player, gui.getAltar(), match.weaponKey(), consumed)) {
            for (int i = 0; i < match.entries().size(); i++) {
               int slot = RitualGUI.INGREDIENT_SLOTS[match.slotForEntry()[i]];
               ItemStack source = inv.getItem(slot);
               int left = source.getAmount() - match.entries().get(i).amount();
               if (left <= 0) {
                  inv.setItem(slot, null);
               } else {
                  source.setAmount(left);
               }
            }

            this.plugin.getSoundManager().world(gui.getAltar(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, 1.0F, 0.7F);
            player.closeInventory();
         }
      } else {
         int tier = recipes.fusionTier(items);
         if (tier > 0) {
            for (int slot : RitualGUI.INGREDIENT_SLOTS) {
               ItemStack item = inv.getItem(slot);
               if (item != null && !item.getType().isAir()) {
                  int left = item.getAmount() - recipes.fusionCost();
                  if (left <= 0) {
                     inv.setItem(slot, null);
                  } else {
                     item.setAmount(left);
                  }
                  break;
               }
            }

            ItemStack fused = CustomItems.get().create(CustomItems.fragmentId(tier + 1), 1);
            player.getInventory().addItem(new ItemStack[]{fused}).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
            this.plugin.getSoundManager().self(player, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1.0F, 1.2F);
            HudManager.message(player, Component.text("Fused into a " + CustomItems.get().displayName(CustomItems.fragmentId(tier + 1)), NamedTextColor.AQUA));
         } else {
            this.plugin.getSoundManager().self(player, Sound.BLOCK_FIRE_EXTINGUISH, 0.5F, 1.5F);
         }
      }
   }

   /** Whatever is still in the ingredient slots -- leftovers or an abandoned attempt -- goes back. */
   @EventHandler
   public void onInventoryClose(InventoryCloseEvent event) {
      if (event.getInventory().getHolder() instanceof RitualGUI gui && event.getPlayer() instanceof Player player) {
         this.plugin.getGuiManager().closeRitualGUI(player);

         for (int slot : RitualGUI.INGREDIENT_SLOTS) {
            ItemStack item = gui.getInventory().getItem(slot);
            if (item != null && !item.getType().isAir()) {
               gui.getInventory().setItem(slot, null);
               player.getInventory().addItem(new ItemStack[]{item}).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
            }
         }
      }
   }
}
