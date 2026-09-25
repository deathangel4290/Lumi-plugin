package com.legendaryweapons.listeners;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.abilities.AbilityDispatcher;
import com.legendaryweapons.managers.KeybindManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;

public class AbilityListener implements Listener {
   private final KeybindManager keybindManager;
   private final AbilityDispatcher dispatcher;
   private final EnderGlaiveListener enderGlaiveListener;

   public AbilityListener(LegendaryWeapons plugin, AbilityDispatcher dispatcher, EnderGlaiveListener enderGlaiveListener) {
      this.keybindManager = plugin.getKeybindManager();
      this.dispatcher = dispatcher;
      this.enderGlaiveListener = enderGlaiveListener;
   }

   @EventHandler
   public void onPlayerInteract(PlayerInteractEvent event) {
      if (event.getHand() == EquipmentSlot.HAND) {
         Player player = event.getPlayer();
         String keybind = this.keybindManager.getKeybind(player);
         boolean wantsRight = keybind.equalsIgnoreCase("RIGHT_CLICK");
         boolean wantsLeft = keybind.equalsIgnoreCase("LEFT_CLICK");
         if (wantsRight || wantsLeft) {
            Action action = event.getAction();
            boolean matched = wantsRight
               ? action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK
               : action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
            if (matched) {
               this.dispatcher.trigger(player);
            }
         }
      }
   }

   @EventHandler
   public void onPlayerDropItem(PlayerDropItemEvent event) {
      Player player = event.getPlayer();
      if (this.keybindManager.getKeybind(player).equalsIgnoreCase("DROP_ITEM") && this.dispatcher.trigger(player)) {
         event.setCancelled(true);
      }
   }

   @EventHandler
   public void onPlayerSwapHandItems(PlayerSwapHandItemsEvent event) {
      Player player = event.getPlayer();
      if (this.keybindManager.getKeybind(player).equalsIgnoreCase("SWAP_HANDS") && this.dispatcher.trigger(player)) {
         event.setCancelled(true);
      }
   }

   @EventHandler
   public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
      Player player = event.getPlayer();
      if (!event.isSneaking()) {
         if (this.dispatcher.isProtectedTrustedTarget(player)) {
            return;
         }

         this.enderGlaiveListener.releaseAbility(player);
      }
   }
}
