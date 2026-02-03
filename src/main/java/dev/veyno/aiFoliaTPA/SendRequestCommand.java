package dev.veyno.aiFoliaTPA;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class SendRequestCommand implements CommandExecutor {
    private final TpaManager tpaManager;
    private final RequestType type;

    public SendRequestCommand(TpaManager tpaManager, RequestType type) {
        this.tpaManager = tpaManager;
        this.type = type;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            tpaManager.sendConsoleOnly(sender);
            return true;
        }
        if (args.length != 1) {
            tpaManager.sendUsage(player, "/" + label + " <player>");
            return true;
        }
        tpaManager.sendRequest(player, args[0], type);
        return true;
    }
}
