package com.legendaryweapons.guis;

import com.legendaryweapons.LegendaryWeapons;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Pick an online player; the choice goes to `onPick` (which usually opens the parent again). */
public final class PlayerPicker extends Menu {
   private final String heading;
   private final String verb;
   private final BiConsumer<Player, Player> onPick;

   public PlayerPicker(LegendaryWeapons plugin, Player viewer, Menu parent, String heading, String verb, BiConsumer<Player, Player> onPick) {
      super(plugin, viewer, parent);
      this.heading = heading;
      this.verb = verb;
      this.onPick = onPick;
   }

   private List<Player> online() {
      List<Player> out = new ArrayList<>(Bukkit.getOnlinePlayers());
      out.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
      return out;
   }

   @Override
   protected String title() {
      return this.heading;
   }

   @Override
   protected int rows() {
      return 6;
   }

   @Override
   protected int pages() {
      return pagesFor(this.online().size(), this.inner().length);
   }

   @Override
   protected void draw() {
      int[] slots = this.inner();
      List<Player> shown = this.slice(this.online(), slots.length);
      for (int i = 0; i < shown.size(); i++) {
         Player target = shown.get(i);
         this.button(slots[i], Icon.head(target.getUniqueId()).name(target.getName(), NamedTextColor.WHITE).click(this.verb).build(), click -> {
            if (!target.isOnline()) {
               this.errorSound(click.player());
               this.open();
               return;
            }

            this.clickSound(click.player());
            this.onPick.accept(click.player(), target);
         });
      }

      if (shown.isEmpty()) {
         this.show(22, Icon.of(Material.PAPER).name("Nobody online", NamedTextColor.WHITE).build());
      }
   }
}
