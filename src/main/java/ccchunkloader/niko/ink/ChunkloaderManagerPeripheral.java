package ccchunkloader.niko.ink;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;

/**
 * Simplified peripheral interface for the Chunkloader Manager block.
 * Provides remote control of active turtle chunk loaders via Lua API.
 */
public class ChunkloaderManagerPeripheral implements IPeripheral {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkloaderManagerPeripheral.class);

    private final World world;

    public ChunkloaderManagerPeripheral(World world) {
        this.world = world;
    }

    @Override
    @NotNull
    public String getType() {
        return "chunkloader_manager";
    }

    @Override
    public boolean equals(IPeripheral other) {
        return this == other;
    }

    /**
     * Get information about a turtle chunk loader by its ID
     */
    @LuaFunction
    public final Map<String, Object> getTurtleInfo(String turtleIdString) throws LuaException {
        UUID turtleId = parseUUID(turtleIdString);
        ChunkLoaderPeripheral chunkLoader = ChunkLoaderRegistry.getPeripheral(turtleId);

        if (chunkLoader == null) {
            // Try to bootstrap the turtle on-demand
            if (world instanceof ServerWorld serverWorld) {
                ChunkManager manager = ChunkManager.get(serverWorld);
                ChunkManager.BootstrapResult result = manager.bootstrapTurtleOnDemand(turtleId);
                if (result.success) {
                    chunkLoader = ChunkLoaderRegistry.getPeripheral(turtleId);
                }
            }
            
            if (chunkLoader == null) {
                throw new LuaException("Turtle with ID " + turtleIdString + " not found. The turtle may have been removed or is out of fuel.");
            }
        }

        ChunkLoaderPeripheral.TurtleInfo info = chunkLoader.getTurtleInfo();

        Map<String, Object> result = new HashMap<>();
        result.put("turtleId", info.turtleId.toString());
        // Position removed for privacy/security - turtle location should not be exposed
        result.put("fuelLevel", info.fuelLevel);
        result.put("radius", info.radius);
        result.put("loadedChunks", info.loadedChunks);
        result.put("fuelRate", info.fuelRate);
        result.put("active", true); // This turtle is currently active since we got info

        return result;
    }

    /**
     * Set the chunk loading radius for a turtle
     */
    @LuaFunction
    public final boolean setTurtleRadius(String turtleIdString, double radius) throws LuaException {
        if (radius < 0.0 || radius > Config.MAX_RADIUS) {
            throw new LuaException("Radius must be between 0.0 and " + Config.MAX_RADIUS);
        }

        UUID turtleId = parseUUID(turtleIdString);
        ChunkLoaderPeripheral chunkLoader = ChunkLoaderRegistry.getPeripheral(turtleId);

        if (chunkLoader == null) {
            // Turtle is dormant - set radius override and try to bootstrap
            if (world instanceof ServerWorld serverWorld) {
                ChunkManager manager = ChunkManager.get(serverWorld);
                
                // CRITICAL: Set radius override in world NBT so it applies during chunk load/unload cycles
                manager.setRadiusOverride(turtleId, radius);
                
                ChunkManager.BootstrapResult result = manager.bootstrapTurtleOnDemand(turtleId);
                if (result.success) {
                    chunkLoader = ChunkLoaderRegistry.getPeripheral(turtleId);
                } else if ("TIMEOUT".equals(result.errorCode)) {
                    // Bootstrap timed out but turtle might still be loading - wait a bit more
                    LOGGER.info("Bootstrap timed out for turtle {}, waiting additional time...", turtleId);
                    
                    for (int i = 0; i < 10; i++) { // Additional 500ms wait
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        
                        chunkLoader = ChunkLoaderRegistry.getPeripheral(turtleId);
                        if (chunkLoader != null) {
                            LOGGER.info("Turtle {} became available after extended wait ({}ms)", turtleId, i * 50);
                            break;
                        }
                    }
                }
            }
            
            // Final check - if still no peripheral but we set the override, that's still partial success
            if (chunkLoader == null) {
                // Check if we at least set the radius override successfully
                if (world instanceof ServerWorld serverWorld) {
                    ChunkManager manager = ChunkManager.get(serverWorld);
                    if (manager.hasRadiusOverride(turtleId)) {
                        // Radius override is queued - turtle will get it when it loads
                        LOGGER.info("Radius override queued for turtle {} - will be applied when turtle loads", turtleId);
                        return true; // Return success since override is set
                    }
                }
                
                throw new LuaException("Turtle with ID " + turtleIdString + " not found. The turtle may have been removed, is out of fuel, or is still loading.");
            }
        } else {
            // Turtle is active - also set override in case it goes dormant and wakes up again
            if (world instanceof ServerWorld serverWorld) {
                ChunkManager manager = ChunkManager.get(serverWorld);
                manager.setRadiusOverride(turtleId, radius);
            }
        }

        // Fuel check for active peripherals
        if (radius > 0) {
            ChunkLoaderPeripheral.TurtleInfo info = chunkLoader.getTurtleInfo();
            if (info.fuelLevel == 0) {
                throw new LuaException("Cannot set radius for turtle " + turtleIdString + ": turtle has no fuel");
            }
        }

        boolean success = chunkLoader.setRadiusRemote(radius);
        if (!success) {
            throw new LuaException("Failed to set radius for turtle " + turtleIdString);
        }

        return success;
    }

    /**
     * Get a list of all active turtle chunk loaders
    @LuaFunction
    public final List<Map<String, Object>> listTurtles() {
        List<Map<String, Object>> result = new ArrayList<>();

        Map<UUID, ChunkLoaderPeripheral> peripherals = ChunkLoaderRegistry.getAllPeripherals();

        for (ChunkLoaderPeripheral peripheral : peripherals.values()) {
            if (world instanceof ServerWorld && peripheral.getTurtleLevel() == world) {
                ChunkLoaderPeripheral.TurtleInfo info = peripheral.getTurtleInfo();

                Map<String, Object> turtleData = new HashMap<>();
                turtleData.put("turtleId", info.turtleId.toString());
                // Position removed for privacy/security - turtle location should not be exposed
                turtleData.put("fuelLevel", info.fuelLevel);
                turtleData.put("radius", info.radius);
                turtleData.put("loadedChunks", info.loadedChunks);
                turtleData.put("fuelRate", info.fuelRate);
                turtleData.put("active", true);

                result.add(turtleData);
            }
        }

        return result;
    }
    */

    /**
     * Set wake on world load preference for a turtle
     */
    @LuaFunction
    public final boolean setTurtleWakeOnWorldLoad(String turtleIdString, boolean wake) throws LuaException {
        UUID turtleId = parseUUID(turtleIdString);
        ChunkLoaderPeripheral chunkLoader = ChunkLoaderRegistry.getPeripheral(turtleId);

        if (chunkLoader == null) {
            // Try to bootstrap the turtle on-demand
            if (world instanceof ServerWorld serverWorld) {
                ChunkManager manager = ChunkManager.get(serverWorld);
                ChunkManager.BootstrapResult result = manager.bootstrapTurtleOnDemand(turtleId);
                if (result.success) {
                    chunkLoader = ChunkLoaderRegistry.getPeripheral(turtleId);
                }
            }
            
            if (chunkLoader == null) {
                throw new LuaException("Turtle with ID " + turtleIdString + " not found. The turtle may have been removed, is out of fuel, or bootstrap failed.");
            }
        }

        chunkLoader.setWakeOnWorldLoad(wake);
        return true;
    }

    /**
     * Get wake on world load preference for a turtle
     */
    @LuaFunction
    public final boolean getTurtleWakeOnWorldLoad(String turtleIdString) throws LuaException {
        UUID turtleId = parseUUID(turtleIdString);
        ChunkLoaderPeripheral chunkLoader = ChunkLoaderRegistry.getPeripheral(turtleId);

        if (chunkLoader == null) {
            // Try to bootstrap the turtle on-demand
            if (world instanceof ServerWorld serverWorld) {
                ChunkManager manager = ChunkManager.get(serverWorld);
                ChunkManager.BootstrapResult result = manager.bootstrapTurtleOnDemand(turtleId);
                if (result.success) {
                    chunkLoader = ChunkLoaderRegistry.getPeripheral(turtleId);
                }
            }
            
            if (chunkLoader == null) {
                throw new LuaException("Turtle with ID " + turtleIdString + " not found. The turtle may have been removed, is out of fuel, or bootstrap failed.");
            }
        }

        return chunkLoader.getWakeOnWorldLoad();
    }

    /**
     * Get diagnostic information about a turtle's status
     */
    @LuaFunction
    public final String getTurtleDiagnostic(String turtleIdString) throws LuaException {
        UUID turtleId = parseUUID(turtleIdString);
        if (world instanceof ServerWorld serverWorld) {
            return ChunkLoaderRegistry.getTurtleDiagnostic(turtleId, serverWorld);
        }
        return "Cannot get diagnostic - world is not ServerWorld";
    }

    // Helper method to parse UUID strings
    private UUID parseUUID(String uuidString) throws LuaException {
        try {
            return UUID.fromString(uuidString);
        } catch (IllegalArgumentException e) {
            throw new LuaException("Invalid turtle ID format: " + uuidString);
        }
    }
}
