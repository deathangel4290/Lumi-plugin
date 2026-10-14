package com.legendaryweapons.abilities;

import com.legendaryweapons.LegendaryWeapons;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * Damage from abilities, done one way everywhere so it always lands.
 *
 * Two things used to swallow ability hits: the target's invulnerability window after any recent
 * hit (vanilla ignores a smaller hit inside it), and armour plus Protection, which together cut a
 * normal hit on a netherite player to almost nothing. So every ability hit clears the window first,
 * and a configurable share of it can go straight through armour and enchantments. The rest is an
 * ordinary hit from the wielder, so it still counts as their kill and still knocks back.
 *
 * Kills get a death message naming the ability.
 */
public final class AbilityDamage implements Listener {
   private static AbilityDamage instance;
   private static int dealing;
   private final LegendaryWeapons plugin;
   private final Map<UUID, Credit> recent = new HashMap<>();

   private record Credit(UUID killer, String message, long at) {
   }

   private AbilityDamage(LegendaryWeapons plugin) {
      this.plugin = plugin;
   }

   public static void init(LegendaryWeapons plugin) {
      instance = new AbilityDamage(plugin);
      plugin.getServer().getPluginManager().registerEvents(instance, plugin);
   }

   /**
    * True while an ability hit is being dealt. Weapon listeners skip their on-hit effects then: a
    * Slam's damage is not a melee swing, so it must not build Slam charge, count as a backstab, etc.
    */
   public static boolean active() {
      return dealing > 0;
   }

   /**
    * Hit `target` for `amount` from an ability. `pierce` (0..1) of it ignores armour and Protection.
    * `deathMessage` uses {victim} and {killer}, e.g. "{victim} was flattened by {killer}'s Slam";
    * null keeps vanilla's message. Returns the health (and absorption) actually taken.
    */
   public static double hit(Player source, LivingEntity target, double amount, double pierce, Location from, String deathMessage) {
      if (amount <= 0.0 || target == null || target.isDead()) {
         return 0.0;
      }

      dealing++;
      try {
         return deal(source, target, amount, pierce, from, deathMessage);
      } finally {
         dealing--;
      }
   }

   private static double deal(Player source, LivingEntity target, double amount, double pierce, Location from, String deathMessage) {
      double before = target.getHealth() + target.getAbsorptionAmount();
      double piercing = amount * Math.max(0.0, Math.min(1.0, pierce));
      if (instance != null && deathMessage != null && target instanceof Player victim && source != null) {
         instance.recent.put(victim.getUniqueId(), new Credit(source.getUniqueId(), deathMessage, System.currentTimeMillis()));
      }

      if (piercing > 0.0) {
         target.setNoDamageTicks(0);
         DamageSource.Builder builder = DamageSource.builder(DamageType.SONIC_BOOM);
         if (source != null) {
            builder.withCausingEntity(source).withDirectEntity(source);
         }

         if (from != null) {
            builder.withDamageLocation(from);
         }

         target.damage(piercing, builder.build());
      }

      if (amount - piercing > 0.0 && !target.isDead()) {
         target.setNoDamageTicks(0);
         if (source != null) {
            target.damage(amount - piercing, source);
         } else {
            target.damage(amount - piercing);
         }
      }

      double after = target.isDead() ? 0.0 : target.getHealth() + target.getAbsorptionAmount();
      return Math.max(0.0, before - after);
   }

   /** A plain ability hit: no armour piercing, but it still cannot be eaten by invulnerability frames. */
   public static double hit(Player source, LivingEntity target, double amount) {
      return hit(source, target, amount, 0.0, null, null);
   }

   @EventHandler(priority = EventPriority.HIGH)
   public void onDeath(PlayerDeathEvent event) {
      Credit credit = this.recent.remove(event.getEntity().getUniqueId());
      if (credit != null && System.currentTimeMillis() - credit.at() < 500L) {
         Player killer = this.plugin.getServer().getPlayer(credit.killer());
         if (killer != null) {
            String text = credit.message()
               .replace("{victim}", this.plugin.getEventManager().publicName(event.getEntity()))
               .replace("{killer}", this.plugin.getEventManager().publicName(killer));
            event.deathMessage(Component.text(text, NamedTextColor.GOLD));
         }
      }

      if (this.recent.size() > 64) {
         long now = System.currentTimeMillis();
         this.recent.values().removeIf(c -> now - c.at() > 5000L);
      }
   }
}
