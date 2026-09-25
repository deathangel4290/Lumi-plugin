package com.legendaryweapons.listeners;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.guis.RitualGUI;
import com.legendaryweapons.managers.RitualManager;
import com.legendaryweapons.rituals.Ritual;
import com.legendaryweapons.rituals.RitualBlock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

public class RitualListener implements Listener {
   private final LegendaryWeapons plugin;
   private final RitualManager ritualManager;
   private final Set<UUID> playersInRitual = new HashSet<>();
   private final Map<UUID, Location> ritualLocations = new HashMap<>();

   public RitualListener(LegendaryWeapons plugin) {
      this.plugin = plugin;
      this.ritualManager = plugin.getRitualManager();
   }

   @EventHandler
   public void onPlayerJoin(PlayerJoinEvent event) {
      Player player = event.getPlayer();
      this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
         if (player.isOnline()) {
            for (Ritual ritual : this.ritualManager.getActiveRituals()) {
               ritual.showTo(player);
            }
         }
      }, 20L);
   }

   @EventHandler
   public void onBlockPlace(BlockPlaceEvent event) {
      ItemStack item = event.getItemInHand();
      if (RitualBlock.isRitualBlock(item)) {
         this.ritualManager.addRitualBlock(event.getBlock().getLocation());
         this.plugin.getTutorialManager().giveTutorialBook(event.getPlayer());
      }
   }

   @EventHandler
   public void onBlockBreak(BlockBreakEvent event) {
      if (this.ritualManager.isRitualBlock(event.getBlock().getLocation())) {
         this.ritualManager.removeRitualBlock(event.getBlock().getLocation());
      }
   }

   @EventHandler
   public void onPlayerInteract(PlayerInteractEvent event) {
      if (event.getClickedBlock() != null && this.ritualManager.isRitualBlock(event.getClickedBlock().getLocation())) {
         if (event.getHand() == EquipmentSlot.HAND) {
            event.setCancelled(true);
            Location location = event.getClickedBlock().getLocation();
            Player player = event.getPlayer();
            Ritual active = this.ritualManager.getRitualAtLocation(location);
            if (active != null) {
               player.sendActionBar(
                  Component.text(active.getDisplayName() + " ", NamedTextColor.LIGHT_PURPLE)
                     .append(Component.text(active.getFormattedTimeRemaining() + " remaining", NamedTextColor.GRAY))
               );
            } else {
               this.ritualLocations.put(player.getUniqueId(), location);
               this.plugin.getGuiManager().openRitualGUI(player);
            }
         }
      }
   }

   @EventHandler
   public void onInventoryClick(InventoryClickEvent event) {
      if (event.getInventory().getHolder() instanceof RitualGUI && event.getClickedInventory() != event.getView().getBottomInventory()) {
         int slot = event.getSlot();
         if (slot != 11 && slot != 13 && slot != 15) {
            event.setCancelled(true);
            if (event.getCurrentItem() != null && event.getCurrentItem().getType() == Material.ANVIL) {
               Player player = (Player)event.getWhoClicked();
               RitualGUI ritualGUI = this.plugin.getGuiManager().getOpenRitualGUI(player);
               if (ritualGUI == null) {
                  return;
               }

               Inventory inv = ritualGUI.getInventory();
               List<ItemStack> items = new ArrayList<>();
               items.add(inv.getItem(11));
               items.add(inv.getItem(13));
               items.add(inv.getItem(15));
               Location location = this.ritualLocations.get(player.getUniqueId());
               if (location != null) {
                  if (this.ritualManager.startRitual(player, location, items)) {
                     this.playersInRitual.add(player.getUniqueId());
                     inv.clear(11);
                     inv.clear(13);
                     inv.clear(15);
                     player.closeInventory();
                  }
               } else {
                  player.sendMessage("§cCould not find the ritual block location.");
               }
            }
         }
      }
   }

   @EventHandler
   public void onInventoryClose(InventoryCloseEvent event) {
      if (event.getInventory().getHolder() instanceof RitualGUI) {
         Player player = (Player)event.getPlayer();
         this.plugin.getGuiManager().closeRitualGUI(player);
         this.ritualLocations.remove(player.getUniqueId());
         if (this.playersInRitual.contains(player.getUniqueId())) {
            this.playersInRitual.remove(player.getUniqueId());
            return;
         }

         Inventory inv = event.getInventory();

         for (int slot : new int[]{11, 13, 15}) {
            ItemStack item = inv.getItem(slot);
            if (item != null) {
               player.getInventory().addItem(new ItemStack[]{item});
            }
         }
      }
   }
}
