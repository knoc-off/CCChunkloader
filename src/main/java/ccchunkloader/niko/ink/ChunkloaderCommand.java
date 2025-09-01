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
            .executes(ctx -> {
                // Default command shows help
                showMainHelp(ctx.getSource());
                return 1;
            })
            .then(literal("help")
                .executes(ctx -> {
                    showMainHelp(ctx.getSource());
                    return 1;
                })
                .then(argument("command", StringArgumentType.word()).executes(ctx -> {
                    String command = StringArgumentType.getString(ctx, "command");
                    showDetailedHelp(ctx.getSource(), command);
                    return 1;
                })))
            .then(literal("list").executes(ctx -> {
                showAllConfigValues(ctx.getSource());
                return 1;
            }))
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

    private static void showMainHelp(ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal("§6=== CC Chunk Loader Commands ==="), false);
        source.sendFeedback(() -> Text.literal("§e/ccchunkloader help §7- Show this help"), false);
        source.sendFeedback(() -> Text.literal("§e/ccchunkloader help <command> §7- Detailed help for a command"), false);
        source.sendFeedback(() -> Text.literal("§e/ccchunkloader list §7- Show all config values"), false);
        source.sendFeedback(() -> Text.literal("§e/ccchunkloader get <key> §7- Get a config value"), false);
        source.sendFeedback(() -> Text.literal("§e/ccchunkloader set <key> <value> §7- Set a config value"), false);
        source.sendFeedback(() -> Text.literal("§7Use §e/ccchunkloader help <command> §7for detailed info"), false);
    }

    private static void showDetailedHelp(ServerCommandSource source, String command) {
        switch (command.toLowerCase()) {
            case "get":
                source.sendFeedback(() -> Text.literal("§6=== /ccchunkloader get ==="), false);
                source.sendFeedback(() -> Text.literal("§7Get the current value of a config setting"), false);
                source.sendFeedback(() -> Text.literal("§eUsage: §f/ccchunkloader get <key>"), false);
                source.sendFeedback(() -> Text.literal("§7Example: §f/ccchunkloader get MAX_RADIUS"), false);
                source.sendFeedback(() -> Text.literal("§7Use §e/ccchunkloader list §7to see all available keys"), false);
                break;
            case "set":
                source.sendFeedback(() -> Text.literal("§6=== /ccchunkloader set ==="), false);
                source.sendFeedback(() -> Text.literal("§7Set a config value (requires OP level 2)"), false);
                source.sendFeedback(() -> Text.literal("§eUsage: §f/ccchunkloader set <key> <value>"), false);
                source.sendFeedback(() -> Text.literal("§7Examples:"), false);
                source.sendFeedback(() -> Text.literal("§f  /ccchunkloader set MAX_RADIUS 3.0"), false);
                source.sendFeedback(() -> Text.literal("§f  /ccchunkloader set BASE_FUEL_COST_PER_CHUNK 0.05"), false);
                source.sendFeedback(() -> Text.literal("§c⚠ Warning: Some settings affect all turtles immediately!"), false);
                break;
            case "list":
                source.sendFeedback(() -> Text.literal("§6=== /ccchunkloader list ==="), false);
                source.sendFeedback(() -> Text.literal("§7Shows all config keys and their current values"), false);
                source.sendFeedback(() -> Text.literal("§eUsage: §f/ccchunkloader list"), false);
                break;
            default:
                source.sendFeedback(() -> Text.literal("§cUnknown command: " + command), false);
                source.sendFeedback(() -> Text.literal("§7Available commands: §eget, set, list, help"), false);
                break;
        }
    }

    private static void showAllConfigValues(ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal("§6=== CC Chunk Loader Configuration ==="), false);
        source.sendFeedback(() -> Text.literal("§7General Settings:"), false);
        source.sendFeedback(() -> Text.literal("§e  MAX_RADIUS: §f" + Config.MAX_RADIUS + " §7(max chunk loading radius)"), false);
        source.sendFeedback(() -> Text.literal("§e  MAX_RANDOM_TICK_RADIUS: §f" + Config.MAX_RANDOM_TICK_RADIUS + " §7(max random tick radius)"), false);
        
        source.sendFeedback(() -> Text.literal("§7Fuel Cost Settings:"), false);
        source.sendFeedback(() -> Text.literal("§e  BASE_FUEL_COST_PER_CHUNK: §f" + Config.BASE_FUEL_COST_PER_CHUNK + " §7(fuel per chunk per tick)"), false);
        source.sendFeedback(() -> Text.literal("§e  DISTANCE_MULTIPLIER: §f" + Config.DISTANCE_MULTIPLIER + " §7(distance cost multiplier)"), false);
        source.sendFeedback(() -> Text.literal("§e  RANDOM_TICK_FUEL_MULTIPLIER: §f" + Config.RANDOM_TICK_FUEL_MULTIPLIER + " §7(random tick cost multiplier)"), false);
        
        source.sendFeedback(() -> Text.literal("§7Cleanup Settings §c(DANGEROUS):"), false);
        source.sendFeedback(() -> Text.literal("§e  ENABLE_TIME_BASED_CLEANUP: §f" + Config.ENABLE_TIME_BASED_CLEANUP + " §c(destroys turtle data!)"), false);
        source.sendFeedback(() -> Text.literal("§e  CLEANUP_INTERVAL_TICKS: §f" + Config.CLEANUP_INTERVAL_TICKS + " §7(cleanup check interval)"), false);
        source.sendFeedback(() -> Text.literal("§e  MAX_INACTIVE_TIME_MS: §f" + Config.MAX_INACTIVE_TIME_MS + " §7(max inactive time)"), false);
        
        source.sendFeedback(() -> Text.literal("§7Use §e/ccchunkloader set <key> <value> §7to change values"), false);
    }
}
