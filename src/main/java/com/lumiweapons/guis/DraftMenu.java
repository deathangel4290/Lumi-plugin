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
 *   header   whose pick it is, the clock (the stack size counts down) and the round
 *   rows 1-3 the pool, 21 a page
 *   row 4    one banner per team with its roster; the team picking glows
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
         case "red", "dark_red" -> Material.RED_WOOL;
         case "blue", "dark_blue" -> Material.BLUE_WOOL;
         case "green" -> Material.LIME_WOOL;
         case "dark_green" -> Material.GREEN_WOOL;
         case "yellow" -> Material.YELLOW_WOOL;
         case "aqua" -> Material.LIGHT_BLUE_WOOL;
         case "dark_aqua" -> Material.CYAN_WOOL;
         case "light_purple" -> Material.MAGENTA_WOOL;
         case "dark_purple" -> Material.PURPLE_WOOL;
         case "gold" -> Material.ORANGE_WOOL;
         case "gray" -> Material.LIGHT_GRAY_WOOL;
         case "dark_gray" -> Material.GRAY_WOOL;
         case "black" -> Material.BLACK_WOOL;
         default -> Material.WHITE_WOOL;
      };
   }

   /** A team as a banner: captain, size, and every player (● online, ○ offline, ♛ captain). */
   static Icon roster(TeamManager.Team team) {
      Icon icon = Icon.of(wool(team.colour)).name(team.name, team.colour)
         .stat("Captain", team.captain == null ? "-" : team.nameOf(team.captain), NamedTextColor.WHITE)
         .stat("Players", String.valueOf(team.members.size()), NamedTextColor.WHITE).blank();
      for (UUID id : team.members) {
         boolean online = Bukkit.getPlayer(id) != null;
         icon.line(Icon.plain((online ? "● " : "○ ") + team.nameOf(id) + (id.equals(team.captain) ? "  ♛" : ""), online ? NamedTextColor.WHITE : DIM));
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
         boolean any = !this.plugin.getTeamManager().teams().isEmpty();
         this.show(4, Icon.of(Material.WHITE_BANNER).name(any ? "Teams" : "No teams yet", NamedTextColor.WHITE)
            .text(any ? "Every team and who is on it. A new Team Draft shows up here live." : "When an admin starts a Team Draft, it shows up here live.").build());
         int slot = 0;
         for (TeamManager.Team team : this.plugin.getTeamManager().teams()) {
            if (slot < TEAMS.length) {
               this.show(TEAMS[slot++], roster(team).build());
            }
         }

         if (this.viewer.hasPermission(AdminMenu.PERMISSION)) {
            this.button(this.rows() * 9 - 1, Icon.of(Material.LIME_BANNER).name("Start a draft", GOOD).text("Admins: choose captains and start.").blank().click("set up").build(), click -> {
               this.clickSound(click.player());
               new Setup(this.plugin, click.player(), this).open();
            });
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
         .blank().text(mine ? "Click a player below to pick them." : "Captains take turns. Run out of time and the pick is made for you.", DIM)
         .glow(mine).build());

      List<UUID> shown = this.slice(draft.pool(), POOL.length);
      for (int i = 0; i < shown.size(); i++) {
         UUID id = shown.get(i);
         boolean online = Bukkit.getPlayer(id) != null;
         Icon head = this.head(id).name(draft.nameOf(id), NamedTextColor.WHITE)
            .line(Icon.plain(online ? "● Online" : "○ Offline", online ? GOOD : DIM));
         if (mine) {
            head.blank().click("pick them for " + current.name);
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

      if (this.viewer.hasPermission(AdminMenu.PERMISSION)) {
         this.button(this.rows() * 9 - 1, Icon.of(Material.BARRIER).name("Cancel the draft", BAD)
            .text(this.plugin.getConfig().getBoolean("teams.draft.cancel-removes-teams", true) ? "The teams it made are removed." : "The teams keep who they have.")
            .blank().hint("Shift-click", "cancel").build(), click -> {
            if (!click.shift()) {
               this.errorSound(click.player());
               return;
            }

            this.toggleSound(click.player(), false);
            draft.cancel(null);
         });
      }
   }

   @Override
   protected void onClose() {
      if (this.watching != null) {
         this.watching.unwatch(this.watcher);
         this.watching = null;
      }
   }

   /**
    * Admin set-up: choose the captains and who sits out, adjust the draft's main settings, start.
    *
    *   rows 1-3  online players (left-click: captain, right-click: sit out)
    *   row 4     pick time · team size · order · out of time · captain order
    *   corner    Start
    */
   public static final class Setup extends Menu {
      private static final int[] PLAYERS = POOL;
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
         return pagesFor(this.online().size(), PLAYERS.length);
      }

      private void setting(String key, Object value) {
         ConfigMenu.apply(this.plugin, this.viewer, "teams.draft." + key, value);
         this.refresh();
      }

      @Override
      protected void draw() {
         var config = this.plugin.getConfig();
         List<NamedTextColor> colours = Draft.colours(this.plugin);
         int minimum = Draft.minCaptains(this.plugin);
         this.captains.removeIf(id -> Bukkit.getPlayer(id) == null);
         int pool = 0;
         for (Player online : Bukkit.getOnlinePlayers()) {
            if (!this.captains.contains(online.getUniqueId()) && !this.sittingOut.contains(online.getUniqueId())) {
               pool++;
            }
         }

         this.show(4, Icon.of(Material.WHITE_BANNER).name("Team Draft", ACCENT)
            .text("Left-click players to make them captains (" + minimum + " to " + colours.size() + "). Right-click anyone who should sit out. Everyone else goes in the pool.")
            .blank().stat("Captains", String.valueOf(this.captains.size()), this.captains.size() >= minimum ? GOOD : BAD)
            .stat("In the pool", String.valueOf(pool), NamedTextColor.WHITE)
            .stat("Sitting out", String.valueOf(this.sittingOut.size()), NamedTextColor.WHITE)
            .blank().text("Everything else: Server Settings > teams > draft.", DIM).build());

         List<Player> shown = this.slice(this.online(), PLAYERS.length);
         for (int i = 0; i < shown.size(); i++) {
            Player player = shown.get(i);
            UUID id = player.getUniqueId();
            int captainAt = this.captains.indexOf(id);
            boolean out = this.sittingOut.contains(id);
            Icon head = this.head(id);
            if (captainAt >= 0) {
               NamedTextColor colour = colours.get(Math.min(captainAt, colours.size() - 1));
               head.name("♛ " + player.getName(), colour).line(Icon.plain("Captain #" + (captainAt + 1) + " (" + NamedTextColor.NAMES.key(colour).replace('_', ' ') + ")", colour)).glow(true);
            } else if (out) {
               head.name(player.getName(), DIM).line(Icon.plain("Sitting out", DIM));
            } else {
               head.name(player.getName(), NamedTextColor.WHITE).line(Icon.plain("In the pool", TEXT));
            }

            head.blank().hint("Left-click", captainAt >= 0 ? "remove as captain" : "make captain").hint("Right-click", out ? "put back in the pool" : "sit out");
            this.button(PLAYERS[i], head.build(), click -> {
               if (click.right()) {
                  if (!this.sittingOut.remove(id)) {
                     this.sittingOut.add(id);
                     this.captains.remove(id);
                  }
               } else if (!this.captains.remove(id)) {
                  if (this.captains.size() >= colours.size()) {
                     tell(click.player(), "At most " + colours.size() + " captains: one per colour in teams.draft.colours.", BAD);
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

         // ---- the draft's main settings, right here
         int pick = config.getInt("teams.draft.pick-seconds", 45);
         this.button(37, Icon.of(Material.CLOCK).name("Time per pick", NamedTextColor.WHITE).stat("Now", pick + "s", GOLD)
            .blank().hint("Left-click", "+5s").hint("Right-click", "-5s").build(), click -> {
            this.clickSound(click.player());
            this.setting("pick-seconds", Math.max(5, Math.min(300, pick + (click.right() ? -5 : 5))));
         });

         int size = config.getInt("teams.draft.max-team-size", 0);
         this.button(38, Icon.of(Material.PLAYER_HEAD).name("Max team size", NamedTextColor.WHITE).stat("Now", size <= 0 ? "no limit" : String.valueOf(size), GOLD)
            .blank().hint("Left-click", "+1").hint("Right-click", "-1 (0 = no limit)").build(), click -> {
            this.clickSound(click.player());
            this.setting("max-team-size", Math.max(0, Math.min(50, size + (click.right() ? -1 : 1))));
         });

         boolean snake = !"ROUND".equalsIgnoreCase(config.getString("teams.draft.order", "SNAKE"));
         this.button(39, Icon.of(snake ? Material.STRING : Material.REPEATER).name("Pick order", NamedTextColor.WHITE)
            .stat("Now", snake ? "Snake (1 2 3 3 2 1 ...)" : "Round (1 2 3 1 2 3 ...)", GOLD)
            .blank().text("Snake makes up for picking last in a round.", DIM).blank().click("switch").build(), click -> {
            this.clickSound(click.player());
            this.setting("order", snake ? "ROUND" : "SNAKE");
         });

         boolean auto = config.getBoolean("teams.draft.auto-pick", true);
         this.button(40, Icon.of(auto ? Material.LIME_DYE : Material.GRAY_DYE).name("Out of time", NamedTextColor.WHITE)
            .stat("Now", auto ? "a random player is picked" : "the turn is skipped", auto ? GOOD : BAD)
            .blank().click("switch").glow(auto).build(), click -> {
            this.toggleSound(click.player(), !auto);
            this.setting("auto-pick", !auto);
         });

         boolean random = "RANDOM".equalsIgnoreCase(config.getString("teams.draft.captain-order", "CHOSEN"));
         this.button(41, Icon.of(random ? Material.COMPARATOR : Material.LECTERN).name("Who picks first", NamedTextColor.WHITE)
            .stat("Now", random ? "random captain order" : "the order you chose them in", GOLD)
            .blank().click("switch").build(), click -> {
            this.clickSound(click.player());
            this.setting("captain-order", random ? "CHOSEN" : "RANDOM");
         });

         boolean bar = config.getBoolean("teams.draft.boss-bar", true);
         this.button(42, Icon.of(bar ? Material.DRAGON_BREATH : Material.GLASS_BOTTLE).name("Boss bar clock", NamedTextColor.WHITE)
            .stat("Now", onOff(bar), bar ? GOOD : BAD).blank().click("switch").glow(bar).build(), click -> {
            this.toggleSound(click.player(), !bar);
            this.setting("boss-bar", !bar);
         });

         this.button(43, Icon.of(Material.WRITABLE_BOOK).name("All draft settings", ACCENT).text("Names, colours, titles, sounds and the rest.").blank().click("open").build(), click -> {
            this.clickSound(click.player());
            new ConfigMenu(this.plugin, click.player(), this, "teams.draft").open();
         });

         boolean ready = this.captains.size() >= minimum;
         this.button(this.rows() * 9 - 1, Icon.of(ready ? Material.LIME_BANNER : Material.GRAY_BANNER).name("Start the draft", ready ? GOOD : TEXT)
            .text(ready ? "Teams are made for the captains, then picking begins." : "Choose at least " + minimum + " captains first.")
            .blank().click("start").glow(ready).build(), click -> {
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

            List<Player> players = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
               if (!this.captains.contains(online.getUniqueId()) && !this.sittingOut.contains(online.getUniqueId())) {
                  players.add(online);
               }
            }

            String error = Draft.start(this.plugin, this.plugin.getTeamManager(), captainPlayers, players);
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
