package com.legendaryweapons.guis;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.events.SeasonManager;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Season Renown: someone's eight stats with their rank in each (/renown), and from any stat its
 * leaderboard (/renown top). Opens on the viewer's own stats.
 */
public final class RenownMenu extends Menu {
   private static final int[] TILES = {19, 21, 23, 25, 28, 30, 32, 34};
   private final UUID subject;
   private final String subjectName;

   public RenownMenu(LegendaryWeapons plugin, Player viewer, Menu parent) {
      this(plugin, viewer, parent, viewer.getUniqueId(), viewer.getName());
   }

   public RenownMenu(LegendaryWeapons plugin, Player viewer, Menu parent, UUID subject, String subjectName) {
      super(plugin, viewer, parent);
      this.subject = subject;
      this.subjectName = subjectName;
   }

   static Material iconFor(SeasonManager.Stat stat) {
      return switch (stat) {
         case RENOWN -> Material.GOLDEN_HELMET;
         case KILLS -> Material.IRON_SWORD;
         case DEATHS -> Material.SKELETON_SKULL;
         case BOSS_DAMAGE -> Material.WITHER_SKELETON_SKULL;
         case BOUNTIES -> Material.GOLD_INGOT;
         case OUTPOST_MINUTES -> Material.BEACON;
         case HEARTS -> Material.HEART_OF_THE_SEA;
         case STARS -> Material.NETHER_STAR;
      };
   }

   @Override
   protected String title() {
      return this.subject.equals(this.viewer.getUniqueId()) ? "Season Renown" : this.subjectName + "'s Renown";
   }

   @Override
   protected int rows() {
      return 6;
   }

   @Override
   protected void draw() {
      SeasonManager season = this.plugin.getEventManager().season();
      double renown = season.get(this.subject, SeasonManager.Stat.RENOWN);
      int rank = season.rank(this.subject, SeasonManager.Stat.RENOWN);
      this.show(13, Icon.head(this.subject).name(this.subjectName, GOLD)
         .text("Season " + season.season())
         .blank().stat("Renown", SeasonManager.format(renown), GOLD).stat("Rank", rank > 0 ? "#" + rank : "unranked", NamedTextColor.WHITE)
         .blank().text("Earn Renown from kills, events, bosses and outposts. The top three are paid when the season ends.", DIM).build());

      SeasonManager.Stat[] stats = SeasonManager.Stat.values();
      for (int i = 0; i < stats.length && i < TILES.length; i++) {
         SeasonManager.Stat stat = stats[i];
         double value = season.get(this.subject, stat);
         int place = season.rank(this.subject, stat);
         this.button(TILES[i], Icon.of(iconFor(stat)).name(stat.icon + " " + stat.label, GOLD)
            .stat("Value", SeasonManager.formatStat(stat, value), NamedTextColor.WHITE)
            .stat("Rank", place > 0 ? "#" + place : "-", place > 0 && place <= 3 ? GOLD : TEXT)
            .blank().click("see the leaderboard").build(), click -> {
            this.clickSound(click.player());
            new Leaderboard(this.plugin, click.player(), this, stat).open();
         });
      }
   }

   /** The top 28 for one stat, three medal places first. */
   static final class Leaderboard extends Menu {
      private final SeasonManager.Stat stat;

      Leaderboard(LegendaryWeapons plugin, Player viewer, Menu parent, SeasonManager.Stat stat) {
         super(plugin, viewer, parent);
         this.stat = stat;
      }

      @Override
      protected String title() {
         return this.stat.label + " leaderboard";
      }

      @Override
      protected int rows() {
         return 6;
      }

      @Override
      protected void draw() {
         SeasonManager season = this.plugin.getEventManager().season();
         int[] slots = this.inner();
         List<SeasonManager.Standing> top = season.top(this.stat, slots.length);
         this.show(4, Icon.of(iconFor(this.stat)).name(this.stat.icon + " " + this.stat.label, GOLD)
            .text("Season " + season.season() + ". " + top.size() + (top.size() == 1 ? " player" : " players") + " on the board.").build());
         if (top.isEmpty()) {
            this.show(22, Icon.of(Material.PAPER).name("Nobody yet", NamedTextColor.WHITE).text("Be the first.").build());
         }

         for (int i = 0; i < top.size(); i++) {
            SeasonManager.Standing standing = top.get(i);
            TextColor colour = switch (i) {
               case 0 -> GOLD;
               case 1 -> TextColor.color(0xD8D8E0);
               case 2 -> TextColor.color(0xCD7F32);
               default -> NamedTextColor.WHITE;
            };
            String medal = (i < 3 ? "★ #" : "#") + (i + 1) + " ";
            boolean me = standing.id().equals(this.viewer.getUniqueId());
            this.button(slots[i], Icon.head(standing.id()).name(medal + standing.name() + (me ? " (you)" : ""), colour)
               .stat(this.stat.label, SeasonManager.formatStat(this.stat, standing.value()), colour)
               .blank().click("see their stats").glow(me).build(), click -> {
               this.clickSound(click.player());
               new RenownMenu(this.plugin, click.player(), this, standing.id(), standing.name()).open();
            });
         }

         int mine = season.rank(this.viewer.getUniqueId(), this.stat);
         this.show(this.rows() * 9 - 1, Icon.head(this.viewer.getUniqueId()).name("You", ACCENT)
            .stat("Rank", mine > 0 ? "#" + mine : "unranked", NamedTextColor.WHITE)
            .stat(this.stat.label, SeasonManager.formatStat(this.stat, season.get(this.viewer.getUniqueId(), this.stat)), NamedTextColor.WHITE).build());
      }
   }
}
