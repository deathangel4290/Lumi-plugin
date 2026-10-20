package com.legendaryweapons.guis;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.teams.TeamManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/** Your team (/team): its players, team chat, and the draft board. Captains can rename it. */
public final class TeamMenu extends Menu {
   public TeamMenu(LegendaryWeapons plugin, Player viewer, Menu parent) {
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

      this.show(4, DraftMenu.roster(team).blank()
         .text("Teammates are allies: your abilities skip them, you can't hurt each other, and you share outposts.", DIM).build());

      List<UUID> members = new ArrayList<>(team.members);
      int[] slots = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
      for (int i = 0; i < members.size() && i < slots.length; i++) {
         UUID id = members.get(i);
         Player online = Bukkit.getPlayer(id);
         Icon head = Icon.head(id).name(team.nameOf(id) + (id.equals(team.captain) ? " ★" : ""), team.colour)
            .line(Icon.plain(online != null ? "● Online" : "○ Offline", online != null ? GOOD : DIM));
         if (online != null && online != this.viewer && online.getWorld().equals(this.viewer.getWorld())) {
            head.stat("Distance", (int)online.getLocation().distance(this.viewer.getLocation()) + " blocks", NamedTextColor.WHITE);
         }

         this.show(slots[i], head.build());
      }

      boolean chat = teams.chatOn(this.viewer);
      this.button(10, Icon.of(chat ? Material.WRITABLE_BOOK : Material.BOOK).name("Team chat", chat ? GOOD : NamedTextColor.WHITE)
         .stat("Your chat goes to", chat ? "your team" : "everyone", chat ? GOOD : NamedTextColor.WHITE)
         .blank().text("/tc <message> always talks to your team.", DIM).blank().click("switch").glow(chat).build(), click -> {
         this.toggleSound(click.player(), teams.toggleChat(click.player()));
         this.refresh();
      });

      if (this.viewer.getUniqueId().equals(team.captain)) {
         this.button(16, Icon.of(Material.NAME_TAG).name("Rename the team", team.colour).text("You're the captain.").blank().click("type a new name").build(), click -> {
            this.clickSound(click.player());
            ChatPrompt.ask(click.player(), "New name for " + team.name + ":", team.name, typed -> {
               String clean = typed.replaceAll("[^A-Za-z0-9 '\\-]", "").trim();
               if (clean.isEmpty() || clean.length() > 20) {
                  return "Use 1 to 20 letters, numbers or spaces.";
               }

               teams.rename(team, clean);
               return null;
            }, this::open);
         });
      }
   }
}
