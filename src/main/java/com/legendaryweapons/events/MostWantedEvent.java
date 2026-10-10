package com.legendaryweapons.events;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.items.CustomItems;
import com.legendaryweapons.managers.ParticleManager;
import com.legendaryweapons.rituals.Ritual;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

/**
 * Most Wanted. An admin names a player (they do not have to be online, or have ever joined).
 * When that player joins they are made anonymous straight away -- no join message, a neutral
 * name and the default skin -- and admins are asked to confirm. Once confirmed they get their
 * buffs (20 hearts, Speed II), everyone is told and their rough position is pinged periodically.
 *
 * It ends when they die: a blast throws everyone nearby back, a chest of loot appears where they
 * fell with a Luminosity Fragment in the centre slot, and (by default) they are banned.
 */
public class MostWantedEvent {
   private final LegendaryWeapons plugin;
   private final EventManager manager;
   private final NamespacedKey healthKey;
   private final BossBar bar = BossBar.bossBar(Component.text("Most Wanted"), 1.0F, BossBar.Color.RED, BossBar.Overlay.NOTCHED_20);
   private String pendingName;
   private UUID wantedId;
   private boolean active;
   private BukkitTask task;

   public MostWantedEvent(LegendaryWeapons plugin, EventManager manager) {
      this.plugin = plugin;
      this.manager = manager;
      this.healthKey = new NamespacedKey(plugin, "most_wanted_health");
   }

   private ConfigurationSection config() {
      return this.plugin.getConfig().getConfigurationSection("events.most-wanted");
   }

   public String alias() {
      String alias = this.config() == null ? "MostWanted" : this.config().getString("alias", "MostWanted");
      return alias.matches("[A-Za-z0-9_]{1,16}") ? alias : "MostWanted";
   }

   // ------------------------------------------------------------------ persistence (events.yml)

   void load(ConfigurationSection data) {
      if (data != null) {
         this.pendingName = data.getString("pending-name");
         String id = data.getString("wanted-id");
         this.wantedId = id == null ? null : UUID.fromString(id);
         this.active = data.getBoolean("active", false);
      }
   }

   void save(ConfigurationSection data) {
      data.set("pending-name", this.pendingName);
      data.set("wanted-id", this.wantedId == null ? null : this.wantedId.toString());
      data.set("active", this.active);
   }

   // ------------------------------------------------------------------ state

   public boolean isWanted(Player player) {
      return player != null && (player.getUniqueId().equals(this.wantedId) || this.pendingName != null && this.pendingName.equalsIgnoreCase(this.realName(player)));
   }

   public boolean isActive() {
      return this.active;
   }

   private String realName(Player player) {
      return this.plugin.getDisguiseManager().realNameOf(player);
   }

   public String set(String name) {
      if (this.active) {
         return "A Most Wanted event is already running. Clear it first.";
      } else if (!name.matches("[A-Za-z0-9_]{1,16}")) {
         return "That is not a valid player name.";
      } else {
         this.pendingName = name.toLowerCase(Locale.ROOT);
         this.wantedId = null;
         this.manager.save();
         Player online = Bukkit.getPlayerExact(name);
         if (online != null) {
            this.onWantedJoined(online);
         }

         return null;
      }
   }

   /** Called on join (and on set, if they are already online). */
   public void onWantedJoined(Player player) {
      this.wantedId = player.getUniqueId();
      this.manager.save();
      this.anonymise(player);
      if (this.active) {
         this.applyBuffs(player);
         player.showBossBar(this.bar);
      } else {
         Component prompt = Component.text("[Most Wanted] ", NamedTextColor.RED)
            .append(Component.text(player.getName() + " is online and anonymous. ", NamedTextColor.GRAY))
            .append(Component.text("[Click to start]", NamedTextColor.GREEN, TextDecoration.BOLD).clickEvent(ClickEvent.runCommand("/lwevent mostwanted confirm")));

         for (Player admin : Bukkit.getOnlinePlayers()) {
            if (admin.hasPermission("legendaryweapons.admin") && !admin.equals(player)) {
               admin.sendMessage(prompt);
            }
         }

         this.plugin.getLogger().info("[Most Wanted] " + player.getName() + " joined; waiting for an admin to run /lwevent mostwanted confirm.");
      }
   }

   private void anonymise(Player player) {
      if (!this.plugin.getDisguiseManager().isDisguised(player)) {
         this.plugin.getDisguiseManager().apply(player, this.alias(), Set.of(), true);
      }
   }

   public String confirm() {
      if (this.active) {
         return "The Most Wanted event is already running.";
      } else {
         Player wanted = this.wantedId == null ? null : Bukkit.getPlayer(this.wantedId);
         if (wanted == null) {
            return this.pendingName == null ? "No Most Wanted player is set." : "The Most Wanted player (" + this.pendingName + ") is not online yet.";
         } else {
            this.active = true;
            this.manager.save();
            this.anonymise(wanted);
            this.applyBuffs(wanted);

            for (Player player : Bukkit.getOnlinePlayers()) {
               player.showBossBar(this.bar);
               player.showTitle(Title.title(Component.text("⚠ MOST WANTED ⚠", NamedTextColor.DARK_RED, TextDecoration.BOLD), Component.text("Hunt them down. Their loot is worth it.", NamedTextColor.GRAY)));
               this.plugin.getSoundManager().self(player, Sound.ENTITY_WITHER_SPAWN, 0.4F, 1.4F);
            }

            Bukkit.broadcast(Component.text("⚠ The Most Wanted player has arrived. Whoever takes them down claims their hoard - and a piece of Luminosity.", NamedTextColor.RED));
            this.task = (new BukkitRunnable() {
               int seconds = 0;

               public void run() {
                  MostWantedEvent.this.tick(this.seconds++);
               }
            }).runTaskTimer(this.plugin, 20L, 20L);
            return null;
         }
      }
   }

   private void applyBuffs(Player player) {
      AttributeInstance health = player.getAttribute(Attribute.MAX_HEALTH);
      if (health != null) {
         removeModifier(health, this.healthKey);
         double target = this.config() == null ? 40.0 : this.config().getDouble("max-health", 40.0);
         double bonus = Math.max(0.0, target - health.getValue());
         health.addModifier(new AttributeModifier(this.healthKey, bonus, AttributeModifier.Operation.ADD_NUMBER, EquipmentSlotGroup.ANY));
         player.setHealth(Math.min(health.getValue(), target));
      }

      int speed = this.config() == null ? 1 : this.config().getInt("speed-amplifier", 1);
      player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, -1, speed, false, false, true));
   }

   private void removeBuffs(Player player) {
      AttributeInstance health = player.getAttribute(Attribute.MAX_HEALTH);
      if (health != null) {
         removeModifier(health, this.healthKey);
         if (player.getHealth() > health.getValue()) {
            player.setHealth(health.getValue());
         }
      }

      player.removePotionEffect(PotionEffectType.SPEED);
   }

   private static void removeModifier(AttributeInstance instance, NamespacedKey key) {
      for (AttributeModifier modifier : new ArrayList<>(instance.getModifiers())) {
         if (key.equals(modifier.getKey())) {
            instance.removeModifier(modifier);
         }
      }
   }

   private void tick(int seconds) {
      Player wanted = this.wantedId == null ? null : Bukkit.getPlayer(this.wantedId);
      if (wanted == null) {
         this.bar.name(Component.text("⚠ The Most Wanted is in hiding (offline)", NamedTextColor.GRAY));
      } else {
         AttributeInstance max = wanted.getAttribute(Attribute.MAX_HEALTH);
         double fraction = max == null ? 1.0 : wanted.getHealth() / max.getValue();
         this.bar.progress((float)Math.max(0.0, Math.min(1.0, fraction)));
         this.bar.name(Component.text("⚠ MOST WANTED  ", NamedTextColor.RED, TextDecoration.BOLD)
            .append(Component.text((int)Math.ceil(wanted.getHealth() / 2.0) + " hearts", NamedTextColor.WHITE)));
         int interval = Math.max(10, this.config() == null ? 60 : this.config().getInt("ping-interval-seconds", 60));
         if (seconds > 0 && seconds % interval == 0) {
            int accuracy = Math.max(0, this.config() == null ? 32 : this.config().getInt("ping-accuracy", 32));
            Location at = wanted.getLocation();
            int x = at.getBlockX() + (accuracy == 0 ? 0 : ThreadLocalRandom.current().nextInt(-accuracy, accuracy + 1));
            int z = at.getBlockZ() + (accuracy == 0 ? 0 : ThreadLocalRandom.current().nextInt(-accuracy, accuracy + 1));
            Bukkit.broadcast(Component.text("⚠ The Most Wanted was sighted in the " + Ritual.dimensionName(at.getWorld()) + " near " + x + ", " + z + ".", NamedTextColor.RED));
         }
      }
   }

   /** Returns true if this death ended the event. */
   public boolean onDeath(Player victim, Player killer) {
      if (!this.active || !victim.getUniqueId().equals(this.wantedId)) {
         return false;
      } else {
         Location at = victim.getLocation();
         this.blast(victim, at);
         this.spawnChest(at);
         Bukkit.broadcast(Component.text("⚠ The Most Wanted has fallen" + (killer != null ? " to " + this.manager.publicName(killer) : "") + "! Their hoard lies at "
            + at.getBlockX() + ", " + at.getBlockY() + ", " + at.getBlockZ() + " (" + Ritual.dimensionName(at.getWorld()) + ").", NamedTextColor.GOLD));
         final UUID id = victim.getUniqueId();
         final String realName = this.realName(victim);
         this.end();
         if (this.config() == null || this.config().getBoolean("ban-on-death", true)) {
            this.plugin.getServer().getScheduler().runTaskLater(this.plugin, () -> {
               Player target = Bukkit.getPlayer(id);
               long minutes = this.config() == null ? 0L : this.config().getLong("ban-minutes", 0L);
               Date expires = minutes > 0L ? new Date(System.currentTimeMillis() + minutes * 60000L) : null;
               String reason = this.config() == null ? "You were the Most Wanted, and you were caught." : this.config().getString("ban-reason", "You were the Most Wanted, and you were caught.");
               if (target != null) {
                  target.ban(reason, expires, "LegendaryWeapons", true);
               } else {
                  Bukkit.getOfflinePlayer(id).ban(reason, expires, "LegendaryWeapons");   // quit on the death screen: still banned
               }

               {
                  this.plugin.getLogger().info("[Most Wanted] Banned " + realName + " (" + id + ")" + (expires == null ? " permanently." : " until " + expires + "."));
               }
            }, 2L);
         }

         return true;
      }
   }

   private void blast(Player victim, Location at) {
      double radius = this.config() == null ? 10.0 : this.config().getDouble("explosion-radius", 10.0);
      double knockback = this.config() == null ? 2.5 : this.config().getDouble("explosion-knockback", 2.5);
      ParticleManager.spawn(Particle.EXPLOSION_EMITTER, at, 1, 0.0, 0.0, 0.0, 0.0);
      this.plugin.getSoundManager().world(at, Sound.ENTITY_GENERIC_EXPLODE, 1.0F, 0.7F);

      for (Entity entity : at.getWorld().getNearbyEntities(at, radius, radius, radius)) {
         if (entity instanceof Player player && !player.equals(victim)) {
            Vector away = player.getLocation().toVector().subtract(at.toVector());
            double falloff = Math.max(0.3, 1.0 - away.length() / radius);
            Vector direction = away.lengthSquared() < 1.0E-4 ? new Vector(0, 1, 0) : away.normalize();
            player.setVelocity(direction.multiply(knockback * falloff).setY(0.6 + 0.6 * falloff));
         }
      }
   }

   private void spawnChest(Location at) {
      Location safe = at.clone();
      safe.setY(Math.max(at.getWorld().getMinHeight() + 1, Math.min(at.getWorld().getMaxHeight() - 8, at.getY())));   // void deaths
      Block block = safe.getBlock();

      for (int i = 0; i < 6 && !block.isPassable(); i++) {
         block = block.getRelative(0, 1, 0);
      }

      block.setType(Material.CHEST);
      if (block.getState() instanceof Chest chest) {
         String centre = this.config() == null ? "LUMINOSITY_FRAGMENT" : this.config().getString("centre-item", "LUMINOSITY_FRAGMENT");
         Loot.scatter(chest.getBlockInventory(), this.manager.loot().roll(this.config() == null ? null : this.config().getConfigurationSection("loot")), 13);
         var item = this.manager.loot().item(centre, 1);
         chest.getBlockInventory().setItem(13, item != null ? item : CustomItems.get().create(CustomItems.LUMINOSITY_FRAGMENT, 1));
      }
   }

   private void end() {
      if (this.task != null) {
         this.task.cancel();
         this.task = null;
      }

      for (Player player : Bukkit.getOnlinePlayers()) {
         player.hideBossBar(this.bar);
      }

      Player wanted = this.wantedId == null ? null : Bukkit.getPlayer(this.wantedId);
      if (wanted != null) {
         this.removeBuffs(wanted);
         this.plugin.getDisguiseManager().remove(wanted);
      }

      this.active = false;
      this.pendingName = null;
      this.wantedId = null;
      this.manager.save();
   }

   public String clear() {
      if (this.pendingName == null && this.wantedId == null && !this.active) {
         return "No Most Wanted player is set.";
      } else {
         this.end();
         return null;
      }
   }

   public void onJoinAny(Player player) {
      if (this.active) {
         player.showBossBar(this.bar);
      }
   }

   public void onQuit(Player player) {
      if (player.getUniqueId().equals(this.wantedId) && this.active) {
         this.removeBuffs(player);
      }
   }

   public String status() {
      if (this.pendingName == null && this.wantedId == null) {
         return "Most Wanted: nobody is set.";
      } else {
         Player wanted = this.wantedId == null ? null : Bukkit.getPlayer(this.wantedId);
         String who = wanted != null ? this.realName(wanted) : this.pendingName;
         return "Most Wanted: " + who + (this.active ? " - ACTIVE" : " - waiting for " + (wanted == null ? "them to join" : "/lwevent mostwanted confirm")) + ".";
      }
   }

   public void shutdown() {
      if (this.task != null) {
         this.task.cancel();
         this.task = null;
      }

      Player wanted = this.wantedId == null ? null : Bukkit.getPlayer(this.wantedId);
      if (wanted != null) {
         this.removeBuffs(wanted);
      }
   }
}
