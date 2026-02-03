package dev.veyno.aiFoliaTPA;

import org.bukkit.plugin.java.JavaPlugin;

public final class AiFoliaTPA extends JavaPlugin {
    private MessageService messageService;
    private TpaManager tpaManager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        messageService = new MessageService(this);
        tpaManager = new TpaManager(this, messageService);

        getServer().getPluginManager().registerEvents(tpaManager, this);
        registerCommands();
    }

    @Override
    public void onDisable() {
        if (tpaManager != null) {
            tpaManager.shutdown();
        }
    }

    private void registerCommands() {
        if (getCommand("tpa") != null) {
            getCommand("tpa").setExecutor(new SendRequestCommand(tpaManager, RequestType.TPA));
            getCommand("tpa").setTabCompleter(new PlayerTabCompleter());
        }
        if (getCommand("tpahere") != null) {
            getCommand("tpahere").setExecutor(new SendRequestCommand(tpaManager, RequestType.TPAHERE));
            getCommand("tpahere").setTabCompleter(new PlayerTabCompleter());
        }
        if (getCommand("tpaccept") != null) {
            getCommand("tpaccept").setExecutor(new AcceptRequestCommand(tpaManager, RequestType.TPA));
            getCommand("tpaccept").setTabCompleter(new PlayerTabCompleter());
        }
        if (getCommand("tpahereaccept") != null) {
            getCommand("tpahereaccept").setExecutor(new AcceptRequestCommand(tpaManager, RequestType.TPAHERE));
            getCommand("tpahereaccept").setTabCompleter(new PlayerTabCompleter());
        }
    }
}
