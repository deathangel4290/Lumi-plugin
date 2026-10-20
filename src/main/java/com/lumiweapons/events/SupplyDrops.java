package com.lumiweapons.events;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.managers.ParticleManager;
import com.lumiweapons.rituals.Ritual;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Supply drops: /lwevent supplydrop <early|mid|late> [count]. Each drop picks a random safe spot
 * inside the world border, falls from the sky as a crate, lands as a chest filled from that
 * tier's loot table and shows a beam until someone opens it (or it times out).
 */
public class SupplyDrops {
   public static final List<String> TIERS = List.of("early", "mid", "late");
   static final String CRATE_TAG = "lw-supply-crate";
   private final LumiWeapons plugin;
   private final EventManager manager;
   private final List<Drop> drops = new ArrayList<>();

   private static final class Drop {
      final Location location;
      final String tier;
      final long expiresAt;
      boolean opened;

      Drop(Location location, String tier, long expiresAt) {
         this.location = location;
         this.tier = tier;
         this.expiresAt = expiresAt;
      }
   }

   public SupplyDrops(LumiWeapons plugin, EventManager manager) {
      this.plugin = plugin;
      this.manager = manager;
      (new BukkitRunnable() {
         public void run() {
            SupplyDrops.this.beams();
         }
      }).runTaskTimer(plugin, 20L, 10L);
      plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
         for (World world : Bukkit.getWorlds()) {
            for (org.bukkit.entity.Entity entity : world.getEntities()) {
               if (entity instanceof BlockDisplay && entity.getScoreboardTags().contains(CRATE_TAG)) {
                  entity.remove();
               }
            }
         }
      }, 40L);
   }

   private ConfigurationSection section() {
      return this.plugin.getConfig().getConfigurationSection("events.supply-drops");
   }

   public String spawn(String tier, int count, World world) {
      String key = tier.toLowerCase(Locale.ROOT);
      ConfigurationSection tiers = this.section() == null ? null : this.section().getConfigurationSection("tiers");
      if (tiers == null || !tiers.isConfigurationSection(key)) {
         return "Unknown tier '" + tier + "'. Use early, mid or late.";
      } else {
         int spawned = 0;

         for (int i = 0; i < Math.max(1, Math.min(10, count)); i++) {
            Location spot = this.findSpot(world);
            if (spot != null) {
               this.drop(spot, key);
               spawned++;
            }
         }

         return spawned == 0 ? "Could not find a safe spot for a supply drop in " + world.getName() + "." : null;
      }
   }

   private Location findSpot(World world) {
      return SafeSpots.find(world, this.section() == null ? 50.0 : this.section().getDouble("border-margin", 50.0),
         this.section() == null ? 2000.0 : this.section().getDouble("max-radius", 2000.0));
   }

   private void drop(final Location landing, final String tier) {
      final World world = landing.getWorld();
      final double height = 30.0;
      final int fallTicks = Math.max(10, (int)(20.0 * (this.section() == null ? 3.0 : this.section().getDouble("fall-seconds", 3.0))));
      final int cx = landing.getBlockX() >> 4;
      final int cz = landing.getBlockZ() >> 4;
      world.addPluginChunkTicket(cx, cz, this.plugin);             // nothing unloads mid-fall
      final BlockDisplay crate = world.spawn(landing.clone().add(0.0, height, 0.0), BlockDisplay.class, display -> {
         display.setBlock(Material.CHEST.createBlockData());
         display.setPersistent(false);
         display.setTeleportDuration(3);
         display.addScoreboardTag(CRATE_TAG);
      });
      String label = tier.substring(0, 1).toUpperCase(Locale.ROOT) + tier.substring(1) + "-game";
      Bukkit.broadcast(Component.text("📦 A " + label + " supply drop is falling in the " + Ritual.dimensionName(world) + " at " + landing.getBlockX() + ", " + landing.getBlockY() + ", " + landing.getBlockZ() + "!", NamedTextColor.AQUA));
      (new BukkitRunnable() {
         int ticks = 0;

         public void run() {
            this.ticks += 3;
            if (this.ticks >= fallTicks || !crate.isValid()) {
               this.cancel();
               // Always remove it: its last frame hangs 1.5 blocks over the chest, and an isValid()
               // check here used to leave it floating there whenever the chunk had no players near.
               crate.remove();
               SupplyDrops.this.land(landing, tier);
               SupplyDrops.sweepCrates(landing, 40.0);
               world.removePluginChunkTicket(cx, cz, SupplyDrops.this.plugin);
            } else {
               double y = height * (1.0 - (double)this.ticks / fallTicks);
               crate.teleport(landing.clone().add(0.0, y, 0.0));
               ParticleManager.spawn(Particle.CLOUD, landing.clone().add(0.5, y + 1.0, 0.5), 2, 0.2, 0.2, 0.2, 0.01);
            }
         }
      }).runTaskTimer(this.plugin, 3L, 3L);
   }

   /** Remove any falling-crate display left near a landing (from this or any earlier drop). */
   static void sweepCrates(Location around, double radius) {
      for (org.bukkit.entity.Entity entity : around.getWorld().getNearbyEntities(around, radius, 64.0, radius)) {
         if (entity instanceof BlockDisplay && entity.getScoreboardTags().contains(CRATE_TAG)) {
            entity.remove();
         }
      }
   }

   private void land(Location landing, String tier) {
      Block block = landing.getBlock();
      // settle onto solid ground in case the spot changed during the fall (a block broken, a plant)
      for (int i = 0; i < 12 && block.getY() > block.getWorld().getMinHeight() + 1 && block.getRelative(0, -1, 0).isPassable(); i++) {
         block = block.getRelative(0, -1, 0);
      }

      landing = block.getLocation();
      block.setType(Material.CHEST);
      if (block.getState() instanceof Chest chest) {
         ConfigurationSection table = this.section().getConfigurationSection("tiers." + tier);
         Loot.scatter(chest.getBlockInventory(), this.manager.loot().roll(table), -1);
      }

      ParticleManager.spawn(Particle.EXPLOSION, landing.clone().add(0.5, 0.5, 0.5), 1, 0.0, 0.0, 0.0, 0.0);
      ParticleManager.spawn(Particle.CLOUD, landing.clone().add(0.5, 0.2, 0.5), 20, 1.0, 0.1, 1.0, 0.05);
      this.plugin.getSoundManager().world(landing, Sound.BLOCK_ANVIL_LAND, 1.0F, 0.6F);
      long beamMillis = (long)((this.section() == null ? 10.0 : this.section().getDouble("beam-minutes", 10.0)) * 60000.0);
      this.drops.add(new Drop(block.getLocation(), tier, System.currentTimeMillis() + beamMillis));
   }

   private void beams() {
      long now = System.currentTimeMillis();
      Iterator<Drop> it = this.drops.iterator();

      while (it.hasNext()) {
         Drop drop = it.next();
         if (drop.opened || drop.expiresAt <= now || drop.location.getBlock().getType() != Material.CHEST) {
            it.remove();
         } else {
            Location base = drop.location.clone().add(0.5, 1.0, 0.5);
            if (drop.location.getWorld().isChunkLoaded(drop.location.getBlockX() >> 4, drop.location.getBlockZ() >> 4) && now % 5000L < 500L) {
               SupplyDrops.sweepCrates(drop.location, 24.0);     // belt and braces against a stuck crate
            }

            for (int i = 0; i < 4; i++) {
               // a landmark, like the altars: not thinned by combat density, seen from 96 blocks
               ParticleManager.showcase(Particle.END_ROD, base.clone().add(0.0, Math.random() * 40.0, 0.0), 1, 0.05, 0.0, 0.05, 0.0, null);
            }
         }
      }
   }

   /** Called when any container opens; the first player to open a drop claims it. */
   public void onOpen(Player player, Location location) {
      if (location != null) {
         Location block = location.getBlock().getLocation();

         for (Drop drop : this.drops) {
            if (!drop.opened && drop.location.equals(block)) {
               drop.opened = true;
               Bukkit.broadcast(Component.text("📦 " + this.manager.publicName(player) + " claimed the supply drop at " + block.getBlockX() + ", " + block.getBlockZ() + ".", NamedTextColor.AQUA));
               return;
            }
         }
      }
   }

   public int active() {
      return this.drops.size();
   }
}
