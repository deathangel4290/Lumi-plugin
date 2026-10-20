package com.lumiweapons.guis;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.teams.Draft;
import com.lumiweapons.teams.TeamManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * The draft board (/draft). Everyone can watch; the captain on the clock clicks a head to pick.
 *
 *   header   whose pick it is and the clock
 *   rows 1-3 the pool (21 a page)
 *   row 4    one banner per team with its roster
 */
public final class DraftMenu extends Menu {
   private static final int[] POOL = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
   private static final int[] TEAMS = {37, 38, 39, 40, 41, 42, 43};
   private final Runnable watcher = this::refresh;
   private Draft watching;

   public DraftMenu(LumiWeapons plugin, Player viewer, Menu parent) {
      super(plugin, viewer, parent);
   }

   static Material wool(NamedTextColor colour) {
      return switch (NamedTextColor.NAMES.key(colour)) {
         case "red" -> Material.RED_WOOL;
         case "blue" -> Material.BLUE_WOOL;
         case "green" -> Material.LIME_WOOL;
         case "yellow" -> Material.YELLOW_WOOL;
         case "aqua" -> Material.LIGHT_BLUE_WOOL;
         case "light_purple" -> Material.MAGENTA_WOOL;
         case "gold" -> Material.ORANGE_WOOL;
         default -> Material.WHITE_WOOL;
      };
   }

   static Icon roster(TeamManager.Team team) {
      Icon icon = Icon.of(wool(team.colour)).name(team.name, team.colour)
         .stat("Captain", team.captain == null ? "-" : team.nameOf(team.captain), NamedTextColor.WHITE)
         .stat("Players", String.valueOf(team.members.size()), NamedTextColor.WHITE).blank();
      for (UUID id : team.members) {
         boolean online = Bukkit.getPlayer(id) != null;
         icon.line(Icon.plain((online ? "● " : "○ ") + team.nameOf(id) + (id.equals(team.captain) ? " ★" : ""), online ? NamedTextColor.WHITE : DIM));
      }

      return icon;
   }

   private Draft draft() {
      return this.plugin.getTeamManager().draft();
   }

   @Override
   protected String title() {
      return "Team Draft";
   }

   @Override
   protected int rows() {
      return 6;
   }

   @Override
   protected int pages() {
      Draft draft = this.draft();
      return draft == null ? 1 : pagesFor(draft.pool().size(), POOL.length);
   }

   @Override
   protected void draw() {
      Draft draft = this.draft();
      if (draft != this.watching) {
         if (this.watching != null) {
            this.watching.unwatch(this.watcher);
         }

         this.watching = draft;
         if (draft != null) {
            draft.watch(this.watcher);
         }
      }

      if (draft == null) {
         this.show(4, Icon.of(Material.WHITE_BANNER).name("No draft running", NamedTextColor.WHITE).text("When an admin starts a Team Draft, it shows up here live.").build());
         int slot = 0;
         for (TeamManager.Team team : this.plugin.getTeamManager().teams()) {
            if (slot < TEAMS.length) {
               this.show(TEAMS[slot++], roster(team).build());
            }
         }

         return;
      }

      TeamManager.Team current = draft.current();
      boolean mine = draft.isPicking(this.viewer);
      this.show(4, Icon.of(current == null ? Material.WHITE_BANNER : wool(current.colour)).amount(Math.max(1, Math.min(64, draft.secondsLeft())))
         .name(mine ? "Your pick!" : (current == null ? "Draft" : current.name + " is picking"), current == null ? NamedTextColor.WHITE : current.colour)
         .stat("Time left", draft.secondsLeft() + "s", draft.secondsLeft() <= 10 ? BAD : NamedTextColor.WHITE)
         .stat("Round", String.valueOf(draft.round()), NamedTextColor.WHITE)
         .stat("Left in the pool", String.valueOf(draft.pool().size()), NamedTextColor.WHITE)
         .blank().text(mine ? "Click a player below to pick them." : "Captains pick in snake order. Run out of time and the pick is random.", DIM)
         .glow(mine).build());

      List<UUID> shown = this.slice(draft.pool(), POOL.length);
      for (int i = 0; i < shown.size(); i++) {
         UUID id = shown.get(i);
         boolean online = Bukkit.getPlayer(id) != null;
         Icon head = Icon.head(id).name(draft.nameOf(id), NamedTextColor.WHITE)
            .line(Icon.plain(online ? "● Online" : "○ Offline", online ? GOOD : DIM));
         if (mine) {
            head.blank().click("pick them");
         }

         this.button(POOL[i], head.build(), click -> {
            if (!draft.isPicking(click.player())) {
               this.errorSound(click.player());
               return;
            }

            String error = draft.pick(click.player(), id);
            if (error != null) {
               tell(click.player(), error, BAD);
               this.errorSound(click.player());
            }
         });
      }

      List<TeamManager.Team> order = draft.order();
      for (int i = 0; i < order.size() && i < TEAMS.length; i++) {
         TeamManager.Team team = order.get(i);
         this.show(TEAMS[i], roster(team).glow(team == current).build());
      }
   }

   @Override
   protected void onClose() {
      if (this.watching != null) {
         this.watching.unwatch(this.watcher);
         this.watching = null;
      }
   }

   /** Admin set-up: choose the captains and who sits out, then start. */
   public static final class Setup extends Menu {
      private final List<UUID> captains = new ArrayList<>();
      private final Set<UUID> sittingOut = new HashSet<>();

      public Setup(LumiWeapons plugin, Player viewer, Menu parent) {
         super(plugin, viewer, parent);
      }

      private List<Player> online() {
         List<Player> out = new ArrayList<>(Bukkit.getOnlinePlayers());
         out.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
         return out;
      }

      @Override
      protected String title() {
         return "Start a Team Draft";
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
         var config = this.plugin.getConfig();
         int pick = config.getInt("teams.draft.pick-seconds", 45);
         int max = config.getInt("teams.draft.max-team-size", 0);
         this.show(4, Icon.of(Material.WHITE_BANNER).name("Team Draft", ACCENT)
            .text("Left-click players to make them captains (2 to " + TeamManager.COLOURS.size() + "). Right-click anyone who should sit out. Everyone else goes in the pool.")
            .blank().stat("Captains", String.valueOf(this.captains.size()), NamedTextColor.WHITE)
            .stat("Seconds per pick", String.valueOf(pick), NamedTextColor.WHITE)
            .stat("Max team size", max <= 0 ? "no limit" : String.valueOf(max), NamedTextColor.WHITE)
            .blank().text("Change these under Server Settings > teams.", DIM).build());

         int[] slots = this.inner();
         List<Player> shown = this.slice(this.online(), slots.length);
         for (int i = 0; i < shown.size(); i++) {
            Player player = shown.get(i);
            UUID id = player.getUniqueId();
            int captainAt = this.captains.indexOf(id);
            boolean out = this.sittingOut.contains(id);
            Icon head = Icon.head(id);
            if (captainAt >= 0) {
               NamedTextColor colour = TeamManager.COLOURS.get(Math.min(captainAt, TeamManager.COLOURS.size() - 1));
               head.name("★ " + player.getName() + " (captain)", colour).glow(true);
            } else if (out) {
               head.name(player.getName() + " (sitting out)", DIM);
            } else {
               head.name(player.getName(), NamedTextColor.WHITE).line(Icon.plain("In the pool", TEXT));
            }

            head.blank().hint("Left-click", captainAt >= 0 ? "remove as captain" : "make captain").hint("Right-click", out ? "put back in the pool" : "sit out");
            this.button(slots[i], head.build(), click -> {
               if (click.right()) {
                  if (!this.sittingOut.remove(id)) {
                     this.sittingOut.add(id);
                     this.captains.remove(id);
                  }
               } else if (!this.captains.remove(id)) {
                  if (this.captains.size() >= TeamManager.COLOURS.size()) {
                     this.errorSound(click.player());
                     return;
                  }

                  this.captains.add(id);
                  this.sittingOut.remove(id);
               }

               this.clickSound(click.player());
               this.refresh();
            });
         }

         boolean ready = this.captains.size() >= 2;
         this.button(this.rows() * 9 - 1, Icon.of(ready ? Material.LIME_BANNER : Material.GRAY_BANNER).name("Start the draft", ready ? GOOD : TEXT)
            .text(ready ? "Teams are made for the captains, then picking begins." : "Choose at least two captains first.")
            .blank().click("start").build(), click -> {
            if (!ready) {
               this.errorSound(click.player());
               return;
            }

            List<Player> captainPlayers = new ArrayList<>();
            for (UUID id : this.captains) {
               Player captain = Bukkit.getPlayer(id);
               if (captain != null) {
                  captainPlayers.add(captain);
               }
            }

            List<Player> pool = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
               if (!this.captains.contains(online.getUniqueId()) && !this.sittingOut.contains(online.getUniqueId())) {
                  pool.add(online);
               }
            }

            String error = Draft.start(this.plugin, this.plugin.getTeamManager(), captainPlayers, pool);
            if (error != null) {
               tell(click.player(), error, BAD);
               this.errorSound(click.player());
               return;
            }

            new DraftMenu(this.plugin, click.player(), this.parent).open();
         });
      }
   }
}
