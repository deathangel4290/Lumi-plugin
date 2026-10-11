package com.legendaryweapons.bosses;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.events.Contribution;
import com.legendaryweapons.events.EventManager;
import com.legendaryweapons.events.RelicManager;
import com.legendaryweapons.events.SeasonManager;
import com.legendaryweapons.managers.ParticleManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;

/**
 * One boss fight. The vanilla mob ("body") provides AI, hitbox and sounds; everything that makes it a
 * boss lives here:
 *
 *  - Health is a pool kept by the plugin (vanilla caps max health at 1024 and some mobs change
 *    behaviour at low health). The body is topped up every tick and only the pool goes down.
 *  - The pool scales with the number of fighters, at the start and whenever more people join.
 *  - Phases trigger at configured fractions of the pool.
 *  - Damage only counts from players inside the arena, not from vehicles; environment damage is
 *    ignored. The body is leashed to the arena.
 *  - A player who dies in the arena feeds the boss: it heals and hits harder for a while, so betraying
 *    your neighbours mid-fight can cost everyone the kill.
 *  - On death: contribution rewards for everyone who helped, then the Boss Heart drops.
 */
public abstract class Boss {
   protected final LegendaryWeapons plugin;
   protected final BossManager manager;
   public final String id;
   protected final BossManager.Arena arena;
   protected final Contribution contribution = new Contribution();
   protected final Set<UUID> minions = new HashSet<>();
   protected final List<Entity> extras = new ArrayList<>();
   protected final TempBlocks terrain;
   protected final BossBar bar;
   protected LivingEntity body;
   protected BossModel model;
   protected double maxPool;
   protected double pool;
   protected int phase;
   protected long ticks;
   protected boolean shielded;
   protected boolean allowTeleport;
   private int fightersCounted;
   private long startedAt;
   private long emptySince;
   private int feedStacks;
   private long feedUntil;
   private boolean over;
   private boolean seenFighter;

   protected Boss(LegendaryWeapons plugin, BossManager manager, String id, BossManager.Arena arena, TempBlocks terrain) {
      this.plugin = plugin;
      this.manager = manager;
      this.id = id;
      this.arena = arena;
      this.terrain = terrain;
      this.bar = BossBar.bossBar(Component.empty(), 1.0F, this.barColour(), BossBar.Overlay.NOTCHED_10);
   }

   // ------------------------------------------------------------------ what each boss defines

   public abstract String displayName();

   protected abstract TextColor colour();

   protected abstract BossBar.Color barColour();

   protected abstract EntityType bodyType();

   /** Pool fractions where the next phase begins, highest first, e.g. {0.66, 0.33}. */
   protected abstract double[] defaultPhases();

   protected abstract void onTick();

   protected void onSpawned() {
   }

   protected void onPhase(int phase) {
   }

   /** Adjust a player's hit before it lands (shields, weaknesses, resistances). */
   protected double incoming(Player attacker, EntityDamageEvent event, double damage) {
      return damage;
   }

   protected void onInteract(Player player, Entity clicked) {
   }

   /** Something other than the body owned by this fight was hit (anchors, decoys). True = handled. */
   protected boolean onPartDamaged(Entity part, Player attacker, EntityDamageEvent event) {
      return false;
   }

   protected void onMinionDeath(LivingEntity minion, Player killer) {
   }

   /** A player was hit by the boss, a minion, or one of their projectiles (after scaling). */
   protected void onHitPlayer(Player victim, Entity source, EntityDamageByEntityEvent event) {
   }

   /** Something the fight owns exploded at `at` (skulls, fireballs). */
   protected void onExplosion(Location at, Entity source) {
   }

   /** Where the body appears and returns to when it strays: the arena floor by default. */
   protected Location spawnPoint() {
      return this.arena.surface(this.arena.centre());
   }

   protected Location heartDrop() {
      return this.body.getLocation();
   }

   // ------------------------------------------------------------------ config

   protected ConfigurationSection cfg() {
      return this.plugin.getConfig().getConfigurationSection("bosses." + this.id);
   }

   protected double num(String key, double fallback) {
      ConfigurationSection cfg = this.cfg();
      return cfg == null ? fallback : cfg.getDouble(key, fallback);
   }

   protected int integer(String key, int fallback) {
      ConfigurationSection cfg = this.cfg();
      return cfg == null ? fallback : cfg.getInt(key, fallback);
   }

   protected EventManager events() {
      return this.plugin.getEventManager();
   }

   // ------------------------------------------------------------------ lifecycle

   public final String start() {
      this.setupBody(this.spawnPoint(), this.bodyType(), this.num("scale", 1.0));
      int fighters = Math.max(1, this.fighters().size());
      this.fightersCounted = fighters;
      double base = this.num("health", 800.0);
      this.maxPool = base * (1.0 + this.num("health-per-player", 0.35) * (fighters - 1));
      this.pool = this.maxPool;
      this.startedAt = System.currentTimeMillis();
      this.emptySince = 0L;
      for (Player player : Bukkit.getOnlinePlayers()) {
         player.showTitle(Title.title(Component.text(this.displayName(), this.colour(), TextDecoration.BOLD), Component.text("has awoken", NamedTextColor.GRAY)));
         this.plugin.getSoundManager().self(player, Sound.ENTITY_WITHER_SPAWN, 0.5F, 0.8F);
      }

      Bukkit.broadcast(Component.text("☠ " + this.displayName().toUpperCase() + " HAS AWOKEN", this.colour(), TextDecoration.BOLD));
      Location c = this.arena.centre();
      Bukkit.broadcast(Component.text("Arena: " + com.legendaryweapons.rituals.Ritual.dimensionName(c.getWorld()) + " " + c.getBlockX() + ", " + c.getBlockY() + ", " + c.getBlockZ()
         + ". Fight it together - or not. Its Heart goes to whoever survives with it.", NamedTextColor.GRAY));
      this.onSpawned();
      return null;
   }

   private void setupBody(Location at, EntityType type, double scale) {
      this.body = (LivingEntity)at.getWorld().spawnEntity(at, type);
      this.body.setPersistent(false);
      this.body.setRemoveWhenFarAway(false);
      this.body.customName(Component.text(this.displayName(), this.colour(), TextDecoration.BOLD));
      this.body.setCustomNameVisible(false);
      this.body.getPersistentDataContainer().set(this.manager.bodyKey(), PersistentDataType.STRING, this.id);
      setBase(this.body, Attribute.MAX_HEALTH, 1024.0);
      this.body.setHealth(1024.0);
      setBase(this.body, Attribute.SCALE, scale);
      setBase(this.body, Attribute.KNOCKBACK_RESISTANCE, 1.0);
      this.model = new BossModel(this.cfg() == null ? null : this.cfg().getConfigurationSection("model"));
      this.model.attach(this.body);
   }

   /** Replace the body with another mob (the Wither King landing), keeping health, phase and model. */
   protected void swapBody(EntityType type, double scale, String modelState) {
      Location at = this.body.getLocation();
      this.model.remove();
      this.body.remove();
      this.setupBody(at, type, scale);
      this.model.setBaseState(modelState);
   }

   /** A line shown under the action bar while this boss is fighting (null = nothing). */
   public Component hud(Player player) {
      return null;
   }

   /**
    * A projectile the plugin moves itself (no entity, nothing to deflect or pick up): travels from
    * {@code from} toward {@code to} at {@code speed} blocks/tick, hits the first fighter within 1 block
    * (calling {@code onHit}) or stops at a wall or after {@code range} blocks.
    */
   protected void bolt(Location from, Location to, double speed, double range, Particle particle, Object data, java.util.function.Consumer<Player> onHit) {
      final Vector step = to.toVector().subtract(from.toVector());
      if (step.lengthSquared() < 1.0E-4) {
         return;
      }

      step.normalize().multiply(speed);
      final Location at = from.clone();
      (new org.bukkit.scheduler.BukkitRunnable() {
         double travelled = 0.0;

         public void run() {
            if (Boss.this.over || this.travelled > range) {
               this.cancel();
               return;
            }

            for (int i = 0; i < 2; i++) {
               at.add(step.clone().multiply(0.5));
               this.travelled += speed * 0.5;
               if (!at.getBlock().isPassable() && !at.getBlock().isLiquid()) {
                  this.cancel();
                  ParticleManager.showcase(particle, at, 10, 0.3, 0.3, 0.3, 0.05, data);
                  return;
               }

               ParticleManager.showcase(particle, at, 2, 0.08, 0.08, 0.08, 0.0, data);
               for (Player player : Boss.this.fighters()) {
                  if (player.getLocation().add(0.0, 1.0, 0.0).distanceSquared(at) <= 1.2) {
                     this.cancel();
                     onHit.accept(player);
                     return;
                  }
               }
            }
         }
      }).runTaskTimer(this.plugin, 1L, 1L);
   }

   /** Run later, unless the fight has ended by then. */
   protected void later(long ticks, Runnable action) {
      this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
         if (!this.over) {
            try {
               action.run();
            } catch (RuntimeException var3) {
               this.plugin.getLogger().warning("Boss ability failed: " + var3);
            }
         }
      }, Math.max(1L, ticks));
   }

   /** Every tick, from the manager. */
   final void tick() {
      if (this.over) {
         return;
      }

      this.ticks++;
      if (this.body == null || !this.body.isValid() || this.body.isDead()) {
         this.fail("The " + this.displayName() + " vanished.");
         return;
      }

      AttributeInstance max = this.body.getAttribute(Attribute.MAX_HEALTH);
      if (max != null && this.body.getHealth() < max.getValue()) {
         this.body.setHealth(max.getValue());           // the pool is the real health
      }

      this.body.setFireTicks(0);
      if (!this.arena.contains(this.body.getLocation(), 0.0)) {
         this.allowTeleport = true;
         try {
            this.body.teleport(this.spawnPoint());
         } finally {
            this.allowTeleport = false;
         }
      }

      this.model.follow(this.body);
      if (this.ticks % 20L == 0L) {
         this.secondTick();
         if (this.over) {
            return;
         }
      }

      if (this.ticks % 5L == 0L) {
         this.updateBar();
      }

      this.onTick();
   }

   private void secondTick() {
      long now = System.currentTimeMillis();
      List<Player> fighters = this.fighters();
      if (!fighters.isEmpty()) {
         this.seenFighter = true;
      }

      if (fighters.isEmpty() && !this.seenFighter && now - this.startedAt < (long)(this.num("arrive-minutes", 5.0) * 60000.0)) {
         // nobody has arrived yet: give people time to travel to the arena
      } else if (fighters.isEmpty()) {
         if (this.emptySince == 0L) {
            this.emptySince = now;
         } else if (now - this.emptySince > (long)(this.num("leave-seconds", 120.0) * 1000.0)) {
            this.fail("Nobody is left to fight. The " + this.displayName() + " retreats.");
            return;
         }
      } else {
         this.emptySince = 0L;
      }

      if (now - this.startedAt > (long)(this.num("max-minutes", 20.0) * 60000.0)) {
         this.fail("The " + this.displayName() + " tires of the fight and retreats.");
         return;
      }

      // more people turned up: the boss grows with them (never shrinks mid-fight)
      if (fighters.size() > this.fightersCounted) {
         double cap = this.num("health", 800.0) * this.num("max-health-multiplier", 4.0);
         double grow = this.num("health", 800.0) * this.num("health-per-player", 0.35) * (fighters.size() - this.fightersCounted);
         grow = Math.max(0.0, Math.min(grow, cap - this.maxPool));
         this.maxPool += grow;
         this.pool += grow;
         this.fightersCounted = fighters.size();
      }

      if (this.feedStacks > 0 && now > this.feedUntil) {
         this.feedStacks = 0;
      }

      if (this.body instanceof Mob mob) {
         Player target = this.chooseTarget(fighters);
         if (target != null && (mob.getTarget() == null || !mob.getTarget().equals(target))) {
            mob.setTarget(target);
         }
      }

      double[] phases = this.phases();
      while (this.phase < phases.length && this.pool / this.maxPool <= phases[this.phase]) {
         this.phase++;
         Bukkit.broadcast(Component.text("☠ " + this.displayName() + " - phase " + (this.phase + 1), this.colour()));
         this.onPhase(this.phase);
      }
   }

   protected Player chooseTarget(List<Player> fighters) {
      Player best = null;
      double bestDistance = Double.MAX_VALUE;
      for (Player player : fighters) {
         double d = player.getLocation().distanceSquared(this.body.getLocation());
         if (d < bestDistance) {
            bestDistance = d;
            best = player;
         }
      }

      return best;
   }

   private double[] phases() {
      List<Double> configured = this.cfg() == null ? List.of() : this.cfg().getDoubleList("phases");
      if (configured.isEmpty()) {
         return this.defaultPhases();
      }

      double[] out = new double[configured.size()];
      for (int i = 0; i < out.length; i++) {
         out[i] = configured.get(i);
      }

      return out;
   }

   private void updateBar() {
      Component name = Component.text("☠ " + this.displayName(), this.colour(), TextDecoration.BOLD)
         .append(Component.text("  " + (int)Math.ceil(this.pool) + " / " + (int)Math.ceil(this.maxPool), NamedTextColor.WHITE));
      if (this.shielded) {
         name = name.append(Component.text("  SHIELDED", NamedTextColor.AQUA));
      }

      if (this.feedStacks > 0) {
         name = name.append(Component.text("  FED x" + this.feedStacks, NamedTextColor.RED));
      }

      this.bar.name(name);
      this.bar.progress((float)Math.max(0.0, Math.min(1.0, this.pool / this.maxPool)));
      double show = this.arena.radius() * 3.0;
      for (Player player : this.arena.centre().getWorld().getPlayers()) {
         if (player.getLocation().distanceSquared(this.arena.centre()) <= show * show) {
            player.showBossBar(this.bar);
         } else {
            player.hideBossBar(this.bar);
         }
      }
   }

   /** Damage that got through, after every rule. */
   final void damage(Player attacker, double amount) {
      if (this.over || amount <= 0.0) {
         return;
      }

      this.pool -= amount;
      if (attacker != null) {
         this.contribution.add(attacker, Contribution.Kind.DAMAGE, amount, this.events().publicName(attacker));
         this.events().season().add(attacker, SeasonManager.Stat.BOSS_DAMAGE, amount);
      }

      if (this.pool <= 0.0) {
         this.defeat();
      }
   }

   /** A player died in the arena: the boss feeds on it. */
   final void feed(Player victim) {
      if (this.over) {
         return;
      }

      double heal = this.maxPool * this.num("feed-heal", 0.04);
      this.pool = Math.min(this.maxPool, this.pool + heal);
      this.feedStacks = Math.min(5, this.feedStacks + 1);
      this.feedUntil = System.currentTimeMillis() + (long)(this.num("feed-seconds", 30.0) * 1000.0);
      Bukkit.broadcast(Component.text("☠ The " + this.displayName() + " feeds on " + this.events().publicName(victim) + "'s death and grows stronger.", NamedTextColor.RED));
      ParticleManager.spawn(Particle.DAMAGE_INDICATOR, this.body.getLocation().add(0.0, 2.0, 0.0), 12, 0.8, 1.0, 0.8, 0.1);
   }

   /** Multiplier on everything this boss and its minions deal. */
   final double outgoing() {
      return this.num("damage-multiplier", 1.0) * (1.0 + 0.1 * this.feedStacks);
   }

   private void defeat() {
      this.over = true;
      Location at = this.body.getLocation();
      Bukkit.broadcast(Component.text("☠ THE " + this.displayName().toUpperCase() + " HAS FALLEN", this.colour(), TextDecoration.BOLD));
      for (Player player : Bukkit.getOnlinePlayers()) {
         player.showTitle(Title.title(Component.text(this.displayName(), this.colour(), TextDecoration.BOLD), Component.text("has fallen", NamedTextColor.GRAY)));
      }

      ParticleManager.showcase(Particle.EXPLOSION_EMITTER, at, 3, 1.0, 1.0, 1.0, 0.0, null);
      ParticleManager.showcase(Particle.SOUL, at.clone().add(0.0, 1.0, 0.0), 60, 1.5, 1.5, 1.5, 0.08, null);
      this.plugin.getSoundManager().world(at, Sound.ENTITY_WITHER_DEATH, 1.0F, 0.8F);
      Location heart = this.heartDrop();
      this.contribution.payOut(this.plugin, this.displayName(), this.cfg() == null ? null : this.cfg().getConfigurationSection("rewards"), "boss");
      this.cleanup();
      this.events().relics().spawn(RelicManager.Type.BOSS_HEART, heart, this.displayName(), claimer -> {
      });
   }

   public final void fail(String message) {
      if (this.over) {
         return;
      }

      this.over = true;
      if (message != null) {
         Bukkit.broadcast(Component.text("☠ " + message, NamedTextColor.GRAY));
      }

      this.cleanup();
   }

   public boolean isOver() {
      return this.over;
   }

   protected void cleanup() {
      this.over = true;
      this.model.remove();
      if (this.body != null && this.body.isValid()) {
         this.body.remove();
      }

      for (UUID id : this.minions) {
         Entity minion = Bukkit.getEntity(id);
         if (minion != null) {
            minion.remove();
         }
      }

      this.minions.clear();
      for (Entity extra : this.extras) {
         if (extra != null && extra.isValid()) {
            extra.remove();
         }
      }

      this.extras.clear();
      for (Player player : Bukkit.getOnlinePlayers()) {
         player.hideBossBar(this.bar);
      }

      this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
         this.terrain.restore();
         this.manager.save();
      }, (long)(this.num("restore-seconds", 20.0) * 20.0));
      this.manager.ended(this);
   }

   // ------------------------------------------------------------------ helpers for the bosses

   public LivingEntity body() {
      return this.body;
   }

   boolean owns(Entity entity) {
      return entity.equals(this.body) || this.minions.contains(entity.getUniqueId()) || this.extras.contains(entity);
   }

   protected List<Player> fighters() {
      List<Player> out = new ArrayList<>();
      for (Player player : this.arena.centre().getWorld().getPlayers()) {
         if (!player.isDead() && (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE) && this.arena.contains(player.getLocation(), 4.0)) {
            out.add(player);
         }
      }

      return out;
   }

   protected Player randomFighter(Player except) {
      List<Player> fighters = this.fighters();
      if (except != null && fighters.size() > 1) {
         fighters.remove(except);
      }

      return fighters.isEmpty() ? null : fighters.get(ThreadLocalRandom.current().nextInt(fighters.size()));
   }

   protected <T extends LivingEntity> T spawnMinion(Location at, Class<T> type) {
      T minion = at.getWorld().spawn(at, type, m -> {
         m.setPersistent(false);
         m.setRemoveWhenFarAway(false);
         m.getPersistentDataContainer().set(this.manager.minionKey(), PersistentDataType.STRING, this.id);
      });
      this.minions.add(minion.getUniqueId());
      if (minion instanceof Mob mob) {
         Player target = this.chooseTarget(this.fighters());
         if (target != null) {
            mob.setTarget(target);
         }
      }

      return minion;
   }

   protected int aliveMinions() {
      this.minions.removeIf(id -> {
         Entity entity = Bukkit.getEntity(id);
         return entity == null || !entity.isValid() || entity.isDead();
      });
      return this.minions.size();
   }

   protected void credit(Player player, Contribution.Kind kind, double amount) {
      if (player != null) {
         this.contribution.add(player, kind, amount, this.events().publicName(player));
      }
   }

   /** Damage from the boss that ignores armour (its big mechanics). */
   protected void trueDamage(LivingEntity victim, double amount) {
      victim.setNoDamageTicks(0);
      victim.damage(amount * this.outgoing(), org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.SONIC_BOOM)
         .withCausingEntity(this.body).withDirectEntity(this.body).build());
   }

   /** A normal hit from the boss (armour applies). */
   protected void hit(LivingEntity victim, double amount) {
      victim.setNoDamageTicks(0);
      victim.damage(amount, this.body);                 // the outgoing multiplier is applied by the manager
   }

   protected void say(String text) {
      for (Player player : this.fighters()) {
         player.sendMessage(Component.text(text, this.colour()));
      }
   }

   protected static void dustRing(Location centre, double radius, Color colour, float size) {
      int points = (int)Math.max(12, radius * 8);
      Particle.DustOptions dust = new Particle.DustOptions(colour, size);
      for (int i = 0; i < points; i++) {
         double a = Math.PI * 2.0 * i / points;
         ParticleManager.showcase(Particle.DUST, centre.clone().add(Math.cos(a) * radius, 0.15, Math.sin(a) * radius), 1, 0.0, 0.0, 0.0, 0.0, dust);
      }
   }

   protected static void dustLine(Location from, Location to, Color colour, float size) {
      Vector step = to.toVector().subtract(from.toVector());
      double length = step.length();
      if (length < 0.01) {
         return;
      }

      step.multiply(0.5 / length);
      Particle.DustOptions dust = new Particle.DustOptions(colour, size);
      Location at = from.clone();
      for (double d = 0.0; d <= length; d += 0.5) {
         ParticleManager.showcase(Particle.DUST, at, 1, 0.0, 0.0, 0.0, 0.0, dust);
         at.add(step);
      }
   }

   protected static void setBase(LivingEntity entity, Attribute attribute, double value) {
      AttributeInstance instance = entity.getAttribute(attribute);
      if (instance != null) {
         instance.setBaseValue(value);
      }
   }

   protected NamespacedKey key(String name) {
      return new NamespacedKey(this.plugin, name);
   }
}
