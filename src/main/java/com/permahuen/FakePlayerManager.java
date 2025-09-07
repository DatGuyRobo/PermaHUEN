package com.permahuen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ChunkTicketType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages lifecycle of Fabric FakePlayers and keeps their spawn chunks loaded.
 * - Uses FORCED chunk tickets with tracked anchors to avoid leaks.
 * - Assigns stable offline UUIDs for consistent identity.
 * - Applies effectively-infinite status effects using Integer.MAX_VALUE.
 * - Persists/restores players across restarts (name, world id, position).
 * - Cleans up chunk tickets on server stop.
 */
public class FakePlayerManager {

    /** Lowercased name -> fake player instance */
    private final Map<String, ServerPlayerEntity> fakePlayers = new ConcurrentHashMap<>();

    /** Lowercased name -> ticket anchor ChunkPos used at spawn time */
    private final Map<String, ChunkPos> ticketAnchors = new ConcurrentHashMap<>();

    private final Path configPath = Paths.get("config", PermahuenMod.MOD_ID + ".json");
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    /** Use vanilla FORCED tickets; argument type: ChunkPos */
    private static final ChunkTicketType<ChunkPos> FORCED = ChunkTicketType.FORCED;

    public FakePlayerManager() {
        // Ensure chunk tickets are removed on clean shutdown even if callers forget.
        ServerLifecycleEvents.SERVER_STOPPING.register(this::cleanupOnStop);
    }

    /* =========================
       === Public API ==========
       ========================= */

    /**
     * Spawns a fake player with the given name at pos in the given world.
     * Keeps the spawn chunk loaded via a FORCED ticket until {@link #killPlayer(String)} is called
     * or the server stops.
     *
     * @return true if spawned; false if a fake with that name already exists
     */
    public boolean spawnPlayer(MinecraftServer server, String name, ServerWorld world, BlockPos pos) {
        final String key = key(name);
        if (fakePlayers.containsKey(key)) {
            return false;
        }

        // Stable offline UUID for consistency across restarts.
        UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        GameProfile profile = new GameProfile(uuid, name);

        // Create or get the FakePlayer.
        ServerPlayerEntity player = FakePlayer.get(world, profile);

        // Position & basic state
        player.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 0f, 0f);

        // Depending on mappings/MC version, changeGameMode or setGameMode may be available.
        try {
            player.changeGameMode(GameMode.SURVIVAL);
        } catch (Throwable t) {
            try {
                // Fallback if changeGameMode isn't present in your target version
                player.setGameMode(GameMode.SURVIVAL);
            } catch (Throwable ignored) {
                // Last resort: ignore—game mode not critical for headless bots
            }
        }

        // Keep the bot durable / maintenance-free.
        final int INF = Integer.MAX_VALUE;
        player.setInvulnerable(true);
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE,      INF, 4, false, false));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.FIRE_RESISTANCE, INF, 0, false, false));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.WATER_BREATHING, INF, 0, false, false));
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.SATURATION,      INF, 0, false, false));
        player.getHungerManager().setFoodLevel(20);
        player.getHungerManager().setSaturationLevel(20f);

        // In most setups, FakePlayer.get(...) provides a valid entity; spawning is typically fine.
        // If you see "already added to world" logs in your environment, comment out the next line.
        world.spawnEntity(player);

        // Forceload spawn chunk and remember the exact anchor used so we can unload correctly later.
        forceLoadChunk(world, key, player.getBlockPos());

        fakePlayers.put(key, player);

        PermahuenMod.LOGGER.info("Spawned fake player '{}' at {} in world {}", name, pos.toShortString(),
                world.getRegistryKey().getValue());
        save();
        return true;
    }

    /**
     * Removes the fake player by name and releases its chunk ticket.
     *
     * @return true if a player with that name existed and was removed; false otherwise.
     */
    public boolean killPlayer(String name) {
        final String key = key(name);
        ServerPlayerEntity player = fakePlayers.remove(key);
        if (player != null) {
            ServerWorld world = (ServerWorld) player.getWorld();
            unLoadChunk(world, key);
            player.remove(ServerPlayerEntity.RemovalReason.KILLED);
            PermahuenMod.LOGGER.info("Killed fake player '{}'", name);
            save();
            return true;
        }
        return false;
    }

    /** Returns an unmodifiable snapshot of current fake players. */
    public List<ServerPlayerEntity> listPlayers() {
        return List.copyOf(fakePlayers.values());
    }

    /** Persist current fake players to disk. */
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

    /** Load previously saved fake players and spawn them. */
    public void load(MinecraftServer server) {
        if (!Files.exists(configPath)) {
            return;
        }

        try (FileReader reader = new FileReader(configPath.toFile())) {
            Type listType = new TypeToken<ArrayList<FakePlayerData>>() {}.getType();
            List<FakePlayerData> playerDataList = gson.fromJson(reader, listType);

            if (playerDataList != null) {
                for (FakePlayerData data : playerDataList) {
                    ServerWorld world = resolveWorld(server, data.world());
                    if (world != null) {
                        BlockPos pos = BlockPos.ofFloored(data.x(), data.y(), data.z());
                        spawnPlayer(server, data.name(), world, pos);
                    } else {
                        PermahuenMod.LOGGER.warn("World '{}' for fake player '{}' not found.", data.world(), data.name());
                    }
                }
                PermahuenMod.LOGGER.info("Loaded {} fake players from config.", playerDataList.size());
            }
        } catch (JsonSyntaxException e) {
            PermahuenMod.LOGGER.error("Malformed fake players JSON at {}: {}", configPath, e.getMessage());
        } catch (IOException e) {
            PermahuenMod.LOGGER.error("Failed to load fake players:", e);
        }
    }

    /* =========================
       === Internals ===========
       ========================= */

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    private static ChunkPos chunkOf(BlockPos pos) {
        return new ChunkPos(pos);
    }

    private void forceLoadChunk(ServerWorld world, String key, BlockPos pos) {
        ChunkPos cp = chunkOf(pos);
        // Level 2 is conventional for permanent forced loading
        world.getChunkManager().addTicket(FORCED, cp, 2, cp);
        ticketAnchors.put(key, cp);
        PermahuenMod.LOGGER.info("Forceloading chunk {}", cp);
    }

    private void unLoadChunk(ServerWorld world, String key) {
        ChunkPos cp = ticketAnchors.remove(key);
        if (cp != null) {
            world.getChunkManager().removeTicket(FORCED, cp, 2, cp);
            PermahuenMod.LOGGER.info("Unloading chunk {}", cp);
        } else {
            PermahuenMod.LOGGER.warn("No ticket anchor recorded for '{}'; nothing to unload.", key);
        }
    }

    private void cleanupOnStop(MinecraftServer server) {
        // Remove any remaining FORCED tickets to avoid leaks across restarts.
        for (Map.Entry<String, ChunkPos> e : ticketAnchors.entrySet()) {
            String k = e.getKey();
            ChunkPos cp = e.getValue();
            ServerPlayerEntity p = fakePlayers.get(k);
            ServerWorld world = null;
            if (p != null) {
                world = (ServerWorld) p.getWorld();
            } else {
                // Fallback: try to infer world from saved data (best effort)
                // Not strictly necessary because tickets are world-scoped;
                // if we don't have the world we can't remove anyway.
            }
            if (world != null) {
                world.getChunkManager().removeTicket(FORCED, cp, 2, cp);
            }
        }
        ticketAnchors.clear();
        PermahuenMod.LOGGER.info("Cleaned up fake player chunk tickets on server stop.");
    }

    private ServerWorld resolveWorld(MinecraftServer server, String worldId) {
        try {
            Identifier id = new Identifier(worldId);
            RegistryKey<World> key = RegistryKey.of(RegistryKeys.WORLD, id);
            return server.getWorld(key);
        } catch (Exception e) {
            PermahuenMod.LOGGER.warn("Invalid world identifier '{}': {}", worldId, e.getMessage());
            return null;
        }
    }

    /* =========================
       === Persistence DTO =====
       ========================= */

    private record FakePlayerData(String name, String world, double x, double y, double z) {}
}
