package com.lumiweapons.guis;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.teams.TeamManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Your team (/team): its players, team chat, and the draft board. What captains and players may do
 * here follows teams.captains.* and teams.allow-leave.
 *
 *   header   the team
 *   row 1    [Team chat] ...................... [Rename] (captain)
 *   rows 2-3 the players (captains with can-kick: shift-right-click to remove)
 *   bottom   [Leave] (when allowed) ........... [Draft board]
 */
public final class TeamMenu extends Menu {
   private static final int[] PLAYERS = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};

   public TeamMenu(LumiWeapons plugin, Player viewer, Menu parent) {
      super(plugin, viewer, parent);
   }

   @Override
   protected String title() {
      return "Your Team";
   }

   @Override
   protected int rows() {
      return 5;
   }

   @Override
   protected boolean live() {
      return true;                                        // online dots and distances
   }

   @Override
   protected void draw() {
      TeamManager teams = this.plugin.getTeamManager();
      TeamManager.Team team = teams.teamOf(this.viewer);
      boolean drafting = teams.draft() != null;
      this.button(this.rows() * 9 - 1, Icon.of(drafting ? Material.LIME_BANNER : Material.WHITE_BANNER).name("Draft board", drafting ? GOOD : NamedTextColor.WHITE)
         .text(drafting ? "A draft is running right now." : "Every team and who is on it.").blank().click("open").glow(drafting).build(), click -> {
         this.clickSound(click.player());
         new DraftMenu(this.plugin, click.player(), this).open();
      });

      if (team == null) {
         this.show(22, Icon.of(Material.WHITE_BANNER).name("No team yet", NamedTextColor.WHITE)
            .text("Teams are made in a Team Draft. If you joined late, ask an admin to add you to one.").build());
         return;
      }

      boolean captain = this.viewer.getUniqueId().equals(team.captain);
      this.show(4, DraftMenu.roster(team).blank()
         .text("Teammates are allies: your abilities skip them, and you share outposts.", DIM)
         .text(this.plugin.getConfig().getBoolean("teams.allies.friendly-fire", false) ? "Friendly fire is ON: normal hits still hurt." : "You can't hurt each other.", DIM).build());

      List<UUID> members = new ArrayList<>(team.members);
      boolean kick = captain && teams.captainsCanKick();
      for (int i = 0; i < members.size() && i < PLAYERS.length; i++) {
         UUID id = members.get(i);
         Player online = Bukkit.getPlayer(id);
         boolean isCaptain = id.equals(team.captain);
         Icon head = this.head(id).name((isCaptain ? "♛ " : "") + team.nameOf(id), team.colour)
            .line(Icon.plain(isCaptain ? "Captain" : "Member", isCaptain ? GOLD : TEXT))
            .line(Icon.plain(online != null ? "● Online" : "○ Offline", online != null ? GOOD : DIM));
         if (online != null && online != this.viewer) {
            if (online.getWorld().equals(this.viewer.getWorld())) {
               head.stat("Distance", (int)online.getLocation().distance(this.viewer.getLocation()) + " blocks", NamedTextColor.WHITE);
            } else {
               head.stat("World", com.lumiweapons.rituals.Ritual.dimensionName(online.getWorld()), NamedTextColor.WHITE);
            }
         }

         if (kick && !isCaptain) {
            head.blank().hint("Shift-right-click", "remove from the team");
         }

         this.button(PLAYERS[i], head.build(), !kick || isCaptain ? null : click -> {
            if (!(click.shift() && click.right())) {
               this.errorSound(click.player());
               return;
            }

            teams.remove(id);
            tell(click.player(), team.nameOf(id) + " is no longer on " + team.name + ".", TEXT);
            Player removed = Bukkit.getPlayer(id);
            if (removed != null) {
               tell(removed, "You were removed from " + team.name + ".", BAD);
            }

            this.toggleSound(click.player(), false);
            this.refresh();
         });
      }

      if (teams.chatEnabled()) {
         boolean chat = teams.chatOn(this.viewer);
         this.button(10, Icon.of(chat ? Material.WRITABLE_BOOK : Material.BOOK).name("Team chat", chat ? GOOD : NamedTextColor.WHITE)
            .stat("Your chat goes to", chat ? "your team" : "everyone", chat ? GOOD : NamedTextColor.WHITE)
            .blank().text("/tc <message> always talks to your team.", DIM).blank().click("switch").glow(chat).build(), click -> {
            this.toggleSound(click.player(), teams.toggleChat(click.player()));
            this.refresh();
         });
      }

      if (captain && teams.captainsCanRename()) {
         int max = teams.maxNameLength();
         this.button(16, Icon.of(Material.NAME_TAG).name("Rename the team", team.colour).text("You're the captain.").blank().click("type a new name").build(), click -> {
            this.clickSound(click.player());
            ChatPrompt.ask(click.player(), "New name for " + team.name + ":", team.name, typed -> {
               String clean = typed.replaceAll("[^A-Za-z0-9 '\\-]", "").trim();
               if (clean.isEmpty() || clean.length() > max) {
                  return "Use 1 to " + max + " letters, numbers or spaces.";
               }

               if (teams.byName(clean) != null && teams.byName(clean) != team) {
                  return "Another team already has that name.";
               }

               teams.rename(team, clean);
               return null;
            }, this::open);
         });
      }

      if (teams.allowLeave()) {
         this.button(this.rows() * 9 - 9 + 1, Icon.of(Material.OAK_DOOR).name("Leave the team", BAD)
            .text(captain ? "Someone else becomes captain." : "You can be added again by an admin.")
            .blank().hint("Shift-click", "leave").build(), click -> {
            if (!click.shift()) {
               this.errorSound(click.player());
               return;
            }

            teams.remove(click.player().getUniqueId());
            tell(click.player(), "You left " + team.name + ".", TEXT);
            this.toggleSound(click.player(), false);
            this.open();
         });
      }
   }
}
