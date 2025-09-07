package com.permahuen;

import com.mojang.authlib.GameProfile;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * FakePlayerManager for 1.21.x:
 * - JSON persistence in the world save directory
 * - Force-load chunks via ServerWorld#setChunkForced
 * - API compatible with PermahuenMod/PermahuenCommands (load/save/spawnPlayer/killPlayer/listPlayers)
 */
public class FakePlayerManager {

    /* ---------- Public API expected by your other classes ---------- */

    public void load(MinecraftServer server) {
        this.server = server;
        readFromDisk();
        // Respawn all persisted players
        for (Stored s : records.values()) {
            try {
                ServerWorld world = server.getWorld(s.dimension());
                if (world == null) continue;
                GameProfile profile = (s.uuid != null) ? new GameProfile(s.uuid, s.name) : new GameProfile(null, s.name);
                ServerPlayerEntity fp = FakePlayer.get(world, profile);
                BlockPos pos = new BlockPos(s.x, s.y, s.z);
                fp.refreshPositionAndAngles(s.x + 0.5, s.y, s.z + 0.5, fp.getYaw(), fp.getPitch());
                if (!world.getPlayers().contains(fp)) world.spawnEntity(fp);
                applyBuffs(fp);
                forceChunks(world, pos, s.radius, true);
            } catch (Exception ignored) { }
        }
    }

    public void save() {
        writeToDisk();
    }

    /**
     * Spawn (and persist) a fake player at pos in world.
     * Returns true if spawned/updated successfully.
     */
    public boolean spawnPlayer(MinecraftServer server, String name, ServerWorld world, BlockPos pos) {
        ensureServer(server);
        if (world == null || name == null || name.isBlank()) return false;

        // If already stored, keep existing radius; else default radius=1
        Stored prev = records.get(name);
        int radius = (prev != null) ? prev.radius : 1;

        GameProfile profile = (prev != null && prev.uuid != null)
                ? new GameProfile(prev.uuid, name)
                : new GameProfile(null, name);

        ServerPlayerEntity fp = FakePlayer.get(world, profile);
        fp.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, fp.getYaw(), fp.getPitch());
        if (!world.getPlayers().contains(fp)) world.spawnEntity(fp);
        applyBuffs(fp);

        forceChunks(world, pos, radius, true);

        Stored s = new Stored(profile.getId(), name, world.getRegistryKey(), pos.getX(), pos.getY(), pos.getZ(), radius);
        records.put(name, s);
        writeToDisk();
        return true;
    }

    /**
     * Despawn and forget a fake player by name.
     * Returns true if removed.
     */
    public boolean killPlayer(String name) {
        if (server == null) return false;
        Stored s = records.remove(name);
        if (s == null) return false;

        ServerWorld world = server.getWorld(s.dimension());
        if (world != null) {
            forceChunks(world, new BlockPos(s.x, s.y, s.z), s.radius, false);
            GameProfile profile = (s.uuid != null) ? new GameProfile(s.uuid, s.name) : new GameProfile(null, s.name);
            ServerPlayerEntity fp = FakePlayer.get(world, profile);
            if (fp != null) fp.discard();
        }
        writeToDisk();
        return true;
    }

    /**
     * Returns currently persisted+known fake players as entities if present,
     * falling back to recreating the FakePlayer handles (not spawning).
     */
    public Collection<ServerPlayerEntity> listPlayers() {
        List<ServerPlayerEntity> out = new ArrayList<>();
        if (server == null) return out;
        for (Stored s : records.values()) {
            ServerWorld world = server.getWorld(s.dimension());
            if (world == null) continue;
            GameProfile profile = (s.uuid != null) ? new GameProfile(s.uuid, s.name) : new GameProfile(null, s.name);
            ServerPlayerEntity fp = FakePlayer.get(world, profile);
            out.add(fp);
        }
        return out;
    }

    /* ---------- Extra helpers if you want a command to change radius ---------- */

    public boolean setRadius(String name, int radius) {
        if (radius < 0) radius = 0;
        Stored s = records.get(name);
        if (s == null) return false;
        records.put(name, new Stored(s.uuid, s.name, s.dimension, s.x, s.y, s.z, radius));
        writeToDisk();
        return true;
    }

    /* ---------- Internals ---------- */

    private MinecraftServer server;
    private final Map<String, Stored> records = new LinkedHashMap<>();

    private void ensureServer(MinecraftServer srv) {
        if (this.server == null) this.server = srv;
    }

    private static void forceChunks(ServerWorld world, BlockPos pos, int radius, boolean force) {
        ChunkPos cp = new ChunkPos(pos);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                world.setChunkForced(cp.x + dx, cp.z + dz, force);
            }
        }
    }

    /** Survival-safe effects using 1.21.x RegistryEntry effects API. */
    private static void applyBuffs(ServerPlayerEntity p) {
        p.setInvulnerable(true);
        ensureEffect(p, StatusEffects.RESISTANCE,       20 * 60 * 60, 4);
        ensureEffect(p, StatusEffects.REGENERATION,     20 * 60 * 60, 2);
        ensureEffect(p, StatusEffects.SATURATION,       20 * 60 * 60, 0);
        ensureEffect(p, StatusEffects.FIRE_RESISTANCE,  20 * 60 * 60, 0);
        ensureEffect(p, StatusEffects.WATER_BREATHING,  20 * 60 * 60, 0);
    }

    private static void ensureEffect(ServerPlayerEntity p, RegistryEntry<net.minecraft.entity.effect.StatusEffect> effect,
                                     int duration, int amplifier) {
        StatusEffectInstance cur = p.getStatusEffect(effect);
        if (cur == null || cur.getDuration() < 20 * 30 || cur.getAmplifier() < amplifier) {
            p.addStatusEffect(new StatusEffectInstance(effect, duration, amplifier, true, false));
        }
    }

    /* ---------- Persistence (JSON in world save dir) ---------- */

    private record Stored(UUID uuid, String name, RegistryKey<World> dimension, int x, int y, int z, int radius) {
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type STORED_LIST = new TypeToken<List<StoredJson>>(){}.getType();
    private static final String FILE_NAME = "permahuen_fakeplayers.json";

    private Path filePath() {
        if (server == null) return null;
        Path root = server.getSavePath(WorldSavePath.ROOT);
        return root.resolve(FILE_NAME);
    }

    private void readFromDisk() {
        records.clear();
        Path file = filePath();
        if (file == null || !Files.exists(file)) return;

        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            List<StoredJson> list = GSON.fromJson(r, STORED_LIST);
            if (list == null) return;
            for (StoredJson j : list) {
                Identifier id = Identifier.tryParse(j.dimension);
                if (id == null) continue;
                RegistryKey<World> dim = RegistryKey.of(RegistryKeys.WORLD, id);
                Stored s = new Stored(j.uuid, j.name, dim, j.x, j.y, j.z, j.radius);
                records.put(s.name, s);
            }
        } catch (IOException ignored) { }
    }

    private void writeToDisk() {
        Path file = filePath();
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            List<StoredJson> list = new ArrayList<>();
            for (Stored s : records.values()) {
                list.add(new StoredJson(s.uuid, s.name, s.dimension.getValue().toString(), s.x, s.y, s.z, s.radius));
            }
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                GSON.toJson(list, STORED_LIST, w);
            }
        } catch (IOException ignored) { }
    }

    private static class StoredJson {
        UUID uuid;
        String name;
        String dimension; // "minecraft:overworld", etc.
        int x, y, z;
        int radius;

        StoredJson(UUID uuid, String name, String dimension, int x, int y, int z, int radius) {
            this.uuid = uuid;
            this.name = name;
            this.dimension = dimension;
            this.x = x; this.y = y; this.z = z;
            this.radius = radius;
        }
    }

    /* ---------- Optional pretty output for /list ---------- */

    public void sendListTo(net.minecraft.server.command.ServerCommandSource src) {
        if (records.isEmpty()) {
            src.sendFeedback(() -> Text.literal("No persistent fake players."), false);
            return;
        }
        for (Stored s : records.values()) {
            src.sendFeedback(() -> Text.literal(
                "- " + s.name + " @ " + s.x + "," + s.y + "," + s.z +
                " in " + s.dimension.getValue() + " (radius " + s.radius + ")"
            ), false);
        }
    }
}
