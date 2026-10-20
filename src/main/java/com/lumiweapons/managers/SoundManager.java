package com.lumiweapons.managers;

import com.lumiweapons.LumiWeapons;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;

public class SoundManager {
   private final LumiWeapons plugin;

   public SoundManager(LumiWeapons plugin) {
      this.plugin = plugin;
   }

   private float scale(float volume) {
      return (float)Math.max(0.0, Math.min(1.0, this.plugin.getConfigManager().getMasterSoundVolume())) * volume;
   }

   private boolean silent() {
      return this.plugin.getConfigManager().getMasterSoundVolume() <= 0.0;
   }

   public void world(Location location, Sound sound, float volume, float pitch) {
      if (!this.silent() && location != null && location.getWorld() != null) {
         location.getWorld().playSound(location, sound, this.scale(volume), pitch);
      }
   }

   public void self(Player player, Sound sound, float volume, float pitch) {
      if (!this.silent() && player != null) {
         player.playSound(player.getLocation(), sound, this.scale(volume), pitch);
      }
   }

   public void ambient(Location location, Sound sound, float volume, float pitch) {
      if (this.plugin.getConfigManager().areAmbientSoundsEnabled()) {
         this.world(location, sound, volume, pitch);
      }
   }

   public void hit(Location location, Sound sound, float volume, float pitch) {
      if (this.plugin.getConfigManager().areHitSoundsEnabled()) {
         this.world(location, sound, volume, pitch);
      }
   }
}
