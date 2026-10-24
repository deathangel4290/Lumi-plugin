package com.lumiweapons.guis;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.events.EventManager;
import com.lumiweapons.rituals.Ritual;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;

/**
 * What is happening on the server: the events running right now (row 1), the next scheduled one
 * (header), the outposts, the Token Traders and the Ancient Altars (row 3). Only what everyone may know (no disguised names, no armed
 * Blood Moon before it rises).
 */
public final class WorldMenu extends Menu {
   public WorldMenu(LumiWeapons plugin, Player viewer, Menu parent) {
      super(plugin, viewer, parent);
   }

   @Override
   protected String title() {
      return "World & Events";
   }

   @Override
   protected int rows() {
      return 5;
   }

   @Override
   protected boolean live() {
      return true;
   }

   @Override
   protected void draw() {
      EventManager events = this.plugin.getEventManager();
      Icon next = Icon.of(Material.CLOCK).name("Next event", GOLD);
      if (events.director().enabled()) {
         List<String> status = events.director().status();
         next.text(status.size() > 1 ? status.get(1).replace("Next: ", "") : "Nothing planned right now.", NamedTextColor.WHITE);
         next.blank().text("Events are announced 10, 5 and 1 minute before they start.", DIM);
      } else {
         next.text("Events are started by the admins.", NamedTextColor.WHITE);
      }

      this.show(4, next.build());

      this.tile(10, Material.GOLD_INGOT, "Bounty Hunt", events.bounty().isRunning(), events.bounty().status(),
         "Everyone gets a target. Hunt yours, and stay alive.");
      this.tile(11, Material.GOLDEN_HELMET, "King of the Hill", events.koth().isRunning(), events.koth().status(),
         "Hold the hill for points. Most points wins.");
      this.tile(12, Material.NETHER_STAR, "Fallen Star", events.fallenStar().isRunning(), events.fallenStar().status(),
         "A star falls. Beat its guardians and claim the Star Core.");
      this.tile(13, Material.REDSTONE, "Blood Moon", events.bloodMoon().isActive(), events.bloodMoon().isActive() ? events.bloodMoon().status() : "Not up.",
         "A night of stronger mobs and blood bounties.");
      var bosses = this.plugin.getBossManager();
      boolean boss = bosses != null && bosses.isRunning();
      this.tile(14, Material.WITHER_SKELETON_SKULL, "World Boss", boss, boss ? bosses.status() : "No boss is awake.",
         "Four bosses, each with its own arena. Damage dealt counts for the rewards.");
      this.tile(15, Material.TARGET, "Most Wanted", events.mostWanted().isActive(), events.mostWanted().isActive() ? "The hunt is on." : "Nobody is wanted.",
         "One player carries a price on their head.");
      int drops = events.supplyDrops().active();
      this.tile(16, Material.CHEST_MINECART, "Supply Drops", drops > 0, drops > 0 ? drops + " waiting to be claimed." : "None waiting.",
         "Crates fall from the sky with loot and fragments.");

      List<Component> outposts = events.outposts().list();
      this.show(29, Icon.of(Material.BEACON).name("Outposts", GOLD)
         .text("Capture one and it pays out while you hold it.")
         .blank().lines(outposts.subList(Math.min(1, outposts.size()), outposts.size())).build());

      List<Location> traders = events.tokenShop().homes();
      Icon shop = Icon.of(Material.EMERALD).name("Token Traders", GOLD).text("Trade Luminara Tokens for gear. Right-click a trader.").blank();
      if (traders.isEmpty()) {
         shop.text("None placed yet.", NamedTextColor.WHITE);
      }

      for (Location home : traders) {
         shop.line(Icon.plain("  " + Ritual.dimensionName(home.getWorld()) + " " + home.getBlockX() + ", " + home.getBlockY() + ", " + home.getBlockZ(), NamedTextColor.YELLOW));
      }

      this.show(31, shop.build());

      var rituals = this.plugin.getRitualManager();
      if (!rituals.ancientAltars().isEmpty()) {
         int found = rituals.discoveredCount();
         int total = rituals.ancientAltars().size();
         this.show(33, Icon.of(com.lumiweapons.rituals.RitualBlock.getAncientAltarItem()).clearLore().name("Ancient Altars", ACCENT)
            .stat("Found", found + " of " + total, found >= total ? GOOD : NamedTextColor.WHITE)
            .blank().text("Hidden around the map. Forge a legendary at one, if you can find it.", DIM).build());
      } else {
         this.show(33, Icon.of(Material.ENCHANTING_TABLE).name("Rituals", ACCENT)
            .stat("Running now", String.valueOf(rituals.getActiveRituals().size()), NamedTextColor.WHITE)
            .blank().text(rituals.clock().windowsEnabled() ? "Rituals can be started " + rituals.clock().describe() + "." : "Craft an altar and forge a legendary.", DIM).build());
      }
   }

   private void tile(int slot, Material icon, String name, boolean on, String status, String about) {
      this.show(slot, Icon.of(icon).name(name, on ? GOOD : NamedTextColor.WHITE)
         .line(Icon.plain(on ? "● Happening now" : "○ Not running", on ? GOOD : DIM))
         .blank().text(status, NamedTextColor.WHITE)
         .blank().text(about, DIM).glow(on).build());
   }
}
