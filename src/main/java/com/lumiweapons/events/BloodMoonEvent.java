package com.lumiweapons.events;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.managers.ParticleManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Enemy;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

/**
 * Blood Moon: one night where the whole server is dangerous.
 *
 * Armed by the Director or /lwevent bloodmoon start; it rises at the next nightfall (or at once if
 * it is already night) and sets at dawn. While it is up, in that world:
 *   - monsters spawn faster and in greater numbers, and every one is stronger;
 *   - a few are Blood-Touched elites (more health, glowing, better drops);
 *   - monster drops and XP are doubled;
 *   - the screen edges carry a red tint (a per-player virtual world border warning, intensity
 *     events.blood-moon.red-tint, 0 = off) - the only way to "redden" the night server-side;
 *   - killing a player puts a token bounty on your head until dawn (blood-moon.bounty-tokens-per-kill
 *     a kill). Whoever kills you collects it; survive to dawn and it's yours.
 *     Whoever brings you down collects it.
 */
public final class BloodMoonEvent implements Listener {
   private static final TextColor BLOOD = TextColor.color(200, 20, 30);
   private final LumiWeapons plugin;
   private final EventManager manager;
   private final NamespacedKey bloodKey;
   private final NamespacedKey healthKey;
   private final NamespacedKey damageKey;
   private final Map<UUID, Double> bounties = new HashMap<>();
   private final Map<UUID, String> bountyNames = new HashMap<>();
   private World world;
   private boolean armed;
   private boolean active;
   private int savedMonsterLimit = -1;
   private long savedMonsterTicks = -1L;
   private BukkitRunnable task;

   public BloodMoonEvent(LumiWeapons plugin, EventManager manager) {
      this.plugin = plugin;
      this.manager = manager;
      this.bloodKey = LumiWeapons.key("blood-moon");
      this.healthKey = LumiWeapons.key("blood_moon_health");
      this.damageKey = LumiWeapons.key("blood_moon_damage");
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
      plugin.getHudManager().register(this::hud);
   }

   private ConfigurationSection config() {
      return this.plugin.getConfig().getConfigurationSection("events.blood-moon");
   }

   private double setting(String key, double fallback) {
      return this.config() == null ? fallback : this.config().getDouble(key, fallback);
   }

   public boolean isActive() {
      return this.active;
   }

   private static boolean isNight(World world) {
      long time = world.getTime() % 24000L;
      return time >= 13000L && time < 23000L;
   }

   public String arm(World target) {
      if (this.armed || this.active) {
         return "A Blood Moon is already " + (this.active ? "up." : "coming tonight.");
      }

      this.world = target;
      this.armed = true;
      if (isNight(target)) {
         this.rise();
      } else {
         Bukkit.broadcast(Component.text("🩸 Tonight, the moon will bleed...", BLOOD, TextDecoration.ITALIC));
      }

      this.task = new BukkitRunnable() {
         public void run() {
            try {
               BloodMoonEvent.this.tick();
            } catch (RuntimeException var2) {
               BloodMoonEvent.this.plugin.getLogger().warning("Blood Moon tick failed: " + var2);
            }
         }
      };
      this.task.runTaskTimer(this.plugin, 40L, 40L);
      return null;
   }

   private void rise() {
      this.active = true;
      this.savedMonsterLimit = this.world.getSpawnLimit(org.bukkit.entity.SpawnCategory.MONSTER);
      this.savedMonsterTicks = this.world.getTicksPerSpawns(org.bukkit.entity.SpawnCategory.MONSTER);
      int limit = this.savedMonsterLimit > 0 ? this.savedMonsterLimit : 70;
      this.world.setSpawnLimit(org.bukkit.entity.SpawnCategory.MONSTER, (int)Math.round(limit * this.setting("spawn-multiplier", 1.8)));
      this.world.setTicksPerSpawns(org.bukkit.entity.SpawnCategory.MONSTER, 1);
      Bukkit.broadcast(Component.text("🩸 THE BLOOD MOON RISES", BLOOD, TextDecoration.BOLD));
      Bukkit.broadcast(Component.text("Monsters are stronger and more numerous until dawn. Spill a player's blood and a bounty goes on your head.", NamedTextColor.GRAY));
      for (Player player : this.world.getPlayers()) {
         player.showTitle(Title.title(Component.text("THE BLOOD MOON RISES", BLOOD, TextDecoration.BOLD), Component.text("the whole server is dangerous tonight", NamedTextColor.GRAY)));
         this.plugin.getSoundManager().self(player, Sound.EVENT_RAID_HORN, 1.0F, 0.6F);
         this.tint(player, true);
      }
   }

   private void set() {
      this.active = false;
      this.armed = false;
      if (this.task != null) {
         this.task.cancel();
         this.task = null;
      }

      if (this.world != null) {
         if (this.savedMonsterLimit >= 0) {
            this.world.setSpawnLimit(org.bukkit.entity.SpawnCategory.MONSTER, this.savedMonsterLimit);
         }

         if (this.savedMonsterTicks >= 0L) {
            this.world.setTicksPerSpawns(org.bukkit.entity.SpawnCategory.MONSTER, (int)this.savedMonsterTicks);
         }

         for (Player player : this.world.getPlayers()) {
            this.tint(player, false);
         }
      }

      // surviving the night with a bounty on your head pays you that bounty
      for (Map.Entry<UUID, Double> entry : this.bounties.entrySet()) {
         this.payTokens(entry.getKey(), (int)Math.round(entry.getValue()), "Survived the Blood Moon with a bounty on your head");
      }

      this.bounties.clear();
      this.bountyNames.clear();
      this.savedMonsterLimit = -1;
      this.savedMonsterTicks = -1L;
   }

   private void tick() {
      if (this.world == null) {
         this.set();
         return;
      }

      if (!this.active) {
         if (isNight(this.world)) {
            this.rise();
         }

         return;
      }

      if (!isNight(this.world)) {
         this.set();
         Bukkit.broadcast(Component.text("☀ The Blood Moon sets. The night is over.", NamedTextColor.GOLD));
         for (Player player : this.world.getPlayers()) {
            this.plugin.getSoundManager().self(player, Sound.BLOCK_BEACON_DEACTIVATE, 0.7F, 1.2F);
         }

         return;
      }

      for (Player player : this.world.getPlayers()) {
         this.tint(player, true);                       // keeps the virtual border centred on them
      }

      for (UUID id : this.bounties.keySet()) {
         Player marked = Bukkit.getPlayer(id);
         if (marked != null) {
            ParticleManager.spawn(Particle.DUST, marked.getLocation().add(0.0, 2.3, 0.0), 3, 0.15, 0.1, 0.15, 0.0, new Particle.DustOptions(Color.fromRGB(180, 0, 0), 1.0F));
         }
      }
   }

   /**
    * A virtual border far away with a huge warning distance tints the screen edges red. It is
    * client-side only (no damage, no wall in sight) and re-centred on the player every tick of
    * the event, so they can never reach it.
    */
   private void tint(Player player, boolean on) {
      double intensity = Math.max(0.0, Math.min(0.9, this.setting("red-tint", 0.35)));
      if (!on || intensity <= 0.0) {
         player.setWorldBorder(null);
         return;
      }

      WorldBorder border = Bukkit.createWorldBorder();
      double distance = 2000.0;
      border.setCenter(player.getLocation());
      border.setSize(distance * 2.0);
      border.setWarningDistance((int)Math.round(distance / (1.0 - intensity)));
      border.setWarningTime(0);
      player.setWorldBorder(border);
   }

   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onSpawn(CreatureSpawnEvent event) {
      if (!this.active || !event.getEntity().getWorld().equals(this.world) || !(event.getEntity() instanceof Enemy)) {
         return;
      }

      switch (event.getSpawnReason()) {
         case NATURAL, REINFORCEMENTS, PATROL -> {
         }
         default -> {
            return;
         }
      }

      LivingEntity mob = event.getEntity();
      boolean elite = ThreadLocalRandom.current().nextDouble() < this.setting("elite-chance", 0.03);
      double health = elite ? this.setting("elite-health-multiplier", 3.0) : this.setting("health-multiplier", 1.5);
      double damage = elite ? this.setting("elite-damage-multiplier", 1.8) : this.setting("damage-multiplier", 1.3);
      modify(mob.getAttribute(Attribute.MAX_HEALTH), this.healthKey, health - 1.0);
      modify(mob.getAttribute(Attribute.ATTACK_DAMAGE), this.damageKey, damage - 1.0);
      AttributeInstance max = mob.getAttribute(Attribute.MAX_HEALTH);
      if (max != null) {
         mob.setHealth(max.getValue());
      }

      mob.getPersistentDataContainer().set(this.bloodKey, PersistentDataType.BYTE, (byte)(elite ? 2 : 1));
      if (elite) {
         mob.customName(Component.text("Blood-Touched " + mob.getName(), BLOOD, TextDecoration.BOLD));
         mob.setCustomNameVisible(true);
         mob.setGlowing(true);
         mob.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, Integer.MAX_VALUE, 0, true, false));
         mob.setRemoveWhenFarAway(true);
      }
   }

   private static void modify(AttributeInstance instance, NamespacedKey key, double amount) {
      if (instance != null && amount != 0.0) {
         for (AttributeModifier modifier : new ArrayList<>(instance.getModifiers())) {
            if (key.equals(modifier.getKey())) {
               instance.removeModifier(modifier);
            }
         }

         instance.addModifier(new AttributeModifier(key, amount, AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
      }
   }

   @EventHandler
   public void onMobDeath(EntityDeathEvent event) {
      Byte tag = event.getEntity().getPersistentDataContainer().get(this.bloodKey, PersistentDataType.BYTE);
      Player killer = event.getEntity().getKiller();
      if (tag == null || killer == null) {
         return;
      }

      List<ItemStack> extra = new ArrayList<>();
      for (ItemStack drop : event.getDrops()) {
         extra.add(drop.clone());
      }

      event.getDrops().addAll(extra);
      event.setDroppedExp(event.getDroppedExp() * 2);
      if (tag == 2) {
         List<String> eliteDrops = this.config() == null ? List.of() : this.config().getStringList("elite-drops");
         event.getDrops().addAll(this.manager.loot().build(eliteDrops));
         ParticleManager.spawn(Particle.DUST, event.getEntity().getLocation().add(0.0, 1.0, 0.0), 20, 0.4, 0.6, 0.4, 0.0, new Particle.DustOptions(Color.fromRGB(180, 0, 0), 1.5F));
      }
   }

   /** Blood bounties, paid in Luminara Tokens. */
   public void onPlayerDeath(Player victim, Player killer) {
      if (!this.active || !victim.getWorld().equals(this.world)) {
         return;
      }

      Double onVictim = this.bounties.remove(victim.getUniqueId());
      this.bountyNames.remove(victim.getUniqueId());
      if (killer == null || killer.equals(victim)) {
         if (onVictim != null) {
            Bukkit.broadcast(Component.text("🩸 " + this.manager.publicName(victim) + " died with a bounty on their head - nobody collects it.", BLOOD));
         }

         return;
      }

      if (onVictim != null) {
         this.payTokens(killer.getUniqueId(), (int)Math.round(onVictim), "Blood bounty on " + this.manager.publicName(victim));
         this.manager.vault().depositSpecs(killer.getUniqueId(), this.manager.publicName(killer),
            this.config() == null ? List.of() : this.config().getStringList("bounty-reward"), "Blood bounty");
         Bukkit.broadcast(Component.text("🩸 " + this.manager.publicName(killer) + " collected the " + Math.round(onVictim) + "-token bounty on " + this.manager.publicName(victim) + ".", BLOOD));
      }

      double step = this.config() == null ? 2.0 : this.config().getDouble("bounty-tokens-per-kill", 2.0);
      double total = this.bounties.merge(killer.getUniqueId(), step, Double::sum);
      this.bountyNames.put(killer.getUniqueId(), this.manager.publicName(killer));
      Bukkit.broadcast(Component.text("🩸 " + this.manager.publicName(killer) + " has spilled blood - a " + Math.round(total) + "-token bounty is on their head!", BLOOD, TextDecoration.BOLD));
      this.plugin.getSoundManager().self(killer, Sound.ENTITY_WITHER_AMBIENT, 0.6F, 1.4F);
   }

   /** Luminara Tokens into someone's reward chest. */
   private void payTokens(UUID id, int tokens, String reason) {
      if (tokens > 0) {
         this.manager.vault().deposit(id, List.of(com.lumiweapons.items.CustomItems.get().create(com.lumiweapons.items.CustomItems.LUMINARA_TOKEN, tokens)), reason);
      }
   }

   private Component hud(Player player) {
      if (!this.active) {
         return null;
      }

      Double mine = this.bounties.get(player.getUniqueId());
      return mine != null
         ? Component.text("🩸 Bounty on you: " + Math.round(mine) + " tokens", BLOOD, TextDecoration.BOLD)
         : (player.getWorld().equals(this.world) ? Component.text("🩸 Blood Moon", BLOOD) : null);
   }

   /** Nobody sleeps through a Blood Moon. */
   @EventHandler(ignoreCancelled = true)
   public void onBed(org.bukkit.event.player.PlayerBedEnterEvent event) {
      if (this.active && event.getPlayer().getWorld().equals(this.world)) {
         event.setCancelled(true);
         com.lumiweapons.managers.HudManager.message(event.getPlayer(), Component.text("🩸 You cannot sleep under a Blood Moon.", BLOOD));
      }
   }

   @EventHandler
   public void onWorldChange(PlayerChangedWorldEvent event) {
      if (this.active) {
         this.tint(event.getPlayer(), event.getPlayer().getWorld().equals(this.world));
      }
   }

   public void onJoin(Player player) {
      if (this.active && player.getWorld().equals(this.world)) {
         this.tint(player, true);
      }
   }

   public void stop() {
      if (this.armed || this.active) {
         boolean wasActive = this.active;
         this.set();
         if (wasActive) {
            Bukkit.broadcast(Component.text("☀ The Blood Moon fades.", NamedTextColor.GOLD));
         }
      }
   }

   public String status() {
      if (this.active) {
         return "Blood Moon: UP in " + this.world.getName() + ", " + this.bounties.size() + " blood bounties.";
      }

      return this.armed ? "Blood Moon: armed, rises at the next nightfall in " + this.world.getName() + "." : "Blood Moon: not active.";
   }
}
