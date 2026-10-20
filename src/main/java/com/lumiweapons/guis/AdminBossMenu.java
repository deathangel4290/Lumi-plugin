package com.lumiweapons.guis;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.bosses.BossManager;
import com.lumiweapons.events.EventDirector;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** The four bosses: where their arenas are, summon one, set an arena, or open its settings. */
public final class AdminBossMenu extends Menu {
   private static final int[] SLOTS = {20, 21, 23, 24};

   public AdminBossMenu(LumiWeapons plugin, Player viewer, Menu parent) {
      super(plugin, viewer, parent);
   }

   @Override
   protected String title() {
      return "Bosses";
   }

   @Override
   protected int rows() {
      return 5;
   }

   static Material icon(String id) {
      return switch (id) {
         case "hollow_warden" -> Material.SCULK_SHRIEKER;
         case "wither_king" -> Material.WITHER_SKELETON_SKULL;
         case "leviathan" -> Material.PRISMARINE_SHARD;
         case "ender_colossus" -> Material.ENDER_EYE;
         default -> Material.SKELETON_SKULL;
      };
   }

   @Override
   protected void draw() {
      BossManager bosses = this.plugin.getBossManager();
      if (bosses == null) {
         this.show(22, Icon.of(Material.BARRIER).name("Bosses are off", BAD).build());
         return;
      }

      boolean fighting = bosses.isRunning();
      this.show(4, Icon.of(Material.DRAGON_HEAD).name("Bosses", GOLD).text(bosses.status(), NamedTextColor.WHITE)
         .blank().text("Stand at an arena's centre and right-click a boss to put its arena there (default radius).", DIM).build());

      for (int i = 0; i < BossManager.IDS.size() && i < SLOTS.length; i++) {
         String id = BossManager.IDS.get(i);
         String arena = bosses.arenaText(id);
         this.button(SLOTS[i], Icon.of(icon(id)).name(EventDirector.displayName("boss:" + id), GOLD)
            .stat("Arena", arena == null ? "not set" : arena, arena == null ? BAD : NamedTextColor.WHITE)
            .blank().hint("Left-click", "summon it now").hint("Right-click", "set its arena where you stand").hint("Shift-click", "edit its settings")
            .build(), click -> {
            if (click.shift()) {
               this.clickSound(click.player());
               new ConfigMenu(this.plugin, click.player(), this, "bosses." + id).open();
            } else if (click.right()) {
               this.toggleSound(click.player(), true);
               AdminMenu.run(this, click.player(), "lwevent boss arena " + id);
            } else {
               this.toggleSound(click.player(), true);
               AdminMenu.run(this, click.player(), "lwevent boss start " + id);
            }
         });
      }

      this.button(31, Icon.of(fighting ? Material.IRON_DOOR : Material.GRAY_DYE).name("Dismiss the boss", fighting ? BAD : TEXT)
         .text(fighting ? "Ends the fight now, with no rewards." : "No boss is fighting.").blank()
         .hint("Shift-click", "dismiss").build(), click -> {
         if (!click.shift() || !fighting) {
            this.errorSound(click.player());
            return;
         }

         this.toggleSound(click.player(), false);
         AdminMenu.run(this, click.player(), "lwevent boss stop");
      });
   }
}
