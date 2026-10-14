package com.legendaryweapons.bosses;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
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
 * A custom look for a mob (bosses.<id>.model, token-shop.trader.model). The vanilla mob keeps doing
 * the work -- AI, hitbox, sounds -- and is hidden; the model is drawn in its place. Two ways:
 *
 * 1. BetterModel (animated, preferred). `bettermodel: <name>` names a model in
 *    plugins/BetterModel/models. BetterModel follows the mob and plays the model's own idle, walk
 *    (only while the mob moves), idle_fly, walk_fly and spawn animations by itself. On top of that
 *    the boss's states play animations of the same name:
 *      - one-off moments (attack, roar, cast; config `once`) play once over whatever is running;
 *      - lasting states (enraged, landed, surfaced, ...) replace the standing idle, so the walk still
 *        takes over while the mob moves;
 *      - idle returns to the plain idle (or to `landed` once the Wither King has landed).
 *    `animations: {attack: swipe}` maps a state to a differently named animation. A state whose
 *    animation the model does not have is skipped. `bettermodel-<form>` (e.g. bettermodel-landed)
 *    swaps to another model when the mob changes form. Only used when BetterModel is installed and
 *    the model is loaded; otherwise:
 *
 * 2. The static model: an item display following the mob, one item model per state ("namespace:path"
 *    or a custom_model_data number on `item`), only when `enabled: true`. Template convention (see
 *    docs/BOSS_MODELS.md): the model stands on the bottom of the Blockbench grid (y = -16), centred
 *    on x = z = 8, front facing north, sized by `scale`. It follows by teleporting rather than
 *    riding: a mob carrying a passenger cannot be teleported, and the bosses teleport.
 *
 * Neither configured: the plain vanilla mob.
 */
public final class BossModel {
   private static final Set<String> WARNED = new HashSet<>();
   private static final List<String> ONCE_DEFAULT = List.of("attack", "roar", "cast");
   private final ConfigurationSection config;
   private LivingEntity body;
   private ItemDisplay display;
   /** A BetterModelBridge.Handle, kept untyped so this class never makes the JVM resolve BetterModel classes. */
   private Object animated;
   private String standing;                  // the lasting state's animation now replacing idle, or null
   private String state = "";
   private String base = "idle";

   public BossModel(ConfigurationSection config) {
      this.config = config;
   }

   // ------------------------------------------------------------------ which look

   private static boolean betterModelInstalled() {
      return Bukkit.getPluginManager().isPluginEnabled("BetterModel");
   }

   /** The BetterModel model for the current form ("bettermodel-landed" once landed, ...), or "". */
   private String betterModelId() {
      if (this.config == null) {
         return "";
      }

      String form = this.config.getString("bettermodel-" + this.base, "");
      return form != null && !form.isBlank() && !"idle".equals(this.base) ? form.trim() : this.config.getString("bettermodel", "").trim();
   }

   private boolean staticConfigured() {
      return this.config != null && this.config.getBoolean("enabled", true) && this.config.isConfigurationSection("states") && this.config.contains("states.idle");
   }

   /** True when this mob gets a custom look (animated or static) instead of the vanilla mob. */
   public boolean enabled() {
      return this.animated != null || this.staticConfigured() || !this.betterModelId().isEmpty() && betterModelInstalled();
   }

   private BetterModelBridge.Handle bm() {
      return (BetterModelBridge.Handle)this.animated;
   }

   private static void warnOnce(String key, String message) {
      if (WARNED.add(key)) {
         Logger.getLogger("LegendaryWeapons").warning(message);
      }
   }

   // ------------------------------------------------------------------ lifecycle

   private static Location place(LivingEntity body) {
      Location at = body.getLocation();
      at.setPitch(0.0F);
      return at;
   }

   public void attach(LivingEntity body) {
      this.body = body;
      if (this.config == null) {
         return;
      }

      if (this.attachAnimated(body)) {
         return;
      }

      if (!this.staticConfigured()) {
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

   /** BetterModel first; false (and the static model is used) if it is missing or the model is not loaded. */
   private boolean attachAnimated(LivingEntity body) {
      String id = this.betterModelId();
      if (id.isEmpty() || !betterModelInstalled()) {
         return false;
      }

      try {
         BetterModelBridge.Handle handle = BetterModelBridge.attach(body, id, this.config.getDouble("bettermodel-scale", 1.0));
         if (handle == null) {
            warnOnce("missing:" + id, "BetterModel has no model named '" + id + "' (put " + id + ".bbmodel in plugins/BetterModel/models and reload it). Using the static model.");
            return false;
         }

         this.animated = handle;
         this.standing = null;
         String keep = this.state.isEmpty() ? this.base : this.state;
         this.state = "";
         this.setState(keep);
         return true;
      } catch (Throwable failure) {
         warnOnce("error:" + id, "BetterModel could not show '" + id + "' (" + failure + "). Using the static model.");
         this.animated = null;
         return false;
      }
   }

   /** The state missing states fall back to (idle, or landed after the Wither King lands). */
   public void setBaseState(String state) {
      String before = this.betterModelId();
      this.base = state;
      if (this.animated != null && this.body != null && !before.equals(this.betterModelId())) {
         // this form has its own model (bettermodel-landed, ...): swap to it
         this.closeAnimated();
         this.state = "";
         this.attach(this.body);
      }

      this.setState(state);
   }

   public void setState(String wanted) {
      if (this.animated != null) {
         this.setAnimatedState(wanted);
         return;
      }

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

   /**
    * A one-off animation for a moment with no lasting state, e.g. "attack" on every melee hit. Only
    * the animated model has these: the static model would have no way back to idle.
    */
   public void pulse(String state) {
      if (this.animated != null) {
         try {
            this.bm().playOnce(this.animationFor(state));
         } catch (Throwable failure) {
            warnOnce("anim:" + state, "BetterModel animation '" + state + "' failed: " + failure);
         }
      }
   }

   private String animationFor(String state) {
      String mapped = this.config.getString("animations." + state, state);
      return mapped == null || mapped.isBlank() ? state : mapped.trim();
   }

   private boolean playsOnce(String state) {
      List<String> once = this.config.isList("once") ? this.config.getStringList("once") : ONCE_DEFAULT;
      return once.contains(state);
   }

   private void setAnimatedState(String wanted) {
      try {
         BetterModelBridge.Handle handle = this.bm();
         if (this.playsOnce(wanted)) {
            this.state = wanted;
            handle.playOnce(this.animationFor(wanted));
            return;
         }

         // a lasting state: "idle" means the current form's standing state (idle, or landed)
         String target = "idle".equals(wanted) ? this.base : wanted;
         this.state = target;
         if ("idle".equals(target)) {
            if (this.standing != null) {
               if (!handle.stand(this.standing, "idle")) {
                  handle.stop(this.standing);
               }

               this.standing = null;
            }

            return;
         }

         String animation = this.animationFor(target);
         if (animation.equals(this.standing) || !handle.has(animation)) {
            return;                                       // already showing it, or this model has no such animation
         }

         handle.stand(this.standing == null ? "idle" : this.standing, animation);
         this.standing = animation;
      } catch (Throwable failure) {
         warnOnce("anim:" + wanted, "BetterModel animation '" + wanted + "' failed: " + failure);
      }
   }

   /** Keep the look on the body. Call every tick. */
   public void follow(LivingEntity body) {
      if (this.animated != null) {
         boolean alive;
         try {
            alive = this.bm().alive();
         } catch (Throwable failure) {
            alive = false;
         }

         if (!alive || body != this.body) {
            this.animated = null;
            this.attach(body);                            // BetterModel dropped it (reload, unload): put it back
         }

         return;
      }

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

   private void closeAnimated() {
      if (this.animated != null) {
         try {
            this.bm().close();
         } catch (Throwable ignored) {
         }

         this.animated = null;
         this.standing = null;
      }
   }

   public void remove() {
      this.closeAnimated();
      if (this.display != null && this.display.isValid()) {
         this.display.remove();
      }

      this.display = null;
   }
}
