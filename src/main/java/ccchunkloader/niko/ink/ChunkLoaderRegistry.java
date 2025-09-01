package ccchunkloader.niko.ink;

import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simplified registry for tracking turtle chunk loaders.
 * Only stores minimal data needed for bootstrapping turtles on world load.
 */
public class ChunkLoaderRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkLoaderRegistry.class);

    private static final Map<UUID, ChunkLoaderPeripheral> ACTIVE_PERIPHERALS = new ConcurrentHashMap<>();


    /**
     * Register an active peripheral
     */
    public static void register(UUID turtleId, ChunkLoaderPeripheral peripheral) {
        ACTIVE_PERIPHERALS.put(turtleId, peripheral);
        LOGGER.debug("Registered active turtle chunk loader: {}", turtleId);
    }

    /**
     * Unregister an active peripheral
     */
    public static void unregister(UUID turtleId) {
        ChunkLoaderPeripheral removed = ACTIVE_PERIPHERALS.remove(turtleId);
        if (removed != null) {
            LOGGER.debug("Unregistered turtle chunk loader: {}", turtleId);
        }
    }

    /**
     * Get an active peripheral by UUID
     */
    public static ChunkLoaderPeripheral getPeripheral(UUID turtleId) {
        return ACTIVE_PERIPHERALS.get(turtleId);
    }

    /**
     * Check if a turtle is active
     */
    public static boolean isActive(UUID turtleId) {
        return ACTIVE_PERIPHERALS.containsKey(turtleId);
    }

    /**
     * Get all active peripherals
     */
    public static Map<UUID, ChunkLoaderPeripheral> getAllPeripherals() {
        return Map.copyOf(ACTIVE_PERIPHERALS);
    }





    /**
     * Check if a turtle can potentially be bootstrapped
     * This checks if the turtle exists in ChunkManager cache
     */
    public static boolean canBootstrap(UUID turtleId, net.minecraft.server.world.ServerWorld world) {
        // Check if turtle exists in ChunkManager cache
        if (world != null) {
            ChunkManager manager = ChunkManager.get(world);
            return manager.getCachedTurtleState(turtleId) != null;
        }
        
        return false;
    }
    
    /**
     * Get diagnostic information about a turtle's availability
     */
    public static String getTurtleDiagnostic(UUID turtleId, net.minecraft.server.world.ServerWorld world) {
        boolean isActive = isActive(turtleId);
        boolean hasCache = (world != null) ? ChunkManager.get(world).getCachedTurtleState(turtleId) != null : false;
        
        return String.format("Turtle %s: active=%s, cached=%s", 
                           turtleId, isActive, hasCache);
    }

    /**
     * Permanently remove a turtle from all tracking (for UUID changes/peripheral removal)
     */
    public static void permanentlyRemoveTurtle(UUID turtleId) {
        LOGGER.info("PERMANENTLY removing turtle {} from registry", turtleId);
        
        ChunkLoaderPeripheral removed = ACTIVE_PERIPHERALS.remove(turtleId);
        
        if (removed != null) {
            LOGGER.info("Removed active peripheral for turtle {}", turtleId);
        }
    }
    
    /**
     * Clear all data (for mod shutdown/world unload)
     */
    public static void clearAll() {
        int activeCount = ACTIVE_PERIPHERALS.size();

        ACTIVE_PERIPHERALS.clear();

        if (activeCount > 0) {
            LOGGER.info("Cleared {} active turtle records", activeCount);
        }
    }

    /**
     * Clean Slate: Remove multiple peripherals at once (for chunk unload events)
     */
    public static void removePeripherals(Set<UUID> turtleIds) {
        int removed = 0;
        for (UUID turtleId : turtleIds) {
            if (ACTIVE_PERIPHERALS.remove(turtleId) != null) {
                removed++;
            }
        }
        if (removed > 0) {
            LOGGER.debug("Clean slate: removed {} peripherals from unloaded chunks", removed);
        }
    }
}
