package com.legendaryweapons.rituals;

import com.legendaryweapons.LegendaryWeapons;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Ritual crafting times. Rituals can only be started inside ritual.windows (admins can always), and
 * every ritual message shows clock times in ritual.timezone (Central Time by default), e.g.
 * "it completes at 8:42 PM CDT".
 *
 * A window is "DAYS HH:MM-HH:MM": ALL, WEEKDAYS, WEEKEND, or days like MON,WED,FRI. A window can't
 * cross midnight (use two).
 */
public final class RitualClock {
   private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("h:mm a z", Locale.ENGLISH);
   private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
   private final LegendaryWeapons plugin;

   private record Window(Set<DayOfWeek> days, LocalTime from, LocalTime to, String daysText) {
      boolean covers(ZonedDateTime when) {
         LocalTime time = when.toLocalTime();
         return this.days.contains(when.getDayOfWeek()) && !time.isBefore(this.from) && time.isBefore(this.to);
      }
   }

   public RitualClock(LegendaryWeapons plugin) {
      this.plugin = plugin;
   }

   private ConfigurationSection ritual() {
      return this.plugin.getConfig().getConfigurationSection("ritual");
   }

   public ZoneId zone() {
      String name = this.ritual() == null ? "" : this.ritual().getString("timezone", "America/Chicago");
      try {
         return name == null || name.isBlank() ? ZoneId.of("America/Chicago") : ZoneId.of(name.trim());
      } catch (RuntimeException failure) {
         return ZoneId.of("America/Chicago");
      }
   }

   public ZonedDateTime now() {
      return ZonedDateTime.now(this.zone());
   }

   /** "8:42 PM CDT" for a moment `millis` from now. */
   public String clockIn(long millis) {
      return this.now().plusNanos(millis * 1_000_000L).format(CLOCK);
   }

   public boolean windowsEnabled() {
      return this.ritual() != null && this.ritual().getBoolean("windows.enabled", false);
   }

   private List<Window> windows() {
      List<Window> out = new ArrayList<>();
      if (this.ritual() == null) {
         return out;
      }

      for (String raw : this.ritual().getStringList("windows.times")) {
         Window window = parse(raw);
         if (window != null) {
            out.add(window);
         } else {
            this.plugin.getLogger().warning("ritual.windows.times: can't read '" + raw + "' (use e.g. \"ALL 17:00-23:00\")");
         }
      }

      return out;
   }

   static Window parse(String raw) {
      String[] parts = raw.trim().split("\\s+");
      if (parts.length != 2) {
         return null;
      }

      Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
      for (String token : parts[0].toUpperCase(Locale.ROOT).split(",")) {
         switch (token) {
            case "ALL", "DAILY", "EVERYDAY" -> days.addAll(EnumSet.allOf(DayOfWeek.class));
            case "WEEKDAYS" -> days.addAll(EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY));
            case "WEEKEND", "WEEKENDS" -> days.addAll(EnumSet.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY));
            default -> {
               DayOfWeek day = null;
               for (DayOfWeek option : DayOfWeek.values()) {
                  if (option.name().startsWith(token) && token.length() >= 3) {
                     day = option;
                  }
               }

               if (day == null) {
                  return null;
               }

               days.add(day);
            }
         }
      }

      String[] range = parts[1].split("-");
      if (range.length != 2) {
         return null;
      }

      try {
         LocalTime from = LocalTime.parse(range[0].length() == 4 ? "0" + range[0] : range[0]);
         LocalTime to = range[1].equals("24:00") ? LocalTime.MAX : LocalTime.parse(range[1].length() == 4 ? "0" + range[1] : range[1]);
         return from.isBefore(to) ? new Window(days, from, to, parts[0].toUpperCase(Locale.ROOT)) : null;
      } catch (RuntimeException failure) {
         return null;
      }
   }

   /** True when a ritual may start now (always, when windows are off or none are valid). */
   public boolean openNow() {
      if (!this.windowsEnabled()) {
         return true;
      }

      List<Window> windows = this.windows();
      if (windows.isEmpty()) {
         return true;
      }

      ZonedDateTime now = this.now();
      for (Window window : windows) {
         if (window.covers(now)) {
            return true;
         }
      }

      return false;
   }

   /** "every day 5:00 PM-11:00 PM CDT; weekends 12:00 PM-4:00 PM CDT". */
   public String describe() {
      String zone = this.now().format(DateTimeFormatter.ofPattern("z", Locale.ENGLISH));
      List<String> out = new ArrayList<>();
      for (Window window : this.windows()) {
         String days = switch (window.daysText()) {
            case "ALL", "DAILY", "EVERYDAY" -> "every day";
            case "WEEKDAYS" -> "weekdays";
            case "WEEKEND", "WEEKENDS" -> "weekends";
            default -> window.daysText().toLowerCase(Locale.ROOT).replace(",", ", ");
         };
         String to = window.to().equals(LocalTime.MAX) ? "midnight" : window.to().format(TIME);
         out.add(days + " " + window.from().format(TIME) + "-" + to + " " + zone);
      }

      return out.isEmpty() ? "any time" : String.join("; ", out);
   }

   /** When the next window opens, e.g. "5:00 PM CDT" (or "Sat 12:00 PM CDT"), or null. */
   public String nextOpening() {
      if (!this.windowsEnabled()) {
         return null;
      }

      ZonedDateTime now = this.now();
      ZonedDateTime best = null;
      for (Window window : this.windows()) {
         for (int day = 0; day <= 7; day++) {
            ZonedDateTime start = now.toLocalDate().plusDays(day).atTime(window.from()).atZone(this.zone());
            if (start.isAfter(now) && window.days().contains(start.getDayOfWeek())) {
               if (best == null || start.isBefore(best)) {
                  best = start;
               }

               break;
            }
         }
      }

      if (best == null) {
         return null;
      }

      boolean today = best.toLocalDate().equals(now.toLocalDate());
      return (today ? "" : best.getDayOfWeek().getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH) + " ") + best.format(CLOCK);
   }
}
