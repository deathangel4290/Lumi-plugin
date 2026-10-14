package com.legendaryweapons.bosses;

import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.animation.AnimationIterator;
import kr.toxicity.model.api.animation.AnimationModifier;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.data.renderer.ModelRenderer;
import kr.toxicity.model.api.tracker.EntityTracker;
import kr.toxicity.model.api.tracker.ModelScaler;
import org.bukkit.entity.LivingEntity;

/**
 * Everything that touches BetterModel lives in this class. BossModel only loads it after checking
 * (with the plain Bukkit API) that BetterModel is enabled, so without BetterModel this class is never
 * loaded and nothing here can fail to link.
 *
 * Built against BetterModel 2.2 (Paper 1.21.x, Java 21) and limited to calls that exist unchanged
 * in 3.x as well. Anything unexpected at runtime -- another API version, a model that fails to
 * load -- is caught by the caller, which falls back to the static model.
 */
final class BetterModelBridge {
   private static final AnimationModifier LOOP = AnimationModifier.builder().type(AnimationIterator.Type.LOOP).build();

   private BetterModelBridge() {
   }

   /**
    * The model `id` (a .bbmodel in plugins/BetterModel/models) on `body`, or null if no such model
    * is loaded. BetterModel then hides the mob, follows it, faces where it faces, and plays the
    * model's own idle / walk / idle_fly / walk_fly / spawn animations by itself (walk only while
    * the mob actually moves). `scale` multiplies the mob's own scale attribute.
    */
   static Handle attach(LivingEntity body, String id, double scale) {
      ModelRenderer renderer = BetterModel.modelOrNull(id);
      if (renderer == null) {
         return null;
      }

      EntityTracker tracker = renderer.getOrCreate(BukkitAdapter.adapt(body));
      if (scale > 0.0 && Math.abs(scale - 1.0) > 1.0E-3) {
         tracker.scaler(ModelScaler.composite(ModelScaler.entity(), ModelScaler.value((float)scale)));
      }

      return new Handle(tracker, id);
   }

   static final class Handle {
      private final EntityTracker tracker;
      final String id;

      private Handle(EntityTracker tracker, String id) {
         this.tracker = tracker;
         this.id = id;
      }

      boolean has(String animation) {
         return this.tracker.renderer().animation(animation).isPresent();
      }

      /** Plays once on top of whatever is running, then the idle/walk underneath shows again. */
      boolean playOnce(String animation) {
         return this.tracker.animate(animation, AnimationModifier.DEFAULT_WITH_PLAY_ONCE);
      }

      /**
       * Makes `animation` the standing animation in place of `current` (idle, or the previous
       * state). Replacing keeps BetterModel's walk on top while the mob moves; if `current` is not
       * running, the animation loops on top instead.
       */
      boolean stand(String current, String animation) {
         return this.tracker.replace(current, animation, LOOP) || this.tracker.animate(animation, LOOP);
      }

      void stop(String animation) {
         this.tracker.stopAnimation(animation);
      }

      boolean alive() {
         return !this.tracker.isClosed();
      }

      void close() {
         if (!this.tracker.isClosed()) {
            this.tracker.close();
         }
      }
   }
}
