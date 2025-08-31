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
    private static final Map<UUID, BootstrapData> BOOTSTRAP_DATA = new ConcurrentHashMap<>();

    /**
     * Minimal data needed to bootstrap a turtle on world load
     */
    public static class BootstrapData {
        public final RegistryKey<World> worldKey;
        public final ChunkPos chunkPos;
        public final int lastKnownFuelLevel;
        public final boolean wakeOnWorldLoad;

        public BootstrapData(RegistryKey<World> worldKey, ChunkPos chunkPos, int fuelLevel, boolean wakeOnWorldLoad) {
            this.worldKey = worldKey;
            this.chunkPos = chunkPos;
            this.lastKnownFuelLevel = fuelLevel;
            this.wakeOnWorldLoad = wakeOnWorldLoad;
        }
    }

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
     * Update bootstrap data for a turtle
     * This is called whenever a turtle's state changes to keep bootstrap data current
     */
    public static void updateBootstrapData(UUID turtleId, RegistryKey<World> worldKey, ChunkPos chunkPos,
                                         int fuelLevel, boolean wakeOnWorldLoad) {
        if (wakeOnWorldLoad && fuelLevel > 0) {
            BOOTSTRAP_DATA.put(turtleId, new BootstrapData(worldKey, chunkPos, fuelLevel, wakeOnWorldLoad));
            LOGGER.debug("Updated bootstrap data for turtle {}: chunk={}, fuel={}", turtleId, chunkPos, fuelLevel);
        } else {
            // Remove from bootstrap data if turtle shouldn't wake up or has no fuel
            BOOTSTRAP_DATA.remove(turtleId);
        }
    }

    /**
     * Get bootstrap data for a turtle
     */
    public static BootstrapData getBootstrapData(UUID turtleId) {
        return BOOTSTRAP_DATA.get(turtleId);
    }

    /**
     * Get all turtles that should be bootstrapped for a given world
     */
    public static Map<UUID, BootstrapData> getTurtlesToBootstrap(RegistryKey<World> worldKey) {
        Map<UUID, BootstrapData> toBootstrap = new ConcurrentHashMap<>();

        for (Map.Entry<UUID, BootstrapData> entry : BOOTSTRAP_DATA.entrySet()) {
            if (entry.getValue().worldKey.equals(worldKey)) {
                toBootstrap.put(entry.getKey(), entry.getValue());
            }
        }

        return toBootstrap;
    }

    /**
     * Remove bootstrap data for a turtle (called when turtle wakes up successfully)
     */
    public static void clearBootstrapData(UUID turtleId) {
        BOOTSTRAP_DATA.remove(turtleId);
    }

    /**
     * Clear all data (for mod shutdown/world unload)
     */
    public static void clearAll() {
        int activeCount = ACTIVE_PERIPHERALS.size();
        int bootstrapCount = BOOTSTRAP_DATA.size();

        ACTIVE_PERIPHERALS.clear();
        BOOTSTRAP_DATA.clear();

        if (activeCount > 0 || bootstrapCount > 0) {
            LOGGER.info("Cleared {} active and {} bootstrap turtle records", activeCount, bootstrapCount);
        }
    }
}
