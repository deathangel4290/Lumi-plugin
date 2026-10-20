package com.lumiweapons.bosses;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;

/**
 * Temporary arena changes (wither roses, soul soil, flood water). Every change records the block as
 * it was and as it was set; restore() puts back only blocks still exactly as we left them, so
 * nothing a player built during the fight is erased. Records are saved with the boss data so a
 * crash mid-fight still heals the arena on the next start.
 */
public final class TempBlocks {
   private final List<String[]> changes = new ArrayList<>();       // world, x, y, z, original, placed
   private final Set<Block> water = new HashSet<>();

   /** Set a block if it is safe to touch (no containers). Returns false when it was skipped. */
   public boolean set(Block block, Material to) {
      if (block.getState() instanceof TileState || block.getType() == to) {
         return false;
      }

      String original = block.getBlockData().getAsString();
      block.setType(to, false);
      this.changes.add(new String[]{block.getWorld().getName(), String.valueOf(block.getX()), String.valueOf(block.getY()), String.valueOf(block.getZ()), original, block.getBlockData().getAsString()});
      if (to == Material.WATER) {
         this.water.add(block);
      }

      return true;
   }

   /** Water we placed never flows (it would make sources nobody can clean up). */
   public boolean isOurWater(Block block) {
      return this.water.contains(block);
   }

   public int size() {
      return this.changes.size();
   }

   public List<String> serialize() {
      List<String> out = new ArrayList<>();
      for (String[] change : this.changes) {
         out.add(String.join("|", change));
      }

      return out;
   }

   public void load(List<String> lines) {
      for (String line : lines) {
         String[] parts = line.split("\\|", -1);
         if (parts.length == 6) {
            this.changes.add(parts);
         }
      }
   }

   public int restore() {
      int restored = 0;
      for (int i = this.changes.size() - 1; i >= 0; i--) {
         String[] entry = this.changes.get(i);
         World world = Bukkit.getWorld(entry[0]);
         if (world == null) {
            continue;
         }

         try {
            Block block = world.getBlockAt(Integer.parseInt(entry[1]), Integer.parseInt(entry[2]), Integer.parseInt(entry[3]));
            if (block.getBlockData().getAsString().equals(entry[5])) {
               block.setBlockData(Bukkit.createBlockData(entry[4]), false);
               restored++;
            }
         } catch (RuntimeException ignored) {
         }
      }

      this.changes.clear();
      this.water.clear();
      return restored;
   }
}
