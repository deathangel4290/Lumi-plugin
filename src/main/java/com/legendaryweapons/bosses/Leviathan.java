package com.legendaryweapons.bosses;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.managers.ParticleManager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Drowned;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Guardian;
import org.bukkit.entity.Player;
import org.bukkit.entity.Trident;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

/**
 * The Leviathan -- the movement boss, fought in open water.
 *
 *  - It spends most of the fight below. Every so often it SURFACES: it glows and takes extra damage
 *    for a few seconds. Learn the rhythm.
 *  - It drags people under (bubbles mark them first), sends water bolts at anyone on the surface,
 *    and opens whirlpools that pull swimmers in.
 *  - Boats do not survive the arena.
 *  - It calls drowned and guardians up from below (phase 2+), and in phase 3 floods the arena's
 *    platforms with standing water (temporary, it never flows; healed after the fight).
 *  - The Lost Trident of Atlantis finally has its purpose: it hits the Leviathan much harder.
 */
final class Leviathan extends Boss {
   private static final TextColor SEA = TextColor.color(50, 150, 210);
   private final List<Object[]> whirlpools = new ArrayList<>();      // centre, endTime
   private boolean surfaced;
   private long surfaceUntil;
   private long nextSurface;
   private long nextDrag;
   private long nextBolt;
   private long nextWhirl;
   private long nextSpawn;
   private long nextFlood;

   Leviathan(LegendaryWeapons plugin, BossManager manager, BossManager.Arena arena, TempBlocks terrain) {
      super(plugin, manager, "leviathan", arena, terrain);
   }

   @Override
   public String displayName() {
      return "Leviathan";
   }

   @Override
   protected TextColor colour() {
      return SEA;
   }

   @Override
   protected BossBar.Color barColour() {
      return BossBar.Color.BLUE;
   }

   @Override
   protected EntityType bodyType() {
      return EntityType.ELDER_GUARDIAN;
   }

   @Override
   protected double[] defaultPhases() {
      return new double[]{0.6, 0.3};
   }

   @Override
   protected void onSpawned() {
      long now = System.currentTimeMillis();
      this.nextSurface = now + 12000L;
      this.nextDrag = now + 9000L;
      this.nextBolt = now + 5000L;
      this.nextWhirl = now + 20000L;
      this.say("The water churns. Watch for it to surface - that is when it can really be hurt.");
   }

   /** It lives in the water under the arena centre (the centre itself may be a platform). */
   @Override
   protected Location spawnPoint() {
      Location c = this.arena.centre();
      for (int r = 0; r <= (int)this.arena.radius(); r += 2) {
         for (int i = 0; i < Math.max(1, r * 3); i++) {
            double a = Math.PI * 2.0 * i / Math.max(1, r * 3);
            Location at = c.clone().add(Math.cos(a) * r, 0.0, Math.sin(a) * r);
            int top = this.waterTop(at);
            if (top != Integer.MIN_VALUE && at.getWorld().getBlockAt(at.getBlockX(), top - 3, at.getBlockZ()).getType() == Material.WATER) {
               return new Location(at.getWorld(), at.getBlockX() + 0.5, top - 3.0, at.getBlockZ() + 0.5);
            }
         }
      }

      return super.spawnPoint();
   }

   /** Where the Heart drops: the arena centre's standing ground, never the sea floor. */
   @Override
   protected Location heartDrop() {
      return this.arena.surface(this.arena.centre());
   }

   private int waterTop(Location at) {
      int top = Integer.MIN_VALUE;
      for (int y = this.arena.centre().getBlockY() - 20; y <= this.arena.centre().getBlockY() + 6; y++) {
         if (at.getWorld().getBlockAt(at.getBlockX(), y, at.getBlockZ()).getType() == Material.WATER) {
            top = y;
         }
      }

      return top;
   }

   private boolean nearSurface(Player player) {
      Location at = player.getLocation();
      int top = this.waterTop(at);
      return top == Integer.MIN_VALUE || at.getY() >= top - 1.5;
   }

   // ------------------------------------------------------------------ damage rules

   @Override
   protected double incoming(Player attacker, EntityDamageEvent event, double damage) {
      double result = damage;
      if (this.surfaced) {
         result *= this.num("surfaced-damage-multiplier", 1.6);
      }

      if (this.isAtlantis(attacker, event)) {
         result *= this.num("lost-trident-multiplier", 1.75);
         ParticleManager.showcase(Particle.NAUTILUS, this.body.getLocation().add(0.0, 1.0, 0.0), 12, 0.6, 0.6, 0.6, 0.4, null);
      }

      return result;
   }

   private boolean isAtlantis(Player attacker, EntityDamageEvent event) {
      if (event instanceof EntityDamageByEntityEvent byEntity && byEntity.getDamager() instanceof Trident trident) {
         return trident.getPersistentDataContainer().has(new NamespacedKey(this.plugin, "lost-trident"), PersistentDataType.BYTE);
      }

      if (attacker == null) {
         return false;
      }

      ItemStack held = attacker.getInventory().getItemInMainHand();
      return this.plugin.getWeaponManager().isLegendaryWeapon(held) && "lost-trident-of-atlantis".equals(this.plugin.getWeaponManager().getWeaponKey(held));
   }

   // ------------------------------------------------------------------ main loop

   @Override
   protected void onTick() {
      long now = System.currentTimeMillis();
      if (this.ticks % 20L == 0L) {
         this.sinkBoats();
      }

      if (this.surfaced) {
         if (this.ticks % 4L == 0L) {
            ParticleManager.showcase(Particle.SPLASH, this.body.getLocation().add(0.0, 1.0, 0.0), 12, 1.5, 0.5, 1.5, 0.1, null);
         }

         if (now >= this.surfaceUntil) {
            this.dive();
         }
      } else if (now >= this.nextSurface) {
         this.surface();
      }

      if (now >= this.nextDrag) {
         this.nextDrag = now + (long)(this.num("drag-interval", 14.0) * 1000.0);
         this.dragUnder();
      }

      if (now >= this.nextBolt) {
         this.nextBolt = now + (long)(this.num("bolt-interval", this.phase >= 2 ? 4.0 : 6.0) * 1000.0);
         this.waterBolt();
      }

      if (this.phase >= 1 && now >= this.nextWhirl) {
         this.nextWhirl = now + (long)(this.num("whirlpool-interval", 20.0) * 1000.0);
         this.openWhirlpools();
      }

      if (this.phase >= 1 && now >= this.nextSpawn) {
         this.nextSpawn = now + (long)(this.num("summon-interval", 25.0) * 1000.0);
         this.summon();
      }

      if (this.phase >= 2 && now >= this.nextFlood) {
         this.nextFlood = now + (long)(this.num("flood-interval", 30.0) * 1000.0);
         this.flood();
      }

      this.tickWhirlpools(now);
   }

   private void surface() {
      Location top = this.body.getLocation();
      int water = this.waterTop(top);
      if (water != Integer.MIN_VALUE) {
         top.setY(water - 0.6);
         this.allowTeleport = true;
         try {
            this.body.teleport(top);
         } finally {
            this.allowTeleport = false;
         }
      }

      this.surfaced = true;
      this.surfaceUntil = System.currentTimeMillis() + (long)(this.num("surface-seconds", 7.0) * 1000.0);
      this.body.setGlowing(true);
      this.body.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, (int)(this.num("surface-seconds", 7.0) * 20.0), 3, true, false));
      this.model.setState("surfaced");
      this.plugin.getSoundManager().world(this.body.getLocation(), Sound.ENTITY_ELDER_GUARDIAN_CURSE, 1.5F, 0.5F);
      ParticleManager.showcase(Particle.SPLASH, this.body.getLocation().add(0.0, 1.5, 0.0), 80, 2.5, 1.0, 2.5, 0.3, null);
      this.say("☠ The Leviathan SURFACES - strike now!");
   }

   private void dive() {
      this.surfaced = false;
      this.body.setGlowing(false);
      this.nextSurface = System.currentTimeMillis() + (long)(this.num(this.phase >= 2 ? "surface-interval-enraged" : "surface-interval", this.phase >= 2 ? 12.0 : 18.0) * 1000.0);
      this.model.setState("idle");
      Location down = this.body.getLocation().subtract(0.0, 5.0, 0.0);
      if (down.getBlock().getType() == Material.WATER) {
         this.allowTeleport = true;
         try {
            this.body.teleport(down);
         } finally {
            this.allowTeleport = false;
         }
      }

      this.plugin.getSoundManager().world(this.body.getLocation(), Sound.ENTITY_GENERIC_SPLASH, 1.5F, 0.5F);
   }

   private void sinkBoats() {
      for (Entity entity : this.arena.centre().getWorld().getNearbyEntities(this.arena.centre(), this.arena.radius(), 30.0, this.arena.radius())) {
         if (entity instanceof Boat boat) {
            Location at = boat.getLocation();
            boat.eject();
            if (boat instanceof InventoryHolder holder) {
               for (ItemStack item : holder.getInventory().getContents()) {
                  if (item != null && !item.getType().isAir()) {
                     at.getWorld().dropItemNaturally(at, item);
                  }
               }

               holder.getInventory().clear();
            }

            boat.remove();
            ParticleManager.showcase(Particle.SPLASH, at, 40, 1.0, 0.5, 1.0, 0.2, null);
            ParticleManager.showcase(Particle.BLOCK, at, 20, 0.6, 0.3, 0.6, 0.0, Material.OAK_PLANKS.createBlockData());
            this.plugin.getSoundManager().world(at, Sound.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 1.0F, 0.7F);
         }
      }
   }

   /** Bubbles mark the victim for a second, then it pulls them down and empties their lungs. */
   private void dragUnder() {
      List<Player> candidates = new ArrayList<>();
      for (Player player : this.fighters()) {
         if (player.getLocation().getBlock().getType() == Material.WATER || player.getEyeLocation().getBlock().getType() == Material.WATER) {
            candidates.add(player);
         }
      }

      if (candidates.isEmpty()) {
         return;
      }

      final Player victim = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
      for (int t = 0; t < 20; t += 4) {
         this.later(t, () -> ParticleManager.showcase(Particle.BUBBLE_COLUMN_UP, victim.getLocation(), 15, 0.4, 0.2, 0.4, 0.05, null));
      }

      this.plugin.getSoundManager().world(victim.getLocation(), Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_INSIDE, 1.2F, 0.8F);
      this.later(20L, () -> {
         if (victim.isOnline() && !victim.isDead() && this.arena.contains(victim.getLocation(), 4.0)) {
            victim.setVelocity(new Vector(0.0, -this.num("drag-strength", 1.2), 0.0));
            victim.setRemainingAir(Math.max(0, victim.getRemainingAir() - 150));
            victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 50, 2, true, false));
            this.trueDamage(victim, this.num("drag-damage", 4.0));
            victim.sendMessage(Component.text("☠ The Leviathan drags you under!", SEA));
         }
      });
   }

   private void waterBolt() {
      List<Player> targets = new ArrayList<>();
      for (Player player : this.fighters()) {
         if (this.nearSurface(player)) {
            targets.add(player);
         }
      }

      if (targets.isEmpty()) {
         return;
      }

      Player target = targets.get(ThreadLocalRandom.current().nextInt(targets.size()));
      Location from = this.body.getLocation().add(0.0, 2.0, 0.0);
      this.plugin.getSoundManager().world(from, Sound.ENTITY_DROWNED_SHOOT, 1.2F, 0.6F);
      this.bolt(from, target.getLocation().add(0.0, 1.0, 0.0), 1.0, this.arena.radius() * 2.5, Particle.DUST, new Particle.DustOptions(Color.fromRGB(70, 160, 230), 1.6F), hit -> {
         this.hit(hit, this.num("bolt-damage", 6.0));
         hit.setVelocity(hit.getLocation().toVector().subtract(this.body.getLocation().toVector()).setY(0.0).normalize().multiply(0.8).setY(0.4));
         ParticleManager.showcase(Particle.SPLASH, hit.getLocation().add(0.0, 1.0, 0.0), 30, 0.4, 0.5, 0.4, 0.2, null);
      });
   }

   private void openWhirlpools() {
      for (int i = 0; i < (this.phase >= 2 ? 3 : 2); i++) {
         double a = ThreadLocalRandom.current().nextDouble(Math.PI * 2.0);
         double r = ThreadLocalRandom.current().nextDouble(0.3, 0.8) * this.arena.radius();
         Location at = this.arena.centre().clone().add(Math.cos(a) * r, 0.0, Math.sin(a) * r);
         int top = this.waterTop(at);
         if (top != Integer.MIN_VALUE) {
            at.setY(top + 0.9);
            this.whirlpools.add(new Object[]{at, System.currentTimeMillis() + (long)(this.num("whirlpool-seconds", 8.0) * 1000.0)});
            this.plugin.getSoundManager().world(at, Sound.BLOCK_BUBBLE_COLUMN_WHIRLPOOL_AMBIENT, 1.5F, 0.6F);
         }
      }
   }

   private void tickWhirlpools(long now) {
      double radius = this.num("whirlpool-radius", 4.5);
      this.whirlpools.removeIf(w -> (Long)w[1] <= now);
      for (Object[] whirl : this.whirlpools) {
         Location centre = (Location)whirl[0];
         if (this.ticks % 2L == 0L) {
            double spin = this.ticks * 0.35;
            Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(40, 110, 190), 1.3F);
            for (int arm = 0; arm < 3; arm++) {
               for (double rr = 0.5; rr <= radius; rr += 0.7) {
                  double a = spin + arm * Math.PI * 2.0 / 3.0 + rr * 0.6;
                  ParticleManager.showcase(Particle.DUST, centre.clone().add(Math.cos(a) * rr, 0.0, Math.sin(a) * rr), 1, 0.0, 0.0, 0.0, 0.0, dust);
               }
            }
         }

         for (Player player : this.fighters()) {
            Vector rel = centre.toVector().subtract(player.getLocation().toVector());
            double flat = Math.hypot(rel.getX(), rel.getZ());
            if (flat <= radius && Math.abs(rel.getY()) < 4.0) {
               Vector inward = new Vector(rel.getX(), 0.0, rel.getZ()).normalize();
               Vector around = new Vector(-inward.getZ(), 0.0, inward.getX());
               player.setVelocity(player.getVelocity().multiply(0.6).add(inward.multiply(0.12)).add(around.multiply(0.1)).add(new Vector(0.0, -0.04, 0.0)));
               if (flat <= 1.5 && this.ticks % 20L == 0L) {
                  this.trueDamage(player, this.num("whirlpool-damage", 2.0));
               }
            }
         }
      }
   }

   private void summon() {
      int fighters = Math.max(1, this.fighters().size());
      for (int i = 0; i < Math.min(6, 1 + fighters); i++) {
         double a = ThreadLocalRandom.current().nextDouble(Math.PI * 2.0);
         double r = ThreadLocalRandom.current().nextDouble(0.3, 0.9) * this.arena.radius();
         Location at = this.arena.centre().clone().add(Math.cos(a) * r, 0.0, Math.sin(a) * r);
         int top = this.waterTop(at);
         if (top == Integer.MIN_VALUE) {
            continue;
         }

         at.setY(top - 1.0);
         if (i % 3 == 2) {
            this.spawnMinion(at, Guardian.class);
         } else {
            Drowned drowned = this.spawnMinion(at, Drowned.class);
            if (ThreadLocalRandom.current().nextDouble() < 0.3) {
               drowned.getEquipment().setItemInMainHand(new ItemStack(Material.TRIDENT));
            }
         }

         ParticleManager.showcase(Particle.BUBBLE_COLUMN_UP, at, 20, 0.4, 0.5, 0.4, 0.1, null);
      }

      this.say("☠ Things rise from the deep.");
   }

   /** Phase 3: standing water on the arena's dry ground (it never flows, healed after the fight). */
   private void flood() {
      int placed = 0;
      for (int attempt = 0; attempt < 40 && placed < 3; attempt++) {
         double a = ThreadLocalRandom.current().nextDouble(Math.PI * 2.0);
         double r = ThreadLocalRandom.current().nextDouble(0.1, 0.95) * this.arena.radius();
         Location spot = this.arena.surface(this.arena.centre().clone().add(Math.cos(a) * r, 0.0, Math.sin(a) * r));
         Block floor = spot.getBlock().getRelative(0, -1, 0);
         if (floor.getType() == Material.WATER || floor.isLiquid()) {
            continue;
         }

         placed++;
         for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
               Block block = spot.getBlock().getRelative(dx, 0, dz);
               if (block.getType().isAir() && !block.getRelative(0, -1, 0).isPassable()) {
                  this.terrain.set(block, Material.WATER);
               }
            }
         }

         ParticleManager.showcase(Particle.SPLASH, spot, 60, 2.0, 0.5, 2.0, 0.2, null);
      }

      if (placed > 0) {
         this.manager.save();
         this.say("☠ The sea climbs onto the land.");
      }
   }
}
