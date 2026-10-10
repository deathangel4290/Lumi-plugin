package com.legendaryweapons.events;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.items.CustomItems;
import com.legendaryweapons.managers.HudManager;
import com.legendaryweapons.managers.ParticleManager;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
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
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.entity.ItemMergeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Carry objectives: the Boss Heart and the Star Core.
 *
 * The relic is never a real inventory item, so it cannot be stashed in a chest, an ender chest or
 * a shulker. On the ground it is a glowing, invulnerable item anyone can walk over to take. Its
 * carrier glows through walls, has it floating over their head, is shown on everyone's boss bar and
 * has their rough position announced every few seconds. Survive hold-seconds with it and it is
 * claimed; die and your killer takes it on the spot (or it falls where you died); log out and it
 * drops. While carrying you cannot pearl, chorus, glide, use portals or teleport by command.
 */
public final class RelicManager implements Listener {
   public enum Type {
      BOSS_HEART("boss-heart", "Boss Heart", "💜", TextColor.color(190, 90, 255), BossBar.Color.PURPLE, CustomItems.BOSS_HEART, SeasonManager.Stat.HEARTS, Color.fromRGB(190, 90, 255)),
      STAR_CORE("star-core", "Star Core", "☄", TextColor.color(255, 215, 80), BossBar.Color.YELLOW, CustomItems.STAR_CORE, SeasonManager.Stat.STARS, Color.fromRGB(255, 215, 80));

      public final String key;
      public final String label;
      public final String icon;
      public final TextColor colour;
      final BossBar.Color barColour;
      final String itemId;
      final SeasonManager.Stat stat;
      final Color dust;

      Type(String key, String label, String icon, TextColor colour, BossBar.Color barColour, String itemId, SeasonManager.Stat stat, Color dust) {
         this.key = key;
         this.label = label;
         this.icon = icon;
         this.colour = colour;
         this.barColour = barColour;
         this.itemId = itemId;
         this.stat = stat;
         this.dust = dust;
      }
   }

   private static final Set<TeleportCause> BLOCKED = EnumSet.of(
      TeleportCause.ENDER_PEARL, TeleportCause.CHORUS_FRUIT, TeleportCause.CONSUMABLE_EFFECT, TeleportCause.COMMAND, TeleportCause.PLUGIN,
      TeleportCause.NETHER_PORTAL, TeleportCause.END_PORTAL, TeleportCause.END_GATEWAY, TeleportCause.SPECTATE
   );

   public final class Relic {
      final Type type;
      final String source;
      final Consumer<Player> onClaim;
      final BossBar bar;
      Location lastSafe;
      Item ground;
      long groundSince;
      UUID carrier;
      String carrierName;
      long carrierSince;
      ItemDisplay halo;
      long lastPing;
      World ticketWorld;
      int ticketX;
      int ticketZ;
      boolean done;

      Relic(Type type, Location at, String source, Consumer<Player> onClaim) {
         this.type = type;
         this.source = source;
         this.onClaim = onClaim;
         this.lastSafe = at.clone();
         this.bar = BossBar.bossBar(Component.empty(), 1.0F, type.barColour, BossBar.Overlay.PROGRESS);
      }

      public boolean isDone() {
         return this.done;
      }
   }

   private final LegendaryWeapons plugin;
   private final EventManager manager;
   private final List<Relic> relics = new ArrayList<>();
   private final Set<UUID> teleportBypass = new HashSet<>();

   public RelicManager(LegendaryWeapons plugin, EventManager manager) {
      this.plugin = plugin;
      this.manager = manager;
      plugin.getServer().getPluginManager().registerEvents(this, plugin);
      (new BukkitRunnable() {
         public void run() {
            RelicManager.this.tick();
         }
      }).runTaskTimer(plugin, 5L, 5L);
      plugin.getHudManager().register(this::hud);
   }

   private ConfigurationSection settings(Type type) {
      return this.plugin.getConfig().getConfigurationSection("events.relics." + type.key);
   }

   private double setting(Type type, String key, double fallback) {
      ConfigurationSection own = this.settings(type);
      return own != null && own.contains(key) ? own.getDouble(key) : this.plugin.getConfig().getDouble("events.relics." + key, fallback);
   }

   private int holdSeconds(Type type) {
      return (int)Math.max(5.0, this.setting(type, "hold-seconds", 60.0));
   }

   /** Drop a relic into the world. {@code onClaim} runs once, for whoever finally claims it. */
   public Relic spawn(Type type, Location at, String source, Consumer<Player> onClaim) {
      Relic relic = new Relic(type, at, source, onClaim);
      this.relics.add(relic);
      for (Player player : Bukkit.getOnlinePlayers()) {
         player.showBossBar(relic.bar);
      }

      Bukkit.broadcast(Component.text(type.icon + " THE " + type.label.toUpperCase() + " HAS FALLEN", type.colour, TextDecoration.BOLD));
      Bukkit.broadcast(Component.text("Take it and survive " + this.holdSeconds(type) + " seconds to claim it. Anyone can take it from you.", NamedTextColor.GRAY));
      this.dropAt(relic, at);
      return relic;
   }

   public boolean isCarrier(Player player) {
      for (Relic relic : this.relics) {
         if (!relic.done && player.getUniqueId().equals(relic.carrier)) {
            return true;
         }
      }

      return false;
   }

   /** Let our own abilities move a carrier (e.g. a boss swapping players) without the block kicking in. */
   public void teleportAllowed(Player player, Location to) {
      this.teleportBypass.add(player.getUniqueId());
      try {
         player.teleport(to);
      } finally {
         this.teleportBypass.remove(player.getUniqueId());
      }
   }

   private ItemStack look(Type type) {
      ItemStack item = CustomItems.get().create(type.itemId, 1);
      return item != null ? item : new ItemStack(org.bukkit.Material.NETHER_STAR);
   }

   private void dropAt(Relic relic, Location at) {
      this.clearCarrier(relic);
      Location spot = at.clone().add(0.0, 0.5, 0.0);
      if (spot.getY() < spot.getWorld().getMinHeight() + 1) {
         spot = relic.lastSafe.clone();
      }

      relic.lastSafe = spot.clone();
      this.ticket(relic, spot);
      relic.ground = spot.getWorld().dropItem(spot, this.look(relic.type), item -> {
         item.setUnlimitedLifetime(true);
         item.setInvulnerable(true);
         item.setGlowing(true);
         item.setCanMobPickup(false);
         item.setPickupDelay(10);
         item.setPersistent(false);
         item.setVelocity(new Vector(0.0, 0.15, 0.0));
         item.customName(Component.text(relic.type.icon + " " + relic.type.label, relic.type.colour, TextDecoration.BOLD));
         item.setCustomNameVisible(true);
      });
      relic.groundSince = System.currentTimeMillis();
   }

   /** Keep the chunk a grounded relic lies in loaded, so it cannot vanish with an unloading chunk. */
   private void ticket(Relic relic, Location at) {
      this.unticket(relic);
      if (at != null && at.getWorld() != null) {
         relic.ticketWorld = at.getWorld();
         relic.ticketX = at.getBlockX() >> 4;
         relic.ticketZ = at.getBlockZ() >> 4;
         relic.ticketWorld.addPluginChunkTicket(relic.ticketX, relic.ticketZ, this.plugin);
      }
   }

   private void unticket(Relic relic) {
      if (relic.ticketWorld != null) {
         relic.ticketWorld.removePluginChunkTicket(relic.ticketX, relic.ticketZ, this.plugin);
         relic.ticketWorld = null;
      }
   }

   private void clearCarrier(Relic relic) {
      if (relic.halo != null) {
         if (relic.halo.isValid()) {
            relic.halo.remove();
         }

         relic.halo = null;
      }

      if (relic.carrier != null) {
         UUID previous = relic.carrier;
         relic.carrier = null;
         Player player = Bukkit.getPlayer(previous);
         if (player != null && !this.isCarrier(player)) {
            player.setGlowing(false);
         }
      }
   }

   private void assign(Relic relic, Player player, Player from) {
      if (relic.ground != null && relic.ground.isValid()) {
         relic.ground.remove();
      }

      relic.ground = null;
      this.unticket(relic);
      this.clearCarrier(relic);
      relic.carrier = player.getUniqueId();
      relic.carrierName = this.manager.publicName(player);
      relic.carrierSince = System.currentTimeMillis();
      relic.lastPing = 0L;
      player.setGlowing(true);
      ItemStack look = this.look(relic.type);
      relic.halo = player.getWorld().spawn(player.getLocation(), ItemDisplay.class, display -> {
         display.setItemStack(look);
         display.setPersistent(false);
         display.setBillboard(Display.Billboard.FIXED);
         display.setTransformation(new Transformation(new Vector3f(0.0F, 0.75F, 0.0F), new Quaternionf(), new Vector3f(0.6F, 0.6F, 0.6F), new Quaternionf()));
         display.setBrightness(new Display.Brightness(15, 15));
      });
      player.addPassenger(relic.halo);
      int hold = this.holdSeconds(relic.type);
      player.showTitle(Title.title(Component.text("HOLD THE " + relic.type.label.toUpperCase(), relic.type.colour, TextDecoration.BOLD), Component.text("Survive " + hold + " seconds", NamedTextColor.GRAY)));
      this.plugin.getSoundManager().self(player, Sound.BLOCK_BEACON_POWER_SELECT, 1.0F, 0.7F);
      if (from != null) {
         Bukkit.broadcast(Component.text(relic.type.icon + " " + relic.carrierName + " ripped the " + relic.type.label + " from " + this.manager.publicName(from) + "!", relic.type.colour));
         this.manager.season().renown(player, "relic-steal", 1.0, "for stealing the " + relic.type.label);
      } else {
         Bukkit.broadcast(Component.text(relic.type.icon + " " + relic.carrierName + " has taken the " + relic.type.label + "! " + hold + " seconds to claim it.", relic.type.colour));
      }
   }

   private void claim(Relic relic, Player player) {
      relic.done = true;
      this.unticket(relic);
      this.clearCarrier(relic);
      this.hideBar(relic);
      Bukkit.broadcast(Component.text(relic.type.icon + " THE " + relic.type.label.toUpperCase() + " HAS BEEN CLAIMED", relic.type.colour, TextDecoration.BOLD));
      Bukkit.broadcast(Component.text(this.manager.publicName(player) + " survived with the " + relic.type.label + (relic.source == null ? "" : " of the " + relic.source) + ".", NamedTextColor.GRAY));
      for (Player online : Bukkit.getOnlinePlayers()) {
         online.showTitle(Title.title(Component.text(relic.type.icon + " CLAIMED", relic.type.colour, TextDecoration.BOLD), Component.text(this.manager.publicName(player), NamedTextColor.WHITE)));
      }

      Location at = player.getLocation().add(0.0, 1.0, 0.0);
      ParticleManager.showcase(Particle.FIREWORK, at, 60, 0.6, 1.0, 0.6, 0.15, null);
      ParticleManager.showcase(Particle.END_ROD, at, 30, 0.4, 1.2, 0.4, 0.05, null);
      this.plugin.getSoundManager().world(at, Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0F, 1.0F);
      this.plugin.getSoundManager().world(at, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1.0F, 1.0F);
      ConfigurationSection settings = this.settings(relic.type);
      this.manager.vault().deposit(player.getUniqueId(), this.manager.loot().roll(settings == null ? null : settings.getConfigurationSection("rewards")), relic.type.label + " claimed");
      this.manager.season().add(player, relic.type.stat, 1.0);
      this.manager.season().renown(player, relic.type.key + "-claimed", 1.0, "for claiming the " + relic.type.label);
      if (relic.onClaim != null) {
         try {
            relic.onClaim.accept(player);
         } catch (RuntimeException var7) {
            this.plugin.getLogger().warning("Relic claim callback failed: " + var7);
         }
      }
   }

   private void lose(Relic relic, String why) {
      relic.done = true;
      this.unticket(relic);
      if (relic.ground != null && relic.ground.isValid()) {
         relic.ground.remove();
      }

      this.clearCarrier(relic);
      this.hideBar(relic);
      Bukkit.broadcast(Component.text(relic.type.icon + " " + why, NamedTextColor.GRAY));
   }

   private void hideBar(Relic relic) {
      for (Player player : Bukkit.getOnlinePlayers()) {
         player.hideBossBar(relic.bar);
      }
   }

   private static boolean eligible(Player player) {
      return player.isOnline() && !player.isDead() && (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE);
   }

   private void tick() {
      long now = System.currentTimeMillis();
      Iterator<Relic> it = this.relics.iterator();
      while (it.hasNext()) {
         Relic relic = it.next();
         if (relic.done) {
            it.remove();
            continue;
         }

         if (relic.carrier != null) {
            Player carrier = Bukkit.getPlayer(relic.carrier);
            if (carrier == null || !eligible(carrier)) {
               this.dropAt(relic, carrier != null ? carrier.getLocation() : relic.lastSafe);
               continue;
            }

            relic.lastSafe = carrier.getLocation();
            int hold = this.holdSeconds(relic.type);
            double left = hold - (now - relic.carrierSince) / 1000.0;
            if (left <= 0.0) {
               this.claim(relic, carrier);
               continue;
            }

            if (relic.halo != null && !relic.halo.isValid() || relic.halo != null && !carrier.getPassengers().contains(relic.halo)) {
               if (relic.halo.isValid()) {
                  relic.halo.remove();
               }

               ItemStack look = this.look(relic.type);
               relic.halo = carrier.getWorld().spawn(carrier.getLocation(), ItemDisplay.class, display -> {
                  display.setItemStack(look);
                  display.setPersistent(false);
                  display.setTransformation(new Transformation(new Vector3f(0.0F, 0.75F, 0.0F), new Quaternionf(), new Vector3f(0.6F, 0.6F, 0.6F), new Quaternionf()));
                  display.setBrightness(new Display.Brightness(15, 15));
               });
               carrier.addPassenger(relic.halo);
            }

            if (relic.halo != null) {
               Transformation t = relic.halo.getTransformation();
               relic.halo.setInterpolationDelay(0);
               relic.halo.setInterpolationDuration(5);
               relic.halo.setTransformation(new Transformation(t.getTranslation(), new Quaternionf().rotationY((float)(now % 4000L / 4000.0 * Math.PI * 2.0)), t.getScale(), t.getRightRotation()));
            }

            Particle.DustOptions dust = new Particle.DustOptions(relic.type.dust, 1.1F);
            double angle = now % 1000L / 1000.0 * Math.PI * 2.0;
            Location body = carrier.getLocation();
            for (int i = 0; i < 2; i++) {
               double a = angle + i * Math.PI;
               ParticleManager.showcase(Particle.DUST, body.clone().add(Math.cos(a) * 0.6, 0.3 + (now % 1500L) / 1000.0, Math.sin(a) * 0.6), 1, 0.0, 0.0, 0.0, 0.0, dust);
            }

            relic.bar.name(Component.text(relic.type.icon + " " + relic.type.label + " - ", relic.type.colour)
               .append(Component.text(relic.carrierName, NamedTextColor.WHITE))
               .append(Component.text("  " + (int)Math.ceil(left) + "s", NamedTextColor.YELLOW)));
            relic.bar.progress((float)Math.max(0.0, Math.min(1.0, left / hold)));
            long pingMillis = (long)(this.setting(relic.type, "ping-seconds", 10.0) * 1000.0);
            if (pingMillis > 0L && now - relic.lastPing >= pingMillis) {
               relic.lastPing = now;
               int fuzz = (int)this.setting(relic.type, "ping-accuracy", 6.0);
               ThreadLocalRandom random = ThreadLocalRandom.current();
               int x = body.getBlockX() + (fuzz > 0 ? random.nextInt(-fuzz, fuzz + 1) : 0);
               int z = body.getBlockZ() + (fuzz > 0 ? random.nextInt(-fuzz, fuzz + 1) : 0);
               Bukkit.broadcast(Component.text(relic.type.icon + " " + relic.carrierName + " carries the " + relic.type.label + " near " + x + ", " + body.getBlockY() + ", " + z
                  + " (" + com.legendaryweapons.rituals.Ritual.dimensionName(body.getWorld()) + ") - " + (int)Math.ceil(left) + "s left", relic.type.colour));
            }
         } else {
            if (relic.ground == null || !relic.ground.isValid()) {
               this.dropAt(relic, relic.lastSafe.clone().subtract(0.0, 0.5, 0.0));
            } else if (relic.ground.getLocation().getY() < relic.ground.getWorld().getMinHeight()) {
               relic.ground.teleport(relic.lastSafe);
               relic.ground.setVelocity(new Vector());
            } else if (relic.ground.isOnGround()) {
               relic.lastSafe = relic.ground.getLocation();
               if (relic.ticketWorld != null && ((relic.lastSafe.getBlockX() >> 4) != relic.ticketX || (relic.lastSafe.getBlockZ() >> 4) != relic.ticketZ)) {
                  this.ticket(relic, relic.lastSafe);   // it rolled into the next chunk
               }
            }

            double timeout = this.setting(relic.type, "ground-timeout-seconds", 180.0);
            double left = timeout - (now - relic.groundSince) / 1000.0;
            if (left <= 0.0) {
               this.lose(relic, "Nobody took the " + relic.type.label + ". It crumbles to dust.");
               continue;
            }

            Location at = relic.ground != null ? relic.ground.getLocation() : relic.lastSafe;
            ParticleManager.showcase(Particle.END_ROD, at.clone().add(0.0, 0.5 + Math.random() * 12.0, 0.0), 1, 0.03, 0.0, 0.03, 0.0, null);
            relic.bar.name(Component.text(relic.type.icon + " The " + relic.type.label + " lies at " + at.getBlockX() + ", " + at.getBlockY() + ", " + at.getBlockZ() + " - take it!", relic.type.colour));
            relic.bar.progress((float)Math.max(0.0, Math.min(1.0, left / timeout)));
         }
      }
   }

   private Component hud(Player player) {
      for (Relic relic : this.relics) {
         if (!relic.done && player.getUniqueId().equals(relic.carrier)) {
            long left = Math.max(0L, this.holdSeconds(relic.type) - (System.currentTimeMillis() - relic.carrierSince) / 1000L);
            return Component.text(relic.type.icon + " HOLD " + left + "s", relic.type.colour, TextDecoration.BOLD);
         }
      }

      return null;
   }

   private Relic relicOf(Item item) {
      for (Relic relic : this.relics) {
         if (!relic.done && item.equals(relic.ground)) {
            return relic;
         }
      }

      return null;
   }

   @EventHandler(priority = EventPriority.HIGHEST)
   public void onPickup(EntityPickupItemEvent event) {
      Relic relic = this.relicOf(event.getItem());
      if (relic != null) {
         event.setCancelled(true);
         if (event.getEntity() instanceof Player player && eligible(player)) {
            this.assign(relic, player, null);
         }
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST)
   public void onHopper(InventoryPickupItemEvent event) {
      if (this.relicOf(event.getItem()) != null) {
         event.setCancelled(true);
      }
   }

   @EventHandler(priority = EventPriority.HIGHEST)
   public void onMerge(ItemMergeEvent event) {
      if (this.relicOf(event.getEntity()) != null || this.relicOf(event.getTarget()) != null) {
         event.setCancelled(true);
      }
   }

   @EventHandler(priority = EventPriority.MONITOR)
   public void onDeath(PlayerDeathEvent event) {
      Player victim = event.getEntity();
      Player killer = victim.getKiller();
      for (Relic relic : new ArrayList<>(this.relics)) {
         if (!relic.done && victim.getUniqueId().equals(relic.carrier)) {
            if (killer != null && !killer.equals(victim) && eligible(killer)) {
               this.assign(relic, killer, victim);
            } else {
               this.dropAt(relic, victim.getLocation());
               Bukkit.broadcast(Component.text(relic.type.icon + " " + this.manager.publicName(victim) + " fell. The " + relic.type.label + " lies on the ground!", relic.type.colour));
            }
         }
      }
   }

   @EventHandler
   public void onQuit(PlayerQuitEvent event) {
      for (Relic relic : this.relics) {
         if (!relic.done && event.getPlayer().getUniqueId().equals(relic.carrier)) {
            this.dropAt(relic, event.getPlayer().getLocation());
            Bukkit.broadcast(Component.text(relic.type.icon + " The carrier fled the server. The " + relic.type.label + " has dropped!", relic.type.colour));
         }
      }

   }

   public void onJoin(Player player) {
      for (Relic relic : this.relics) {
         if (!relic.done) {
            player.showBossBar(relic.bar);
         }
      }
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onGameMode(PlayerGameModeChangeEvent event) {
      if (event.getNewGameMode() == GameMode.CREATIVE || event.getNewGameMode() == GameMode.SPECTATOR) {
         for (Relic relic : this.relics) {
            if (!relic.done && event.getPlayer().getUniqueId().equals(relic.carrier)) {
               this.dropAt(relic, event.getPlayer().getLocation());
            }
         }
      }
   }

   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onTeleport(PlayerTeleportEvent event) {
      if (BLOCKED.contains(event.getCause()) && !this.teleportBypass.contains(event.getPlayer().getUniqueId())
         && this.isCarrier(event.getPlayer()) && this.plugin.getConfig().getBoolean("events.relics.block-teleports", true)) {
         event.setCancelled(true);
         HudManager.message(event.getPlayer(), Component.text("The relic holds you in place - no teleporting.", NamedTextColor.RED));
      }
   }

   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onPortal(PlayerPortalEvent event) {
      if (this.isCarrier(event.getPlayer()) && this.plugin.getConfig().getBoolean("events.relics.block-teleports", true)) {
         event.setCancelled(true);
         HudManager.message(event.getPlayer(), Component.text("The relic will not leave this world.", NamedTextColor.RED));
      }
   }

   @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
   public void onGlide(EntityToggleGlideEvent event) {
      if (event.isGliding() && event.getEntity() instanceof Player player && this.isCarrier(player) && this.plugin.getConfig().getBoolean("events.relics.block-elytra", true)) {
         event.setCancelled(true);
         HudManager.message(player, Component.text("The relic is too heavy to fly with.", NamedTextColor.RED));
      }
   }

   public List<String> status() {
      List<String> lines = new ArrayList<>();
      for (Relic relic : this.relics) {
         if (!relic.done) {
            lines.add(relic.type.label + ": " + (relic.carrier != null ? "carried by " + relic.carrierName : "on the ground at " + relic.lastSafe.getBlockX() + ", " + relic.lastSafe.getBlockY() + ", " + relic.lastSafe.getBlockZ()));
         }
      }

      return lines;
   }

   public void shutdown() {
      for (Relic relic : this.relics) {
         if (!relic.done) {
            relic.done = true;
            this.unticket(relic);
            if (relic.ground != null && relic.ground.isValid()) {
               relic.ground.remove();
            }

            this.clearCarrier(relic);
            this.hideBar(relic);
         }
      }

      this.relics.clear();
   }
}
