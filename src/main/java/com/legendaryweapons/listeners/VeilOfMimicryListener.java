package com.legendaryweapons.listeners;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.abilities.AbstractWeaponListener;
import com.legendaryweapons.guis.VeilGUI;
import com.legendaryweapons.managers.DisguiseManager;
import com.legendaryweapons.managers.HudManager;
import com.legendaryweapons.managers.ParticleManager;
import io.papermc.paper.event.player.AsyncChatEvent;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

/**
 * Veil of Mimicry.
 *
 * Rules, all configurable:
 *   - The morph fails if any player within witness-range (20) blocks, facing you, has line of
 *     sight to you. A failed attempt costs a short cooldown; a successful morph the long one (4m).
 *   - Morphing makes a sound everyone within sound-mask-range (16) blocks hears, so stealth
 *     needs distance, not just breaking line of sight.
 *   - The disguise breaks when you attack, take 4+ hearts in one hit, or use the book again.
 *     Lighter hits have a chance to show a brief particle "glitch" around you.
 *
 * Anti-framing: chat from a disguised player is shown under the disguise name, but the real
 * sender (name + UUID) is written to the console and to plugins/LegendaryWeapons/veil-chat.log,
 * and staff with legendaryweapons.veil.spy see who it really was.
 */
public class VeilOfMimicryListener extends AbstractWeaponListener {
   private static final String COOLDOWN_KEY = "veil-morph";
   private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
   private final VeilGUI gui;
   private final File chatLog;

   public VeilOfMimicryListener(LegendaryWeapons plugin) {
      super(plugin);
      this.gui = new VeilGUI(plugin, this::morphInto);
      this.chatLog = new File(plugin.getDataFolder(), "veil-chat.log");
      plugin.getHudManager().register(player -> {
         String fake = plugin.getDisguiseManager().fakeNameOf(player);
         return fake == null ? null : Component.text("Disguised as ", NamedTextColor.DARK_PURPLE).append(Component.text(fake, NamedTextColor.LIGHT_PURPLE));
      });
   }

   @Override
   public String weaponKey() {
      return "veil-of-mimicry";
   }

   @Override
   public Component hud(Player player) {
      return this.plugin.getDisguiseManager().isDisguised(player) ? null : HudManager.cooldown("Morph", this.cooldownLeft(player, COOLDOWN_KEY));
   }

   @EventHandler(priority = EventPriority.LOWEST)
   public void onJoin(PlayerJoinEvent event) {
      this.plugin.getPlayerRosterManager().record(event.getPlayer());
   }

   @Override
   public boolean trigger(Player player, boolean secondary) {
      if (!this.isHolding(player)) {
         return false;
      } else if (this.plugin.getDisguiseManager().isDisguised(player)) {
         this.reveal(player, "You drop the disguise.");
         return true;
      } else if (this.gui.candidateCount(player) == 0) {
         this.explainEmptyRoster(player);
         return true;
      } else if (this.plugin.getCooldownManager().isOnCooldown(player, COOLDOWN_KEY)) {
         this.plugin.getCooldownManager().notifyOnCooldown(player, COOLDOWN_KEY);
         return true;
      } else {
         Player witness = this.findWitness(player);
         if (witness != null) {
            this.failMorph(player, witness);
         } else {
            this.gui.open(player, 0);
         }

         return true;
      }
   }

   private void failMorph(Player player, Player witness) {
      HudManager.message(player, Component.text(witness.getName() + " is watching you - the morph fails.", NamedTextColor.RED));
      this.plugin.getSoundManager().self(player, Sound.BLOCK_FIRE_EXTINGUISH, 0.7F, 1.3F);
      ParticleManager.spawn(Particle.SMOKE, player.getLocation().add(0.0, 1.0, 0.0), 12, 0.3, 0.5, 0.3, 0.02);
      this.plugin.getCooldownManager().setWithHeldItemFeedback(player, COOLDOWN_KEY, this.abilityLong("failed-morph-cooldown", 200L));
   }

   private void explainEmptyRoster(Player player) {
      int roster = this.plugin.getPlayerRosterManager().size();
      int hiddenBecauseOnline = this.gui.excludedOnlineCount(player);
      Component message;
      if (hiddenBecauseOnline > 0) {
         message = Component.text("No faces available - " + hiddenBecauseOnline + " hidden because they are online.", NamedTextColor.RED);
      } else if (roster <= 1) {
         message = Component.text("No other players have joined this server yet.", NamedTextColor.RED);
      } else {
         message = Component.text("No faces available.", NamedTextColor.RED);
      }

      HudManager.message(player, message);
      player.sendMessage(message);
   }

   private Player findWitness(Player player) {
      double range = this.ability("witness-range", 20.0);
      double cosLimit = Math.cos(Math.toRadians(this.ability("witness-fov-degrees", 110.0) / 2.0));

      for (Player other : Bukkit.getOnlinePlayers()) {
         if (!other.equals(player) && other.getWorld().equals(player.getWorld()) && !other.isDead() && other.getLocation().distance(player.getLocation()) <= range && other.canSee(player)) {
            Vector toPlayer = player.getEyeLocation().toVector().subtract(other.getEyeLocation().toVector());
            if (toPlayer.lengthSquared() < 1.0E-4) {
               return other;
            }

            double facing = other.getEyeLocation().getDirection().normalize().dot(toPlayer.normalize());
            if (facing >= cosLimit && other.hasLineOfSight(player)) {
               return other;
            }
         }
      }

      return null;
   }

   private void morphInto(Player player, String targetName) {
      if (player.isOnline() && !this.plugin.getDisguiseManager().isDisguised(player)) {
         Player witness = this.findWitness(player);
         if (witness != null) {
            this.failMorph(player, witness);
         } else {
            UUID targetId = this.plugin.getPlayerRosterManager().idOf(targetName);
            HudManager.message(player, Component.text("Taking the face of " + targetName + "...", NamedTextColor.DARK_PURPLE));
            this.plugin.getDisguiseManager().fetchTextures(targetId, targetName, textures -> {
               if (player.isOnline() && !this.plugin.getDisguiseManager().isDisguised(player)) {
                  if (textures.isEmpty() && (this.abilities() == null || this.abilities().getBoolean("require-skin", true))) {
                     HudManager.message(player, Component.text("That face would not hold (no skin found for " + targetName + ").", NamedTextColor.RED));
                  } else if (this.plugin.getDisguiseManager().apply(player, targetName, textures)) {
                     this.plugin.getCooldownManager().setWithHeldItemFeedback(player, COOLDOWN_KEY, this.abilityLong("morph-cooldown", 4800L));
                     Bukkit.broadcast(Component.translatable("multiplayer.player.joined", NamedTextColor.YELLOW, Component.text(targetName)));
                     HudManager.message(player, Component.text("You are " + targetName + ".", NamedTextColor.LIGHT_PURPLE));
                     Location loc = player.getLocation().add(0.0, 1.0, 0.0);
                     ParticleManager.spawn(Particle.REVERSE_PORTAL, loc, 20, 0.3, 0.6, 0.3, 0.03);
                     this.soundMask(player);
                     this.plugin.getLogger().info("[Veil] " + this.plugin.getDisguiseManager().realNameOf(player) + " (" + player.getUniqueId() + ") is now disguised as " + targetName);
                  }
               }
            });
         }
      }
   }

   /** Everyone close enough hears the veil tear, whether or not they could see it. */
   private void soundMask(Player player) {
      double range = this.ability("sound-mask-range", 16.0);

      for (Player other : player.getWorld().getPlayers()) {
         if (!other.equals(player) && other.getLocation().distance(player.getLocation()) <= range) {
            other.playSound(player.getLocation(), Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.8F, 0.8F);
         }
      }

      player.playSound(player.getLocation(), Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.6F, 0.8F);
   }

   private void reveal(Player player, String reasonForSelf) {
      DisguiseManager.Disguise disguise = this.plugin.getDisguiseManager().remove(player);
      if (disguise != null) {
         Bukkit.broadcast(Component.translatable("multiplayer.player.left", NamedTextColor.YELLOW, Component.text(disguise.fakeName())));
         if (reasonForSelf != null) {
            HudManager.message(player, Component.text(reasonForSelf, NamedTextColor.GRAY));
         }

         Location loc = player.getLocation();
         ParticleManager.spawn(Particle.SMOKE, loc.clone().add(0.0, 1.0, 0.0), 20, 0.35, 0.7, 0.35, 0.03);
         this.plugin.getSoundManager().world(loc, Sound.ENTITY_ENDERMAN_TELEPORT, 0.6F, 0.8F);
      }
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onAttack(EntityDamageByEntityEvent event) {
      if (event.getDamager() instanceof Player attacker && this.plugin.getDisguiseManager().isDisguised(attacker) && !this.plugin.getEventManager().isMostWanted(attacker)) {
         this.reveal(attacker, "Striking broke your disguise.");
      }
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onDamaged(EntityDamageEvent event) {
      if (event.getEntity() instanceof Player player && this.plugin.getDisguiseManager().isDisguised(player) && !this.plugin.getEventManager().isMostWanted(player)) {
         if (event.getFinalDamage() >= this.ability("break-damage-threshold", 8.0)) {
            this.reveal(player, "The hit broke your disguise.");
         } else if (event instanceof EntityDamageByEntityEvent && Math.random() < this.ability("tell-chance", 0.25)) {
            this.showTell(player);
         }
      }
   }

   /**
    * The tell: for a moment the disguise flickers -- a crackle of dark particles over the body and
    * a faint glitch sound. No text, nothing that reads as a UI element; you have to notice it.
    */
   private void showTell(final Player player) {
      final int duration = this.abilityInt("tell-duration", 20);
      final Particle.DustOptions dark = new Particle.DustOptions(Color.fromRGB(40, 0, 60), 0.9F);
      final Particle.DustOptions light = new Particle.DustOptions(Color.fromRGB(200, 120, 255), 0.7F);
      this.plugin.getSoundManager().world(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 0.5F, 1.8F);
      (new BukkitRunnable() {
         int ticks = 0;

         public void run() {
            if (this.ticks < duration && player.isOnline()) {
               Location body = player.getLocation().add(0.0, 0.9 + Math.random() * 0.9, 0.0);
               ParticleManager.spawn(Particle.DUST, body, 4, 0.25, 0.35, 0.25, 0.0, this.ticks % 4 == 0 ? light : dark);
               if (this.ticks % 6 == 0) {
                  ParticleManager.spawn(Particle.REVERSE_PORTAL, body, 3, 0.2, 0.3, 0.2, 0.01);
               }

               this.ticks += 2;
            } else {
               this.cancel();
            }
         }
      }).runTaskTimer(this.plugin, 0L, 2L);
   }

   /** Disguised chat is shown under the disguise, and the real sender is always recorded. */
   @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
   public void onChat(AsyncChatEvent event) {
      Player player = event.getPlayer();
      DisguiseManager.Disguise disguise = this.plugin.getDisguiseManager().disguiseOf(player);
      if (disguise != null) {
         final String fake = disguise.fakeName();
         event.renderer((source, displayName, message, viewer) -> Component.translatable("chat.type.text", Component.text(fake), message));
         String plain = PlainTextComponentSerializer.plainText().serialize(event.message());
         String record = "[Veil] " + disguise.realName() + " (" + player.getUniqueId() + ") as " + fake + ": " + plain;
         this.plugin.getLogger().info(record);
         this.appendLog(record);
         Component spy = Component.text("[Veil] ", NamedTextColor.DARK_PURPLE)
            .append(Component.text(disguise.realName(), NamedTextColor.LIGHT_PURPLE))
            .append(Component.text(" is speaking as ", NamedTextColor.GRAY))
            .append(Component.text(fake, NamedTextColor.WHITE));
         this.plugin.getServer().getScheduler().runTask(this.plugin, () -> {
            for (Player staff : Bukkit.getOnlinePlayers()) {
               if (!staff.equals(player) && staff.hasPermission("legendaryweapons.veil.spy")) {
                  staff.sendMessage(spy);
               }
            }
         });
      }
   }

   private synchronized void appendLog(String line) {
      try (PrintWriter out = new PrintWriter(new FileWriter(this.chatLog, true))) {
         out.println(LocalDateTime.now().format(STAMP) + " " + line);
      } catch (IOException var6) {
         this.plugin.getLogger().warning("Could not write veil-chat.log: " + var6.getMessage());
      }
   }

   /** Leaving while disguised: the quit line shows the disguise (it is the name everyone saw). */
   @EventHandler(priority = EventPriority.LOWEST)
   public void onQuit(PlayerQuitEvent event) {
      DisguiseManager.Disguise disguise = this.plugin.getDisguiseManager().remove(event.getPlayer());
      if (disguise != null) {
         event.quitMessage(Component.translatable("multiplayer.player.left", NamedTextColor.YELLOW, Component.text(disguise.fakeName())));
      }
   }
}
