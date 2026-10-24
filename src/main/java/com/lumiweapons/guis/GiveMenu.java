package com.lumiweapons.guis;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.items.CustomItems;
import com.lumiweapons.managers.HudManager;
import com.lumiweapons.rituals.RitualBlock;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/** Every legendary, custom item and the Ritual Altar (/gui). Left-click takes one; right-click gives it to someone. */
public final class GiveMenu extends Menu {
   public GiveMenu(LumiWeapons plugin, Player viewer, Menu parent) {
      super(plugin, viewer, parent);
   }

   private List<ItemStack> items() {
      List<ItemStack> out = new ArrayList<>();
      for (String key : this.plugin.getWeaponManager().getWeaponKeys()) {
         ItemStack weapon = this.plugin.getWeaponManager().getWeapon(key);
         if (weapon != null && !weapon.getType().isAir()) {
            out.add(weapon);
         }
      }

      out.addAll(CustomItems.get().sampleSet());
      out.add(RitualBlock.getRitualBlockItem());
      out.add(RitualBlock.getAncientAltarItem());
      return out;
   }

   @Override
   protected String title() {
      return "Give Items";
   }

   @Override
   protected int rows() {
      return 6;
   }

   @Override
   protected int pages() {
      return pagesFor(this.items().size(), this.inner().length);
   }

   private static void give(Player to, ItemStack item) {
      ItemStack copy = item.clone();
      to.getInventory().addItem(copy).values().forEach(left -> to.getWorld().dropItem(to.getLocation(), left));
      Component name = copy.hasItemMeta() && copy.getItemMeta().hasDisplayName() ? copy.getItemMeta().displayName() : Component.text(copy.getType().name(), NamedTextColor.WHITE);
      HudManager.message(to, Component.text("Received ", NamedTextColor.GRAY).append(name));
   }

   @Override
   protected void draw() {
      int[] slots = this.inner();
      List<ItemStack> shown = this.slice(this.items(), slots.length);
      for (int i = 0; i < shown.size(); i++) {
         ItemStack item = shown.get(i);
         ItemStack icon = item.clone();
         icon.editMeta(meta -> {
            List<Component> lore = meta.lore() == null ? new ArrayList<>() : new ArrayList<>(meta.lore());
            lore.add(Component.empty());
            lore.add(Icon.plain("▸ ", ACCENT).append(Component.text("Left-click", NamedTextColor.YELLOW)).append(Component.text(" to take", DIM)));
            if (item.getMaxStackSize() > 1) {
               lore.add(Icon.plain("▸ ", ACCENT).append(Component.text("Shift-click", NamedTextColor.YELLOW)).append(Component.text(" to take a stack", DIM)));
            }

            lore.add(Icon.plain("▸ ", ACCENT).append(Component.text("Right-click", NamedTextColor.YELLOW)).append(Component.text(" to give to a player", DIM)));
            meta.lore(lore);
         });
         this.button(slots[i], icon, click -> {
            if (click.right()) {
               this.clickSound(click.player());
               new PlayerPicker(this.plugin, click.player(), this, "Give to...", "give it to them", (admin, target) -> {
                  give(target, item);
                  tell(admin, "Gave it to " + target.getName() + ".", GOOD);
                  this.open();
               }).open();
            } else {
               this.toggleSound(click.player(), true);
               ItemStack taken = item.clone();
               if (click.shift() && item.getMaxStackSize() > 1) {
                  taken.setAmount(item.getMaxStackSize());
               }

               give(click.player(), taken);
            }
         });
      }
   }
}
