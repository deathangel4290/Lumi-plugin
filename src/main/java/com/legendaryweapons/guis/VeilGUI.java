package com.legendaryweapons.guis;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import com.legendaryweapons.LegendaryWeapons;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Map.Entry;
import java.util.function.BiConsumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

public class VeilGUI {
   private final LegendaryWeapons plugin;
   private final BiConsumer<Player, String> onChoose;

   public VeilGUI(LegendaryWeapons plugin, BiConsumer<Player, String> onChoose) {
      this.plugin = plugin;
      this.onChoose = onChoose;
   }

   public boolean allowOnlineTargets() {
      ConfigurationSection abilities = this.plugin.getConfigManager().getWeaponAbilities("veil-of-mimicry");
      return abilities == null || abilities.getBoolean("allow-online-targets", true);
   }

   private List<Entry<UUID, String>> candidates(Player viewer) {
      boolean allowOnline = this.allowOnlineTargets();
      List<Entry<UUID, String>> out = new ArrayList<>();

      for (Entry<UUID, String> entry : this.plugin.getPlayerRosterManager().entries()) {
         if (!entry.getKey().equals(viewer.getUniqueId()) && (allowOnline || Bukkit.getPlayer(entry.getKey()) == null)) {
            out.add(entry);
         }
      }

      return out;
   }

   public int candidateCount(Player viewer) {
      return this.candidates(viewer).size();
   }

   public int excludedOnlineCount(Player viewer) {
      if (this.allowOnlineTargets()) {
         return 0;
      } else {
         int count = 0;

         for (Entry<UUID, String> entry : this.plugin.getPlayerRosterManager().entries()) {
            if (!entry.getKey().equals(viewer.getUniqueId()) && Bukkit.getPlayer(entry.getKey()) != null) {
               count++;
            }
         }

         return count;
      }
   }

   public void open(Player player, int page) {
      Faces menu = new Faces(player);
      menu.page = page;
      menu.open();
   }

   /** The faces this player can wear, a page of 28 at a time. */
   private final class Faces extends Menu {
      Faces(Player viewer) {
         super(VeilGUI.this.plugin, viewer, null);
      }

      @Override
      protected String title() {
         return "Veil of Mimicry";
      }

      @Override
      protected int rows() {
         return 6;
      }

      @Override
      protected int pages() {
         return pagesFor(VeilGUI.this.candidates(this.viewer).size(), this.inner().length);
      }

      @Override
      protected void draw() {
         List<Entry<UUID, String>> all = VeilGUI.this.candidates(this.viewer);
         this.show(4, Icon.of(Material.ENDER_EYE).name("Choose a face", ACCENT)
            .stat("Players seen", String.valueOf(all.size()), NamedTextColor.WHITE)
            .blank().text("The morph fails if anyone within 20 blocks sees you.", DIM).build());
         int[] slots = this.inner();
         List<Entry<UUID, String>> shown = this.slice(all, slots.length);
         for (int i = 0; i < shown.size(); i++) {
            Entry<UUID, String> entry = shown.get(i);
            this.button(slots[i], VeilGUI.this.headFor(entry.getKey(), entry.getValue()), click -> {
               this.clickSound(click.player());
               click.player().closeInventory();
               VeilGUI.this.onChoose.accept(click.player(), entry.getValue());
            });
         }

         if (all.isEmpty()) {
            this.show(22, Icon.of(Material.PAPER).name("No faces yet", NamedTextColor.WHITE).text("Players you can copy show up here once they have joined the server.").build());
         }
      }
   }

   private ItemStack headFor(UUID id, String name) {
      ItemStack head = new ItemStack(Material.PLAYER_HEAD);
      SkullMeta meta = (SkullMeta)head.getItemMeta();
      if (meta != null) {
         ProfileProperty skin = this.plugin.getPlayerRosterManager().cachedTextures(id);
         if (skin != null) {
            PlayerProfile profile = Bukkit.createProfileExact(id, name);
            profile.setProperty(skin);
            meta.setPlayerProfile(profile);
         } else {
            meta.setOwningPlayer(Bukkit.getOfflinePlayer(id));
         }

         meta.displayName(Component.text(name, NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
         meta.lore(List.of(Icon.plain("▸ ", Menu.ACCENT).append(Component.text("Click", NamedTextColor.YELLOW)).append(Component.text(" to wear this face", Menu.DIM))));
         head.setItemMeta(meta);
      }

      return head;
   }
}
