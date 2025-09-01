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

        Map<String, Object> result = new HashMap<>();
        result.put("turtleId", chunkLoader.getTurtleId().toString());
        // Position removed for privacy/security - turtle location should not be exposed
        result.put("fuelLevel", chunkLoader.getFuelLevel());
        result.put("radius", chunkLoader.getRadius());
        result.put("loadedChunks", chunkLoader.getLoadedChunkCount());
        result.put("fuelRate", chunkLoader.calculateFuelCost());
        result.put("active", true); // This turtle is currently active since we got info

        return result;
    }

    /**
     * Set the chunk loading radius for a turtle
     * NOW USES NEW ARCHITECTURE - Robust command queue system!
     */
    @LuaFunction
    public final boolean setTurtleRadius(String turtleIdString, double radius) throws LuaException {
        if (radius < 0.0 || radius > Config.MAX_RADIUS) {
            throw new LuaException("Radius must be between 0.0 and " + Config.MAX_RADIUS);
        }

        UUID turtleId = parseUUID(turtleIdString);
        
        if (!(world instanceof ServerWorld)) {
            throw new LuaException("Remote management only available on server");
        }
        
        // Use new architecture - command queue with retry logic!
        TurtleCommandQueue commandQueue = CCChunkloader.getCommandQueue();
        TurtleStateEvents eventSystem = CCChunkloader.getEventSystem();
        
        // Create radius command (no timeout needed - expires in 2 seconds automatically)
        String commandSource = "manager:" + turtleId.toString().substring(0, 8);
        TurtleCommandQueue.SetRadiusCommand command = new TurtleCommandQueue.SetRadiusCommand(radius, commandSource);
        
        // Queue the command with fuel validation - will reject if insufficient fuel
        boolean queued = commandQueue.queueCommand(turtleId, command, commandSource);
        if (!queued) {
            throw new LuaException("Cannot set radius " + radius + " for turtle " + turtleIdString + " - insufficient fuel or invalid state");
        }
        
        LOGGER.debug("SetRadius({}) queued for turtle {} by {}", radius, turtleId, commandSource);
        
        // Check if turtle needs bootstrap and attempt it
        ChunkLoaderPeripheral chunkLoader = ChunkLoaderRegistry.getPeripheral(turtleId);
        if (chunkLoader == null && world instanceof ServerWorld serverWorld) {
            // Turtle is dormant - try to bootstrap it
            LOGGER.debug("Attempting bootstrap for dormant turtle {}", turtleId);
            ChunkManager manager = ChunkManager.get(serverWorld);
            ChunkManager.BootstrapResult result = manager.bootstrapTurtleOnDemand(turtleId);
            
            if (result.success) {
                LOGGER.debug("Turtle {} bootstrap successful", turtleId);
                chunkLoader = ChunkLoaderRegistry.getPeripheral(turtleId);
            } else {
                LOGGER.debug("Turtle {} bootstrap failed: {}", turtleId, result.errorCode);
            }
        }
        
        // Fire event to trigger command processing
        eventSystem.fireEvent(new TurtleStateEvents.CommandQueuedEvent(turtleId, command, commandSource));
        if (chunkLoader != null && radius > 0 && chunkLoader.getFuelLevel() == 0) {
            throw new LuaException("Warning: Turtle " + turtleIdString + " has no fuel. Command queued but may not execute immediately.");
        }
        
        return true; // Command is queued and will be processed reliably
    }

    /**
     * Get a list of all active turtle chunk loaders
    @LuaFunction
    public final List<Map<String, Object>> listTurtles() {
        List<Map<String, Object>> result = new ArrayList<>();

        Map<UUID, ChunkLoaderPeripheral> peripherals = ChunkLoaderRegistry.getAllPeripherals();

        for (ChunkLoaderPeripheral peripheral : peripherals.values()) {
            if (world instanceof ServerWorld && peripheral.getTurtleLevel() == world) {
                Map<String, Object> turtleData = new HashMap<>();
                turtleData.put("turtleId", peripheral.getTurtleId().toString());
                // Position removed for privacy/security - turtle location should not be exposed
                turtleData.put("fuelLevel", peripheral.getFuelLevel());
                turtleData.put("radius", peripheral.getRadius());
                turtleData.put("loadedChunks", peripheral.getLoadedChunkCount());
                turtleData.put("fuelRate", peripheral.calculateFuelCost());
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
