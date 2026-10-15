package com.legendaryweapons.guis;

import com.legendaryweapons.LegendaryWeapons;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * Every Luminara menu. A menu draws itself into a framed chest window and says what each slot
 * does; {@link MenuListener} cancels every click in it and routes the click here, so no menu can
 * ever leak or swallow items.
 *
 * Layout: the top and bottom rows are the frame (dark glass, violet corners). The bottom row holds
 * the controls in fixed places: Back (left corner), page arrows, Close (middle). Content goes in
 * the rows between, normally columns 1-7 ({@link #inner()}).
 */
public abstract class Menu implements InventoryHolder {
   public static final TextColor ACCENT = TextColor.color(0xB98CFF);      // Luminara violet
   public static final TextColor GOLD = TextColor.color(0xF2C14E);
   public static final TextColor TEXT = NamedTextColor.GRAY;
   public static final TextColor DIM = NamedTextColor.DARK_GRAY;
   public static final TextColor GOOD = TextColor.color(0x6BE07A);
   public static final TextColor BAD = TextColor.color(0xFF6B6B);
   private static final ItemStack FRAME = Icon.pane(Material.BLACK_STAINED_GLASS_PANE);
   private static final ItemStack CORNER = Icon.pane(Material.PURPLE_STAINED_GLASS_PANE);
   protected final LegendaryWeapons plugin;
   protected final Player viewer;
   protected final Menu parent;
   private final Map<Integer, Consumer<Click>> actions = new HashMap<>();
   private Inventory inventory;
   protected int page;

   /** What the player did: which button, and with shift or not. */
   public record Click(Player player, ClickType type) {
      public boolean left() {
         return this.type.isLeftClick();
      }

      public boolean right() {
         return this.type.isRightClick();
      }

      public boolean shift() {
         return this.type.isShiftClick();
      }

      public boolean drop() {
         return this.type == ClickType.DROP || this.type == ClickType.CONTROL_DROP;
      }
   }

   protected Menu(LegendaryWeapons plugin, Player viewer, Menu parent) {
      this.plugin = plugin;
      this.viewer = viewer;
      this.parent = parent;
   }

   /** The window title, e.g. "Settings". It is shown after the Luminara mark. */
   protected abstract String title();

   protected abstract int rows();

   /** Fill the content slots with {@link #button} calls. */
   protected abstract void draw();

   /** How many pages {@link #draw} has; above 1 adds the page arrows. */
   protected int pages() {
      return 1;
   }

   protected void onClose() {
   }

   @NotNull
   public Inventory getInventory() {
      return this.inventory;
   }

   // ------------------------------------------------------------------ opening and drawing

   public final void open() {
      ChatPrompt.cancel(this.viewer);
      this.page = Math.max(0, Math.min(this.page, this.pages() - 1));
      Component title = Component.text("✦ ", ACCENT).append(Component.text(this.title(), NamedTextColor.DARK_GRAY));
      if (this.pages() > 1) {
         title = title.append(Component.text("  " + (this.page + 1) + "/" + this.pages(), NamedTextColor.GRAY));
      }

      this.inventory = Bukkit.createInventory(this, this.rows() * 9, title.decoration(TextDecoration.ITALIC, false));
      this.render();
      this.viewer.openInventory(this.inventory);
   }

   /** Redraw in place (no reopen, so the cursor stays put). */
   public final void refresh() {
      if (this.inventory == null || this.viewer.getOpenInventory().getTopInventory().getHolder() != this) {
         return;
      }

      this.render();
   }

   private void render() {
      this.actions.clear();
      this.inventory.clear();
      int size = this.inventory.getSize();
      int last = size - 9;
      for (int i = 0; i < 9; i++) {
         this.inventory.setItem(i, i == 0 || i == 8 ? CORNER : FRAME);
         this.inventory.setItem(last + i, i == 0 || i == 8 ? CORNER : FRAME);
      }

      for (int row = 1; row < this.rows() - 1; row++) {
         this.inventory.setItem(row * 9, FRAME);
         this.inventory.setItem(row * 9 + 8, FRAME);
      }

      if (this.parent != null) {
         this.button(last, Icon.of(Material.ARROW).name("Back", NamedTextColor.WHITE).text("To " + this.parent.title() + ".").build(), click -> {
            this.sound(click.player(), Sound.ITEM_BOOK_PAGE_TURN, 1.0F);
            this.parent.open();
         });
      }

      this.button(last + 4, Icon.of(Material.BARRIER).name("Close", BAD).build(), click -> click.player().closeInventory());
      if (this.pages() > 1) {
         if (this.page > 0) {
            this.button(last + 3, Icon.of(Material.SPECTRAL_ARROW).name("Previous page", NamedTextColor.WHITE).text("Page " + this.page + " of " + this.pages() + ".").build(), click -> this.turn(-1));
         }

         if (this.page < this.pages() - 1) {
            this.button(last + 5, Icon.of(Material.SPECTRAL_ARROW).name("Next page", NamedTextColor.WHITE).text("Page " + (this.page + 2) + " of " + this.pages() + ".").build(), click -> this.turn(1));
         }
      }

      this.draw();
   }

   private void turn(int by) {
      this.page += by;
      this.sound(this.viewer, Sound.ITEM_BOOK_PAGE_TURN, 1.0F);
      this.open();
   }

   protected final void button(int slot, ItemStack icon, Consumer<Click> action) {
      if (slot >= 0 && slot < this.inventory.getSize()) {
         this.inventory.setItem(slot, icon);
         if (action != null) {
            this.actions.put(slot, action);
         } else {
            this.actions.remove(slot);
         }
      }
   }

   protected final void show(int slot, ItemStack icon) {
      this.button(slot, icon, null);
   }

   /** The content slots: rows 1..rows-2, columns 1..7. */
   protected final int[] inner() {
      int[] slots = new int[(this.rows() - 2) * 7];
      int n = 0;
      for (int row = 1; row < this.rows() - 1; row++) {
         for (int col = 1; col <= 7; col++) {
            slots[n++] = row * 9 + col;
         }
      }

      return slots;
   }

   /** The slice of `all` for the current page, `per` to a page. */
   protected final <T> List<T> slice(List<T> all, int per) {
      int from = Math.min(all.size(), this.page * per);
      return all.subList(from, Math.min(all.size(), from + per));
   }

   protected static int pagesFor(int count, int per) {
      return Math.max(1, (count + per - 1) / per);
   }

   final void handle(int slot, ClickType type) {
      Consumer<Click> action = this.actions.get(slot);
      if (action != null) {
         action.accept(new Click(this.viewer, type));
      }
   }

   final void closed() {
      this.onClose();
   }

   // ------------------------------------------------------------------ feedback

   protected final void sound(Player player, Sound sound, float pitch) {
      this.plugin.getSoundManager().self(player, sound, 0.6F, pitch);
   }

   protected final void clickSound(Player player) {
      this.sound(player, Sound.UI_BUTTON_CLICK, 1.4F);
   }

   protected final void toggleSound(Player player, boolean on) {
      this.sound(player, Sound.BLOCK_NOTE_BLOCK_PLING, on ? 1.6F : 0.9F);
   }

   protected final void errorSound(Player player) {
      this.sound(player, Sound.ENTITY_VILLAGER_NO, 1.0F);
   }

   protected static void tell(Player player, String text, TextColor colour) {
      player.sendMessage(Component.text("✦ ", ACCENT).append(Component.text(text, colour)));
   }

   protected static String onOff(boolean on) {
      return on ? "On" : "Off";
   }
}
