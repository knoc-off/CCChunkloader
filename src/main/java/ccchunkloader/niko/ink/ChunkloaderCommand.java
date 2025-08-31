package ccchunkloader.niko.ink;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

import static net.minecraft.server.command.CommandManager.argument;
import static net.minecraft.server.command.CommandManager.literal;


// just for commands to configure options
public class ChunkloaderCommand {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        LiteralArgumentBuilder<ServerCommandSource> builder = literal("ccchunkloader")
            .requires(source -> source.hasPermissionLevel(2))
            .then(literal("get").then(argument("key", StringArgumentType.word()).executes(ctx -> {
                String key = StringArgumentType.getString(ctx, "key");
                switch (key) {
                    case "MAX_RADIUS":
                        ctx.getSource().sendFeedback(() -> Text.literal("MAX_RADIUS: " + Config.MAX_RADIUS), false);
                        break;
                    case "MAX_RANDOM_TICK_RADIUS":
                        ctx.getSource().sendFeedback(() -> Text.literal("MAX_RANDOM_TICK_RADIUS: " + Config.MAX_RANDOM_TICK_RADIUS), false);
                        break;
                    case "BASE_FUEL_COST_PER_CHUNK":
                        ctx.getSource().sendFeedback(() -> Text.literal("BASE_FUEL_COST_PER_CHUNK: " + Config.BASE_FUEL_COST_PER_CHUNK), false);
                        break;
                    case "DISTANCE_MULTIPLIER":
                        ctx.getSource().sendFeedback(() -> Text.literal("DISTANCE_MULTIPLIER: " + Config.DISTANCE_MULTIPLIER), false);
                        break;
                    case "RANDOM_TICK_FUEL_MULTIPLIER":
                        ctx.getSource().sendFeedback(() -> Text.literal("RANDOM_TICK_FUEL_MULTIPLIER: " + Config.RANDOM_TICK_FUEL_MULTIPLIER), false);
                        break;
                    case "CLEANUP_INTERVAL_TICKS":
                        ctx.getSource().sendFeedback(() -> Text.literal("CLEANUP_INTERVAL_TICKS: " + Config.CLEANUP_INTERVAL_TICKS), false);
                        break;
                    case "MAX_INACTIVE_TIME_MS":
                        ctx.getSource().sendFeedback(() -> Text.literal("MAX_INACTIVE_TIME_MS: " + Config.MAX_INACTIVE_TIME_MS), false);
                        break;
                    default:
                        ctx.getSource().sendError(Text.literal("Unknown config key: " + key));
                        break;
                }
                return 1;
            })))
            .then(literal("set").then(argument("key", StringArgumentType.word())
                .then(argument("value", StringArgumentType.greedyString()).executes(ctx -> {
                    String key = StringArgumentType.getString(ctx, "key");
                    String value = StringArgumentType.getString(ctx, "value");
                    try {
                        switch (key) {
                            case "MAX_RADIUS":
                                Config.MAX_RADIUS = Double.parseDouble(value);
                                break;
                            case "MAX_RANDOM_TICK_RADIUS":
                                Config.MAX_RANDOM_TICK_RADIUS = Double.parseDouble(value);
                                break;
                            case "BASE_FUEL_COST_PER_CHUNK":
                                Config.BASE_FUEL_COST_PER_CHUNK = Double.parseDouble(value);
                                break;
                            case "DISTANCE_MULTIPLIER":
                                Config.DISTANCE_MULTIPLIER = Double.parseDouble(value);
                                break;
                            case "RANDOM_TICK_FUEL_MULTIPLIER":
                                Config.RANDOM_TICK_FUEL_MULTIPLIER = Double.parseDouble(value);
                                break;
                            case "CLEANUP_INTERVAL_TICKS":
                                Config.CLEANUP_INTERVAL_TICKS = Integer.parseInt(value);
                                break;
                            case "MAX_INACTIVE_TIME_MS":
                                Config.MAX_INACTIVE_TIME_MS = Long.parseLong(value);
                                break;
                            default:
                                ctx.getSource().sendError(Text.literal("Unknown config key: " + key));
                                return 0;
                        }
                        ctx.getSource().sendFeedback(() -> Text.literal("Set " + key + " to " + value), true);
                        return 1;
                    } catch (NumberFormatException e) {
                        ctx.getSource().sendError(Text.literal("Invalid value: " + value));
                        return 0;
                    }
                }))));
        dispatcher.register(builder);
    }
}
