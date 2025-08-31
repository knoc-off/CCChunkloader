package ccchunkloader.niko.ink;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages chunk loading with reference counting to handle multiple turtles
 * loading the same chunks safely.
 */
public class ChunkManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkManager.class);
    private static final Map<ServerWorld, ChunkManager> MANAGERS = new ConcurrentHashMap<>();

    // Map from ChunkPos to set of turtle UUIDs that are keeping it loaded
    private final Map<ChunkPos, Set<UUID>> chunkLoaders = new ConcurrentHashMap<>();
    // Map from turtle UUID to set of chunks they're currently loading
    private final Map<UUID, Set<ChunkPos>> turtleChunks = new ConcurrentHashMap<>();
    // Timestamp of last activity for cleanup
    private final Map<UUID, Long> lastTouch = new ConcurrentHashMap<>();

    private final ServerWorld world;
    private boolean bootstrapped = false;

    private ChunkManager(ServerWorld world) {
        this.world = world;
        LOGGER.debug("Created new ChunkManager for world: {}", world.getRegistryKey().getValue());
    }

    public static ChunkManager get(ServerWorld world) {
        ChunkManager manager = MANAGERS.computeIfAbsent(world, ChunkManager::new);

        // Bootstrap turtles on first access to the world's ChunkManager
        manager.bootstrapTurtles();

        return manager;
    }

    /**
     * Force bootstrap turtles even if already bootstrapped
     * Called when new bootstrap data becomes available
     */
    public static void forceBootstrap(ServerWorld world) {
        ChunkManager manager = MANAGERS.get(world);
        if (manager != null) {
            manager.forceBootstrapTurtles();
        }
    }

    /**
     * Force bootstrap without checking the bootstrapped flag
     */
    private void forceBootstrapTurtles() {
        LOGGER.info("Force-bootstrapping turtles for world {}", world.getRegistryKey().getValue());

        Map<UUID, ChunkLoaderRegistry.BootstrapData> turtlesToBootstrap =
            ChunkLoaderRegistry.getTurtlesToBootstrap(world.getRegistryKey());

        LOGGER.info("Found {} turtles in bootstrap registry for force-bootstrap in world {}",
                   turtlesToBootstrap.size(), world.getRegistryKey().getValue());

        if (turtlesToBootstrap.isEmpty()) {
            LOGGER.info("No turtles to force-bootstrap for world {}", world.getRegistryKey().getValue());
            return;
        }

        for (Map.Entry<UUID, ChunkLoaderRegistry.BootstrapData> entry : turtlesToBootstrap.entrySet()) {
            UUID turtleId = entry.getKey();
            ChunkLoaderRegistry.BootstrapData data = entry.getValue();

            LOGGER.info("Force-processing bootstrap data for turtle {}: chunk={}, fuel={}, wake={}",
                       turtleId, data.chunkPos, data.lastKnownFuelLevel, data.wakeOnWorldLoad);

            if (data.lastKnownFuelLevel > 0 && data.wakeOnWorldLoad) {
                // Force-load the chunk where the turtle is located to wake it up
                world.setChunkForced(data.chunkPos.x, data.chunkPos.z, true);
                LOGGER.info("FORCE-LOADED chunk {} to bootstrap turtle {} (fuel: {}) [FORCE-BOOTSTRAP]",
                           data.chunkPos, turtleId, data.lastKnownFuelLevel);
            }
        }

        LOGGER.info("Force-bootstrap process completed for world {}", world.getRegistryKey().getValue());
    }

    /**
     * Bootstrap turtles that should wake up when the world loads
     */
    private void bootstrapTurtles() {
        if (bootstrapped) {
            LOGGER.debug("ChunkManager for world {} already bootstrapped, skipping", world.getRegistryKey().getValue());
            return; // Already bootstrapped
        }

        bootstrapped = true;
        LOGGER.info("Starting bootstrap process for world {}", world.getRegistryKey().getValue());

        Map<UUID, ChunkLoaderRegistry.BootstrapData> turtlesToBootstrap =
            ChunkLoaderRegistry.getTurtlesToBootstrap(world.getRegistryKey());

        LOGGER.info("Found {} turtles in bootstrap registry for world {}",
                   turtlesToBootstrap.size(), world.getRegistryKey().getValue());

        if (turtlesToBootstrap.isEmpty()) {
            LOGGER.info("No turtles to bootstrap for world {}", world.getRegistryKey().getValue());
            return;
        }

        LOGGER.info("Bootstrapping {} turtles with wake-on-world-load enabled for world {}",
                   turtlesToBootstrap.size(), world.getRegistryKey().getValue());

        for (Map.Entry<UUID, ChunkLoaderRegistry.BootstrapData> entry : turtlesToBootstrap.entrySet()) {
            UUID turtleId = entry.getKey();
            ChunkLoaderRegistry.BootstrapData data = entry.getValue();

            LOGGER.info("Processing bootstrap data for turtle {}: chunk={}, fuel={}, wake={}",
                       turtleId, data.chunkPos, data.lastKnownFuelLevel, data.wakeOnWorldLoad);

            if (data.lastKnownFuelLevel > 0 && data.wakeOnWorldLoad) {
                // Force-load the chunk where the turtle is located to wake it up
                world.setChunkForced(data.chunkPos.x, data.chunkPos.z, true);
                LOGGER.info("FORCE-LOADED chunk {} to bootstrap turtle {} (fuel: {})",
                           data.chunkPos, turtleId, data.lastKnownFuelLevel);
            } else {
                LOGGER.info("Skipping bootstrap for turtle {}: fuel={}, wake={}",
                           turtleId, data.lastKnownFuelLevel, data.wakeOnWorldLoad);
            }
        }

        LOGGER.info("Bootstrap process completed for world {}", world.getRegistryKey().getValue());
    }

    /**
     * Add chunks from a pre-computed set to the force-loaded set
     * Uses atomic swapping: loads new chunks BEFORE removing old chunks
     * IMPORTANT: World operations are done OUTSIDE synchronized blocks to prevent deadlocks
     */
    public void addChunksFromSet(UUID turtleId, Set<ChunkPos> newChunks) {
        LOGGER.debug("Adding {} chunks for turtle {}", newChunks.size(), turtleId);

        // PHASE 1: Calculate changes under lock (fast, non-blocking)
        Set<ChunkPos> chunksToLoad;
        Set<ChunkPos> chunksToUnload;
        Set<ChunkPos> oldChunks;

        synchronized (this) {
            oldChunks = turtleChunks.get(turtleId);

            // Calculate which chunks need to be force-loaded (new chunks that have no other loaders)
            chunksToLoad = ConcurrentHashMap.newKeySet();
            for (ChunkPos chunkPos : newChunks) {
                // Add this turtle as a loader for this chunk
                Set<UUID> loaders = chunkLoaders.computeIfAbsent(chunkPos, k -> ConcurrentHashMap.newKeySet());
                loaders.add(turtleId);

                // If this is the first turtle to load this chunk, mark it for force loading
                if (loaders.size() == 1) {
                    chunksToLoad.add(chunkPos);
                }
            }

            // Calculate which chunks need to be removed
            chunksToUnload = ConcurrentHashMap.newKeySet();
            if (oldChunks != null) {
                for (ChunkPos oldChunk : oldChunks) {
                    if (!newChunks.contains(oldChunk)) {
                        chunksToUnload.add(oldChunk);
                    }
                }
            }

            // Update turtle's chunk set immediately
            turtleChunks.put(turtleId, Set.copyOf(newChunks));
            touch(turtleId);
        }

        // PHASE 2: Apply world changes WITHOUT holding locks (can block safely)
        // Force load new chunks
        for (ChunkPos chunkPos : chunksToLoad) {
            world.setChunkForced(chunkPos.x, chunkPos.z, true);
            LOGGER.debug("Force loaded chunk {} for turtle {}", chunkPos, turtleId);
        }

        // Remove old chunks (this can also call removeChunkLoader which might force-unload)
        for (ChunkPos oldChunk : chunksToUnload) {
            removeChunkLoader(turtleId, oldChunk);
        }

        LOGGER.debug("Completed chunk update for turtle {}: +{} -{}",
                    turtleId, chunksToLoad.size(), chunksToUnload.size());
    }

    /**
     * Add chunks in a circular radius around the turtle's position to the force-loaded set
     * @deprecated Use addChunksFromSet with pre-computed chunks instead
     */
    @Deprecated
    public void addChunksInRadius(UUID turtleId, ChunkPos centerChunk, double radius) {
        Set<ChunkPos> newChunks = ConcurrentHashMap.newKeySet();

        // Calculate chunks based on radius:
        // Radius 1.0 = current chunk only (distance < 1.0)
        // Radius 2.0 = current chunk + neighbors (distance < 2.0)
        // etc.
        int searchRadius = (int) Math.ceil(radius) + 1;
        for (int x = -searchRadius; x <= searchRadius; x++) {
            for (int z = -searchRadius; z <= searchRadius; z++) {
                // Calculate distance from center chunk
                double distance = Math.sqrt(x * x + z * z);

                // Include chunks within radius distance (strict less-than for radius 1.0 = current only)
                if (distance < radius) {
                    ChunkPos chunkPos = new ChunkPos(centerChunk.x + x, centerChunk.z + z);
                    newChunks.add(chunkPos);
                }
            }
        }

        addChunksFromSet(turtleId, newChunks);
    }

    /**
     * Remove all chunks loaded by a specific turtle
     * IMPORTANT: World operations are done OUTSIDE synchronized blocks to prevent deadlocks
     */
    public void removeAllChunks(UUID turtleId) {
        // PHASE 1: Get chunks to remove under lock (fast, non-blocking)
        Set<ChunkPos> chunks;
        synchronized (this) {
            chunks = turtleChunks.remove(turtleId);
            lastTouch.remove(turtleId);
        }

        // PHASE 2: Remove chunks WITHOUT holding locks (can block safely)
        if (chunks != null) {
            for (ChunkPos chunk : chunks) {
                removeChunkLoader(turtleId, chunk);
            }
        }
        LOGGER.debug("Removed {} chunks for turtle {}", chunks != null ? chunks.size() : 0, turtleId);
    }

    /**
     * Remove a turtle as a loader for a specific chunk
     * IMPORTANT: World operations are done OUTSIDE synchronized blocks to prevent deadlocks
     */
    private void removeChunkLoader(UUID turtleId, ChunkPos chunk) {
        // PHASE 1: Update data structures under lock (fast, non-blocking)
        boolean shouldUnforce;
        synchronized (this) {
            Set<UUID> loaders = chunkLoaders.get(chunk);
            if (loaders == null) {
                return; // Chunk not tracked
            }

            loaders.remove(turtleId);

            // If no more turtles are loading this chunk, mark for unforcing
            if (loaders.isEmpty()) {
                chunkLoaders.remove(chunk);
                shouldUnforce = true;
            } else {
                shouldUnforce = false;
            }
        }

        // PHASE 2: Apply world changes WITHOUT holding locks (can block safely)
        if (shouldUnforce) {
            world.setChunkForced(chunk.x, chunk.z, false);
            LOGGER.debug("Unforced chunk {} (no more loaders)", chunk);
        }
    }

    /**
     * Check if a turtle is already tracked in this ChunkManager
     */
    public boolean isTurtleTracked(UUID turtleId) {
        return lastTouch.containsKey(turtleId) && turtleChunks.containsKey(turtleId);
    }

    /**
     * Update the last activity timestamp for a turtle
     * Also ensures the turtle is tracked in turtleChunks even with empty chunk set
     */
    public void touch(UUID turtleId) {
        long timestamp = System.currentTimeMillis();
        lastTouch.put(turtleId, timestamp);

        // Ensure turtle is tracked in turtleChunks even if it has no chunks loaded
        // This is important for persistence - we want to save ALL turtle interactions
        if (!turtleChunks.containsKey(turtleId)) {
            turtleChunks.put(turtleId, ConcurrentHashMap.newKeySet());
            LOGGER.debug("Added turtle {} to turtleChunks tracking with empty chunk set", turtleId);
        }
    }

    /**
     * Get the number of chunks currently loaded by a turtle
     */
    public int getLoadedChunkCount(UUID turtleId) {
        Set<ChunkPos> chunks = turtleChunks.get(turtleId);
        return chunks != null ? chunks.size() : 0;
    }

    /**
     * Check if a turtle has any loaded chunks
     */
    public boolean hasLoadedChunks(UUID turtleId) {
        return turtleChunks.containsKey(turtleId) && !turtleChunks.get(turtleId).isEmpty();
    }

    /**
     * Get all chunks currently loaded by a turtle
     */
    public Set<ChunkPos> getLoadedChunks(UUID turtleId) {
        Set<ChunkPos> chunks = turtleChunks.get(turtleId);
        return chunks != null ? Set.copyOf(chunks) : Set.of();
    }

    /**
     * Cleanup inactive chunk loaders (called periodically)
     * IMPORTANT: World operations are done OUTSIDE synchronized blocks to prevent deadlocks
     */
    public void cleanup(long maxInactiveTime) {
        long currentTime = System.currentTimeMillis();

        // PHASE 1: Identify inactive turtles under lock (fast, non-blocking)
        Set<UUID> inactiveTurtles = ConcurrentHashMap.newKeySet();
        synchronized (this) {
            lastTouch.entrySet().removeIf(entry -> {
                UUID turtleId = entry.getKey();
                long lastActivity = entry.getValue();

                if (currentTime - lastActivity > maxInactiveTime) {
                    inactiveTurtles.add(turtleId);
                    return true;
                }
                return false;
            });
        }

        // PHASE 2: Clean up inactive turtles WITHOUT holding locks (can block safely)
        for (UUID turtleId : inactiveTurtles) {
            LOGGER.info("Cleaning up inactive turtle chunk loader: {}", turtleId);
            removeAllChunks(turtleId);
        }
    }

    /**
     * Get total number of force-loaded chunks in this world
     */
    public int getTotalLoadedChunks() {
        return chunkLoaders.size();
    }

    /**
     * Get total number of active turtle chunk loaders
     */
    public int getActiveTurtleCount() {
        return turtleChunks.size();
    }

    /**
     * Get all turtle UUIDs that have been restored from world data
     * This includes turtles that may not have active ChunkLoaderPeripheral instances yet
     */
    public synchronized Set<UUID> getRestoredTurtleIds() {
        return Set.copyOf(turtleChunks.keySet());
    }

    // Map to store complete restored turtle states for registry population
    private final Map<UUID, ChunkLoaderPeripheral.SavedState> restoredTurtleStates = new ConcurrentHashMap<>();

    /**
     * Get the complete restored state for a turtle UUID
     */
    public synchronized ChunkLoaderPeripheral.SavedState getRestoredTurtleState(UUID turtleId) {
        return restoredTurtleStates.get(turtleId);
    }

    /**
     * Get all complete restored turtle states
     */
    public synchronized Map<UUID, ChunkLoaderPeripheral.SavedState> getAllRestoredTurtleStates() {
        return Map.copyOf(restoredTurtleStates);
    }

    /**
     * Get the restored position for a turtle UUID (backward compatibility)
     */
    public synchronized ChunkPos getRestoredTurtlePosition(UUID turtleId) {
        ChunkLoaderPeripheral.SavedState state = restoredTurtleStates.get(turtleId);
        return state != null ? state.lastChunkPos : null;
    }

    /**
     * Get all restored turtle positions for registry population (backward compatibility)
     */
    public synchronized Map<UUID, ChunkPos> getAllRestoredTurtlePositions() {
        Map<UUID, ChunkPos> positions = new HashMap<>();
        for (Map.Entry<UUID, ChunkLoaderPeripheral.SavedState> entry : restoredTurtleStates.entrySet()) {
            if (entry.getValue().lastChunkPos != null) {
                positions.put(entry.getKey(), entry.getValue().lastChunkPos);
            }
        }
        return positions;
    }

    /**
     * Get wake preference for a restored turtle (backward compatibility)
     */
    public synchronized boolean getRestoredWakePreference(UUID turtleId) {
        ChunkLoaderPeripheral.SavedState state = restoredTurtleStates.get(turtleId);
        return state != null ? state.wakeOnWorldLoad : false;
    }

    /**
     * Get all restored turtle wake preferences (backward compatibility)
     */
    public synchronized Map<UUID, Boolean> getAllRestoredWakePreferences() {
        Map<UUID, Boolean> wakePrefs = new HashMap<>();
        for (Map.Entry<UUID, ChunkLoaderPeripheral.SavedState> entry : restoredTurtleStates.entrySet()) {
            wakePrefs.put(entry.getKey(), entry.getValue().wakeOnWorldLoad);
        }
        return wakePrefs;
    }

    /**
     * Emergency cleanup - remove all chunk loaders
     * IMPORTANT: World operations are done OUTSIDE synchronized blocks to prevent deadlocks
     */
    public void clearAll() {
        // PHASE 1: Get all chunks to unforce under lock (fast, non-blocking)
        Set<ChunkPos> chunksToUnforce;
        synchronized (this) {
            chunksToUnforce = Set.copyOf(chunkLoaders.keySet());
            chunkLoaders.clear();
            turtleChunks.clear();
            lastTouch.clear();
        }

        // PHASE 2: Unforce chunks WITHOUT holding locks (can block safely)
        for (ChunkPos chunk : chunksToUnforce) {
            world.setChunkForced(chunk.x, chunk.z, false);
        }
        LOGGER.info("Emergency cleanup: cleared {} chunk loaders for world {}",
                   chunksToUnforce.size(), world.getRegistryKey().getValue());
    }

    /**
     * Static cleanup for world shutdown
     */
    public static void cleanupWorld(ServerWorld world) {
        ChunkManager manager = MANAGERS.remove(world);
        if (manager != null) {
            manager.clearAll();
        }
    }

    /**
     * Static cleanup for mod shutdown
     */
    public static void cleanupAll() {
        for (ChunkManager manager : MANAGERS.values()) {
            manager.clearAll();
        }
        MANAGERS.clear();
    }

    /**
     * Serialize ChunkManager state to NBT for persistent storage
     * SIMPLIFIED: Only saves turtle positions for registry restoration, not complex chunk data
     */
    public synchronized NbtCompound serializeToNbt() {
        NbtCompound nbt = new NbtCompound();
        NbtList turtleStates = new NbtList();

        for (UUID turtleId : turtleChunks.keySet()) {
            ChunkLoaderPeripheral chunkLoader = ChunkLoaderRegistry.getPeripheral(turtleId);
            if (chunkLoader != null) {
                ChunkLoaderPeripheral.SavedState state = chunkLoader.getSavedState();
                if (state != null) {
                    NbtCompound stateData = new NbtCompound();
                    stateData.putString("uuid", turtleId.toString());
                    stateData.putDouble("radius", state.radius);
                    if (state.lastChunkPos != null) {
                        stateData.putInt("lastChunkX", state.lastChunkPos.x);
                        stateData.putInt("lastChunkZ", state.lastChunkPos.z);
                    }
                    stateData.putDouble("fuelDebt", state.fuelDebt);
                    stateData.putBoolean("wakeOnWorldLoad", state.wakeOnWorldLoad);
                    stateData.putBoolean("randomTickEnabled", state.randomTickEnabled);
                    stateData.putInt("fuelLevel", state.fuelLevel);
                    turtleStates.add(stateData);
                }
            } else {
                LOGGER.warn("Could not find a chunk loader for tracked turtle ID {}. It will not be saved.", turtleId);
            }
        }

        nbt.put("turtleStates", turtleStates);
        return nbt;
    }

    /**
     * A simple record to hold the results of deserialization for logging.
     */
    public static class DeserializationResult {
        public final int total;
        public final int toWake;

        public DeserializationResult(int total, int toWake) {
            this.total = total;
            this.toWake = toWake;
        }
    }

    public synchronized DeserializationResult deserializeFromNbt(NbtCompound nbt) {
        chunkLoaders.clear();
        turtleChunks.clear();
        lastTouch.clear();
        restoredTurtleStates.clear();

        if (nbt.contains("turtleStates")) {
            NbtList turtleStatesNbt = nbt.getList("turtleStates", 10);
            for (int i = 0; i < turtleStatesNbt.size(); i++) {
                NbtCompound stateData = turtleStatesNbt.getCompound(i);
                try {
                    UUID turtleId = UUID.fromString(stateData.getString("uuid"));
                    double radius = stateData.getDouble("radius");
                    ChunkPos lastChunkPos = null;
                    if (stateData.contains("lastChunkX") && stateData.contains("lastChunkZ")) {
                        lastChunkPos = new ChunkPos(stateData.getInt("lastChunkX"), stateData.getInt("lastChunkZ"));
                    }
                    double fuelDebt = stateData.getDouble("fuelDebt");
                    boolean wakeOnWorldLoad = stateData.getBoolean("wakeOnWorldLoad");
                    boolean randomTickEnabled = stateData.contains("randomTickEnabled") ? stateData.getBoolean("randomTickEnabled") : false;
                    int fuelLevel = stateData.contains("fuelLevel") ? stateData.getInt("fuelLevel") : -1;

                    ChunkLoaderPeripheral.SavedState state = new ChunkLoaderPeripheral.SavedState(
                        radius, lastChunkPos, fuelDebt, wakeOnWorldLoad, randomTickEnabled, fuelLevel
                    );

                    turtleChunks.put(turtleId, ConcurrentHashMap.newKeySet());
                    lastTouch.put(turtleId, System.currentTimeMillis());
                    restoredTurtleStates.put(turtleId, state);
                } catch (Exception e) {
                    LOGGER.error("Failed to restore a turtle state from NBT.", e);
                }
            }
        }

        int toWakeCount = (int) restoredTurtleStates.values().stream().filter(s -> s.wakeOnWorldLoad).count();
        return new DeserializationResult(restoredTurtleStates.size(), toWakeCount);
    }

    /**
     * Wake turtles that are marked for `wakeOnWorldLoad`.
     * This is called AFTER the world has loaded and registration is complete.
     * It only wakes turtles that have explicitly opted-in and have fuel.
     */
    public void wakeTurtlesOnLoad() {
        int restoredCount = restoredTurtleStates.size();
        if (restoredCount == 0) {
            return;
        }

        long turtlesToWake = restoredTurtleStates.values().stream()
            .filter(state -> state.wakeOnWorldLoad)
            .count();

        if (turtlesToWake == 0) {
            LOGGER.info("No restored turtles marked to wake on load.");
            return;
        }

        LOGGER.info("Attempting to wake {}/{} restored turtles that have opted-in...", turtlesToWake, restoredCount);

        for (Map.Entry<UUID, ChunkLoaderPeripheral.SavedState> entry : restoredTurtleStates.entrySet()) {
            UUID turtleId = entry.getKey();
            ChunkLoaderPeripheral.SavedState state = entry.getValue();

            // Wake the turtle only if it has opted-in
            if (state.wakeOnWorldLoad) {
                // AND check if it has fuel
                if (state.fuelLevel == 0) {
                    LOGGER.warn("Skipping wake for opt-in turtle {} because it has no fuel.", turtleId);
                    continue;
                }

                // Add to bootstrap registry for simplified wakeup
                if (state.lastChunkPos != null) {
                    ChunkLoaderRegistry.updateBootstrapData(
                        turtleId,
                        this.world.getRegistryKey(),
                        state.lastChunkPos,
                        state.fuelLevel,
                        state.wakeOnWorldLoad
                    );
                    LOGGER.info("Added turtle {} to bootstrap registry for wakeup", turtleId);
                } else {
                    LOGGER.warn("Cannot bootstrap turtle {} - no last known position", turtleId);
                }
            }
        }
    }


}
