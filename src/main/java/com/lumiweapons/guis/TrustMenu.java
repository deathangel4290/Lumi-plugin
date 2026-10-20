package com.lumiweapons.guis;

import com.lumiweapons.LumiWeapons;
import com.lumiweapons.managers.TrustManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * The players someone trusts (/trustlist), with whether each trusts them back. Shift-click a head
 * to stop trusting them; "Trust a player" lists who is online.
 */
public final class TrustMenu extends Menu {
   public TrustMenu(LumiWeapons plugin, Player viewer, Menu parent) {
      super(plugin, viewer, parent);
   }

   private List<UUID> trusted() {
      List<UUID> out = new ArrayList<>();
      for (String raw : this.plugin.getTrustManager().getTrustedPlayers(this.viewer)) {
         try {
            out.add(UUID.fromString(raw));
         } catch (IllegalArgumentException ignored) {
         }
      }

      return out;
   }

   @Override
   protected String title() {
      return "Trusted Players";
   }

   @Override
   protected int rows() {
      return 6;
   }

   @Override
   protected int pages() {
      return pagesFor(this.trusted().size(), this.inner().length);
   }

   @Override
   protected void draw() {
      TrustManager trust = this.plugin.getTrustManager();
      List<UUID> all = this.trusted();
      this.show(4, Icon.of(Material.SHIELD).name("Trust", ACCENT)
         .text("Your abilities never hit the players you trust.")
         .blank().text("When you trust each other, neither of you can hurt the other at all, and you count as allies at outposts.", DIM)
         .blank().stat("You trust", String.valueOf(all.size()), NamedTextColor.WHITE).build());

      int[] slots = this.inner();
      List<UUID> shown = this.slice(all, slots.length);
      if (all.isEmpty()) {
         this.show(22, Icon.of(Material.PAPER).name("Nobody yet", NamedTextColor.WHITE).text("Trust a player with the button below.").build());
      }

      for (int i = 0; i < shown.size(); i++) {
         UUID id = shown.get(i);
         OfflinePlayer other = Bukkit.getOfflinePlayer(id);
         String name = other.getName() == null ? id.toString().substring(0, 8) : other.getName();
         boolean mutual = trust.isMutuallyTrusted(this.viewer.getUniqueId(), id);
         this.button(slots[i], Icon.head(id).name(name, NamedTextColor.WHITE)
            .line(mutual ? Icon.plain("✔ Trusts you back", GOOD) : Icon.plain("✖ Doesn't trust you back", TEXT))
            .line(Icon.plain(other.isOnline() ? "● Online" : "○ Offline", other.isOnline() ? GOOD : DIM))
            .blank().hint("Shift-click", "stop trusting").build(), click -> {
            if (!click.shift()) {
               this.errorSound(click.player());
               return;
            }

            trust.removeTrustedPlayer(click.player(), other);
            tell(click.player(), "You no longer trust " + name + ".", TEXT);
            this.toggleSound(click.player(), false);
            this.open();
         });
      }

      this.button(this.rows() * 9 - 1, Icon.of(Material.LIME_DYE).name("Trust a player", GOOD).text("Pick someone who is online.").blank().click("choose").build(), click -> {
         this.clickSound(click.player());
         new Picker(this.plugin, click.player(), this).open();
      });
   }

   /** Online players the viewer does not trust yet. */
   static final class Picker extends Menu {
      Picker(LumiWeapons plugin, Player viewer, Menu parent) {
         super(plugin, viewer, parent);
      }

      private List<Player> candidates() {
         TrustManager trust = this.plugin.getTrustManager();
         List<Player> out = new ArrayList<>();
         for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.equals(this.viewer) && !trust.isTrusted(this.viewer, online) && this.viewer.canSee(online)) {
               out.add(online);
            }
         }

         out.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
         return out;
      }

      @Override
      protected String title() {
         return "Trust a player";
      }

      @Override
      protected int rows() {
         return 6;
      }

      @Override
      protected int pages() {
         return pagesFor(this.candidates().size(), this.inner().length);
      }

      @Override
      protected void draw() {
         int[] slots = this.inner();
         List<Player> all = this.candidates();
         List<Player> shown = this.slice(all, slots.length);
         if (all.isEmpty()) {
            this.show(22, Icon.of(Material.PAPER).name("Nobody to add", NamedTextColor.WHITE).text("Everyone online is already trusted, or you're alone.").build());
         }

         for (int i = 0; i < shown.size(); i++) {
            Player other = shown.get(i);
            boolean trustsMe = this.plugin.getTrustManager().isTrusted(other, this.viewer);
            this.button(slots[i], Icon.head(other.getUniqueId()).name(other.getName(), NamedTextColor.WHITE)
               .line(trustsMe ? Icon.plain("✔ Already trusts you", GOOD) : Icon.plain("Doesn't trust you yet", TEXT))
               .blank().click("trust").build(), click -> {
               if (!other.isOnline()) {
                  this.errorSound(click.player());
                  this.open();
                  return;
               }

               this.plugin.getTrustManager().addTrustedPlayer(click.player(), other);
               tell(click.player(), "You now trust " + other.getName() + ".", GOOD);
               this.toggleSound(click.player(), true);
               this.parent.open();
            });
         }
      }
   }
}
