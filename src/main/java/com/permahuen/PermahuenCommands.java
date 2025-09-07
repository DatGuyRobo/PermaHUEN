package com.permahuen;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.BlockPosArgumentType;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

public class PermahuenCommands {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("permahuen")
            .requires(source -> source.hasPermissionLevel(2)) // Requires op level 2
            .then(CommandManager.literal("spawn")
                .then(CommandManager.argument("name", StringArgumentType.word())
                    .executes(ctx -> spawn(ctx, StringArgumentType.getString(ctx, "name"), null))
                    .then(CommandManager.argument("pos", BlockPosArgumentType.blockPos())
                        .executes(ctx -> spawn(ctx, StringArgumentType.getString(ctx, "name"), BlockPosArgumentType.getBlockPos(ctx, "pos")))
                    )
                )
            )
            .then(CommandManager.literal("kill")
                .then(CommandManager.argument("name", StringArgumentType.word())
                    .suggests((ctx, builder) -> CommandSource.suggestMatching(PermahuenMod.playerManager.listPlayers().stream().map(p -> p.getName().getString()), builder))
                    .executes(ctx -> kill(ctx, StringArgumentType.getString(ctx, "name")))
                )
            )
            .then(CommandManager.literal("list")
                .executes(PermahuenCommands::list)
            )
        );
    }

    private static int spawn(CommandContext<ServerCommandSource> context, String name, BlockPos pos) {
        ServerCommandSource source = context.getSource();
        BlockPos spawnPos = (pos == null) ? source.getEntityOrThrow().getBlockPos() : pos;

        boolean success = PermahuenMod.playerManager.spawnPlayer(source.getServer(), name, source.getWorld(), spawnPos);

        if (success) {
            source.sendFeedback(() -> Text.literal("Spawned player ").append(Text.literal(name).formatted(Formatting.YELLOW)).append(" at " + spawnPos.toShortString()), true);
        } else {
            source.sendError(Text.literal("Player ").append(Text.literal(name).formatted(Formatting.YELLOW)).append(" already exists."));
        }
        return success ? 1 : 0;
    }

    private static int kill(CommandContext<ServerCommandSource> context, String name) {
        ServerCommandSource source = context.getSource();
        boolean success = PermahuenMod.playerManager.killPlayer(name);

        if (success) {
            source.sendFeedback(() -> Text.literal("Killed player ").append(Text.literal(name).formatted(Formatting.YELLOW)), true);
        } else {
            source.sendError(Text.literal("Player ").append(Text.literal(name).formatted(Formatting.YELLOW)).append(" not found."));
        }
        return success ? 1 : 0;
    }

    private static int list(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        var players = PermahuenMod.playerManager.listPlayers();

        if (players.isEmpty()) {
            source.sendFeedback(() -> Text.literal("There are no active players.").formatted(Formatting.GRAY), false);
            return 1;
        }

        source.sendFeedback(() -> Text.literal("--- Active Permahuen Players (" + players.size() + ") ---").formatted(Formatting.GOLD), false);
        for (ServerPlayerEntity player : players) {
            BlockPos pos = player.getBlockPos();
            String world = player.getWorld().getRegistryKey().getValue().getPath();
            source.sendFeedback(() -> Text.literal("- ").append(Text.literal(player.getName().getString()).formatted(Formatting.YELLOW))
                    .append(Text.literal(String.format(" at [%d, %d, %d] in %s", pos.getX(), pos.getY(), pos.getZ(), world)).formatted(Formatting.GRAY)),
                    false
            );
        }
        return players.size();
    }
}