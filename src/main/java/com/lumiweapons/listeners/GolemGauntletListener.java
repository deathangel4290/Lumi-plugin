package com.lumiweapons.listeners;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.abilities.AbilityDamage;
import com.lumiweapons.abilities.UltimateWeapon;
import com.lumiweapons.managers.HudManager;
import com.lumiweapons.managers.ParticleManager;
import com.lumiweapons.managers.SpriteFx;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.util.Vector;

/**
 * Golem Gauntlet -- the tank. It turns the hits you take into a stone giant that fights beside you.
 *
 *   Passive, Stone Skin: damage you take with the gauntlet in hand builds Stone energy.
 *   Ability 1, Boulder Punch: a heavy punch that sends the enemy in front of you flying.
 *   Ability 2, Stone Wall: a short wall rises in front of you. It stops people and projectiles,
 *     drops nothing when broken and crumbles after a few seconds. It only fills air, and only
 *     where you could build.
 *   Ultimate, Colossus: a stone golem fights for you for a short time. It has limited health,
 *     hits slower and softer than an iron golem, never turns on you or your allies, and drops
 *     nothing. It distracts; it doesn't win the fight alone.
 */
public class GolemGauntletListener extends UltimateWeapon {
   public static final String KEY = "golem-gauntlet";
   private static final String PUNCH_KEY = "golem-boulder-punch";
   private static final String WALL_KEY = "golem-stone-wall";
   private static final Color STONE = Color.fromRGB(0x8A8A80);
   /** wall block -> when it crumbles (ms). */
   private final Map<Block, Long> wall = new HashMap<>();
   /** golem -> owner. */
   private final Map<UUID, UUID> golems = new HashMap<>();
   private final Map<UUID, IronGolem> golemEntities = new HashMap<>();

   public GolemGauntletListener(LumiWeapons plugin) {
      super(plugin);
      plugin.getServer().getScheduler().runTaskTimer(plugin, this::crumbleTick, 10L, 10L);
      plugin.getServer().getScheduler().runTaskTimer(plugin, this::golemTick, 20L, 20L);
   }

   @Override
   public String weaponKey() {
      return KEY;
   }

   @Override
   protected String ultimateName() {
      return "Colossus";
   }

   @Override
   protected NamedTextColor ultimateColour() {
      return NamedTextColor.GRAY;
   }

   @Override
   public Component hud(Player player) {
      return HudManager.cooldown(player, "Boulder Punch", PUNCH_KEY).append(Component.text("  "))
         .append(HudManager.cooldown(player, "Stone Wall", WALL_KEY)).append(Component.text("  "))
         .append(this.ultimateHud(player));
   }

   @Override
   protected String ultimateBlocked(Player player) {
      return this.golems.containsValue(player.getUniqueId()) ? "Your Colossus is still standing." : null;
   }

   // ------------------------------------------------------------------ Stone Skin

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onHurt(EntityDamageEvent event) {
      if (event.getEntity() instanceof Player player && this.isHolding(player) && event.getFinalDamage() > 0.0) {
         this.chargeUltimate(player, event.getFinalDamage() * this.ability("stone-charge-per-damage", 3.0));
         this.plugin.getChargeMeter().markCombat(player);
      }
   }

   // ------------------------------------------------------------------ Boulder Punch

   @Override
   protected void firstAbility(Player player) {
      double reach = this.ability("punch-range", 4.0);
      LivingEntity target = this.arc(player, reach, 70.0, 0.0).stream()
         .min(Comparator.comparingDouble(e -> e.getLocation().distanceSquared(player.getLocation()))).orElse(null);
      if (target == null) {
         HudManager.message(player, Component.text("Nothing in reach to punch.", NamedTextColor.GRAY));
         return;
      }

      if (!this.tryCooldown(player, PUNCH_KEY, this.abilityLong("punch-cooldown", 200L))) {
         return;
      }

      Location at = target.getLocation().add(0, 1, 0);
      AbilityDamage.hit(player, target, this.ability("punch-damage", 7.0), 0.0, player.getLocation(), "{victim} was flattened by {killer}'s Golem Gauntlet");
      push(target, player.getLocation(), this.ability("punch-knockback", 1.8), 0.5);
      ParticleManager.spawn(Particle.BLOCK, at, 25, 0.3, 0.4, 0.3, 0.1, Material.STONE.createBlockData());
      SpriteFx.burst(at, STONE, 1.2, 1, 1, 3);
      this.plugin.getSoundManager().world(at, Sound.ENTITY_IRON_GOLEM_ATTACK, 1.0F, 0.8F);
      this.plugin.getSoundManager().world(at, Sound.BLOCK_STONE_BREAK, 1.0F, 0.6F);
      this.chargeFromAbility(player);
   }

   // ------------------------------------------------------------------ Stone Wall

   @Override
   protected void secondAbility(Player player) {
      if (!this.tryCooldown(player, WALL_KEY, this.abilityLong("wall-cooldown", 400L))) {
         return;
      }

      Material material = Material.matchMaterial(this.abilities() == null ? "" : this.abilities().getString("wall-material", "COBBLED_DEEPSLATE"));
      if (material == null || !material.isBlock() || !material.isSolid()) {
         material = Material.COBBLED_DEEPSLATE;
      }

      int width = Math.max(1, this.abilityInt("wall-width", 5));
      int height = Math.max(1, this.abilityInt("wall-height", 3));
      Vector forward = facing(player);
      Vector side = new Vector(-forward.getZ(), 0, forward.getX());
      Location base = player.getLocation().add(forward.clone().multiply(this.ability("wall-distance", 2.5)));
      long until = System.currentTimeMillis() + this.abilityLong("wall-duration", 120L) * 50L;
      int placed = 0;
      for (int w = 0; w < width; w++) {
         Location column = base.clone().add(side.clone().multiply(w - (width - 1) / 2.0));
         for (int h = 0; h < height; h++) {
            Block block = column.clone().add(0, h, 0).getBlock();
            if (block.getType().isAir() && this.plugin.getProtectionManager().canBuild(player, block.getLocation())) {
               block.setType(material, false);
               this.wall.put(block, until);
               placed++;
               ParticleManager.spawn(Particle.BLOCK, block.getLocation().add(0.5, 0.5, 0.5), 6, 0.3, 0.3, 0.3, 0.0, material.createBlockData());
            }
         }
      }

      if (placed == 0) {
         HudManager.message(player, Component.text("No room for a wall there.", NamedTextColor.GRAY));
         return;
      }

      this.plugin.getSoundManager().world(base, Sound.BLOCK_DEEPSLATE_PLACE, 1.2F, 0.6F);
      this.plugin.getSoundManager().world(base, Sound.ENTITY_IRON_GOLEM_REPAIR, 0.8F, 0.7F);
      this.chargeFromAbility(player);
   }

   private void crumbleTick() {
      long now = System.currentTimeMillis();
      for (Map.Entry<Block, Long> entry : new ArrayList<>(this.wall.entrySet())) {
         if (entry.getValue() <= now) {
            this.crumble(entry.getKey());
         }
      }
   }

   private void crumble(Block block) {
      this.wall.remove(block);
      if (!block.getType().isAir()) {
         ParticleManager.spawn(Particle.BLOCK, block.getLocation().add(0.5, 0.5, 0.5), 8, 0.3, 0.3, 0.3, 0.0, block.getBlockData());
         block.setType(Material.AIR, false);
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onBreakWall(BlockBreakEvent event) {
      if (this.wall.remove(event.getBlock()) != null) {
         event.setDropItems(false);
         event.setExpToDrop(0);
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onEntityExplode(EntityExplodeEvent event) {
      event.blockList().removeIf(this.wall::containsKey);
   }

   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onBlockExplode(BlockExplodeEvent event) {
      event.blockList().removeIf(this.wall::containsKey);
   }

   // ------------------------------------------------------------------ Colossus

   @Override
   protected void ultimate(Player player) {
      Location at = player.getLocation().add(facing(player).multiply(1.5));
      if (!at.getBlock().isPassable()) {
         at = player.getLocation();
      }

      IronGolem golem = at.getWorld().spawn(at, IronGolem.class, g -> {
         g.setPersistent(false);
         g.setRemoveWhenFarAway(false);
         g.setPlayerCreated(true);
         g.customName(Component.text(this.plugin.getEventManager().publicName(player) + "'s Colossus", NamedTextColor.GRAY));
         g.setCustomNameVisible(true);
         setBase(g, Attribute.MAX_HEALTH, this.ability("colossus-health", 40.0));
         setBase(g, Attribute.ATTACK_DAMAGE, this.ability("colossus-damage", 6.0));
         setBase(g, Attribute.MOVEMENT_SPEED, 0.25 * this.ability("colossus-speed", 1.0));
         g.setHealth(Math.max(1.0, this.ability("colossus-health", 40.0)));
      });
      this.golems.put(golem.getUniqueId(), player.getUniqueId());
      this.golemEntities.put(golem.getUniqueId(), golem);
      ParticleManager.showcase(Particle.BLOCK, at.clone().add(0, 1, 0), 60, 0.8, 1.2, 0.8, 0.1, Material.STONE.createBlockData());
      SpriteFx.wave(at, STONE, 4.0, 8);
      this.plugin.getSoundManager().world(at, Sound.ENTITY_IRON_GOLEM_REPAIR, 1.2F, 0.5F);
      this.plugin.getSoundManager().world(at, Sound.ENTITY_WARDEN_EMERGE, 0.6F, 1.4F);
      HudManager.message(player, Component.text("Colossus rises!", NamedTextColor.GRAY));
      UUID id = golem.getUniqueId();
      this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> this.dismiss(id), Math.max(40L, this.abilityLong("colossus-duration", 300L)));
   }

   private static void setBase(LivingEntity entity, Attribute attribute, double value) {
      AttributeInstance instance = entity.getAttribute(attribute);
      if (instance != null) {
         instance.setBaseValue(value);
      }
   }

   private void dismiss(UUID id) {
      this.golems.remove(id);
      IronGolem golem = this.golemEntities.remove(id);
      if (golem != null && golem.isValid()) {
         ParticleManager.spawn(Particle.BLOCK, golem.getLocation().add(0, 1, 0), 40, 0.6, 1.0, 0.6, 0.1, Material.STONE.createBlockData());
         this.plugin.getSoundManager().world(golem.getLocation(), Sound.ENTITY_IRON_GOLEM_DEATH, 0.8F, 0.8F);
         golem.remove();
      }
   }

   /** Once a second: each golem picks the nearest enemy of its owner; it ends if the owner leaves. */
   private void golemTick() {
      for (Map.Entry<UUID, UUID> entry : new ArrayList<>(this.golems.entrySet())) {
         IronGolem golem = this.golemEntities.get(entry.getKey());
         Player owner = this.plugin.getServer().getPlayer(entry.getValue());
         if (golem == null || !golem.isValid() || owner == null || !owner.getWorld().equals(golem.getWorld())) {
            this.dismiss(entry.getKey());
            continue;
         }

         LivingEntity current = golem.getTarget();
         if (current != null && current.isValid() && !current.isDead() && this.isEnemy(owner, current)) {
            continue;
         }

         List<LivingEntity> enemies = this.around(owner, golem.getLocation(), this.ability("colossus-range", 16.0));
         enemies.removeIf(e -> e.equals(golem) || this.golems.containsKey(e.getUniqueId()));
         golem.setTarget(enemies.stream()
            .min(Comparator.comparingDouble(e -> e.getLocation().distanceSquared(golem.getLocation()))).orElse(null));
         if (golem.getTarget() == null && golem.getLocation().distanceSquared(owner.getLocation()) > 64.0) {
            golem.getPathfinder().moveTo(owner);
         }
      }
   }

   private boolean isEnemy(Player owner, Entity entity) {
      if (entity.equals(owner) || this.golems.containsKey(entity.getUniqueId())) {
         return false;
      }

      return !(entity instanceof Player other && this.isAlly(owner, other)) && this.canAffect(owner, entity);
   }

   private UUID ownerOf(Entity entity) {
      if (entity instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) {
         entity = shooter;
      }

      return this.golems.get(entity.getUniqueId());
   }

   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onGolemTarget(EntityTargetLivingEntityEvent event) {
      UUID owner = this.golems.get(event.getEntity().getUniqueId());
      if (owner == null || event.getTarget() == null) {
         return;
      }

      Player player = this.plugin.getServer().getPlayer(owner);
      if (player == null || !this.isEnemy(player, event.getTarget())) {
         event.setCancelled(true);
      }
   }

   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onGolemDamage(EntityDamageByEntityEvent event) {
      UUID owner = this.ownerOf(event.getDamager());
      if (owner != null) {
         Player player = this.plugin.getServer().getPlayer(owner);
         if (player == null || !this.isEnemy(player, event.getEntity())) {
            event.setCancelled(true);                     // never the owner, their allies, or another Colossus
         }

         return;
      }

      // the owner and their allies can't hurt their own Colossus by accident
      UUID golemOwner = this.golems.get(event.getEntity().getUniqueId());
      Entity attacker = event.getDamager() instanceof Projectile p && p.getShooter() instanceof Entity s ? s : event.getDamager();
      if (golemOwner != null && attacker instanceof Player hitter) {
         Player player = this.plugin.getServer().getPlayer(golemOwner);
         if (player != null && this.isAlly(player, hitter)) {
            event.setCancelled(true);
         }
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST)
   public void onGolemDeath(EntityDeathEvent event) {
      UUID id = event.getEntity().getUniqueId();
      if (this.golems.remove(id) != null) {
         this.golemEntities.remove(id);
         event.getDrops().clear();
         event.setDroppedExp(0);
      }
   }

   /** Plugin disable: walls crumble, golems leave. */
   public void cleanup() {
      for (Block block : new ArrayList<>(this.wall.keySet())) {
         this.crumble(block);
      }

      for (UUID id : new ArrayList<>(this.golems.keySet())) {
         this.dismiss(id);
      }
   }
}
