package eu.pb4.stylednicknames.command;


import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import eu.pb4.placeholders.api.Placeholders;
import eu.pb4.placeholders.api.TextParserUtils;
import eu.pb4.placeholders.api.parsers.TextParserV1;
import eu.pb4.stylednicknames.NicknameHolder;
import eu.pb4.stylednicknames.StyledNicknamesMod;
import eu.pb4.stylednicknames.config.ConfigManager;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.*;
import java.util.stream.Collectors;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;

public class Commands {
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                    literal("styled-nicknames")
                            .requires(Permissions.require("stylednicknames.main", true))
                            .executes(Commands::about)

                            .then(literal("reload")
                                    .requires(Permissions.require("stylednicknames.reload", 3))
                                    .executes(Commands::reloadConfig)
                            )

                            .then(literal("set")
                                    .requires(Permissions.require("stylednicknames.change_others", 3))
                                    .then(argument("player", EntityArgumentType.player())
                                            .then(argument("nickname", StringArgumentType.greedyString()).suggests(OTHER_PREVIOUS_NICKNAME_PROVIDER)
                                                    .executes(Commands::changeOther)
                                            )
                                    )
                            )
                            .then(literal("clear")
                                    .requires(Permissions.require("stylednicknames.change_others", 3))
                                    .then(argument("player", EntityArgumentType.player())
                                            .executes(Commands::resetOther)
                                    )
                            )
            );

            var node = dispatcher.register(
                    literal("nickname")
                            .requires(Permissions.require("stylednicknames.use", 3).or((s) -> ConfigManager.getConfig().configData.allowByDefault))

                            .then(literal("set")
                                    .then(argument("nickname", StringArgumentType.greedyString()).suggests(PREVIOUS_NICKNAME_PROVIDER)
                                            .executes(Commands::change)
                                    )
                            )
                            .then(literal("clear").executes(Commands::reset))
            );

            dispatcher.register(
                    literal("nick")
                            .requires(Permissions.require("stylednicknames.use", 3).or((s) -> ConfigManager.getConfig().configData.allowByDefault))
                            .redirect(node)
            );

            dispatcher.register(
                    literal("realname")
                            .requires(Permissions.require("stylednicknames.realname", 3).or((s) -> ConfigManager.getConfig().configData.allowByDefault))
                            .then(argument("nickname", StringArgumentType.greedyString()).suggests(NICKNAME_PROVIDER)
                                    .executes(Commands::realname)
                            )
            );
        });
    }

    private static int change(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        NicknameHolder holder = NicknameHolder.of(context.getSource().getPlayerOrThrow());
        var config = ConfigManager.getConfig();
        var nickname = context.getArgument("nickname", String.class);
        if (config.configData.maxLength > 0) {
            Map<String, TextParserV1.TagNodeBuilder> handlers = new HashMap<>();
            for (var entry : TextParserV1.SAFE.getTags()) {
                if ((config.defaultFormattingCodes.getBoolean(entry.name())
                        || Permissions.check(context.getSource(), "stylednicknames.format." + entry.name(), 2))) {

                    handlers.put(entry.name(), entry.parser());

                    if (entry.aliases() != null) {
                        for (var a : entry.aliases()) {
                            handlers.put(a, entry.parser());
                        }
                    }
                }
            }

            if (config.configData.allowLegacyFormatting) {
                for (Formatting formatting : Formatting.values()) {
                    if (handlers.get(formatting.getName()) != null) {
                        nickname = nickname.replace(String.copyValueOf(new char[]{'&', formatting.getCode()}), "<" + formatting.getName() + ">");
                    }
                }
            }

            var output = TextParserUtils.formatText(nickname, handlers::get);

            if (output.getString().length() > config.configData.maxLength && !Permissions.check(context.getSource(), "stylednicknames.ignore_limit", 2)) {
                context.getSource().sendFeedback(ConfigManager.getConfig().tooLongText, false);
                return 1;
            }
        }

        holder.styledNicknames$set(nickname, true);
        context.getSource().sendFeedback(
                Placeholders.parseText(ConfigManager.getConfig().changeText, Placeholders.PREDEFINED_PLACEHOLDER_PATTERN, holder.styledNicknames$placeholdersCommand()),
                false);
        return 0;
    }

    private static int reset(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        NicknameHolder.of(context.getSource().getPlayerOrThrow()).styledNicknames$set(null, false);
        context.getSource().sendFeedback(
                Placeholders.parseText(ConfigManager.getConfig().resetText, Placeholders.PREDEFINED_PLACEHOLDER_PATTERN, Map.of(
                        "nickname", context.getSource().getPlayer().getName(),
                        "name", context.getSource().getPlayer().getName()
                )),
                false);
        return 0;
    }

    private static int changeOther(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
        NicknameHolder.of(player).styledNicknames$set(context.getArgument("nickname", String.class), false);
        context.getSource().sendFeedback(Text.translatable("Changed nickname of %s to %s", player.getName(), NicknameHolder.of(player).styledNicknames$getOutputOrVanilla()), false);
        return 0;
    }

    private static int resetOther(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(context, "player");
        NicknameHolder.of(player).styledNicknames$set(null, false);
        context.getSource().sendFeedback(Text.translatable("Cleared nickname of %s", player.getName()), false);
        return 0;
    }

    private static int realname(CommandContext<ServerCommandSource> context) {
        String nickname = StringArgumentType.getString(context, "nickname");
        List<ServerPlayerEntity> players = context.getSource().getServer().getPlayerManager().getPlayerList();
        Map<ServerPlayerEntity, MutableText> foundPlayers = new HashMap<>();
        for (ServerPlayerEntity player : players) {
            MutableText output = NicknameHolder.of(player).styledNicknames$getOutput();
            if (output == null) continue;
            if (output.getString().equals(nickname)) {
                foundPlayers.put(player, output);
            }
        }
        if (foundPlayers.isEmpty()) {
            context.getSource().sendError(Text.translatable("No player with that nickname is currently online."));
        } else {
            if (foundPlayers.size() > 1) {
                context.getSource().sendFeedback(Text.translatable("Found %s players with that nickname:", foundPlayers.size()), false);
            }
            foundPlayers.forEach((serverPlayerEntity, mutableText) -> {
                        context.getSource().sendFeedback(Text.translatable("The real name of %s is %s.", serverPlayerEntity.getDisplayName(), serverPlayerEntity.getEntityName()), false);
                    }
            );
        }
        return 0;
    }

    private static int reloadConfig(CommandContext<ServerCommandSource> context) {
        if (ConfigManager.loadConfig()) {
            context.getSource().sendFeedback(Text.literal("Reloaded config!"), false);
        } else {
            context.getSource().sendError(Text.literal("Error occurred while reloading config!").formatted(Formatting.RED));

        }
        return 1;
    }

    private static int about(CommandContext<ServerCommandSource> context) {
        context.getSource().sendFeedback(Text.literal("Styled Nicknames")
                .formatted(Formatting.BLUE)
                .append(Text.literal(" - " + StyledNicknamesMod.VERSION)
                        .formatted(Formatting.WHITE)
                ), false);

        return 1;
    }

    private static final SuggestionProvider<ServerCommandSource> PREVIOUS_NICKNAME_PROVIDER = (source, builder) -> {
        ServerPlayerEntity player = source.getSource().getPlayer();
        return CommandSource.suggestMatching(getNicknameSuggestion(player), builder);
    };

    private static final SuggestionProvider<ServerCommandSource> OTHER_PREVIOUS_NICKNAME_PROVIDER = (source, builder) -> {
        ServerPlayerEntity player = EntityArgumentType.getPlayer(source, "player");
        return CommandSource.suggestMatching(getNicknameSuggestion(player), builder);
    };

    private static Collection<String> getNicknameSuggestion(ServerPlayerEntity player) {
        if (player != null) {
            String nickname = NicknameHolder.of(player).styledNicknames$get();
            if (nickname != null) {
                return Collections.singletonList(nickname);
            }
        }
        return Collections.emptyList();
    }

    private static final SuggestionProvider<ServerCommandSource> NICKNAME_PROVIDER = (source, builder) -> {
        List<ServerPlayerEntity> players = source.getSource().getServer().getPlayerManager().getPlayerList();
        Set<String> nicknames = players.stream()
                .map(player -> NicknameHolder.of(player).styledNicknames$getOutput())
                .filter(Objects::nonNull)
                .map(Text::getString)
                .collect(Collectors.toSet());

        return CommandSource.suggestMatching(nicknames, builder);
    };

}
