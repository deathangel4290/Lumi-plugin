package com.legendaryweapons.listeners;

import com.legendaryweapons.LegendaryWeapons;
import com.legendaryweapons.items.CustomItems;
import com.legendaryweapons.managers.ProtectionManager;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

/**
 * DonutSMP-style amethyst tools.
 *
 *   Amethyst Pickaxe / Shovel: break a 3x3 square facing the player (flat when looking up or down).
 *     Only blocks that tool is meant for, never containers, spawners or anything harder than
 *     tools.max-hardness. Each extra block costs durability (Unbreaking applies).
 *   Amethyst Axe: breaking a log fells the whole connected tree, up to tools.axe.max-logs logs, and
 *     clears its natural leaves. It only fires on real trees (logs touching natural leaves), so log
 *     houses are safe.
 *
 * Sneaking mines a single block. Every extra block is checked against WorldGuard/Towny/claims
 * through the same probe the weapons use.
 */
public class AmethystToolsListener implements Listener {
   private final LegendaryWeapons plugin;
   private boolean busy;

   public AmethystToolsListener(LegendaryWeapons plugin) {
      this.plugin = plugin;
   }

   @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
   public void onBreak(BlockBreakEvent event) {
      if (this.busy || ProtectionManager.isProbing()) {
         return;
      }

      Player player = event.getPlayer();
      if (player.getGameMode() == GameMode.SPECTATOR || player.getGameMode() == GameMode.ADVENTURE) {
         return;
      }

      if (player.isSneaking() && this.plugin.getConfig().getBoolean("tools.sneak-to-disable", true)) {
         return;
      }

      ItemStack tool = player.getInventory().getItemInMainHand();
      String id = this.plugin.getCustomItems().idOf(tool);
      if (id == null) {
         return;
      }

      this.busy = true;
      try {
         switch (id) {
            case CustomItems.AMETHYST_PICKAXE -> this.mineSquare(player, event.getBlock(), tool, Tag.MINEABLE_PICKAXE);
            case CustomItems.AMETHYST_SHOVEL -> this.mineSquare(player, event.getBlock(), tool, Tag.MINEABLE_SHOVEL);
            case CustomItems.AMETHYST_AXE -> this.fellTree(player, event.getBlock(), tool);
            default -> {
            }
         }
      } finally {
         this.busy = false;
      }
   }

   private boolean breakable(Block block, Tag<Material> tag) {
      Material type = block.getType();
      if (type.isAir() || !tag.isTagged(type)) {
         return false;
      }

      float hardness = type.getHardness();
      if (hardness < 0.0F || hardness > this.plugin.getConfig().getDouble("tools.max-hardness", 40.0)) {
         return false;                                  // bedrock, obsidian and friends
      }

      if (block.getState() instanceof TileState) {
         return false;                                  // chests, spawners, furnaces...
      }

      for (String name : this.plugin.getConfig().getStringList("tools.blacklist")) {
         if (type.name().equalsIgnoreCase(name.trim())) {
            return false;
         }
      }

      return true;
   }

   private void mineSquare(Player player, Block origin, ItemStack tool, Tag<Material> tag) {
      if (!tag.isTagged(origin.getType())) {
         return;                                        // a pickaxe swung at dirt only mines the one block
      }

      float pitch = player.getLocation().getPitch();
      float yaw = ((player.getLocation().getYaw() % 360.0F) + 360.0F) % 360.0F;
      boolean vertical = Math.abs(pitch) > 50.0F;
      boolean alongX = (yaw >= 45.0F && yaw < 135.0F) || (yaw >= 225.0F && yaw < 315.0F);   // facing east/west
      List<Block> targets = new ArrayList<>();

      for (int a = -1; a <= 1; a++) {
         for (int b = -1; b <= 1; b++) {
            if (a == 0 && b == 0) continue;
            Block block = vertical ? origin.getRelative(a, 0, b) : (alongX ? origin.getRelative(0, b, a) : origin.getRelative(a, b, 0));
            if (this.breakable(block, tag)) {
               targets.add(block);
            }
         }
      }

      this.breakAll(player, tool, targets, true);
   }

   private void fellTree(Player player, Block origin, ItemStack tool) {
      if (!Tag.LOGS.isTagged(origin.getType())) {
         return;
      }

      int maxLogs = Math.max(1, this.plugin.getConfig().getInt("tools.axe.max-logs", 300));
      boolean clearLeaves = this.plugin.getConfig().getBoolean("tools.axe.break-leaves", true);
      List<Block> logs = new ArrayList<>();
      List<Block> leaves = new ArrayList<>();
      Set<Block> seen = new HashSet<>();
      ArrayDeque<Block> queue = new ArrayDeque<>();
      queue.add(origin);
      seen.add(origin);
      boolean natural = false;

      while (!queue.isEmpty() && logs.size() < maxLogs) {
         Block current = queue.poll();
         for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
               for (int dz = -1; dz <= 1; dz++) {
                  Block next = current.getRelative(dx, dy, dz);
                  if (!seen.add(next) || next.getY() < origin.getY() - 1) continue;
                  Material type = next.getType();
                  if (Tag.LOGS.isTagged(type)) {
                     queue.add(next);
                     logs.add(next);
                  } else if (Tag.LEAVES.isTagged(type) && next.getBlockData() instanceof Leaves leaf && !leaf.isPersistent()) {
                     natural = true;
                     if (clearLeaves && leaves.size() < maxLogs * 3) {
                        leaves.add(next);
                     }
                  }
               }
            }
         }
      }

      if (!natural && this.plugin.getConfig().getBoolean("tools.axe.require-leaves", true)) {
         return;                                        // a log wall, not a tree
      }

      this.breakAll(player, tool, logs, true);
      if (!leaves.isEmpty()) {
         this.breakAll(player, null, leaves, false);
      }
   }

   private void breakAll(Player player, ItemStack tool, List<Block> blocks, boolean wearTool) {
      boolean creative = player.getGameMode() == GameMode.CREATIVE;
      int cost = Math.max(0, this.plugin.getConfig().getInt("tools.durability-per-block", 1));

      for (Block block : blocks) {
         if (!this.plugin.getProtectionManager().canBuild(player, block.getLocation())) {
            continue;
         }

         if (wearTool && tool != null) {
            ItemStack held = player.getInventory().getItemInMainHand();
            if (held.getType() != tool.getType() || held.getType().isAir()) {
               return;                                  // the tool broke part-way through
            }

            if (creative) {
               block.setType(Material.AIR);
               continue;
            }

            block.breakNaturally(held, false, true);
            if (cost > 0) {
               player.damageItemStack(org.bukkit.inventory.EquipmentSlot.HAND, cost);   // Unbreaking and breaking handled by the game
            }
         } else {
            if (creative) {
               block.setType(Material.AIR);
            } else {
               block.breakNaturally(null, false, false);
            }
         }
      }
   }

   public static String describe(String id) {
      return switch (id.toLowerCase(Locale.ROOT)) {
         case CustomItems.AMETHYST_PICKAXE -> "Mines a 3x3 area. Sneak to mine one block.";
         case CustomItems.AMETHYST_SHOVEL -> "Digs a 3x3 area. Sneak to dig one block.";
         case CustomItems.AMETHYST_AXE -> "Fells a whole tree at once. Sneak to chop one log.";
         default -> "";
      };
   }
}
