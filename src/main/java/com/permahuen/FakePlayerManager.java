package com.permahuen;

import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateManager;
import net.minecraft.world.World;

import java.util.*;

/**
 * Manager for persistent, chunk-loading fake players.
 *
 * Public API:
 *  - spawn(server, name, world, pos, radius)
 *  - kill(server, name)
 *  - list(server)
 *  - respawnAll(server)  // call on SERVER_STARTED
 */
public class FakePlayerManager {

    /** Spawns (or updates) a persistent fake player at the given position and radius. */
    public static ServerPlayerEntity spawn(MinecraftServer server, String name, ServerWorld world, BlockPos pos, int radius) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(name, "name");
        if (radius < 0) radius = 0;

        // Build a GameProfile and try to pull the user's skin
        GameProfile profile = new GameProfile(null, name);
        try {
            server.getSessionService().fillProfileProperties(profile, true);
        } catch (Exception ignored) { }

        // Get/create the FakePlayer instance
        ServerPlayerEntity fp = FakePlayer.get(world, profile);

        // Move to target position and ensure it ticks
        fp.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, fp.getYaw(), fp.getPitch());
        if (!world.getPlayers().contains(fp)) {
            world.spawnEntity(fp);
        }

        // Survival-safe buffs
        applyBuffs(fp);

        // Keep chunks loaded in a square radius
        forceChunks(world, pos, radius, true);

        // Persist record
        State state = State.get(server);
        Stored stored = new Stored(fp.getGameProfile().getId(), name, world.getRegistryKey(), pos, radius);
        state.put(stored);
        state.markDirty();

        return fp;
    }

    /** Despawns and removes a persistent fake player by name. */
    public static boolean kill(MinecraftServer server, String name) {
        Objects.requireNonNull(server, "server");
        Objects.requireNonNull(name, "name");

        State state = State.get(server);
        Stored stored = state.remove(name);
        if (stored == null) return false;

        ServerWorld world = server.getWorld(stored.dimension());
        if (world != null) {
            // Unforce chunks
            forceChunks(world, stored.pos(), stored.radius(), false);

            // Discard entity if present
            GameProfile profile = new GameProfile(stored.uuid(), stored.name());
            ServerPlayerEntity fp = FakePlayer.get(world, profile);
            if (fp != null) {
                fp.discard();
            }
        }

        state.markDirty();
        return true;
    }

    /** Returns all stored fake players (immutable). */
    public static Collection<Stored> list(MinecraftServer server) {
        return Collections.unmodifiableCollection(State.get(server).all());
    }

    /** Re-spawn all stored fake players. Call this on SERVER_STARTED. */
    public static void respawnAll(MinecraftServer server) {
        State state = State.get(server);
        int count = 0;
        for (Stored s : state.all()) {
            try {
                ServerWorld world = server.getWorld(s.dimension());
                if (world == null) continue;

                GameProfile profile = new GameProfile(s.uuid(), s.name());
                try { server.getSessionService().fillProfileProperties(profile, true); } catch (Exception ignored) { }

                ServerPlayerEntity fp = FakePlayer.get(world, profile);
                BlockPos pos = s.pos();
                fp.refreshPositionAndAngles(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, fp.getYaw(), fp.getPitch());
                if (!world.getPlayers().contains(fp)) {
                    world.spawnEntity(fp);
                }
                applyBuffs(fp);
                forceChunks(world, pos, s.radius(), true);
                count++;
            } catch (Exception e) {
                server.getLogger().error("[FakePlayerManager] Failed to respawn {}: {}", s.name(), e.toString());
            }
        }
        server.getLogger().info("[FakePlayerManager] Respawned {} fake players.", count);
    }

    /* ------------------------------------------------------------ */
    /* Helpers                                                      */
    /* ------------------------------------------------------------ */

    private static void forceChunks(ServerWorld world, BlockPos pos, int radius, boolean force) {
        ChunkPos cp = new ChunkPos(pos);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                world.setChunkForced(cp.x + dx, cp.z + dz, force);
            }
        }
    }

    /** Survival-safe: invulnerable + long-duration defensive effects. */
    private static void applyBuffs(ServerPlayerEntity p) {
        p.setInvulnerable(true);
        ensureEffect(p, StatusEffects.RESISTANCE,       20 * 60 * 60, 4); // Resistance V
        ensureEffect(p, StatusEffects.REGENERATION,     20 * 60 * 60, 2); // Regeneration III
        ensureEffect(p, StatusEffects.SATURATION,       20 * 60 * 60, 0); // No hunger
        ensureEffect(p, StatusEffects.FIRE_RESISTANCE,  20 * 60 * 60, 0);
        ensureEffect(p, StatusEffects.WATER_BREATHING,  20 * 60 * 60, 0);
    }

    private static void ensureEffect(ServerPlayerEntity p, net.minecraft.entity.effect.StatusEffect effect, int duration, int amplifier) {
        StatusEffectInstance cur = p.getStatusEffect(effect);
        if (cur == null || cur.getDuration() < 20 * 30 || cur.getAmplifier() < amplifier) {
            p.addStatusEffect(new StatusEffectInstance(effect, duration, amplifier, true, false));
        }
    }

    /* ------------------------------------------------------------ */
    /* Persistent State                                             */
    /* ------------------------------------------------------------ */

    /** Immutable stored record. */
    public record Stored(UUID uuid, String name, RegistryKey<World> dimension, BlockPos pos, int radius) { }

    /** Persistent state kept in the Overworld's save data. */
    static class State extends PersistentState {
        private static final String NAME = "permahuen_fakeplayer_state_v1";

        private final Map<String, Stored> byName = new LinkedHashMap<>();

        static State get(MinecraftServer server) {
            PersistentStateManager mgr = server.getOverworld().getPersistentStateManager();
            return mgr.getOrCreate(State::fromNbt, State::new, NAME);
        }

        Collection<Stored> all() { return byName.values(); }

        void put(Stored s) {
            byName.put(s.name(), s);
            markDirty();
        }

        Stored remove(String name) {
            Stored s = byName.remove(name);
            if (s != null) markDirty();
            return s;
        }

        @Override
        public NbtCompound writeNbt(NbtCompound nbt, NbtOps ops) {
            NbtList list = new NbtList();
            for (Stored s : byName.values()) {
                NbtCompound t = new NbtCompound();
                if (s.uuid() != null) t.putUuid("uuid", s.uuid());
                t.putString("name", s.name());
                t.putString("dim", s.dimension().getValue().toString());
                t.putInt("x", s.pos().getX());
                t.putInt("y", s.pos().getY());
                t.putInt("z", s.pos().getZ());
                t.putInt("radius", s.radius());
                list.add(t);
            }
            nbt.put("records", list);
            return nbt;
        }

        static State fromNbt(NbtCompound nbt, NbtOps ops) {
            State st = new State();
            NbtList list = nbt.getList("records", NbtElement.COMPOUND_TYPE);
            for (int i = 0; i < list.size(); i++) {
                NbtCompound t = list.getCompound(i);

                UUID uuid = t.containsUuid("uuid") ? t.getUuid("uuid") : null;
                String name = t.getString("name");

                // Build Identifier using 2-arg constructor to avoid single-arg constructor issues
                String worldId = t.getString("dim");
                Identifier id;
                int idx = worldId.indexOf(':');
                if (idx >= 0) {
                    id = new Identifier(worldId.substring(0, idx), worldId.substring(idx + 1));
                } else {
                    // fallback if stored as "overworld" etc.
                    id = new Identifier("minecraft", worldId);
                }
                RegistryKey<World> dim = RegistryKey.of(RegistryKeys.WORLD, id);

                BlockPos pos = new BlockPos(t.getInt("x"), t.getInt("y"), t.getInt("z"));
                int radius = t.getInt("radius");

                st.byName.put(name, new Stored(uuid, name, dim, pos, radius));
            }
            return st;
        }
    }

    /* ------------------------------------------------------------ */
    /* Optional: pretty listing to a command source                 */
    /* ------------------------------------------------------------ */

    public static void sendListTo(MinecraftServer server, net.minecraft.server.command.ServerCommandSource src) {
        Collection<Stored> all = list(server);
        if (all.isEmpty()) {
            src.sendFeedback(() -> Text.literal("No persistent fake players."), false);
        } else {
            for (Stored s : all) {
                src.sendFeedback(() -> Text.literal(
                    "- " + s.name() + " @ " + s.pos().toShortString() +
                    " in " + s.dimension().getValue() +
                    " (radius " + s.radius() + ")"
                ), false);
            }
        }
    }
}
