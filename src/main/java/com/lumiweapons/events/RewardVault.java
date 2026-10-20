package com.lumiweapons.events;

import com.lumiweapons.LumiWeapons;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Personal reward chests. Every new event pays out here instead of straight into the inventory, so
 * prizes are never lost to a full inventory, a death or being offline. Players open theirs with
 * /rewards and can only take items out; whatever is left stays for next time.
 *
 * Items are stored serialized in vault.yml and written after every change, so a crash can never
 * hand the same item out twice.
 */
public final class RewardVault implements Listener {
   private static final int SIZE = 54;
   private final LumiWeapons plugin;
   private final File file;
   private final Map<UUID, List<ItemStack>> vaults = new HashMap<>();

   public static final class Holder implements InventoryHolder {
      final UUID owner;
      final List<ItemStack> overflow;
      Inventory inventory;

      Holder(UUID owner, List<ItemStack> overflow) {
         this.owner = owner;
         this.overflow = overflow;
      }

      @NotNull
      public Inventory getInventory() {
         return this.inventory;
      }
   }

   public RewardVault(LumiWeapons plugin) {
      this.plugin = plugin;
      this.file = new File(plugin.getDataFolder(), "vault.yml");
      this.load();
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
   }

   private void load() {
      YamlConfiguration data = YamlConfiguration.loadConfiguration(this.file);
      for (String key : data.getKeys(false)) {
         try {
            UUID id = UUID.fromString(key);
            List<ItemStack> items = new ArrayList<>();
            for (String encoded : data.getStringList(key)) {
               try {
                  items.add(ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded)));
               } catch (RuntimeException var8) {
                  this.plugin.getLogger().warning("Skipping an unreadable vault item for " + key);
               }
            }

            if (!items.isEmpty()) {
               this.vaults.put(id, items);
            }
         } catch (IllegalArgumentException ignored) {
         }
      }
   }

   private void save() {
      YamlConfiguration data = new YamlConfiguration();
      for (Map.Entry<UUID, List<ItemStack>> entry : this.vaults.entrySet()) {
         List<String> encoded = new ArrayList<>();
         for (ItemStack item : entry.getValue()) {
            if (item != null && !item.getType().isAir()) {
               encoded.add(Base64.getEncoder().encodeToString(item.serializeAsBytes()));
            }
         }

         if (!encoded.isEmpty()) {
            data.set(entry.getKey().toString(), encoded);
         }
      }

      try {
         this.file.getParentFile().mkdirs();
         data.save(this.file);
      } catch (IOException var5) {
         this.plugin.getLogger().severe("Could not save vault.yml: " + var5.getMessage());
      }
   }

   public int count(UUID id) {
      List<ItemStack> items = this.vaults.get(id);
      return items == null ? 0 : items.size();
   }

   /** Put items in a player's vault (online or not) and tell them if they are on. */
   public void deposit(UUID id, List<ItemStack> items, String reason) {
      List<ItemStack> clean = new ArrayList<>();
      for (ItemStack item : items) {
         if (item != null && !item.getType().isAir()) {
            clean.add(item.clone());
         }
      }

      if (!clean.isEmpty()) {
         this.vaults.computeIfAbsent(id, k -> new ArrayList<>()).addAll(clean);
         this.save();
         Player online = Bukkit.getPlayer(id);
         if (online != null) {
            online.sendMessage(Component.text("📦 " + clean.size() + " reward" + (clean.size() == 1 ? "" : "s") + " added to your reward chest", NamedTextColor.GOLD)
               .append(Component.text(reason == null ? "" : " (" + reason + ")", NamedTextColor.GRAY))
               .append(Component.text(" - /rewards", NamedTextColor.YELLOW)));
            this.plugin.getSoundManager().self(online, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7F, 1.4F);
         }
      }
   }

   /** Roll "ITEM [amount]" specs and deposit them. */
   public void depositSpecs(UUID id, String name, List<String> specs, String reason) {
      this.deposit(id, this.plugin.getEventManager().loot().build(specs), reason);
   }

   public void remind(Player player) {
      int count = this.count(player.getUniqueId());
      if (count > 0) {
         player.sendMessage(Component.text("📦 You have " + count + " unclaimed reward" + (count == 1 ? "" : "s") + ". ", NamedTextColor.GOLD)
            .append(Component.text("/rewards", NamedTextColor.YELLOW)));
      }
   }

   public void open(Player player) {
      List<ItemStack> items = this.vaults.getOrDefault(player.getUniqueId(), new ArrayList<>());
      List<ItemStack> shown = new ArrayList<>(items.subList(0, Math.min(SIZE, items.size())));
      List<ItemStack> overflow = new ArrayList<>(items.subList(Math.min(SIZE, items.size()), items.size()));
      Holder holder = new Holder(player.getUniqueId(), overflow);
      Inventory inventory = Bukkit.createInventory(holder, SIZE, Component.text("Reward Chest" + (overflow.isEmpty() ? "" : " (+" + overflow.size() + " more)"), NamedTextColor.DARK_PURPLE));
      holder.inventory = inventory;
      for (int i = 0; i < shown.size(); i++) {
         inventory.setItem(i, shown.get(i));
      }

      player.openInventory(inventory);
   }

   /** The vault becomes whatever is still in the window, plus anything that did not fit in it. */
   private void sync(Holder holder) {
      List<ItemStack> remaining = new ArrayList<>();
      for (ItemStack item : holder.inventory.getContents()) {
         if (item != null && !item.getType().isAir()) {
            remaining.add(item.clone());
         }
      }

      remaining.addAll(holder.overflow);
      if (remaining.isEmpty()) {
         this.vaults.remove(holder.owner);
      } else {
         this.vaults.put(holder.owner, remaining);
      }

      this.save();
   }

   /** Take-only: nothing can be put into the reward chest. */
   @EventHandler(priority = EventPriority.HIGH)
   public void onClick(InventoryClickEvent event) {
      if (event.getView().getTopInventory().getHolder() instanceof Holder holder) {
         boolean top = event.getRawSlot() >= 0 && event.getRawSlot() < SIZE;
         InventoryAction action = event.getAction();
         boolean allowed;
         if (top) {
            allowed = switch (action) {
               case PICKUP_ALL, PICKUP_HALF, PICKUP_ONE, PICKUP_SOME, MOVE_TO_OTHER_INVENTORY, DROP_ALL_SLOT, DROP_ONE_SLOT -> true;
               case HOTBAR_SWAP -> {
                  ItemStack hotbar = event.getHotbarButton() >= 0 ? event.getWhoClicked().getInventory().getItem(event.getHotbarButton()) : null;
                  yield hotbar == null || hotbar.getType().isAir();
               }
               default -> false;
            };
         } else {
            allowed = action != InventoryAction.MOVE_TO_OTHER_INVENTORY && action != InventoryAction.COLLECT_TO_CURSOR;
         }

         if (!allowed) {
            event.setCancelled(true);
         } else if (top) {
            this.plugin.getServer().getScheduler().runTask(this.plugin, () -> this.sync(holder));
         }
      }
   }

   @EventHandler(priority = EventPriority.HIGH)
   public void onDrag(InventoryDragEvent event) {
      if (event.getView().getTopInventory().getHolder() instanceof Holder) {
         for (int slot : event.getRawSlots()) {
            if (slot < SIZE) {
               event.setCancelled(true);
               return;
            }
         }
      }
   }

   @EventHandler
   public void onClose(InventoryCloseEvent event) {
      if (event.getInventory().getHolder() instanceof Holder holder) {
         this.sync(holder);
      }
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      if (event.getPlayer().getOpenInventory().getTopInventory().getHolder() instanceof Holder holder) {
         this.sync(holder);
      }
   }
}
