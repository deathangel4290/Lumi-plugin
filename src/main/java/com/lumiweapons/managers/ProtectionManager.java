package com.lumiweapons.managers;

import com.lumiweapons.LumiWeapons;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;

/**
 * Region-protection checks for abilities.
 *
 * Damage dealt with LivingEntity#damage(amount, source) already goes through the normal damage
 * event, so WorldGuard/Towny cancel it in no-PvP areas on their own. What they cannot see is
 * everything else an ability does -- knockback, slowness, freezing, pulling, resizing -- so those
 * paths ask {@link #canPvp} first.
 *
 * Both hooks use reflection so the plugin has no hard dependency and still loads without them.
 * If a hook throws once it is switched off and logged, rather than breaking every ability.
 */
public class ProtectionManager {
   private static boolean probing;
   private final LumiWeapons plugin;
   private WorldGuardHook worldGuard;
   private TownyHook towny;

   public ProtectionManager(LumiWeapons plugin) {
      this.plugin = plugin;
   }

   /** Called after the server has finished enabling plugins, so soft dependencies are loaded. */
   public void hook() {
      if (this.plugin.getServer().getPluginManager().isPluginEnabled("WorldGuard")) {
         try {
            this.worldGuard = new WorldGuardHook();
            this.plugin.getLogger().info("Hooked into WorldGuard: abilities respect the pvp flag.");
         } catch (Throwable var3) {
            this.plugin.getLogger().warning("WorldGuard is installed but the hook failed: " + var3);
         }
      }

      if (this.plugin.getServer().getPluginManager().isPluginEnabled("Towny")) {
         try {
            this.towny = new TownyHook();
            this.plugin.getLogger().info("Hooked into Towny: abilities respect town PvP settings.");
         } catch (Throwable var2) {
            this.plugin.getLogger().warning("Towny is installed but the hook failed: " + var2);
         }
      }
   }

   /** True while a synthetic BlockBreakEvent is being fired; our own listeners must ignore it. */
   public static boolean isProbing() {
      return probing;
   }

   public boolean canPvp(Player attacker, Player victim) {
      if (attacker == null || victim == null || attacker.equals(victim)) {
         return true;
      } else if (!victim.getWorld().getPVP() || !attacker.getWorld().getPVP()) {
         return false;
      } else {
         if (this.worldGuard != null) {
            try {
               if (!this.worldGuard.allowsPvp(victim.getLocation(), attacker) || !this.worldGuard.allowsPvp(attacker.getLocation(), attacker)) {
                  return false;
               }
            } catch (Throwable var5) {
               this.plugin.getLogger().warning("WorldGuard check failed, disabling the hook: " + var5);
               this.worldGuard = null;
            }
         }

         if (this.towny != null) {
            try {
               if (this.towny.prevents(attacker, victim)) {
                  return false;
               }
            } catch (Throwable var4) {
               this.plugin.getLogger().warning("Towny check failed, disabling the hook: " + var4);
               this.towny = null;
            }
         }

         return true;
      }
   }

   /**
    * Whether an ability owned by {@code source} may touch {@code target}: never yourself, never a
    * trusted player when the trust system protects abilities, never a player you may not PvP.
    */
   public boolean canAffect(Player source, Entity target) {
      if (target == null || target.equals(source) || !(target instanceof LivingEntity) || target.isDead()) {
         return false;
      } else if (target instanceof Player victim) {
         if (this.plugin.getConfigManager().isPreventAbilityDamage() && this.plugin.getTrustManager().isTrusted(source, victim)) {
            return false;
         } else {
            return this.canPvp(source, victim);
         }
      } else {
         return true;
      }
   }

   /**
    * Whether {@code player} could break the block at {@code location}. Fires a synthetic
    * BlockBreakEvent, which every protection plugin (WorldGuard, Towny, GriefPrevention, Lands...)
    * already understands. Disable with protection.block-probe if a logging plugin records these.
    */
   public boolean canBuild(Player player, Location location) {
      if (player == null || location == null || location.getWorld() == null) {
         return false;
      } else if (!this.plugin.getConfig().getBoolean("protection.block-probe", true)) {
         return true;
      } else {
         Block block = location.getBlock();
         BlockBreakEvent probe = new BlockBreakEvent(block, player);
         probe.setDropItems(false);
         probing = true;

         try {
            this.plugin.getServer().getPluginManager().callEvent(probe);
         } finally {
            probing = false;
         }

         return !probe.isCancelled();
      }
   }

   private static final class WorldGuardHook {
      private final Object regionContainer;
      private final Method createQuery;
      private final Method adaptLocation;
      private final Object worldGuardPlugin;
      private final Method wrapPlayer;
      private final Object pvpFlags;
      private Method testState;

      WorldGuardHook() throws ReflectiveOperationException {
         Class<?> worldGuardClass = Class.forName("com.sk89q.worldguard.WorldGuard");
         Object worldGuard = worldGuardClass.getMethod("getInstance").invoke(null);
         Object platform = worldGuardClass.getMethod("getPlatform").invoke(worldGuard);
         this.regionContainer = platform.getClass().getMethod("getRegionContainer").invoke(platform);
         this.createQuery = this.regionContainer.getClass().getMethod("createQuery");
         this.adaptLocation = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter").getMethod("adapt", Location.class);
         Class<?> pluginClass = Class.forName("com.sk89q.worldguard.bukkit.WorldGuardPlugin");
         this.worldGuardPlugin = pluginClass.getMethod("inst").invoke(null);
         this.wrapPlayer = pluginClass.getMethod("wrapPlayer", Player.class);
         Class<?> stateFlag = Class.forName("com.sk89q.worldguard.protection.flags.StateFlag");
         Object pvp = Class.forName("com.sk89q.worldguard.protection.flags.Flags").getField("PVP").get(null);
         this.pvpFlags = Array.newInstance(stateFlag, 1);
         Array.set(this.pvpFlags, 0, pvp);
      }

      boolean allowsPvp(Location location, Player subject) throws ReflectiveOperationException {
         Object query = this.createQuery.invoke(this.regionContainer);
         if (this.testState == null) {
            for (Method method : query.getClass().getMethods()) {
               if (method.getName().equals("testState") && method.getParameterCount() == 3 && method.getParameterTypes()[2].isArray()) {
                  this.testState = method;
                  break;
               }
            }

            if (this.testState == null) {
               throw new NoSuchMethodException("RegionQuery#testState");
            }
         }

         Object weLocation = this.adaptLocation.invoke(null, location);
         Object localPlayer = this.wrapPlayer.invoke(this.worldGuardPlugin, subject);
         return (Boolean)this.testState.invoke(query, weLocation, localPlayer, this.pvpFlags);
      }
   }

   private static final class TownyHook {
      private final Method preventDamageCall;
      private final boolean withCause;

      TownyHook() throws ReflectiveOperationException {
         Class<?> combatUtil = Class.forName("com.palmergames.bukkit.towny.utils.CombatUtil");
         Method found = null;
         boolean cause = false;

         try {
            found = combatUtil.getMethod("preventDamageCall", Entity.class, Entity.class, DamageCause.class);
            cause = true;
         } catch (NoSuchMethodException var6) {
            for (Method method : combatUtil.getMethods()) {
               if (method.getName().equals("preventDamageCall") && method.getParameterCount() == 2) {
                  found = method;
                  break;
               }
            }
         }

         if (found == null) {
            throw new NoSuchMethodException("CombatUtil#preventDamageCall");
         } else {
            this.preventDamageCall = found;
            this.withCause = cause;
         }
      }

      boolean prevents(Player attacker, Player victim) throws ReflectiveOperationException {
         Object result = this.withCause
            ? this.preventDamageCall.invoke(null, attacker, victim, DamageCause.ENTITY_ATTACK)
            : this.preventDamageCall.invoke(null, attacker, victim);
         return Boolean.TRUE.equals(result);
      }
   }
}
