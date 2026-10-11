package com.legendaryweapons.bosses;

import java.util.Locale;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * A custom look for a mob, if one is configured (bosses.<id>.model, token-shop.trader.model). The
 * vanilla mob keeps doing the work -- AI, hitbox, sounds -- and turns invisible; an item display
 * follows it every tick and shows the model.
 *
 * Template convention (see docs/BOSS_MODELS.md and resourcepack/_source/blockbench/): the model
 * stands on the BOTTOM of the Blockbench grid (y = -16), centred on x = z = 8, front facing north.
 * `scale` then sizes it; the feet land on the mob's feet with no offset to fiddle with.
 *
 * It follows by teleporting rather than riding: an entity with a passenger cannot be teleported,
 * which would break every boss teleport (leash, swaps, landing) and the trader's snap-back.
 *
 * Each state (idle, attack, enraged, landed, ...) names a model:
 *   - "namespace:path"  -> an item model (assets/<namespace>/items/<path>.json in the pack)
 *   - a number          -> custom_model_data on model.item (default PAPER)
 * A state with no entry falls back to the base state (idle, or landed once the Wither King has
 * landed). `enabled: false` keeps the vanilla mob.
 */
public final class BossModel {
   private final ConfigurationSection config;
   private ItemDisplay display;
   private String state = "";
   private String base = "idle";

   public BossModel(ConfigurationSection config) {
      this.config = config;
   }

   public boolean enabled() {
      return this.config != null && this.config.getBoolean("enabled", true) && this.config.isConfigurationSection("states") && this.config.contains("states.idle");
   }

   private static Location place(LivingEntity body) {
      Location at = body.getLocation();
      at.setPitch(0.0F);
      return at;
   }

   public void attach(LivingEntity body) {
      if (!this.enabled()) {
         return;
      }

      if (this.config.getBoolean("hide-body", true)) {
         body.setInvisible(true);
      }

      float scale = (float)this.config.getDouble("scale", 1.0);
      // the template's feet sit 1.5 grid-heights below its centre
      float lift = 1.5F * scale + (float)this.config.getDouble("offset-y", 0.0);
      float turn = (float)Math.toRadians(this.config.getDouble("yaw-offset", 0.0));
      this.display = body.getWorld().spawn(place(body), ItemDisplay.class, d -> {
         d.setPersistent(false);
         d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
         d.setBillboard(Display.Billboard.FIXED);
         d.setTeleportDuration(1);
         d.setViewRange((float)this.config.getDouble("view-range", 2.0));
         d.setTransformation(new Transformation(new Vector3f(0.0F, lift, 0.0F), new Quaternionf().rotationY(turn), new Vector3f(scale, scale, scale), new Quaternionf()));
      });
      String keep = this.state.isEmpty() ? this.base : this.state;
      this.state = "";
      this.setState(keep);
   }

   /** The state missing states fall back to (idle, or landed after the Wither King lands). */
   public void setBaseState(String state) {
      this.base = state;
      this.setState(state);
   }

   public void setState(String wanted) {
      if (this.display == null || !this.display.isValid()) {
         return;
      }

      String key = this.config.contains("states." + wanted) ? wanted : (this.config.contains("states." + this.base) ? this.base : "idle");
      if (key.equals(this.state)) {
         return;
      }

      this.state = key;
      Object value = this.config.get("states." + key);
      Material material = Material.matchMaterial(this.config.getString("item", "PAPER"));
      ItemStack item = new ItemStack(material == null ? Material.PAPER : material);
      ItemMeta meta = item.getItemMeta();
      String text = String.valueOf(value).trim();
      if (text.contains(":")) {
         NamespacedKey model = NamespacedKey.fromString(text.toLowerCase(Locale.ROOT));
         if (model != null) {
            meta.setItemModel(model);
         }
      } else {
         try {
            org.bukkit.inventory.meta.components.CustomModelDataComponent cmd = meta.getCustomModelDataComponent();
            cmd.setFloats(java.util.List.of(Float.parseFloat(text)));
            meta.setCustomModelDataComponent(cmd);
         } catch (NumberFormatException ignored) {
         }
      }

      item.setItemMeta(meta);
      this.display.setItemStack(item);
   }

   /** Keep it on the body, facing where the body faces. Call every tick. */
   public void follow(LivingEntity body) {
      if (!this.enabled()) {
         return;
      }

      if (this.display == null || !this.display.isValid()) {
         String keep = this.state;
         this.display = null;
         this.state = keep;
         this.attach(body);
         return;
      }

      Location at = place(body);
      Location now = this.display.getLocation();
      if (!now.getWorld().equals(at.getWorld()) || now.distanceSquared(at) > 1.0E-4 || Math.abs(now.getYaw() - at.getYaw()) > 0.5F) {
         this.display.teleport(at);
      }
   }

   public void remove() {
      if (this.display != null && this.display.isValid()) {
         this.display.remove();
      }

      this.display = null;
   }
}
