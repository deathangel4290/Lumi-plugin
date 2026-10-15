package com.legendaryweapons.guis;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.events.EventManager;
import com.legendaryweapons.events.SupplyDrops;
import java.util.Locale;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * Events for admins: each event's live status, left-click starts it, right-click stops it. The
 * Event Director, Most Wanted and supply drops have their own controls, described on each button.
 */
public final class AdminEventsMenu extends Menu {
   public AdminEventsMenu(LegendaryWeapons plugin, Player viewer, Menu parent) {
      super(plugin, viewer, parent);
   }

   @Override
   protected String title() {
      return "Events";
   }

   @Override
   protected int rows() {
      return 6;
   }

   /** Left-click runs `start`, right-click runs `stop` (either may be null). */
   private void event(int slot, Material icon, String name, boolean on, String status, String start, String stop, String extraHint, String extra) {
      Icon item = Icon.of(icon).name(name, on ? GOOD : NamedTextColor.WHITE)
         .line(Icon.plain(on ? "● Running" : "○ Not running", on ? GOOD : DIM))
         .blank().text(status, NamedTextColor.WHITE).blank();
      if (start != null) {
         item.hint("Left-click", "start");
      }

      if (stop != null) {
         item.hint("Right-click", "stop");
      }

      if (extra != null) {
         item.hint("Shift-click", extraHint);
      }

      this.button(slot, item.glow(on).build(), click -> {
         String command = click.shift() ? extra : (click.right() ? stop : start);
         if (command == null) {
            this.errorSound(click.player());
            return;
         }

         this.toggleSound(click.player(), !click.right());
         AdminMenu.run(this, click.player(), command);
      });
   }

   @Override
   protected void draw() {
      EventManager events = this.plugin.getEventManager();
      var director = events.director();
      boolean auto = director.enabled();
      Icon brain = Icon.of(auto ? Material.OBSERVER : Material.DISPENSER).name("Event Director", auto ? GOOD : BAD)
         .text("Starts events by itself in the windows set in config.yml (director.windows).").blank();
      for (String line : director.status()) {
         brain.text(line, NamedTextColor.WHITE);
      }

      this.button(4, brain.blank().hint("Left-click", "turn " + (auto ? "off" : "on")).hint("Right-click", "skip the planned event").glow(auto).build(), click -> {
         this.toggleSound(click.player(), click.right() || !auto);
         AdminMenu.run(this, click.player(), click.right() ? "lwevent director skip" : "lwevent director " + (auto ? "off" : "on"));
      });

      this.event(19, Material.GOLD_INGOT, "Bounty Hunt", events.bounty().isRunning(), events.bounty().status(),
         "lwevent bounty start", "lwevent bounty stop", null, null);
      this.event(20, Material.GOLDEN_HELMET, "King of the Hill", events.koth().isRunning(), events.koth().status(),
         "lwevent koth start", "lwevent koth stop", "put the hill where you stand", "lwevent koth setcenter");
      this.event(21, Material.NETHER_STAR, "Fallen Star", events.fallenStar().isRunning(), events.fallenStar().status(),
         "lwevent fallenstar start", "lwevent fallenstar stop", null, null);
      this.event(22, Material.REDSTONE, "Blood Moon", events.bloodMoon().isActive(), events.bloodMoon().status(),
         "lwevent bloodmoon start", "lwevent bloodmoon stop", null, null);

      boolean wanted = events.mostWanted().isActive();
      this.button(23, Icon.of(Material.TARGET).name("Most Wanted", wanted ? GOOD : NamedTextColor.WHITE)
         .line(Icon.plain(wanted ? "● Running" : "○ Not running", wanted ? GOOD : DIM))
         .blank().text(events.mostWanted().status(), NamedTextColor.WHITE).blank()
         .hint("Left-click", "choose the player").hint("Shift-click", "confirm and start").hint("Right-click", "clear")
         .glow(wanted).build(), click -> {
         if (click.shift()) {
            this.toggleSound(click.player(), true);
            AdminMenu.run(this, click.player(), "lwevent mostwanted confirm");
         } else if (click.right()) {
            this.toggleSound(click.player(), false);
            AdminMenu.run(this, click.player(), "lwevent mostwanted clear");
         } else {
            this.clickSound(click.player());
            new PlayerPicker(this.plugin, click.player(), this, "Choose the Most Wanted", "make them Most Wanted", (admin, target) -> {
               admin.performCommand("lwevent mostwanted set " + target.getName());
               this.open();
            }).open();
         }
      });

      int slot = 28;
      for (String tier : SupplyDrops.TIERS) {
         String label = tier.substring(0, 1).toUpperCase(Locale.ROOT) + tier.substring(1);
         this.button(slot++, Icon.of(Material.CHEST_MINECART).name(label + "-game Supply Drop", GOLD)
            .text("Waiting to be claimed: " + events.supplyDrops().active(), NamedTextColor.WHITE).blank()
            .hint("Left-click", "drop one in this world").hint("Right-click", "drop three").build(), click -> {
            this.toggleSound(click.player(), true);
            AdminMenu.run(this, click.player(), "lwevent supplydrop " + tier + (click.right() ? " 3" : " 1"));
         });
      }

      var bosses = this.plugin.getBossManager();
      this.button(32, Icon.of(Material.WITHER_SKELETON_SKULL).name("Bosses", GOLD)
         .text(bosses == null ? "Off." : bosses.status(), NamedTextColor.WHITE).blank().click("open").build(),
         AdminMenu.opens(this, p -> new AdminBossMenu(this.plugin, p, this)));
      this.button(33, Icon.of(Material.HONEYCOMB).name("Season", GOLD)
         .text(events.season().status(), NamedTextColor.WHITE).blank()
         .text("End it with /lwevent season reset confirm (pays the top three).", DIM).build(), null);
      this.button(34, Icon.of(Material.BEACON).name("Outposts", GOLD)
         .lines(events.outposts().list().subList(Math.min(1, events.outposts().list().size()), events.outposts().list().size()))
         .blank().text("Make one with /lwevent outpost create <name> <type> where it should be.", DIM).build(), null);

      this.button(this.rows() * 9 - 1, Icon.of(Material.TNT).name("Stop everything", BAD)
         .text("Stops the bounty hunt, King of the Hill, Fallen Star, Blood Moon and any boss, without prizes.")
         .blank().hint("Shift-click", "stop all").build(), click -> {
         if (!click.shift()) {
            this.errorSound(click.player());
            return;
         }

         this.toggleSound(click.player(), false);
         AdminMenu.run(this, click.player(), "lwevent stopall");
      });
   }
}
