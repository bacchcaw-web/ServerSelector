package gg.sheeesh.serverselector;

import gg.sheeesh.serverselector.Store.Entry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.function.Consumer;

/** Chest-GUI menu for Java players (add/edit use chat prompts). */
@SuppressWarnings("deprecation")
public final class JavaUI implements Listener {

    private static final class Menu implements InventoryHolder {
        final Map<Integer, Runnable> actions = new HashMap<>();
        Inventory inv;

        @Override
        public Inventory getInventory() { return inv; }
    }

    private final ServerSelector pl;
    private final Map<UUID, Consumer<String>> pending = new HashMap<>();

    JavaUI(ServerSelector pl) { this.pl = pl; }

    // ───────────── helpers ─────────────
    private static Material mat(String icon) {
        String k = icon == null ? "" : icon.substring(icon.lastIndexOf('/') + 1);
        String n = switch (k) {
            case "compass_item" -> "COMPASS";
            case "clock_item" -> "CLOCK";
            case "redstone_dust" -> "REDSTONE";
            case "apple_golden" -> "GOLDEN_APPLE";
            case "book_enchanted" -> "ENCHANTED_BOOK";
            case "map_filled" -> "FILLED_MAP";
            case "bucket_water" -> "WATER_BUCKET";
            case "bucket_lava" -> "LAVA_BUCKET";
            case "bow_standby" -> "BOW";
            case "crossbow_standby" -> "CROSSBOW";
            case "fishing_rod_uncast" -> "FISHING_ROD";
            case "beef_cooked" -> "COOKED_BEEF";
            case "fireball" -> "FIRE_CHARGE";
            case "fireworks" -> "FIREWORK_ROCKET";
            case "minecart_normal" -> "MINECART";
            case "totem" -> "TOTEM_OF_UNDYING";
            default -> k.toUpperCase(Locale.ROOT);
        };
        Material m = Material.matchMaterial(n);
        return m != null && m.isItem() ? m : Material.COMPASS;
    }

    private static ItemStack item(Material m, String name, String... lore) {
        ItemStack it = new ItemStack(m);
        ItemMeta meta = it.getItemMeta();
        meta.setDisplayName("§r" + name);
        if (lore.length > 0) {
            List<String> l = new ArrayList<>();
            for (String s : lore) l.add("§r" + s);
            meta.setLore(l);
        }
        it.setItemMeta(meta);
        return it;
    }

    private static Menu menu(int size, String title) {
        Menu m = new Menu();
        m.inv = Bukkit.createInventory(m, size, LegacyComponentSerializer.legacySection().deserialize(title));
        return m;
    }

    private static void set(Menu m, int slot, ItemStack it, Runnable r) {
        m.inv.setItem(slot, it);
        m.actions.put(slot, r);
    }

    private static String addr(Entry e) { return e.host() + ":" + e.port(); }

    // ───────────── main list ─────────────
    void openList(Player p, int page) {
        List<Entry> mine = pl.store().load(p.getUniqueId());
        List<ItemStack> items = new ArrayList<>();
        List<Runnable> acts = new ArrayList<>();

        for (Entry e : pl.recommended(false)) {
            items.add(item(mat(e.icon()), "§6★ §l" + e.name(), "§7" + addr(e), "§8Recommended"));
            acts.add(() -> openServer(p, e, false));
        }
        items.add(item(Material.EMERALD_BLOCK, "§2§l+ Add Server", "§7Your servers: §f" + mine.size() + "/" + pl.maxServers()));
        acts.add(() -> {
            if (mine.size() >= pl.maxServers()) p.sendMessage("§cYour list is full (" + pl.maxServers() + " servers max). Delete one to add another.");
            else editFlow(p, null);
        });
        for (Entry e : mine) {
            items.add(item(mat(e.icon()), "§l" + e.name(), "§7" + addr(e)));
            acts.add(() -> openServer(p, e, true));
        }

        int per = 45;
        int pages = Math.max(1, (items.size() + per - 1) / per);
        int pg = Math.min(Math.max(page, 0), pages - 1);

        Menu m = menu(54, "§l§aServer List §8(" + (pg + 1) + "/" + pages + ")");
        for (int i = 0; i < per; i++) {
            int idx = pg * per + i;
            if (idx >= items.size()) break;
            set(m, i, items.get(idx), acts.get(idx));
        }
        if (pg > 0) set(m, 45, item(Material.ARROW, "§ePrevious page"), () -> openList(p, pg - 1));
        if (pg < pages - 1) set(m, 53, item(Material.ARROW, "§eNext page"), () -> openList(p, pg + 1));
        p.openInventory(m.inv);
    }

    // ───────────── one server ─────────────
    private void openServer(Player p, Entry e, boolean editable) {
        Menu m = menu(27, "§l" + e.name());
        Runnable join = () -> { p.closeInventory(); pl.startTransfer(p, e); };
        Runnable copy = () -> {
            p.closeInventory();
            p.sendMessage(LegacyComponentSerializer.legacySection()
                    .deserialize("§aClick here to copy: §f" + addr(e))
                    .clickEvent(ClickEvent.copyToClipboard(addr(e))));
        };
        Runnable back = () -> openList(p, 0);

        ItemStack joinItem = item(mat(e.icon()), "§a§lJoin", "§7" + addr(e));
        ItemStack copyItem = item(Material.PAPER, "§b§lCopy IP & Port", "§7Click, then click the chat line");
        ItemStack backItem = item(Material.ARROW, "§7§lBack");

        if (editable) {
            set(m, 10, joinItem, join);
            set(m, 12, copyItem, copy);
            set(m, 14, item(Material.WRITABLE_BOOK, "§e§lEdit"), () -> editFlow(p, e));
            set(m, 16, item(Material.BARRIER, "§c§lDelete"), () -> openDelete(p, e));
        } else {
            set(m, 11, joinItem, join);
            set(m, 15, copyItem, copy);
        }
        set(m, 22, backItem, back);
        p.openInventory(m.inv);
    }

    private void openDelete(Player p, Entry e) {
        Menu m = menu(27, "§l§cDelete " + e.name() + "?");
        set(m, 11, item(Material.RED_CONCRETE, "§c§lYes, delete", "§7" + addr(e)), () -> {
            List<Entry> list = pl.store().load(p.getUniqueId());
            list.removeIf(s -> s.id().equals(e.id()));
            pl.store().save(p.getUniqueId(), list);
            p.sendMessage("§cDeleted §e" + e.name() + "§c.");
            openList(p, 0);
        });
        set(m, 15, item(Material.LIME_CONCRETE, "§a§lCancel"), () -> openServer(p, e, true));
        p.openInventory(m.inv);
    }

    // ───────────── add / edit through chat ─────────────
    private void ask(Player p, String question, Consumer<String> cb) {
        p.sendMessage(question + " §8(type §ccancel §8to stop)");
        pending.put(p.getUniqueId(), cb);
    }

    private static String keep(String input, Entry ex, String current) {
        return ex != null && input.equals("-") ? current : input;
    }

    private void editFlow(Player p, Entry ex) {
        p.closeInventory();
        String hint = ex == null ? "" : " §7(or §f-§7 to keep)";
        ask(p, "§eType the server §lname" + hint + "§e:", name ->
        ask(p, "§eType the server §lIP / domain" + hint + "§e (no port):", host ->
        ask(p, "§eType the §lport" + hint + "§e (Java default 25565):", port -> {
            UUID id = p.getUniqueId();
            List<Entry> list = pl.store().load(id);
            Store.Result r = pl.store().validate(
                    keep(name, ex, ex == null ? "" : ex.name()),
                    keep(host, ex, ex == null ? "" : ex.host()),
                    keep(port, ex, ex == null ? "" : String.valueOf(ex.port())),
                    list, ex == null ? null : ex.id(), false);
            if (r.error() != null) {
                p.sendMessage("§c" + r.error());
                openList(p, 0);
                return;
            }
            Entry c = r.entry();
            if (ex == null) {
                if (list.size() >= pl.maxServers()) {
                    p.sendMessage("§cThe list is full (" + pl.maxServers() + " servers max).");
                    return;
                }
                list.add(new Entry(Store.newId(), c.name(), c.host(), c.port(), pl.defaultIcon()));
                p.sendMessage("§aAdded §e" + c.name() + "§a to the server list.");
            } else {
                int i = 0;
                while (i < list.size() && !list.get(i).id().equals(ex.id())) i++;
                if (i == list.size()) {
                    p.sendMessage("§cThat server no longer exists.");
                    openList(p, 0);
                    return;
                }
                list.set(i, new Entry(ex.id(), c.name(), c.host(), c.port(), ex.icon()));
                p.sendMessage("§aSaved §e" + c.name() + "§a.");
            }
            pl.store().save(id, list);
            openList(p, 0);
        })));
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        Consumer<String> cb = pending.remove(e.getPlayer().getUniqueId());
        if (cb == null) return;
        e.setCancelled(true);
        Player p = e.getPlayer();
        String msg = e.getMessage().trim();
        Bukkit.getScheduler().runTask(pl, () -> {
            if (msg.equalsIgnoreCase("cancel")) {
                p.sendMessage("§7Cancelled.");
                openList(p, 0);
            } else {
                cb.accept(msg);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) { pending.remove(e.getPlayer().getUniqueId()); }

    // ───────────── clicks ─────────────
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Menu m)) return;
        e.setCancelled(true);
        if (e.getClickedInventory() != e.getInventory()) return;
        Runnable r = m.actions.get(e.getSlot());
        if (r != null) Bukkit.getScheduler().runTask(pl, r);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Menu) e.setCancelled(true);
    }
}
