package com.lumiweapons.listeners;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.abilities.AbilityDispatcher;
import com.lumiweapons.managers.KeybindManager;
import com.lumiweapons.managers.WeaponManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public class AbilityListener implements Listener {
   private final LumiWeapons plugin;
   private final WeaponManager weaponManager;
   private final KeybindManager keybindManager;
   private final AbilityDispatcher dispatcher;
   private final EnderGlaiveListener enderGlaiveListener;

   public AbilityListener(LumiWeapons plugin, AbilityDispatcher dispatcher, EnderGlaiveListener enderGlaiveListener) {
      this.plugin = plugin;
      this.weaponManager = plugin.getWeaponManager();
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

   /**
    * By the time this fires the weapon has already left the hand (the event carries it), so the
    * ability cannot be read from the main hand here. Cancelling puts the weapon back after the
    * event; the ability then fires on the next tick with it in hand again. Setting the hand
    * ourselves instead would duplicate it, because the server also returns a cancelled drop.
    */
   @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
   public void onPlayerDropItem(PlayerDropItemEvent event) {
      Player player = event.getPlayer();
      if (!this.keybindManager.getKeybind(player).equalsIgnoreCase("DROP_ITEM")) {
         return;
      }

      ItemStack dropped = event.getItemDrop().getItemStack();
      String key = this.weaponManager.isLegendaryWeapon(dropped) ? this.weaponManager.getWeaponKey(dropped) : null;
      // Q on the held weapon leaves the hand empty; a drop from the inventory screen does not
      if (key == null || !this.dispatcher.isRegistered(key) || !player.getInventory().getItemInMainHand().getType().isAir()) {
         return;
      }

      event.setCancelled(true);
      this.plugin.getServer().getScheduler().runTask(this.plugin, () -> {
         if (player.isOnline() && key.equals(this.dispatcher.heldWeaponKey(player))) {
            this.dispatcher.trigger(player);
         }
      });
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
