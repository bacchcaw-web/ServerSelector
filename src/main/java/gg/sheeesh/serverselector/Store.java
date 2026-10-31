package gg.sheeesh.serverselector;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.*;

/** Per-player server lists, saved in plugins/ServerSelector/data.yml */
public final class Store {

    public record Entry(String id, String name, String host, int port, String icon) {}

    public record Result(Entry entry, String error) {}

    private final ServerSelector plugin;
    private final File file;
    private final YamlConfiguration yml;

    Store(ServerSelector plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "data.yml");
        this.yml = YamlConfiguration.loadConfiguration(file);
    }

    public static String newId() {
        return Long.toString(System.currentTimeMillis(), 36) + Integer.toString(new Random().nextInt(1679616), 36);
    }

    private static String str(Map<?, ?> m, String k) {
        Object o = m.get(k);
        return o == null ? "" : String.valueOf(o);
    }

    public List<Entry> load(UUID id) {
        List<Entry> out = new ArrayList<>();
        for (Map<?, ?> m : yml.getMapList("players." + id)) {
            try {
                out.add(new Entry(str(m, "id"), str(m, "name"), str(m, "host"),
                        Integer.parseInt(str(m, "port")), str(m, "icon")));
            } catch (Exception ignored) {}
        }
        return out;
    }

    public void save(UUID id, List<Entry> list) {
        List<Map<String, Object>> raw = new ArrayList<>();
        for (Entry e : list) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", e.id());
            m.put("name", e.name());
            m.put("host", e.host());
            m.put("port", e.port());
            m.put("icon", e.icon());
            raw.add(m);
        }
        yml.set("players." + id, raw);
        try {
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().warning("Could not save data.yml: " + ex.getMessage());
        }
    }

    /** Returns the cleaned values (id/icon left empty) or an error message. */
    public Result validate(String name, String host, String portText, List<Entry> list, String editingId, boolean bedrock) {
        name = name.replace("§", "").trim();
        host = host.trim();
        portText = portText.trim();

        if (name.isEmpty() || name.length() > 24) return err("Name must be 1-24 characters.");
        if (!host.matches("[A-Za-z0-9.\\-]{1,100}"))
            return err("Invalid IP/domain. Use only letters, numbers, dots and dashes (no port, no spaces).");
        if (!portText.matches("\\d{1,5}") || Integer.parseInt(portText) < 1 || Integer.parseInt(portText) > 65535)
            return err("Port must be a number from 1 to 65535.");

        int port = Integer.parseInt(portText);
        for (Entry s : list) {
            if (!s.id().equals(editingId) && s.host().equalsIgnoreCase(host) && s.port() == port)
                return err("That server is already in the list as \"" + s.name() + "\".");
        }
        for (Entry s : plugin.recommended(bedrock)) {
            if (s.host().equalsIgnoreCase(host) && s.port() == port)
                return err("\"" + s.name() + "\" is already in the recommended list.");
        }
        return new Result(new Entry(editingId, name, host, port, ""), null);
    }

    private static Result err(String m) {
        return new Result(null, m);
    }
}
