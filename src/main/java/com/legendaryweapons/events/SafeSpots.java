package com.legendaryweapons.events;

import java.util.concurrent.ThreadLocalRandom;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.World.Environment;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;

/** Random reachable ground inside the world border, shared by supply drops and fallen stars. */
public final class SafeSpots {
   private SafeSpots() {
   }

   /** A random safe spot (the block above solid ground), or null after 24 failed tries. */
   public static Location find(World world, double borderMargin, double maxRadius) {
      WorldBorder border = world.getWorldBorder();
      double half = Math.max(8.0, border.getSize() / 2.0 - borderMargin);
      if (maxRadius > 0.0) {
         half = Math.min(half, maxRadius);
      }

      Location centre = border.getCenter();
      for (int attempt = 0; attempt < 24; attempt++) {
         int x = (int)Math.floor(centre.getX() + (ThreadLocalRandom.current().nextDouble() * 2.0 - 1.0) * half);
         int z = (int)Math.floor(centre.getZ() + (ThreadLocalRandom.current().nextDouble() * 2.0 - 1.0) * half);
         Block ground = groundAt(world, x, z);
         if (ground != null) {
            return ground.getLocation().add(0.0, 1.0, 0.0);
         }
      }

      return null;
   }

   /** Top solid, non-liquid block with two free blocks above. The Nether is scanned below its roof. */
   public static Block groundAt(World world, int x, int z) {
      int top = world.getEnvironment() == Environment.NETHER ? Math.min(120, world.getMaxHeight() - 1) : world.getHighestBlockYAt(x, z);

      for (int y = top; y > world.getMinHeight() + 1; y--) {
         Block block = world.getBlockAt(x, y, z);
         Material type = block.getType();
         if (!type.isAir()) {
            if (block.isLiquid() || type == Material.LAVA || type == Material.WATER) {
               if (world.getEnvironment() != Environment.NETHER) {
                  return null;
               }
            } else if (!type.isSolid() || type.name().endsWith("_LEAVES")) {
               continue;
            } else if (block.getRelative(0, 1, 0).isPassable() && block.getRelative(0, 2, 0).isPassable() && !block.getRelative(0, 1, 0).isLiquid()) {
               return block;
            }
         }
      }

      return null;
   }
}
