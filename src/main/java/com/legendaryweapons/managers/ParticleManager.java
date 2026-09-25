package com.legendaryweapons.managers;

import com.legendaryweapons.LegendaryWeapons;
import org.bukkit.Location;
import org.bukkit.Particle;

public class ParticleManager {
   private final LegendaryWeapons plugin;

   public ParticleManager(LegendaryWeapons plugin) {
      this.plugin = plugin;
   }

   private boolean enabled() {
      return this.plugin.getConfig().getBoolean("effects.particles", true);
   }

   public double density() {
      double d = this.plugin.getConfig().getDouble("effects.density", 0.5);
      return Math.max(0.0, Math.min(1.0, d));
   }

   public boolean passiveEnabled() {
      return this.enabled() && this.plugin.getConfig().getBoolean("effects.passive-auras", false);
   }

   public boolean trailEnabled() {
      return this.enabled() && this.plugin.getConfig().getBoolean("effects.trails", true);
   }

   public int scale(int count) {
      return !this.enabled() ? 0 : Math.max(1, (int)Math.round(count * this.density()));
   }

   public void burst(Location location, Particle particle, int count, double dx, double dy, double dz, double speed) {
      if (this.enabled() && location.getWorld() != null) {
         location.getWorld().spawnParticle(particle, location, this.scale(count), dx, dy, dz, speed);
      }
   }

   public <T> void burst(Location location, Particle particle, int count, double dx, double dy, double dz, double speed, T data) {
      if (this.enabled() && location.getWorld() != null) {
         location.getWorld().spawnParticle(particle, location, this.scale(count), dx, dy, dz, speed, data);
      }
   }

   public void trail(Location location, Particle particle, int count, double dx, double dy, double dz, double speed) {
      if (this.trailEnabled() && location.getWorld() != null) {
         location.getWorld().spawnParticle(particle, location, this.scale(count), dx, dy, dz, speed);
      }
   }
}
