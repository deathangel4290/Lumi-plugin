// Runs ConfigCheck on a config file without a server (tools/check.sh runs it on the stock one).
// The "weapons" part needs the server's registries and is skipped with a note when run like this.
import com.lumiweapons.managers.ConfigCheck;
import java.io.*;
import java.lang.reflect.*;
import org.bukkit.configuration.file.YamlConfiguration;

public class CheckHarness {
   public static void main(String[] args) throws Exception {
      YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new File(args[0]));
      YamlConfiguration config = YamlConfiguration.loadConfiguration(new File(args[1]));
      config.setDefaults(defaults);
      config.options().copyDefaults(true);
      Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
      f.setAccessible(true);
      sun.misc.Unsafe unsafe = (sun.misc.Unsafe) f.get(null);
      ConfigCheck check = (ConfigCheck) unsafe.allocateInstance(ConfigCheck.class);
      Field problems = ConfigCheck.class.getDeclaredField("problems");
      problems.setAccessible(true);
      problems.set(check, new java.util.LinkedHashMap<String, String>());
      for (String name : new String[]{"shape", "choices", "weapons"}) {
         Method m = name.equals("shape")
            ? ConfigCheck.class.getDeclaredMethod(name, org.bukkit.configuration.file.FileConfiguration.class, org.bukkit.configuration.Configuration.class)
            : ConfigCheck.class.getDeclaredMethod(name, org.bukkit.configuration.file.FileConfiguration.class);
         m.setAccessible(true);
         try {
            if (name.equals("shape")) m.invoke(check, config, defaults); else m.invoke(check, config);
         } catch (InvocationTargetException e) {
            System.out.println(name + " threw (needs a server?): " + e.getCause());
         }
      }
      System.out.println("problems: " + check.count());
      check.lines().forEach(l -> System.out.println("  " + l));
   }
}
