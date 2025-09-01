package ccchunkloader.niko.ink;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
            .then(literal("debug")
                .then(literal("uuids").executes(ctx -> {
                    showAllUUIDs(ctx.getSource());
                    return 1;
                }))
                .then(literal("computer").then(argument("computer_id", StringArgumentType.word()).executes(ctx -> {
                    String computerIdStr = StringArgumentType.getString(ctx, "computer_id");
                    try {
                        int computerId = Integer.parseInt(computerIdStr);
                        showComputerUUIDs(ctx.getSource(), computerId);
                    } catch (NumberFormatException e) {
                        ctx.getSource().sendError(Text.literal("Invalid computer ID: " + computerIdStr));
                    }
                    return 1;
                })))
                .then(literal("validate").then(argument("computer_id", StringArgumentType.word()).executes(ctx -> {
                    String computerIdStr = StringArgumentType.getString(ctx, "computer_id");
                    try {
                        int computerId = Integer.parseInt(computerIdStr);
                        validateComputer(ctx.getSource(), computerId);
                    } catch (NumberFormatException e) {
                        ctx.getSource().sendError(Text.literal("Invalid computer ID: " + computerIdStr));
                    }
                    return 1;
                })))
                .then(literal("orphans").executes(ctx -> {
                    showOrphanedUUIDs(ctx.getSource());
                    return 1;
                }))
                .then(literal("purge").then(argument("uuid", StringArgumentType.word()).executes(ctx -> {
                    String uuidStr = StringArgumentType.getString(ctx, "uuid");
                    try {
                        UUID uuid = UUID.fromString(uuidStr);
                        purgeUUID(ctx.getSource(), uuid);
                    } catch (IllegalArgumentException e) {
                        ctx.getSource().sendError(Text.literal("Invalid UUID format: " + uuidStr));
                    }
                    return 1;
                })))
                .then(literal("states").executes(ctx -> {
                    showStateBreakdown(ctx.getSource());
                    return 1;
                }))
                .then(literal("stats").executes(ctx -> {
                    showTrackingStats(ctx.getSource());
                    return 1;
                })))
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
        source.sendFeedback(() -> Text.literal("§6Debug Commands:"), false);
        source.sendFeedback(() -> Text.literal("§e/ccchunkloader debug uuids §7- List all tracked UUIDs"), false);
        source.sendFeedback(() -> Text.literal("§e/ccchunkloader debug computer <id> §7- Show UUIDs for computer"), false);
        source.sendFeedback(() -> Text.literal("§e/ccchunkloader debug states §7- Show turtle state breakdown"), false);
        source.sendFeedback(() -> Text.literal("§e/ccchunkloader debug stats §7- Show tracking statistics"), false);
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
            case "debug":
                source.sendFeedback(() -> Text.literal("§6=== /ccchunkloader debug ==="), false);
                source.sendFeedback(() -> Text.literal("§7Debug commands for UUID lifecycle management"), false);
                source.sendFeedback(() -> Text.literal("§eSubcommands:"), false);
                source.sendFeedback(() -> Text.literal("§f  uuids §7- List all tracked UUIDs by computer"), false);
                source.sendFeedback(() -> Text.literal("§f  computer <id> §7- Show UUIDs for specific computer"), false);
                source.sendFeedback(() -> Text.literal("§f  states §7- Show turtle state breakdown (active/dormant/loaded)"), false);
                source.sendFeedback(() -> Text.literal("§f  orphans §7- List UUIDs without computer mapping"), false);
                source.sendFeedback(() -> Text.literal("§f  purge <uuid> §7- Force remove specific UUID"), false);
                source.sendFeedback(() -> Text.literal("§f  stats §7- Show tracking statistics"), false);
                break;
            default:
                source.sendFeedback(() -> Text.literal("§cUnknown command: " + command), false);
                source.sendFeedback(() -> Text.literal("§7Available commands: §eget, set, list, debug, help"), false);
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
        
        
        source.sendFeedback(() -> Text.literal("§7Use §e/ccchunkloader set <key> <value> §7to change values"), false);
    }

    private static void showAllUUIDs(ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal("§6=== All Tracked UUIDs ==="), false);
        
        if (!(source.getWorld() instanceof ServerWorld)) {
            source.sendError(Text.literal("Command must be run in a server world"));
            return;
        }
        
        ServerWorld serverWorld = (ServerWorld) source.getWorld();
        ChunkManager manager = ChunkManager.get(serverWorld);
        Set<Integer> computerIds = manager.getAllComputerIds();
        
        if (computerIds.isEmpty()) {
            source.sendFeedback(() -> Text.literal("§7No computers with tracked UUIDs"), false);
            return;
        }
        
        for (int computerId : computerIds) {
            Set<UUID> uuids = manager.getUUIDsForComputer(computerId);
            source.sendFeedback(() -> Text.literal("§eComputer " + computerId + ": §f" + uuids.size() + " UUIDs"), false);
            for (UUID uuid : uuids) {
                boolean isActive = ChunkLoaderRegistry.isActive(uuid);
                boolean isChunkLoaded = manager.isTurtleChunkLoaded(uuid);
                String status;
                if (isActive) {
                    status = isChunkLoaded ? "§aACTIVE_LOADED" : "§6ACTIVE_UNLOADED";
                } else {
                    status = "§7DORMANT";
                }
                source.sendFeedback(() -> Text.literal("  §7" + uuid + " " + status), false);
            }
        }
    }

    private static void showComputerUUIDs(ServerCommandSource source, int computerId) {
        source.sendFeedback(() -> Text.literal("§6=== Computer " + computerId + " UUIDs ==="), false);
        
        if (!(source.getWorld() instanceof ServerWorld)) {
            source.sendError(Text.literal("Command must be run in a server world"));
            return;
        }
        
        ServerWorld serverWorld = (ServerWorld) source.getWorld();
        ChunkManager manager = ChunkManager.get(serverWorld);
        Set<UUID> uuids = manager.getUUIDsForComputer(computerId);
        
        if (uuids.isEmpty()) {
            source.sendFeedback(() -> Text.literal("§7No UUIDs tracked for computer " + computerId), false);
            return;
        }
        
        source.sendFeedback(() -> Text.literal("§7Found " + uuids.size() + " UUIDs:"), false);
        for (UUID uuid : uuids) {
            boolean isActive = ChunkLoaderRegistry.isActive(uuid);
            boolean isChunkLoaded = manager.isTurtleChunkLoaded(uuid);
            String status;
            if (isActive) {
                status = isChunkLoaded ? "§aACTIVE_LOADED" : "§6ACTIVE_UNLOADED";
            } else {
                status = "§7DORMANT";
            }
            source.sendFeedback(() -> Text.literal("§f" + uuid + " " + status), false);
        }
    }

    private static void validateComputer(ServerCommandSource source, int computerId) {
        source.sendFeedback(() -> Text.literal("§6=== Validating Computer " + computerId + " ==="), false);
        
        if (!(source.getWorld() instanceof ServerWorld)) {
            source.sendError(Text.literal("Command must be run in a server world"));
            return;
        }
        
        source.sendFeedback(() -> Text.literal("§7Manual validation is not safe - validation only occurs when turtle is loaded and active"), false);
        source.sendFeedback(() -> Text.literal("§7UUIDs are automatically validated when chunkloader peripherals are created"), false);
        source.sendFeedback(() -> Text.literal("§7Use §e/ccchunkloader debug computer " + computerId + " §7to see current UUIDs"), false);
    }

    private static void showOrphanedUUIDs(ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal("§6=== Orphaned UUIDs ==="), false);
        
        if (!(source.getWorld() instanceof ServerWorld)) {
            source.sendError(Text.literal("Command must be run in a server world"));
            return;
        }
        
        ServerWorld serverWorld = (ServerWorld) source.getWorld();
        ChunkManager manager = ChunkManager.get(serverWorld);
        Set<UUID> allTrackedUUIDs = manager.getRestoredTurtleIds();
        
        int orphanCount = 0;
        for (UUID uuid : allTrackedUUIDs) {
            Integer computerId = manager.getComputerIdForUUID(uuid);
            if (computerId == null) {
                source.sendFeedback(() -> Text.literal("§c" + uuid + " §7(no computer ID mapping)"), false);
                orphanCount++;
            }
        }
        
        final int finalOrphanCount = orphanCount;
        if (finalOrphanCount == 0) {
            source.sendFeedback(() -> Text.literal("§7No orphaned UUIDs found"), false);
        } else {
            source.sendFeedback(() -> Text.literal("§7Found " + finalOrphanCount + " orphaned UUIDs"), false);
        }
    }

    private static void purgeUUID(ServerCommandSource source, UUID uuid) {
        source.sendFeedback(() -> Text.literal("§6=== Purging UUID " + uuid + " ==="), false);
        
        if (!(source.getWorld() instanceof ServerWorld)) {
            source.sendError(Text.literal("Command must be run in a server world"));
            return;
        }
        
        ServerWorld serverWorld = (ServerWorld) source.getWorld();
        ChunkManager manager = ChunkManager.get(serverWorld);
        
        // Check if UUID exists
        Integer computerId = manager.getComputerIdForUUID(uuid);
        boolean isTracked = manager.getRestoredTurtleIds().contains(uuid);
        boolean isActive = ChunkLoaderRegistry.isActive(uuid);
        
        if (!isTracked) {
            source.sendError(Text.literal("UUID " + uuid + " is not tracked"));
            return;
        }
        
        if (isActive) {
            source.sendFeedback(() -> Text.literal("§cWARNING: UUID " + uuid + " is currently ACTIVE!"), false);
        }
        
        // Permanently remove the UUID
        ChunkManager.permanentlyRemoveTurtleFromWorld(serverWorld, uuid);
        ChunkLoaderRegistry.permanentlyRemoveTurtle(uuid);
        
        source.sendFeedback(() -> Text.literal("§aPurged UUID " + uuid + " from computer " + computerId), false);
    }

    private static void showStateBreakdown(ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal("§6=== Turtle State Breakdown ==="), false);
        
        if (!(source.getWorld() instanceof ServerWorld)) {
            source.sendError(Text.literal("Command must be run in a server world"));
            return;
        }
        
        ServerWorld serverWorld = (ServerWorld) source.getWorld();
        ChunkManager manager = ChunkManager.get(serverWorld);
        Set<UUID> allTrackedUUIDs = manager.getRestoredTurtleIds();
        
        if (allTrackedUUIDs.isEmpty()) {
            source.sendFeedback(() -> Text.literal("§7No tracked turtles"), false);
            return;
        }
        
        int activeLoadedCount = 0;
        int activeUnloadedCount = 0;
        int dormantCount = 0;
        
        // Count turtles in each state
        for (UUID uuid : allTrackedUUIDs) {
            boolean isActive = ChunkLoaderRegistry.isActive(uuid);
            boolean isChunkLoaded = manager.isTurtleChunkLoaded(uuid);
            
            if (isActive) {
                if (isChunkLoaded) {
                    activeLoadedCount++;
                } else {
                    activeUnloadedCount++;
                }
            } else {
                dormantCount++;
            }
        }
        
        // Display breakdown (capture variables as final)
        final int finalActiveLoadedCount = activeLoadedCount;
        final int finalActiveUnloadedCount = activeUnloadedCount;
        final int finalDormantCount = dormantCount;
        
        source.sendFeedback(() -> Text.literal("§7Total Turtles: §f" + allTrackedUUIDs.size()), false);
        source.sendFeedback(() -> Text.literal("§a  ACTIVE_LOADED: §f" + finalActiveLoadedCount + " §7(peripheral exists, chunk loaded)"), false);
        source.sendFeedback(() -> Text.literal("§6  ACTIVE_UNLOADED: §f" + finalActiveUnloadedCount + " §7(peripheral exists, chunk unloaded)"), false);
        source.sendFeedback(() -> Text.literal("§7  DORMANT: §f" + finalDormantCount + " §7(no peripheral, chunk unloaded)"), false);
        
        // Highlight garbage collection candidates
        if (finalActiveUnloadedCount > 0) {
            source.sendFeedback(() -> Text.literal(""), false);
            source.sendFeedback(() -> Text.literal("§c⚠ " + finalActiveUnloadedCount + " turtles are candidates for garbage collection"), false);
            source.sendFeedback(() -> Text.literal("§7(Active peripherals in unloaded chunks could be cleaned up)"), false);
        }
    }

    private static void showTrackingStats(ServerCommandSource source) {
        source.sendFeedback(() -> Text.literal("§6=== UUID Tracking Statistics ==="), false);
        
        if (!(source.getWorld() instanceof ServerWorld)) {
            source.sendError(Text.literal("Command must be run in a server world"));
            return;
        }
        
        ServerWorld serverWorld = (ServerWorld) source.getWorld();
        ChunkManager manager = ChunkManager.get(serverWorld);
        Map<String, Object> stats = manager.getComputerIdStats();
        
        source.sendFeedback(() -> Text.literal("§7Total Computers: §f" + stats.get("totalComputers")), false);
        source.sendFeedback(() -> Text.literal("§7Total UUIDs: §f" + stats.get("totalUUIDs")), false);
        source.sendFeedback(() -> Text.literal("§7Orphaned UUIDs: §f" + stats.get("orphanedUUIDs")), false);
        
        int activeCount = ChunkLoaderRegistry.getAllPeripherals().size();
        source.sendFeedback(() -> Text.literal("§7Active Peripherals: §f" + activeCount), false);
        
        // Add load state breakdown
        Set<UUID> allTrackedUUIDs = manager.getRestoredTurtleIds();
        int activeLoadedCount = 0;
        int activeUnloadedCount = 0;
        int dormantCount = 0;
        
        for (UUID uuid : allTrackedUUIDs) {
            boolean isActive = ChunkLoaderRegistry.isActive(uuid);
            boolean isChunkLoaded = manager.isTurtleChunkLoaded(uuid);
            
            if (isActive) {
                if (isChunkLoaded) {
                    activeLoadedCount++;
                } else {
                    activeUnloadedCount++;
                }
            } else {
                dormantCount++;
            }
        }
        
        // Capture variables as final for lambda expressions
        final int finalActiveLoadedCount2 = activeLoadedCount;
        final int finalActiveUnloadedCount2 = activeUnloadedCount;
        final int finalDormantCount2 = dormantCount;
        
        source.sendFeedback(() -> Text.literal(""), false);
        source.sendFeedback(() -> Text.literal("§6Load State Breakdown:"), false);
        source.sendFeedback(() -> Text.literal("§a  Active + Loaded: §f" + finalActiveLoadedCount2), false);
        source.sendFeedback(() -> Text.literal("§6  Active + Unloaded: §f" + finalActiveUnloadedCount2), false);
        source.sendFeedback(() -> Text.literal("§7  Dormant: §f" + finalDormantCount2), false);
    }
}
