package dev.veyno.aiFoliaTPA;

import java.util.List;
import java.util.stream.Collectors;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class PendingRequestTabCompleter implements TabCompleter {
    private final TpaManager tpaManager;
    private final RequestType type;

    public PendingRequestTabCompleter(TpaManager tpaManager, RequestType type) {
        this.tpaManager = tpaManager;
        this.type = type;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
                                      @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            return List.of();
        }
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase();
        return tpaManager.getPendingRequesters(player, type).stream()
            .filter(name -> name.toLowerCase().startsWith(prefix))
            .sorted()
            .collect(Collectors.toList());
    }
}
