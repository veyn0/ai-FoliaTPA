package dev.veyno.aiFoliaTPA;

import java.util.Map;
import java.util.Objects;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;

public class MessageService {
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final FileConfiguration config;

    public MessageService(AiFoliaTPA plugin) {
        this.config = plugin.getConfig();
    }

    public void send(CommandSender sender, String key) {
        sender.sendMessage(message(key, Map.of()));
    }

    public void send(CommandSender sender, String key, Map<String, String> placeholders) {
        sender.sendMessage(message(key, placeholders));
    }

    public Component message(String key, Map<String, String> placeholders) {
        String base = config.getString("messages." + key, "<red>Missing message: " + key + "</red>");
        String prefix = Objects.toString(config.getString("messages.prefix"), "");
        String resolved = base.replace("{prefix}", prefix);
        TagResolver.Builder builder = TagResolver.builder();
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            builder.resolver(Placeholder.parsed(entry.getKey(), entry.getValue()));
        }
        return miniMessage.deserialize(resolved, builder.build());
    }

    public int getInt(String key, int def) {
        return config.getInt(key, def);
    }
}
