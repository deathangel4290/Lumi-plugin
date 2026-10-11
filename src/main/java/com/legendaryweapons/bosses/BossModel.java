package com.legendaryweapons.bosses;

import java.util.Locale;
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
 * A boss's custom look, if one is configured (bosses.<id>.model). The vanilla mob keeps doing the
 * fighting -- AI, hitbox, sounds -- and becomes invisible; an item display riding it shows the model.
 *
 * Each state (idle, attack, special, enraged, ...) names a model:
 *   - "namespace:path"  -> an item model (assets/<namespace>/items/<path>.json in the pack)
 *   - a number          -> custom_model_data on model.item (default PAPER)
 * Abilities call setState("attack") etc.; a state with no entry falls back to idle. With no model
 * section at all, the plain vanilla mob is shown -- the placeholder until real models exist.
 */
public final class BossModel {
   private final ConfigurationSection config;
   private ItemDisplay display;
   private String state = "";

   public BossModel(ConfigurationSection config) {
      this.config = config;
   }

   public boolean enabled() {
      return this.config != null && this.config.isConfigurationSection("states") && this.config.contains("states.idle");
   }

   public void attach(LivingEntity body) {
      if (!this.enabled()) {
         return;
      }

      if (this.config.getBoolean("hide-body", true)) {
         body.setInvisible(true);
      }

      float scale = (float)this.config.getDouble("scale", 1.0);
      float offset = (float)this.config.getDouble("offset-y", 0.0);
      this.display = body.getWorld().spawn(body.getLocation(), ItemDisplay.class, d -> {
         d.setPersistent(false);
         d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
         d.setBillboard(Display.Billboard.FIXED);
         d.setTeleportDuration(1);
         d.setViewRange((float)this.config.getDouble("view-range", 2.0));
         d.setTransformation(new Transformation(new Vector3f(0.0F, offset, 0.0F), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
      });
      body.addPassenger(this.display);
      this.setState("idle");
   }

   public void setState(String wanted) {
      if (this.display == null || !this.display.isValid()) {
         return;
      }

      String key = this.config.contains("states." + wanted) ? wanted : "idle";
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

   /** Keep it on the body and facing where the body faces. */
   public void follow(LivingEntity body) {
      if (this.display == null) {
         return;
      }

      if (!this.display.isValid()) {
         String keep = this.state;
         this.state = "";
         this.display = null;
         this.attach(body);
         this.setState(keep);
         return;
      }

      if (!body.getPassengers().contains(this.display)) {
         body.addPassenger(this.display);
      }

      this.display.setRotation(body.getLocation().getYaw(), 0.0F);
   }

   public void remove() {
      if (this.display != null && this.display.isValid()) {
         this.display.remove();
      }

      this.display = null;
   }
}
