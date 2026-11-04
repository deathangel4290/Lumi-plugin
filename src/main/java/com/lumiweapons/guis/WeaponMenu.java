package com.lumiweapons.guis;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.abilities.Passives;
import com.lumiweapons.rituals.RitualRecipes;
import java.util.List;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * One legendary up close (from the recipe book): the weapon with its full tooltip, the ritual's
 * ingredients as the real items, and how the ritual goes.
 *
 *   header   the weapon
 *   row 2    the ingredients, centred
 *   row 3    ritual length · forged or not · off-hand passives
 */
public final class WeaponMenu extends Menu {
   private final String weaponKey;

   public WeaponMenu(LumiWeapons plugin, Player viewer, Menu parent, String weaponKey) {
      super(plugin, viewer, parent);
      this.weaponKey = weaponKey;
   }

   @Override
   protected String title() {
      ItemStack weapon = this.plugin.getWeaponManager().getWeapon(this.weaponKey);
      if (weapon.hasItemMeta() && weapon.getItemMeta().hasDisplayName()) {
         return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(weapon.getItemMeta().displayName());
      }

      return ConfigMenu.pretty(this.weaponKey);
   }

   @Override
   protected int rows() {
      return 5;
   }

   private ItemStack ingredient(RitualRecipes.Entry entry) {
      return Icon.of(entry.display()).clearLore().name(entry.describe(), entry.customId() != null ? NamedTextColor.AQUA : NamedTextColor.WHITE)
         .text(entry.amount() + " needed", TEXT).build();
   }

   @Override
   protected void draw() {
      var config = this.plugin.getConfigManager();
      ItemStack weapon = this.plugin.getWeaponManager().getWeapon(this.weaponKey);
      this.show(4, weapon);

      List<RitualRecipes.Entry> entries = this.plugin.getRitualRecipes().entries(this.weaponKey);
      int count = Math.min(7, entries.size());
      int start = 19 + (7 - count) / 2;                 // centred in row 2
      for (int i = 0; i < count; i++) {
         this.show(start + i, this.ingredient(entries.get(i)));
      }

      if (entries.isEmpty()) {
         this.show(22, Icon.of(Material.BARRIER).name("No ritual recipe", BAD).text("This legendary can't be forged at an altar.").build());
      }

      int seconds = this.plugin.getRitualManager().ritualSeconds(this.weaponKey);
      Icon ritual = Icon.of(Material.CLOCK).name("The ritual", ACCENT)
         .stat("Takes", seconds >= 60 ? seconds / 60 + " minutes" : seconds + " seconds", NamedTextColor.WHITE);
      var clock = this.plugin.getRitualManager().clock();
      if (clock.windowsEnabled()) {
         ritual.blank().text("Can be started " + clock.describe() + ".", GOLD);
      }

      this.show(30, ritual.blank().text("Everyone can see where it is while it runs. Breaking the altar ends it.", DIM).build());

      if (config.isSingleCraft()) {
         boolean forged = this.plugin.getRitualManager().isCrafted(this.weaponKey);
         Icon status = Icon.of(forged ? Material.RED_DYE : Material.LIME_DYE).name(forged ? "Already forged" : "Not forged yet", forged ? BAD : GOOD)
            .text("Only one of each legendary exists on this server.");
         boolean admin = forged && this.viewer.hasPermission("lumiweapons.admin");
         if (admin) {
            status.blank().text("Admins: lets it be forged again. Copies players already have are kept.", DIM).hint("Shift-right-click", "un-forge");
         }

         this.button(31, status.build(), !admin ? null : click -> {
            if (!(click.shift() && click.right())) {
               this.errorSound(click.player());
               return;
            }

            if (this.plugin.getRitualManager().uncraft(this.weaponKey)) {
               this.toggleSound(click.player(), true);
               tell(click.player(), "It can be forged again.", GOOD);
            } else {
               this.errorSound(click.player());
               tell(click.player(), "It's being forged right now. Break its altar to cancel the ritual.", BAD);
            }

            this.refresh();
         });
      }

      if (com.lumiweapons.abilities.AbilityDispatcher.OFF_HAND_WIELDED.contains(this.weaponKey)) {
         this.show(32, Icon.of(Material.SHIELD).name("Either hand", GOOD).text("Its passive and abilities work from the main hand or the off-hand.").build());
         return;
      }

      boolean passives = Passives.WITH_PASSIVES.contains(this.weaponKey) && Passives.offHandEnabled(this.plugin);
      double strength = Passives.offHandStrength(this.plugin);
      this.show(32, Icon.of(Material.SHIELD).name("Off-hand", passives ? GOOD : TEXT)
         .text(passives
            ? "In your off-hand it gives its passives" + (strength < 1.0 ? " at " + Math.round(strength * 100.0) + "% strength" : "") + ", on top of your main weapon's."
            : "Its powers only work from the main hand.").build());
   }
}
