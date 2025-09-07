package com.permahuen;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v2.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class permahuen implements ModInitializer {
    public static final String MOD_ID = "permahuen";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static FakePlayerManager playerManager;

    @Override
    public void onInitialize() {
        playerManager = new FakePlayerManager();

        // Register commands
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            PermahuenCommands.register(dispatcher)
        );

        // Register server start event to load players
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            playerManager.load(server);
        });

        // Register server stop event to save players
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            playerManager.save();
        });
    }
}