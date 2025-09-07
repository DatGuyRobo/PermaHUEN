package com.permahuen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.chunk.ChunkTicketType;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class FakePlayerManager {
    private final Map<String, ServerPlayerEntity> fakePlayers = new ConcurrentHashMap<>();
    private final Path configPath = Paths.get("config", PermahuenMod.MOD_ID + ".json");
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    // A custom ticket type to distinguish our chunk loaders
    private static final ChunkTicketType<String> FAKE_PLAYER_TICKET =
            ChunkTicketType.create("fake_player", String::compareTo, 600);

    public boolean spawnPlayer(MinecraftServer server, String name, ServerWorld world, BlockPos pos) {
        if (fakePlayers.containsKey(name.toLowerCase())) {
            return false;
        }

        GameProfile profile = new GameProfile(null, name);
        FakePlayer player = FakePlayer.create(world, profile);
        
        player.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0, 0);
        player.changeGameMode(GameMode.SURVIVAL);

        // Make the player invincible
        player.setInvulnerable(true);
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, -1, 4, false, false));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, -1, 0, false, false));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.WATER_BREATHING, -1, 0, false, false));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.SATURATION, -1, 0, false, false));
        player.getHungerManager().setFoodLevel(20);

        world.spawnNewEntity(player);
        forceLoadChunk(world, player.getBlockPos());
        fakePlayers.put(name.toLowerCase(), player);
        
        PermahuenMod.LOGGER.info("Spawned fake player '{}' at {}", name, pos.toShortString());
        save();
        return true;
    }

    public boolean killPlayer(String name) {
        ServerPlayerEntity player = fakePlayers.remove(name.toLowerCase());
        if (player != null) {
            unLoadChunk((ServerWorld) player.getWorld(), player.getBlockPos());
            player.kill(); // This removes the entity from the world
            PermahuenMod.LOGGER.info("Killed fake player '{}'", name);
            save();
            return true;
        }
        return false;
    }

    public List<ServerPlayerEntity> listPlayers() {
        return new ArrayList<>(fakePlayers.values());
    }

    private void forceLoadChunk(ServerWorld world, BlockPos pos) {
        world.getChunkManager().addTicket(FAKE_PLAYER_TICKET, pos.toChunkPos(), 3, pos.toShortString());
        PermahuenMod.LOGGER.info("Forceloading chunk at {}", pos.toChunkPos());
    }

    private void unLoadChunk(ServerWorld world, BlockPos pos) {
        world.getChunkManager().removeTicket(FAKE_PLAYER_TICKET, pos.toChunkPos(), 3, pos.toShortString());
        PermahuenMod.LOGGER.info("Unloading chunk at {}", pos.toChunkPos());
    }

    public void save() {
        List<FakePlayerData> playerDataList = new ArrayList<>();
        for (ServerPlayerEntity player : fakePlayers.values()) {
            playerDataList.add(new FakePlayerData(
                    player.getName().getString(),
                    player.getWorld().getRegistryKey().getValue().toString(),
                    player.getX(),
                    player.getY(),
                    player.getZ()
            ));
        }

        try {
            Files.createDirectories(configPath.getParent());
            try (FileWriter writer = new FileWriter(configPath.toFile())) {
                gson.toJson(playerDataList, writer);
            }
        } catch (IOException e) {
            PermahuenMod.LOGGER.error("Failed to save fake players:", e);
        }
    }

    public void load(MinecraftServer server) {
        if (!Files.exists(configPath)) {
            return;
        }

        try (FileReader reader = new FileReader(configPath.toFile())) {
            Type listType = new TypeToken<ArrayList<FakePlayerData>>() {}.getType();
            List<FakePlayerData> playerDataList = gson.fromJson(reader, listType);

            if (playerDataList != null) {
                for (FakePlayerData data : playerDataList) {
                    Optional<ServerWorld> worldOpt = server.getWorldRegistryKeys().stream()
                            .filter(key -> key.getValue().toString().equals(data.world()))
                            .findFirst()
                            .map(server::getWorld);

                    if (worldOpt.isPresent()) {
                        BlockPos pos = new BlockPos((int)data.x(), (int)data.y(), (int)data.z());
                        spawnPlayer(server, data.name(), worldOpt.get(), pos);
                    } else {
                        PermahuenMod.LOGGER.warn("World '{}' for fake player '{}' not found.", data.world(), data.name());
                    }
                }
                PermahuenMod.LOGGER.info("Loaded {} fake players from config.", playerDataList.size());
            }
        } catch (IOException e) {
            PermahuenMod.LOGGER.error("Failed to load fake players:", e);
        }
    }

    private record FakePlayerData(String name, String world, double x, double y, double z) {}
}