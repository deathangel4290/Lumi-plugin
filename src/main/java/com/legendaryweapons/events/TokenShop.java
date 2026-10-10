package com.legendaryweapons.events;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.items.CustomItems;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

/**
 * /tokenshop: spend Luminara Tokens. Offers are token-shop.offers, each "ITEM [amount] @ cost"
 * in the loot format, e.g. "FRAGMENT_T1 1 @ 3". Kept to consumables, materials and fragments on
 * purpose: events should not mint power faster than the rituals do.
 */
public final class TokenShop implements Listener {
   private final LegendaryWeapons plugin;

   private record Offer(String spec, int cost) {
   }

   public static final class Holder implements InventoryHolder {
      final List<Offer> offers = new ArrayList<>();
      Inventory inventory;

      @NotNull
      public Inventory getInventory() {
         return this.inventory;
      }
   }

   public TokenShop(LegendaryWeapons plugin) {
      this.plugin = plugin;
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
   }

   private List<Offer> offers() {
      List<Offer> out = new ArrayList<>();
      for (String line : this.plugin.getConfig().getStringList("token-shop.offers")) {
         int at = line.lastIndexOf('@');
         if (at < 0) {
            continue;
         }

         try {
            out.add(new Offer(line.substring(0, at).trim(), Math.max(1, Integer.parseInt(line.substring(at + 1).trim()))));
         } catch (NumberFormatException var5) {
            this.plugin.getLogger().warning("Bad token-shop offer: " + line);
         }
      }

      return out;
   }

   public static int count(Player player) {
      int total = 0;
      for (ItemStack item : player.getInventory().getContents()) {
         if (CustomItems.LUMINARA_TOKEN.equals(CustomItems.get().idOf(item))) {
            total += item.getAmount();
         }
      }

      return total;
   }

   private static void take(Player player, int amount) {
      ItemStack[] contents = player.getInventory().getContents();
      for (int i = 0; i < contents.length && amount > 0; i++) {
         ItemStack item = contents[i];
         if (CustomItems.LUMINARA_TOKEN.equals(CustomItems.get().idOf(item))) {
            int used = Math.min(amount, item.getAmount());
            amount -= used;
            if (used >= item.getAmount()) {
               player.getInventory().setItem(i, null);
            } else {
               item.setAmount(item.getAmount() - used);
            }
         }
      }
   }

   public void open(Player player) {
      Holder holder = new Holder();
      Inventory inventory = Bukkit.createInventory(holder, 54, Component.text("Token Shop - " + count(player) + " tokens", NamedTextColor.GOLD));
      holder.inventory = inventory;
      for (Offer offer : this.offers()) {
         if (holder.offers.size() >= 54) {
            break;
         }

         List<ItemStack> built = this.plugin.getEventManager().loot().build(List.of(offer.spec()));
         if (built.isEmpty()) {
            continue;
         }

         ItemStack icon = built.get(0).clone();
         ItemMeta meta = icon.getItemMeta();
         List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
         lore.add(Component.empty());
         lore.add(Component.text("Cost: " + offer.cost() + " token" + (offer.cost() == 1 ? "" : "s"), NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
         lore.add(Component.text("Click to buy", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
         meta.lore(lore);
         icon.setItemMeta(meta);
         inventory.setItem(holder.offers.size(), icon);
         holder.offers.add(offer);
      }

      player.openInventory(inventory);
   }

   @EventHandler(priority = EventPriority.HIGH)
   public void onClick(InventoryClickEvent event) {
      if (event.getView().getTopInventory().getHolder() instanceof Holder holder) {
         event.setCancelled(true);
         int slot = event.getRawSlot();
         if (slot < 0 || slot >= holder.offers.size() || !(event.getWhoClicked() instanceof Player player)) {
            return;
         }

         Offer offer = holder.offers.get(slot);
         int have = count(player);
         if (have < offer.cost()) {
            player.sendMessage(Component.text("You need " + offer.cost() + " tokens (you have " + have + ").", NamedTextColor.RED));
            this.plugin.getSoundManager().self(player, Sound.ENTITY_VILLAGER_NO, 0.7F, 1.0F);
            return;
         }

         List<ItemStack> bought = this.plugin.getEventManager().loot().build(List.of(offer.spec()));
         if (bought.isEmpty()) {
            return;
         }

         take(player, offer.cost());
         for (ItemStack item : bought) {
            player.getInventory().addItem(item).values().forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
         }

         this.plugin.getSoundManager().self(player, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8F, 1.2F);
         player.sendMessage(Component.text("Bought for " + offer.cost() + " token" + (offer.cost() == 1 ? "" : "s") + ".", NamedTextColor.GOLD));
         this.plugin.getServer().getScheduler().runTask(this.plugin, () -> {
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Holder) {
               this.open(player);
            }
         });
      }
   }

   @EventHandler(priority = EventPriority.HIGH)
   public void onDrag(InventoryDragEvent event) {
      if (event.getView().getTopInventory().getHolder() instanceof Holder) {
         event.setCancelled(true);
      }
   }
}
