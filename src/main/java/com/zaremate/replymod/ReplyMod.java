package com.zaremate.replymod;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Mod(ReplyMod.MOD_ID)
public final class ReplyMod {
    public static final String MOD_ID = "replymod";

    private static final Logger LOGGER = LogUtils.getLogger();

    /*
     * Each player has one reply target:
     *
     * Player A -> Player B
     * Player B -> Player A
     *
     * This is intentionally kept in memory. It represents the last private
     * message conversation for the current server session.
     */
    private final Map<UUID, UUID> replyTargets = new ConcurrentHashMap<>();

    public ReplyMod(IEventBus modBus, ModContainer modContainer) {
        if (FMLEnvironment.dist.isDedicatedServer()) {
            NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
            NeoForge.EVENT_BUS.addListener(this::onCommand);
            LOGGER.info("Reply Mod loaded.");
        }
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        registerCommands(event.getDispatcher());
    }

    private void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        var command = Commands.literal("r")
                .then(Commands.argument("message", StringArgumentType.greedyString())
                        .executes(this::reply));

        dispatcher.register(command);

        dispatcher.register(
                Commands.literal("reply")
                        .then(Commands.argument("message", StringArgumentType.greedyString())
                                .executes(this::reply))
        );
    }

    private int reply(CommandContext<CommandSourceStack> context) {
        ServerPlayer sender;

        try {
            sender = context.getSource().getPlayerOrException();
        } catch (Exception ignored) {
            context.getSource().sendFailure(Component.literal("Only players can use /r or /reply."));
            return 0;
        }

        UUID targetUuid = replyTargets.get(sender.getUUID());

        if (targetUuid == null) {
            sender.sendSystemMessage(Component.literal("You have nobody to reply to."));
            return 0;
        }

        MinecraftServer server = sender.getServer();
        if (server == null) {
            return 0;
        }

        ServerPlayer target = server.getPlayerList().getPlayer(targetUuid);

        if (target == null) {
            sender.sendSystemMessage(Component.literal("That player is no longer online."));
            return 0;
        }

        String message = StringArgumentType.getString(context, "message").trim();

        if (message.isEmpty()) {
            sender.sendSystemMessage(Component.literal("Usage: /r <message>"));
            return 0;
        }

        // Use vanilla /msg so the reply has the same behavior and formatting
        // as a normal private message. The command is executed with the
        // player's own permissions and source.
        String command = "msg " + target.getGameProfile().getName() + " " + message;
        server.getCommands().performPrefixedCommand(sender.createCommandSourceStack(), command);

        return 1;
    }

    private void onCommand(CommandEvent event) {
        var parse = event.getParseResults();
        CommandSourceStack source = parse.getContext().getSource();

        ServerPlayer sender = source.getPlayer();
        if (sender == null) {
            return;
        }

        String command = parse.getReader().getString();
        String root = getCommandRoot(parse);

        if (!isPrivateMessageCommand(root)) {
            return;
        }

        List<ServerPlayer> targets = resolveTargets(sender, parse, command);

        if (targets.isEmpty()) {
            return;
        }

        for (ServerPlayer target : targets) {
            if (target.getUUID().equals(sender.getUUID())) {
                continue;
            }

            replyTargets.put(sender.getUUID(), target.getUUID());
            replyTargets.put(target.getUUID(), sender.getUUID());

            LOGGER.debug("Updated reply target: {} <-> {}",
                    sender.getGameProfile().getName(),
                    target.getGameProfile().getName());
        }
    }

    private boolean isPrivateMessageCommand(String root) {
        return root.equals("msg")
                || root.equals("tell")
                || root.equals("w");
    }

    private String getCommandRoot(com.mojang.brigadier.ParseResults<CommandSourceStack> parse) {
        String input = parse.getReader().getString();
        if (input.startsWith("/")) {
            input = input.substring(1);
        }

        int space = input.indexOf(' ');
        return (space == -1 ? input : input.substring(0, space)).toLowerCase();
    }

    private List<ServerPlayer> resolveTargets(
            ServerPlayer sender,
            com.mojang.brigadier.ParseResults<CommandSourceStack> parse,
            String command
    ) {
        List<ServerPlayer> targets = new ArrayList<>();

        /*
         * Vanilla /msg normally has a target argument called "targets".
         * Reading it from Brigadier means selectors such as @p can also work.
         */
        try {
            var context = parse.getContext();
            Object value = context.getArgument("targets", Object.class);

            if (value instanceof net.minecraft.commands.arguments.selector.EntitySelector selector) {
                targets.addAll(selector.findPlayers(context.getSource()));
                return targets;
            }
        } catch (Exception ignored) {
            // Fall back to the simple player-name parser below.
        }

        // /msg <player> <message>
        String input = command;
        if (input.startsWith("/")) {
            input = input.substring(1);
        }

        String[] parts = input.split("\\s+", 3);
        if (parts.length < 2) {
            return targets;
        }

        String playerName = parts[1];

        ServerPlayer target = sender.getServer().getPlayerList().getPlayerByName(playerName);
        if (target != null) {
            targets.add(target);
        }

        return targets;
    }
}
