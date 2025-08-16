package vadlox.dev.maxEnchants;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MaxEnchants extends JavaPlugin implements CommandExecutor {

    // Plugin colors
    private static final String PRIMARY_COLOR = "#A72BFF";
    private static final String SECONDARY_COLOR = "#D7A1FF";

    // Define negative/cursed enchantments to exclude
    private static final Set<Enchantment> NEGATIVE_ENCHANTMENTS = new HashSet<>(Arrays.asList(
            Enchantment.BINDING_CURSE,
            Enchantment.VANISHING_CURSE
    ));

    // Define mutually exclusive enchantment groups
    private static final Map<Enchantment, Set<Enchantment>> CONFLICTING_ENCHANTMENTS = new HashMap<>();

    static {
        // Protection enchantments are mutually exclusive
        Set<Enchantment> protectionGroup = new HashSet<>(Arrays.asList(
                Enchantment.PROTECTION,
                Enchantment.FIRE_PROTECTION,
                Enchantment.BLAST_PROTECTION,
                Enchantment.PROJECTILE_PROTECTION
        ));

        for (Enchantment prot : protectionGroup) {
            Set<Enchantment> conflicts = new HashSet<>(protectionGroup);
            conflicts.remove(prot); // Remove self from conflicts
            CONFLICTING_ENCHANTMENTS.put(prot, conflicts);
        }

        // Damage enchantments are mutually exclusive
        Set<Enchantment> damageGroup = new HashSet<>(Arrays.asList(
                Enchantment.SHARPNESS, // Sharpness
                Enchantment.BANE_OF_ARTHROPODS, // Bane of Arthropods
                Enchantment.SMITE // Smite
        ));

        for (Enchantment dmg : damageGroup) {
            Set<Enchantment> conflicts = new HashSet<>(damageGroup);
            conflicts.remove(dmg);
            CONFLICTING_ENCHANTMENTS.put(dmg, conflicts);
        }

        // Loyalty and Riptide are mutually exclusive
        CONFLICTING_ENCHANTMENTS.put(Enchantment.LOYALTY, new HashSet<>(Arrays.asList(Enchantment.RIPTIDE)));
        CONFLICTING_ENCHANTMENTS.put(Enchantment.RIPTIDE, new HashSet<>(Arrays.asList(Enchantment.LOYALTY, Enchantment.CHANNELING)));

        // Channeling and Riptide are mutually exclusive (already handled above)

        // Infinity and Mending are mutually exclusive
        CONFLICTING_ENCHANTMENTS.put(Enchantment.INFINITY, new HashSet<>(Arrays.asList(Enchantment.MENDING)));
        CONFLICTING_ENCHANTMENTS.put(Enchantment.MENDING, new HashSet<>(Arrays.asList(Enchantment.INFINITY)));

        // Multishot and Piercing are mutually exclusive
        CONFLICTING_ENCHANTMENTS.put(Enchantment.MULTISHOT, new HashSet<>(Arrays.asList(Enchantment.PIERCING)));
        CONFLICTING_ENCHANTMENTS.put(Enchantment.PIERCING, new HashSet<>(Arrays.asList(Enchantment.MULTISHOT)));

        // Silk Touch and Fortune are mutually exclusive
        CONFLICTING_ENCHANTMENTS.put(Enchantment.SILK_TOUCH, new HashSet<>(Arrays.asList(Enchantment.FORTUNE)));
        CONFLICTING_ENCHANTMENTS.put(Enchantment.FORTUNE, new HashSet<>(Arrays.asList(Enchantment.SILK_TOUCH)));
    }

    @Override
    public void onEnable() {
        getLogger().info("MaxEnchant plugin has been enabled!");
        this.getCommand("maxenchant").setExecutor(this);
    }

    @Override
    public void onDisable() {
        getLogger().info("MaxEnchant plugin has been disabled!");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(colorize("&cThis command can only be used by players!"));
            return true;
        }

        Player player = (Player) sender;
        ItemStack item = player.getInventory().getItemInMainHand();

        // Check if player is holding an item
        if (item == null || item.getType() == Material.AIR) {
            sender.sendMessage(colorize(PRIMARY_COLOR + "You must be holding an item to use this command!"));
            return true;
        }

        // Check if player has permission
        if (!player.hasPermission("maxenchant.use") && !player.isOp()) {
            sender.sendMessage(colorize(PRIMARY_COLOR + "You don't have permission to use this command!"));
            return true;
        }

        int enchantsAdded = applyMaxEnchantments(item);

        if (enchantsAdded > 0) {
            sender.sendMessage(colorize(PRIMARY_COLOR + "Added " + SECONDARY_COLOR + enchantsAdded + PRIMARY_COLOR +
                    " enchantments to your " + SECONDARY_COLOR +
                    item.getType().name().toLowerCase().replace("_", " ") + PRIMARY_COLOR + "!"));
        } else {
            sender.sendMessage(colorize(PRIMARY_COLOR + "No new enchantments could be applied to this item!"));
        }

        return true;
    }

    private int applyMaxEnchantments(ItemStack item) {
        int enchantsAdded = 0;
        ItemMeta meta = item.getItemMeta();

        if (meta == null) {
            return 0;
        }

        // Get currently applied enchantments
        Map<Enchantment, Integer> currentEnchants = meta.getEnchants();

        // List to store enchantments we want to add
        List<Enchantment> toAdd = new ArrayList<>();

        // First pass: collect all valid enchantments
        for (Enchantment enchantment : Enchantment.values()) {
            // Skip negative enchantments
            if (NEGATIVE_ENCHANTMENTS.contains(enchantment)) {
                continue;
            }

            // Check if the enchantment can be applied to this item type
            if (enchantment.canEnchantItem(item)) {
                // Check if the item doesn't already have this enchantment
                if (!currentEnchants.containsKey(enchantment)) {
                    toAdd.add(enchantment);
                }
            }
        }

        // Second pass: apply enchantments while respecting conflicts
        Set<Enchantment> applied = new HashSet<>(currentEnchants.keySet());

        // For conflicting groups, prioritize based on a preference order
        Map<Enchantment, Integer> preferenceOrder = getPreferenceOrder();

        // Sort enchantments by preference (higher preference first)
        toAdd.sort((a, b) -> {
            int prefA = preferenceOrder.getOrDefault(a, 0);
            int prefB = preferenceOrder.getOrDefault(b, 0);
            return Integer.compare(prefB, prefA);
        });

        for (Enchantment enchantment : toAdd) {
            boolean canApply = true;

            // Check for conflicts with already applied enchantments
            Set<Enchantment> conflicts = CONFLICTING_ENCHANTMENTS.get(enchantment);
            if (conflicts != null) {
                for (Enchantment conflict : conflicts) {
                    if (applied.contains(conflict)) {
                        canApply = false;
                        break;
                    }
                }
            }

            if (canApply) {
                // Use survival-obtainable max level (not the theoretical max)
                int maxLevel = getSurvivalMaxLevel(enchantment);
                meta.addEnchant(enchantment, maxLevel, false); // false = respect vanilla limitations
                applied.add(enchantment);
                enchantsAdded++;
            }
        }

        // Apply the modified meta back to the item
        item.setItemMeta(meta);
        return enchantsAdded;
    }

    private Map<Enchantment, Integer> getPreferenceOrder() {
        Map<Enchantment, Integer> preferences = new HashMap<>();

        // Higher numbers = higher preference
        // Protection enchantments - prefer general protection
        preferences.put(Enchantment.PROTECTION, 100);
        preferences.put(Enchantment.FIRE_PROTECTION, 80);
        preferences.put(Enchantment.BLAST_PROTECTION, 70);
        preferences.put(Enchantment.PROJECTILE_PROTECTION, 60);

        // Damage enchantments - prefer sharpness
        preferences.put(Enchantment.SHARPNESS, 100);
        preferences.put(Enchantment.SMITE, 80);
        preferences.put(Enchantment.BANE_OF_ARTHROPODS, 70);

        // Tool enchantments - prefer fortune over silk touch for most cases
        preferences.put(Enchantment.FORTUNE, 90);
        preferences.put(Enchantment.SILK_TOUCH, 70);

        // Bow enchantments - prefer mending over infinity
        preferences.put(Enchantment.MENDING, 90);
        preferences.put(Enchantment.INFINITY, 70);

        // Trident enchantments - prefer loyalty
        preferences.put(Enchantment.LOYALTY, 90);
        preferences.put(Enchantment.RIPTIDE, 70);
        preferences.put(Enchantment.CHANNELING, 80);

        // Crossbow enchantments - prefer multishot
        preferences.put(Enchantment.MULTISHOT, 80);
        preferences.put(Enchantment.PIERCING, 70);

        return preferences;
    }

    private int getSurvivalMaxLevel(Enchantment enchantment) {
        // Return survival-obtainable max levels
        // Most enchantments have the same max level in survival as their theoretical max
        // but this method allows for future customization
        return enchantment.getMaxLevel();
    }

    /**
     * Translates color codes and hex colors in a string
     * @param message The message to colorize
     * @return Colorized message
     */
    public static String colorize(String message) {
        // First replace & color codes
        String colorized = ChatColor.translateAlternateColorCodes('&', message);

        // Then replace hex color codes
        try {
            // For versions 1.16+
            Pattern hexPattern = Pattern.compile("#[a-fA-F0-9]{6}");
            Matcher matcher = hexPattern.matcher(colorized);
            StringBuffer buffer = new StringBuffer();

            while (matcher.find()) {
                String hexCode = matcher.group();
                // Convert hex code to the format Bukkit expects for 1.16+
                StringBuilder bukkitHexCode = new StringBuilder("§x");
                for (char c : hexCode.substring(1).toCharArray()) {
                    bukkitHexCode.append("§").append(c);
                }
                matcher.appendReplacement(buffer, bukkitHexCode.toString());
            }

            matcher.appendTail(buffer);
            return buffer.toString();
        } catch (Exception e) {
            // For versions below 1.16, just return with standard color codes
            return colorized;
        }
    }
}
