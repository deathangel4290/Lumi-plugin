package com.lumiweapons.guis;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.teams.Draft;
import com.lumiweapons.teams.TeamManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Teams for admins: every team, the draft, and making a team by hand. A team opens its own page
 * to rename it, change its colour, add or remove players, pick a captain, or disband it.
 */
public final class AdminTeamsMenu extends Menu {
   public AdminTeamsMenu(LumiWeapons plugin, Player viewer, Menu parent) {
      super(plugin, viewer, parent);
   }

   @Override
   protected String title() {
      return "Teams";
   }

   @Override
   protected int rows() {
      return 6;
   }

   @Override
   protected boolean live() {
      return true;
   }

   private List<TeamManager.Team> teams() {
      return new ArrayList<>(this.plugin.getTeamManager().teams());
   }

   @Override
   protected int pages() {
      return pagesFor(this.teams().size(), this.inner().length);
   }

   @Override
   protected void draw() {
      TeamManager manager = this.plugin.getTeamManager();
      var draft = manager.draft();
      this.button(4, Icon.of(draft != null ? Material.LIME_BANNER : Material.WHITE_BANNER).name(draft != null ? "Draft running" : "Team Draft", draft != null ? GOOD : ACCENT)
         .text(draft != null ? draft.current().name + " is picking, " + draft.pool().size() + " left." : "Captains pick their teams from everyone online.")
         .blank().click(draft != null ? "watch the board" : "set one up").glow(draft != null).build(), click -> {
         this.clickSound(click.player());
         (draft != null ? new DraftMenu(this.plugin, click.player(), this) : new DraftMenu.Setup(this.plugin, click.player(), this)).open();
      });

      int[] slots = this.inner();
      List<TeamManager.Team> shown = this.slice(this.teams(), slots.length);
      for (int i = 0; i < shown.size(); i++) {
         TeamManager.Team team = shown.get(i);
         this.button(slots[i], DraftMenu.roster(team).blank().click("manage").build(), click -> {
            this.clickSound(click.player());
            new Edit(this.plugin, click.player(), this, team.id).open();
         });
      }

      if (shown.isEmpty()) {
         this.show(22, Icon.of(Material.PAPER).name("No teams", NamedTextColor.WHITE).text("Run a draft, or make a team by hand below.").build());
      }

      this.button(this.rows() * 9 - 1, Icon.of(Material.LIME_DYE).name("New team", GOOD).text("Type its name; it gets the next free colour.").blank().click("create").build(), click -> {
         this.clickSound(click.player());
         ChatPrompt.ask(click.player(), "Name for the new team:", null, typed -> {
            String clean = typed.replaceAll("[^A-Za-z0-9 '\\-]", "").trim();
            if (clean.isEmpty() || clean.length() > manager.maxNameLength()) {
               return "Use 1 to " + manager.maxNameLength() + " letters, numbers or spaces.";
            }

            if (manager.byName(clean) != null) {
               return "There's already a team called that.";
            }

            NamedTextColor colour = TeamManager.COLOURS.get(0);
            for (NamedTextColor option : Draft.colours(this.plugin)) {
               boolean used = false;
               for (TeamManager.Team existing : manager.teams()) {
                  used |= existing.colour == option;
               }

               if (!used) {
                  colour = option;
                  break;
               }
            }

            manager.create(clean, colour, null, null);
            return null;
         }, this::open);
      });

      if (!shown.isEmpty()) {
         this.button(this.rows() * 9 - 9 + 1, Icon.of(Material.TNT).name("Disband every team", BAD).text("Everyone goes back to having no team.")
            .blank().hint("Shift-right-click", "disband all").build(), click -> {
            if (!(click.shift() && click.right())) {
               this.errorSound(click.player());
               return;
            }

            manager.disbandAll();
            this.toggleSound(click.player(), false);
            this.open();
         });
      }
   }

   /** One team: name, colour, players, captain, disband. */
   static final class Edit extends Menu {
      private static final int[] PLAYERS = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
      private final String teamId;

      Edit(LumiWeapons plugin, Player viewer, Menu parent, String teamId) {
         super(plugin, viewer, parent);
         this.teamId = teamId;
      }

      private TeamManager.Team team() {
         for (TeamManager.Team team : this.plugin.getTeamManager().teams()) {
            if (team.id.equals(this.teamId)) {
               return team;
            }
         }

         return null;
      }

      @Override
      protected String title() {
         TeamManager.Team team = this.team();
         return team == null ? "Team" : team.name;
      }

      @Override
      protected int rows() {
         return 5;
      }

      @Override
      protected void draw() {
         TeamManager manager = this.plugin.getTeamManager();
         TeamManager.Team team = this.team();
         if (team == null) {
            this.show(22, Icon.of(Material.BARRIER).name("This team is gone", BAD).build());
            return;
         }

         this.show(4, DraftMenu.roster(team).build());
         this.button(10, Icon.of(Material.NAME_TAG).name("Rename", team.colour).stat("Now", team.name, NamedTextColor.WHITE).blank().click("type a new name").build(), click -> {
            this.clickSound(click.player());
            ChatPrompt.ask(click.player(), "New name for " + team.name + ":", team.name, typed -> {
               String clean = typed.replaceAll("[^A-Za-z0-9 '\\-]", "").trim();
               if (clean.isEmpty() || clean.length() > 32) {
                  return "Use 1 to 32 letters, numbers or spaces.";
               }

               manager.rename(team, clean);
               return null;
            }, this::open);
         });

         List<NamedTextColor> colours = new ArrayList<>(NamedTextColor.NAMES.values());
         this.button(12, Icon.of(DraftMenu.wool(team.colour)).name("Colour", team.colour).stat("Now", NamedTextColor.NAMES.key(team.colour).replace('_', ' '), team.colour)
            .blank().hint("Left-click", "next colour").hint("Right-click", "previous").build(), click -> {
            int at = colours.indexOf(team.colour);
            int next = Math.floorMod(at + (click.right() ? -1 : 1), colours.size());
            manager.recolour(team, colours.get(next));
            this.clickSound(click.player());
            this.refresh();
         });

         this.button(14, Icon.of(Material.PLAYER_HEAD).name("Add a player", GOOD).text("Anyone online; they move from their old team.").blank().click("choose").build(), click -> {
            this.clickSound(click.player());
            new PlayerPicker(this.plugin, click.player(), this, "Add to " + team.name, "put them on " + team.name, (admin, target) -> {
               manager.add(team, target.getUniqueId(), target.getName());
               tell(target, "You're now on " + team.name + ".", team.colour);
               this.open();
            }).open();
         });

         this.button(16, Icon.of(Material.TNT).name("Disband", BAD).text("Everyone on it goes back to having no team.").blank().hint("Shift-right-click", "disband").build(), click -> {
            if (!(click.shift() && click.right())) {
               this.errorSound(click.player());
               return;
            }

            manager.disband(team);
            this.toggleSound(click.player(), false);
            this.parent.open();
         });

         List<UUID> members = new ArrayList<>(team.members);
         for (int i = 0; i < members.size() && i < PLAYERS.length; i++) {
            UUID id = members.get(i);
            boolean captain = id.equals(team.captain);
            this.button(PLAYERS[i], this.head(id).name((captain ? "♛ " : "") + team.nameOf(id), team.colour)
               .line(Icon.plain(captain ? "Captain" : "Member", captain ? GOLD : TEXT))
               .line(Icon.plain(Bukkit.getPlayer(id) != null ? "● Online" : "○ Offline", Bukkit.getPlayer(id) != null ? GOOD : DIM))
               .blank().hint("Left-click", captain ? "(already captain)" : "make captain").hint("Shift-right-click", "remove").build(), click -> {
               if (click.shift() && click.right()) {
                  manager.remove(id);
                  this.toggleSound(click.player(), false);
               } else if (!captain) {
                  manager.setCaptain(team, id);
                  this.toggleSound(click.player(), true);
               } else {
                  this.errorSound(click.player());
                  return;
               }

               this.refresh();
            });
         }
      }
   }
}
