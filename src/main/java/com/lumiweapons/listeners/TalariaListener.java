package com.lumiweapons.listeners;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.abilities.UltimateWeapon;
import com.lumiweapons.managers.HudManager;
import com.lumiweapons.managers.ParticleManager;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Blade of Talaria -- a sword and its shield in one legendary.
 *
 *   - The shield floats around you while the blade is in your hand. Hold right-click and the blade
 *     blocks like a shield (the blocks_attacks component WeaponManager gives it), so your off-hand
 *     stays free for a totem; the shield swings in front of you while you block.
 *   - Axes still disable it for their normal 5 seconds, but the legendary axes' longer shield
 *     break (Axe of Zeus, Titan's Axe) does not apply to it: see guarding().
 *   - Blocking hits and landing hits fill Divine Ascension: you rise in a burst of light and take
 *     no damage for a few seconds.
 */
public class TalariaListener extends UltimateWeapon {
   public static final String KEY = "blade-of-talaria";
   private final Map<UUID, ItemDisplay> shields = new HashMap<>();
   /** player -> when Divine Ascension ends (ms). */
   private final Map<UUID, Long> ascended = new HashMap<>();
   private long ticks;

   public TalariaListener(LumiWeapons plugin) {
      super(plugin);
      plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 2L, 2L);
   }

   @Override
   public String weaponKey() {
      return KEY;
   }

   @Override
   protected String ultimateName() {
      return "Divine Ascension";
   }

   /** True while this player is blocking with the Blade of Talaria: the legendary axes' long shield break skips them. */
   public static boolean guarding(LumiWeapons plugin, Player player) {
      var weapons = plugin.getWeaponManager();
      ItemStack held = player.getInventory().getItemInMainHand();
      return player.isBlocking() && weapons.isLegendaryWeapon(held) && KEY.equals(weapons.getWeaponKey(held));
   }

   @Override
   public Component hud(Player player) {
      Long until = this.ascended.get(player.getUniqueId());
      if (until != null && until > System.currentTimeMillis()) {
         return Component.text("✦ Ascended " + HudManager.seconds((until - System.currentTimeMillis()) / 1000.0), NamedTextColor.GOLD);
      }

      return this.ultimateHud(player);
   }

   /** The ability key (either way) is Divine Ascension: the blade's other power is the block itself. */
   @Override
   public boolean trigger(Player player, boolean secondary) {
      if (this.isHolding(player) && this.spendUltimate(player)) {
         this.ascend(player);
      }

      return true;
   }

   // ------------------------------------------------------------------ the floating shield

   /** A shield wearing the pack's Talaria shield model (shield-model-data; 0 = a plain shield). */
   private ItemStack shieldItem() {
      ItemStack shield = new ItemStack(Material.SHIELD);
      int model = this.plugin.getConfig().getInt("weapons." + KEY + ".shield-model-data", 22);
      if (model > 0) {
         shield.editMeta(meta -> {
            var data = meta.getCustomModelDataComponent();
            data.setFloats(java.util.List.of((float)model));
            meta.setCustomModelDataComponent(data);
         });
      }

      return shield;
   }

   private void tick() {
      this.ticks += 2L;
      for (Player player : this.plugin.getServer().getOnlinePlayers()) {
         boolean show = this.isHolding(player) && !player.isDead() && player.getGameMode() != GameMode.SPECTATOR
            && !player.hasPotionEffect(PotionEffectType.INVISIBILITY);
         ItemDisplay shield = this.shields.get(player.getUniqueId());
         if (!show) {
            this.dropShield(player.getUniqueId());
            continue;
         }

         if (shield == null || !shield.isValid() || !shield.getWorld().equals(player.getWorld())) {
            this.dropShield(player.getUniqueId());
            shield = player.getWorld().spawn(player.getLocation().add(0.0, 1.0, 0.0), ItemDisplay.class, display -> {
               display.setPersistent(false);
               display.setItemStack(this.shieldItem());
               display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
               display.setBrightness(new Display.Brightness(15, 15));
               display.setTeleportDuration(2);
               display.setShadowRadius(0.0F);
               display.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.75F), new Quaternionf()));
            });
            this.shields.put(player.getUniqueId(), shield);
         }

         this.place(player, shield);
      }

      this.ascensionTick();
   }

   /** Orbiting at chest height; in front of you while you block (hidden from you then, so it never covers your view). */
   private void place(Player player, ItemDisplay shield) {
      Location body = player.getLocation();
      Location at;
      float yaw;
      if (player.isBlocking()) {
         Vector facing = horizontal(body.getDirection()).normalize();
         at = body.clone().add(facing.multiply(0.75)).add(0.0, 1.05, 0.0);
         yaw = body.getYaw();
         player.hideEntity(this.plugin, shield);
      } else {
         double angle = this.ticks * 0.09;
         at = body.clone().add(Math.cos(angle) * 0.95, 1.1 + Math.sin(this.ticks * 0.11) * 0.08, Math.sin(angle) * 0.95);
         yaw = (float)Math.toDegrees(Math.atan2(-Math.cos(angle), Math.sin(angle)));      // face outward from you
         player.showEntity(this.plugin, shield);
      }

      at.setYaw(yaw);
      at.setPitch(0.0F);
      shield.teleport(at);
   }

   private void dropShield(UUID id) {
      ItemDisplay shield = this.shields.remove(id);
      if (shield != null && shield.isValid()) {
         shield.remove();
      }
   }

   /** Plugin disable: every floating shield goes. */
   public void cleanup() {
      for (UUID id : new java.util.ArrayList<>(this.shields.keySet())) {
         this.dropShield(id);
      }
   }

   // ------------------------------------------------------------------ blocking feeds the ultimate

   @SuppressWarnings("deprecation")                          // DamageModifier is the only way to see a block
   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onBlocked(EntityDamageByEntityEvent event) {
      if (!(event.getEntity() instanceof Player victim) || !guarding(this.plugin, victim)
         || !event.isApplicable(EntityDamageEvent.DamageModifier.BLOCKING) || event.getDamage(EntityDamageEvent.DamageModifier.BLOCKING) >= 0.0) {
         return;
      }

      this.chargeUltimate(victim, this.ability("ultimate-charge-per-block", 8.0));
      ItemDisplay shield = this.shields.get(victim.getUniqueId());
      Location flash = shield != null && shield.isValid() ? shield.getLocation() : victim.getEyeLocation();
      ParticleManager.spawn(Particle.WAX_OFF, flash, 6, 0.25, 0.25, 0.25, 0.4);
   }

   // ------------------------------------------------------------------ Divine Ascension

   private void ascend(Player player) {
      long duration = Math.max(20L, this.abilityLong("ascension-duration", 80L));
      this.ascended.put(player.getUniqueId(), System.currentTimeMillis() + duration * 50L);
      player.setVelocity(player.getVelocity().setY(Math.max(0.2, this.ability("ascension-lift", 0.9))));
      player.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, (int)duration + 40, 0, true, false, true));
      Location at = player.getLocation().add(0.0, 1.0, 0.0);
      this.plugin.getSoundManager().world(at, Sound.ITEM_TOTEM_USE, 0.8F, 1.3F);
      this.plugin.getSoundManager().world(at, Sound.BLOCK_BEACON_ACTIVATE, 1.0F, 1.6F);
      ParticleManager.showcase(Particle.FLASH, at, 1, 0.0, 0.0, 0.0, 0.0, Color.fromRGB(0xFFE8A0));
      ParticleManager.showcase(Particle.END_ROD, at, 40, 0.4, 0.8, 0.4, 0.18, null);
      com.lumiweapons.managers.SpriteFx.wave(player.getLocation(), Color.fromRGB(0xE8C46A), 4.0, 8);

      // the light throws back anyone right next to you (ascension-push, 0 = off)
      double push = this.ability("ascension-push", 0.6);
      if (push > 0.0) {
         for (LivingEntity nearby : this.around(player, player.getLocation(), 3.0)) {
            Vector away = horizontal(nearby.getLocation().toVector().subtract(player.getLocation().toVector()));
            nearby.setVelocity(away.lengthSquared() < 1.0E-4 ? new Vector(0.0, push * 0.5, 0.0) : away.normalize().multiply(push).setY(0.3));
         }
      }

      player.sendActionBar(Component.text("✦ Divine Ascension", NamedTextColor.GOLD));
   }

   private void ascensionTick() {
      long now = System.currentTimeMillis();
      for (Map.Entry<UUID, Long> entry : new java.util.ArrayList<>(this.ascended.entrySet())) {
         Player player = this.plugin.getServer().getPlayer(entry.getKey());
         if (player == null || entry.getValue() <= now) {
            this.ascended.remove(entry.getKey());
            if (player != null) {
               HudManager.message(player, Component.text("The light fades.", NamedTextColor.GRAY));
            }

            continue;
         }

         // wings of light behind you
         Location back = player.getLocation().add(0.0, 1.3, 0.0).subtract(horizontal(player.getLocation().getDirection()).normalize().multiply(0.35));
         Vector side = new Vector(-player.getLocation().getDirection().getZ(), 0.0, player.getLocation().getDirection().getX()).normalize();
         Particle.DustOptions gold = new Particle.DustOptions(Color.fromRGB(0xF2D27A), 0.9F);
         for (int i = 1; i <= 4; i++) {
            double spread = i * 0.22;
            double rise = Math.sin(i * 0.6 + this.ticks * 0.3) * 0.12 + i * 0.06;
            ParticleManager.showcase(Particle.DUST, back.clone().add(side.clone().multiply(spread)).add(0.0, rise, 0.0), 1, 0.0, 0.0, 0.0, 0.0, gold);
            ParticleManager.showcase(Particle.DUST, back.clone().add(side.clone().multiply(-spread)).add(0.0, rise, 0.0), 1, 0.0, 0.0, 0.0, 0.0, gold);
         }
      }
   }

   /** Untouchable while ascended (the void and /kill still apply). */
   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onDamaged(EntityDamageEvent event) {
      if (event.getEntity() instanceof Player player) {
         Long until = this.ascended.get(player.getUniqueId());
         EntityDamageEvent.DamageCause cause = event.getCause();
         if (until != null && until > System.currentTimeMillis()
            && cause != EntityDamageEvent.DamageCause.VOID && cause != EntityDamageEvent.DamageCause.KILL && cause != EntityDamageEvent.DamageCause.WORLD_BORDER) {
            event.setCancelled(true);
            ParticleManager.spawn(Particle.WAX_OFF, player.getLocation().add(0.0, 1.0, 0.0), 4, 0.3, 0.5, 0.3, 0.2);
         }
      }
   }

   @EventHandler
   public void onDeath(PlayerDeathEvent event) {
      this.ascended.remove(event.getEntity().getUniqueId());
      this.dropShield(event.getEntity().getUniqueId());
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      this.ascended.remove(event.getPlayer().getUniqueId());
      this.dropShield(event.getPlayer().getUniqueId());
   }
}
