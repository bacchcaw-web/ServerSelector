package gg.sheeesh.serverselector;

import gg.sheeesh.serverselector.Store.Entry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.CustomForm;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.util.FormImage;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Bedrock form menus (Floodgate + Cumulus).
 * This is the ONLY class that touches Floodgate, so the plugin still works for Java players if Floodgate is missing.
 */
final class BedrockUI {

    private BedrockUI() {}

    private static final FormImage.Type PATH = FormImage.Type.PATH;

    // ───────────── helpers ─────────────
    static boolean isBedrock(UUID id) {
        return FloodgateApi.getInstance().isFloodgatePlayer(id);
    }

    static boolean transfer(UUID id, String host, int port) {
        return FloodgateApi.getInstance().transferPlayer(id, host, port);
    }

    // Form handlers run off the main thread — hop back before touching Bukkit
    private static void sync(ServerSelector pl, Runnable r) {
        Bukkit.getScheduler().runTask(pl, r);
    }

    private static void send(Player p, SimpleForm.Builder f) {
        FloodgateApi.getInstance().sendForm(p.getUniqueId(), f);
    }

    private static void send(Player p, CustomForm.Builder f) {
        FloodgateApi.getInstance().sendForm(p.getUniqueId(), f);
    }

    private static String addr(Entry s) { return s.host() + ":" + s.port(); }

    private static String icon(ServerSelector pl, Entry s) {
        return s.icon() == null || s.icon().isEmpty() ? pl.defaultIcon() : s.icon();
    }

    private static String text(Object o) { return o == null ? "" : String.valueOf(o); }

    private static Entry find(ServerSelector pl, Player p, String id) {
        for (Entry s : pl.store().load(p.getUniqueId())) if (s.id().equals(id)) return s;
        return null;
    }

    // ───────────── main list ─────────────
    static void openList(ServerSelector pl, Player p) {
        List<Entry> mine = pl.store().load(p.getUniqueId());
        List<Entry> rec = pl.recommended(true);

        SimpleForm.Builder f = SimpleForm.builder()
                .title("§l§aServer List")
                .content("§6★ Recommended §7servers are at the top, your own servers are at the bottom.\n§7Your servers: §f"
                        + mine.size() + "/" + pl.maxServers());
        for (Entry s : rec) f.button("§l§6★ " + s.name() + "\n§r§7" + addr(s), PATH, icon(pl, s));
        f.button("§l§2+ Add Server", PATH, "textures/ui/color_plus");
        for (Entry s : mine) f.button("§l" + s.name() + "\n§r§7" + addr(s), PATH, icon(pl, s));

        f.validResultHandler(r -> sync(pl, () -> {
            int sel = r.clickedButtonId();
            if (sel < rec.size()) {
                openRecommended(pl, p, rec.get(sel));
            } else if (sel == rec.size()) {
                if (mine.size() >= pl.maxServers())
                    p.sendMessage("§cYour list is full (" + pl.maxServers() + " servers max). Delete one to add another.");
                else openEditor(pl, p, null);
            } else {
                int idx = sel - rec.size() - 1;
                if (idx < mine.size()) openServer(pl, p, mine.get(idx).id());
            }
        }));
        send(p, f);
    }

    // ───────────── recommended: Join / View IP / Back ─────────────
    private static void openRecommended(ServerSelector pl, Player p, Entry s) {
        SimpleForm.Builder f = SimpleForm.builder()
                .title("§l§6★ " + s.name())
                .content("§7Address: §f" + s.host() + "\n§7Port: §f" + s.port()
                        + "\n\n§8Recommended servers can't be edited or deleted.")
                .button("§l§aJoin", PATH, icon(pl, s))
                .button("§l§bView / Copy IP & Port", PATH, "textures/items/paper")
                .button("§l§7Back", PATH, "textures/ui/arrow_left");
        f.validResultHandler(r -> sync(pl, () -> {
            switch (r.clickedButtonId()) {
                case 0 -> pl.startTransfer(p, s);
                case 1 -> openCopyInfo(pl, p, s);
                default -> openList(pl, p);
            }
        }));
        send(p, f);
    }

    // Text fields so the player can tap, select and copy the IP and port
    private static void openCopyInfo(ServerSelector pl, Player p, Entry s) {
        CustomForm.Builder f = CustomForm.builder()
                .title("§l" + s.name() + " §8(X = Back)")
                .input("Server IP / domain (tap the text to select and copy)", "IP", s.host())
                .input("Port", "Port", String.valueOf(s.port()));
        f.validResultHandler(r -> sync(pl, () -> openRecommended(pl, p, s)));
        f.closedOrInvalidResultHandler(() -> sync(pl, () -> openRecommended(pl, p, s)));
        send(p, f);
    }

    // ───────────── your own server: Join / Icon / Edit / Delete / Back ─────────────
    private static void openServer(ServerSelector pl, Player p, String id) {
        Entry s = find(pl, p, id);
        if (s == null) {
            p.sendMessage("§cThat server no longer exists.");
            openList(pl, p);
            return;
        }
        SimpleForm.Builder f = SimpleForm.builder()
                .title("§l" + s.name())
                .content("§7Address: §f" + s.host() + "\n§7Port: §f" + s.port())
                .button("§l§aJoin", PATH, icon(pl, s))
                .button("§l§dChange Icon", PATH, "textures/items/feather")
                .button("§l§eEdit " + s.name(), PATH, "textures/items/book_writable")
                .button("§l§cDelete", PATH, "textures/ui/cancel")
                .button("§l§7Back", PATH, "textures/ui/arrow_left");
        f.validResultHandler(r -> sync(pl, () -> {
            switch (r.clickedButtonId()) {
                case 0 -> pl.startTransfer(p, s);
                case 1 -> openChangeIcon(pl, p, s);
                case 2 -> showEditor(pl, p, s, s.name(), s.host(), String.valueOf(s.port()), icon(pl, s));
                case 3 -> openDelete(pl, p, s);
                default -> openList(pl, p);
            }
        }));
        send(p, f);
    }

    private static void openChangeIcon(ServerSelector pl, Player p, Entry s) {
        pickIcon(pl, p, icon(pl, s), true, "", (picked, back) -> {
            if (back) {
                openServer(pl, p, s.id());
                return;
            }
            List<Entry> list = pl.store().load(p.getUniqueId());
            for (int i = 0; i < list.size(); i++) {
                Entry e = list.get(i);
                if (e.id().equals(s.id())) {
                    list.set(i, new Entry(e.id(), e.name(), e.host(), e.port(), picked));
                    pl.store().save(p.getUniqueId(), list);
                    p.sendMessage("§aIcon changed for §e" + s.name() + "§a.");
                    openServer(pl, p, s.id());
                    return;
                }
            }
            p.sendMessage("§cThat server no longer exists.");
            openList(pl, p);
        });
    }

    // ───────────── icon picker with Back + Search ─────────────
    // cb(icon, back): back = true for the Back button. X (close) = nothing happens.
    private static void pickIcon(ServerSelector pl, Player p, String current, boolean allowKeep, String query,
                                 BiConsumer<String, Boolean> cb) {
        List<String[]> matches = new ArrayList<>();
        for (String[] i : pl.icons()) if (i[0].toLowerCase().contains(query.toLowerCase())) matches.add(i);

        boolean keepBtn = allowKeep && query.isEmpty();
        int offset = keepBtn ? 3 : 2;

        SimpleForm.Builder f = SimpleForm.builder()
                .title(query.isEmpty() ? "§l§aChoose an Icon" : "§l§aSearch Results")
                .content(query.isEmpty()
                        ? "§7Pick an image for your server. Scroll down to see all of them."
                        : "§7Results for §f\"" + query + "\"§7: §f" + matches.size())
                .button("§l§7Back", PATH, "textures/ui/arrow_left")
                .button(query.isEmpty() ? "§l§eSearch" : "§l§eSearch again", PATH, "textures/ui/magnifyingGlass");
        if (keepBtn) f.button("§l§aKeep current icon", PATH, current);
        for (String[] i : matches) f.button(i[0], PATH, i[1]);

        f.validResultHandler(r -> sync(pl, () -> {
            int sel = r.clickedButtonId();
            if (sel == 0) {
                if (!query.isEmpty()) pickIcon(pl, p, current, allowKeep, "", cb); // Back from results → full list
                else cb.accept(null, true);                                         // Back from full list → previous menu
            } else if (sel == 1) {
                askSearch(pl, p, query, q -> pickIcon(pl, p, current, allowKeep, q == null ? query : q, cb));
            } else if (keepBtn && sel == 2) {
                cb.accept(current, false);
            } else {
                int idx = sel - offset;
                if (idx >= 0 && idx < matches.size()) cb.accept(matches.get(idx)[1], false);
            }
        }));
        send(p, f);
    }

    // cb gets the typed text, or null if the player closed the form
    private static void askSearch(ServerSelector pl, Player p, String current, Consumer<String> cb) {
        CustomForm.Builder f = CustomForm.builder()
                .title("§l§eSearch Icons §8(X = Back)")
                .input("Type an item name", "e.g. sword", current);
        f.validResultHandler(r -> {
            String q = text(r.next()).replace("§", "").trim();
            sync(pl, () -> cb.accept(q));
        });
        f.closedOrInvalidResultHandler(() -> sync(pl, () -> cb.accept(null)));
        send(p, f);
    }

    // ───────────── add / edit ─────────────
    // existing == null → add new (pick the icon first), otherwise edit that server
    private static void openEditor(ServerSelector pl, Player p, Entry existing) {
        pickIcon(pl, p, null, false, "", (icon, back) -> {
            if (back) openList(pl, p);
            else showEditor(pl, p, existing, "", "", "19132", icon);
        });
    }

    private static void showEditor(ServerSelector pl, Player p, Entry existing, String name, String host, String port, String icon) {
        CustomForm.Builder f = CustomForm.builder()
                .title(existing != null ? "§lEdit Server §8(X = Back)" : "§lAdd Server §8(X = Back)")
                .input("Server name", "e.g. WorldSMP", name)
                .input("Server IP / domain", "e.g. play.example.com", host)
                .input("Port", "19132", port);

        f.validResultHandler(r -> {
            String n = text(r.next());
            String h = text(r.next());
            String pt = text(r.next());
            sync(pl, () -> {
                List<Entry> list = pl.store().load(p.getUniqueId());
                Store.Result res = pl.store().validate(n, h, pt, list, existing == null ? null : existing.id(), true);
                if (res.error() != null) {
                    p.sendMessage("§c" + res.error());
                    showEditor(pl, p, existing, n, h, pt, icon);
                    return;
                }
                Entry c = res.entry();
                if (existing != null) {
                    int i = 0;
                    while (i < list.size() && !list.get(i).id().equals(existing.id())) i++;
                    if (i == list.size()) {
                        p.sendMessage("§cThat server no longer exists.");
                        openList(pl, p);
                        return;
                    }
                    list.set(i, new Entry(existing.id(), c.name(), c.host(), c.port(), icon));
                    p.sendMessage("§aSaved §e" + c.name() + "§a.");
                } else {
                    if (list.size() >= pl.maxServers()) {
                        p.sendMessage("§cThe list is full (" + pl.maxServers() + " servers max).");
                        return;
                    }
                    list.add(new Entry(Store.newId(), c.name(), c.host(), c.port(), icon));
                    p.sendMessage("§aAdded §e" + c.name() + "§a to the server list.");
                }
                pl.store().save(p.getUniqueId(), list);
                openList(pl, p);
            });
        });
        f.closedOrInvalidResultHandler(() -> sync(pl, () -> {
            if (existing != null) openServer(pl, p, existing.id());
            else openList(pl, p);
        }));
        send(p, f);
    }

    // ───────────── delete ─────────────
    private static void openDelete(ServerSelector pl, Player p, Entry s) {
        SimpleForm.Builder f = SimpleForm.builder()
                .title("§l§cDelete Server")
                .content("§7Are you sure you want to delete §e" + s.name() + "§7?\n§8" + addr(s))
                .button("§l§cYes, delete", PATH, "textures/ui/check")
                .button("§l§7Cancel", PATH, "textures/ui/cancel");
        f.validResultHandler(r -> sync(pl, () -> {
            if (r.clickedButtonId() != 0) {
                openServer(pl, p, s.id());
                return;
            }
            List<Entry> list = pl.store().load(p.getUniqueId());
            list.removeIf(e -> e.id().equals(s.id()));
            pl.store().save(p.getUniqueId(), list);
            p.sendMessage("§cDeleted §e" + s.name() + "§c.");
            openList(pl, p);
        }));
        send(p, f);
    }
}
