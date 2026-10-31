package gg.sheeesh.serverselector;

import gg.sheeesh.serverselector.Store.Entry;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public final class ServerSelector extends JavaPlugin implements Listener {

    private Store store;
    private JavaUI javaUI;
    private boolean floodgate;
    private final List<String[]> icons = new ArrayList<>();
    private final Set<UUID> transferring = new HashSet<>();
    private final Map<UUID, Long> lastUse = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        store = new Store(this);
        floodgate = getServer().getPluginManager().getPlugin("floodgate") != null;

        for (String s : getConfig().getStringList("icons")) {
            String[] p = s.split("\\|", 2);
            if (p.length == 2) icons.add(new String[]{p[0].trim(), p[1].trim()});
        }
        if (icons.isEmpty()) icons.add(new String[]{"Compass", "textures/items/compass_item"});

        javaUI = new JavaUI(this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(javaUI, this);
        getLogger().info("Bedrock forms: " + (floodgate ? "ON (Floodgate found)" : "OFF (Floodgate not found)"));
    }

    // ───────────── helpers ─────────────
    Store store() { return store; }

    List<String[]> icons() { return icons; }

    String defaultIcon() { return icons.get(0)[1]; }

    int maxServers() { return getConfig().getInt("max-servers", 100); }

    boolean isBedrock(Player p) {
        return floodgate && BedrockUI.isBedrock(p.getUniqueId());
    }

    /** Recommended servers for one edition (uses java-port or bedrock-port from config). */
    List<Entry> recommended(boolean bedrock) {
        List<Entry> out = new ArrayList<>();
        for (Map<?, ?> m : getConfig().getMapList("recommended")) {
            Object port = m.get(bedrock ? "bedrock-port" : "java-port");
            if (port == null || m.get("host") == null) continue;
            try {
                Object icon = m.get("icon");
                out.add(new Entry("rec", String.valueOf(m.get("name")), String.valueOf(m.get("host")),
                        Integer.parseInt(String.valueOf(port)), icon == null ? defaultIcon() : String.valueOf(icon)));
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    void openMenu(Player p) {
        if (isBedrock(p)) BedrockUI.openList(this, p);
        else javaUI.openList(p, 0);
    }

    // ───────────── join (countdown, cancelled if the player moves) ─────────────
    void startTransfer(Player p, Entry s) {
        UUID id = p.getUniqueId();
        if (transferring.contains(id)) {
            p.sendMessage("§cYou are already being transferred.");
            return;
        }
        int seconds = getConfig().getInt("transfer-countdown", 3);
        if (seconds <= 0) {
            doTransfer(p, s);
            return;
        }

        transferring.add(id);
        Location start = p.getLocation().clone();
        int[] left = {seconds};
        p.sendMessage("§aJoining §e" + s.name() + "§a in §e" + seconds + "§a seconds. §cDon't move!");

        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!p.isOnline()) {
                transferring.remove(id);
                task[0].cancel();
                return;
            }
            Location cur = p.getLocation();
            if (cur.getWorld() != start.getWorld() || cur.distanceSquared(start) > 0.25) {
                transferring.remove(id);
                task[0].cancel();
                p.sendMessage("§cTransfer cancelled! You moved.");
                return;
            }
            left[0]--;
            if (left[0] > 0) {
                p.sendMessage("§e" + left[0] + "...");
            } else {
                transferring.remove(id);
                task[0].cancel();
                doTransfer(p, s);
            }
        }, 20L, 20L);
    }

    private void doTransfer(Player p, Entry s) {
        p.sendMessage("§aTransferring you to §e" + s.name() + "§a...");
        try {
            if (isBedrock(p)) {
                if (!BedrockUI.transfer(p.getUniqueId(), s.host(), s.port()))
                    p.sendMessage("§cTransfer failed. The server may be offline.");
            } else {
                p.transfer(s.host(), s.port()); // Java 1.20.5+
            }
        } catch (Throwable t) {
            getLogger().warning("Transfer failed: " + t);
            p.sendMessage("§cTransfer failed. The server may be offline.");
        }
    }

    // ───────────── right-click a sign → open the menu ─────────────
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null || !Tag.ALL_SIGNS.isTagged(b.getType())) return;

        Player p = e.getPlayer();
        // Tag "edit" + creative = normal sign editing (sneak + right-click opens the menu instead)
        boolean canEditSign = p.getScoreboardTags().contains(getConfig().getString("edit-tag", "edit"))
                && p.getGameMode() == GameMode.CREATIVE;
        if (canEditSign && !p.isSneaking()) return;

        e.setCancelled(true); // stops the sign editor

        long now = System.currentTimeMillis();
        if (now - lastUse.getOrDefault(p.getUniqueId(), 0L) < 1000) return;
        lastUse.put(p.getUniqueId(), now);

        openMenu(p);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        lastUse.remove(e.getPlayer().getUniqueId());
        transferring.remove(e.getPlayer().getUniqueId());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (sender instanceof Player p) openMenu(p);
        else sender.sendMessage("Players only.");
        return true;
    }
}
