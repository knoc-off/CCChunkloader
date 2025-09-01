package ccchunkloader.niko.ink;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;

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
    // Unified remote management state for offline turtles (position, fuel, wake preference, computer ID)
    private final Map<UUID, RemoteManagementState> remoteManagementStates = new ConcurrentHashMap<>();
    // Bootstrap states from NBT (temporary during world load)
    private final Map<UUID, ChunkLoaderPeripheral.SavedState> restoredTurtleStates = new ConcurrentHashMap<>();
    // Unified computer ID to UUID tracking (replaces separate bidirectional maps)
    private final ComputerUUIDTracker computerTracker = new ComputerUUIDTracker();

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

        Map<UUID, BootstrapData> turtlesToBootstrap =
            getTurtlesToBootstrap(world.getRegistryKey());

        LOGGER.info("Found {} turtles in bootstrap registry for force-bootstrap in world {}",
                   turtlesToBootstrap.size(), world.getRegistryKey().getValue());

        if (turtlesToBootstrap.isEmpty()) {
            LOGGER.info("No turtles to force-bootstrap for world {}", world.getRegistryKey().getValue());
            return;
        }

        for (Map.Entry<UUID, BootstrapData> entry : turtlesToBootstrap.entrySet()) {
            UUID turtleId = entry.getKey();
            BootstrapData data = entry.getValue();

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

        Map<UUID, BootstrapData> turtlesToBootstrap =
            getTurtlesToBootstrap(world.getRegistryKey());

        LOGGER.info("Found {} turtles in bootstrap registry for world {}",
                   turtlesToBootstrap.size(), world.getRegistryKey().getValue());

        if (turtlesToBootstrap.isEmpty()) {
            LOGGER.info("No turtles to bootstrap for world {}", world.getRegistryKey().getValue());
            return;
        }

        LOGGER.info("Bootstrapping {} turtles with wake-on-world-load enabled for world {}",
                   turtlesToBootstrap.size(), world.getRegistryKey().getValue());

        for (Map.Entry<UUID, BootstrapData> entry : turtlesToBootstrap.entrySet()) {
            UUID turtleId = entry.getKey();
            BootstrapData data = entry.getValue();

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
     * Remove all chunks loaded by a specific turtle but preserve turtle tracking
     * IMPORTANT: World operations are done OUTSIDE synchronized blocks to prevent deadlocks
     * NOTE: This method preserves turtle tracking data to prevent permanent data loss
     */
    public void removeAllChunks(UUID turtleId) {
        // PHASE 1: Get chunks to remove under lock but PRESERVE turtle tracking (fast, non-blocking)
        Set<ChunkPos> chunks;
        synchronized (this) {
            chunks = turtleChunks.get(turtleId);
            if (chunks != null) {
                // Clear the chunk set but keep the turtle tracked with empty set
                turtleChunks.put(turtleId, ConcurrentHashMap.newKeySet());
                chunks = Set.copyOf(chunks); // Make a copy for iteration
            }
        }

        // PHASE 2: Remove chunks WITHOUT holding locks (can block safely)
        if (chunks != null) {
            for (ChunkPos chunk : chunks) {
                removeChunkLoader(turtleId, chunk);
            }
        }
        LOGGER.debug("Removed {} chunks for turtle {} (turtle tracking preserved)", chunks != null ? chunks.size() : 0, turtleId);
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
        return turtleChunks.containsKey(turtleId);
    }

    /**
     * Update the last activity timestamp for a turtle
     * Also ensures the turtle is tracked in turtleChunks even with empty chunk set
     */
    public void touch(UUID turtleId) {
        // Ensure turtle is tracked in turtleChunks even if it has no chunks loaded
        // This is important for persistence - we want to save ALL turtle interactions
        if (!turtleChunks.containsKey(turtleId)) {
            turtleChunks.put(turtleId, ConcurrentHashMap.newKeySet());
            LOGGER.debug("Added turtle {} to turtleChunks tracking with empty chunk set", turtleId);
        }
    }
    
    /**
     * Update remote management state for a turtle (for offline management)
     */
    public void updateRemoteManagementState(UUID turtleId, ChunkPos position, int fuelLevel, boolean wakeOnWorldLoad, Integer computerId) {
        // Preserve existing radius override if any
        RemoteManagementState current = remoteManagementStates.get(turtleId);
        Double existingOverride = current != null ? current.radiusOverride : null;
        
        RemoteManagementState newState = new RemoteManagementState(position, fuelLevel, wakeOnWorldLoad, computerId, existingOverride);
        remoteManagementStates.put(turtleId, newState);
        touch(turtleId); // Ensure turtle is tracked
        LOGGER.debug("Updated remote management state for turtle {}: pos={}, fuel={}, wake={}, computerId={}, override={}", 
                    turtleId, position, fuelLevel, wakeOnWorldLoad, computerId, existingOverride);
    }
    
    /**
     * Update position only (convenience method)
     */
    public void updateTurtlePosition(UUID turtleId, ChunkPos position) {
        RemoteManagementState current = remoteManagementStates.get(turtleId);
        if (current != null) {
            updateRemoteManagementState(turtleId, position, current.lastKnownFuel, current.wakeOnWorldLoad, current.computerId);
        } else {
            updateRemoteManagementState(turtleId, position, 1, false, null);
        }
    }
    
    /**
     * Update fuel only (convenience method)
     */
    public void updateTurtleFuel(UUID turtleId, int fuelLevel) {
        RemoteManagementState current = remoteManagementStates.get(turtleId);
        if (current != null) {
            updateRemoteManagementState(turtleId, current.lastKnownPosition, fuelLevel, current.wakeOnWorldLoad, current.computerId);
        } else {
            updateRemoteManagementState(turtleId, null, fuelLevel, false, null);
        }
    }
    
    /**
     * Get persistent position for a turtle (always available)
     */
    public ChunkPos getPersistentPosition(UUID turtleId) {
        RemoteManagementState state = remoteManagementStates.get(turtleId);
        return state != null ? state.lastKnownPosition : null;
    }
    
    /**
     * Get persistent fuel level for a turtle (always available)
     */
    public Integer getPersistentFuelLevel(UUID turtleId) {
        RemoteManagementState state = remoteManagementStates.get(turtleId);
        return state != null ? state.lastKnownFuel : null;
    }
    
    /**
     * Set a radius override for a turtle (persisted in world NBT)
     * This will be applied when the turtle's peripheral is created and then cleared
     */
    public void setRadiusOverride(UUID turtleId, double radius) {
        RemoteManagementState current = remoteManagementStates.get(turtleId);
        if (current != null) {
            RemoteManagementState updated = new RemoteManagementState(
                current.lastKnownPosition, current.lastKnownFuel, current.wakeOnWorldLoad, current.computerId, radius
            );
            remoteManagementStates.put(turtleId, updated);
        } else {
            // Create new state with just override
            RemoteManagementState newState = new RemoteManagementState(null, 1, false, null, radius);
            remoteManagementStates.put(turtleId, newState);
        }
        LOGGER.info("Set radius override for turtle {}: {}", turtleId, radius);
        // Save to world immediately to persist across chunk loads
        markDirty();
    }
    
    /**
     * Get and clear radius override for a turtle
     * Returns null if no override exists
     */
    public Double getAndClearRadiusOverride(UUID turtleId) {
        RemoteManagementState current = remoteManagementStates.get(turtleId);
        if (current != null && current.radiusOverride != null) {
            Double override = current.radiusOverride;
            // Clear the override by updating state
            RemoteManagementState updated = new RemoteManagementState(
                current.lastKnownPosition, current.lastKnownFuel, current.wakeOnWorldLoad, current.computerId, null
            );
            remoteManagementStates.put(turtleId, updated);
            LOGGER.info("Retrieved and cleared radius override for turtle {}: {}", turtleId, override);
            markDirty();
            return override;
        }
        return null;
    }
    
    /**
     * Check if a turtle has a radius override pending
     */
    public boolean hasRadiusOverride(UUID turtleId) {
        RemoteManagementState state = remoteManagementStates.get(turtleId);
        return state != null && state.radiusOverride != null;
    }
    
    /**
     * Mark the persistent state as dirty to ensure it gets saved
     */
    private void markDirty() {
        ChunkManagerPersistentState.getWorldState(world).markDirty();
    }
    

    /**
     * Update turtle state in cache for persistence
     * Called whenever a turtle's state changes to ensure dormant turtles can be saved
     */
    public synchronized void updateTurtleStateCache(UUID turtleId, ChunkLoaderPeripheral.SavedState state) {
        if (state != null) {
            // Update unified remote management state from turtle's own state
            RemoteManagementState current = remoteManagementStates.get(turtleId);
            Integer computerId = current != null ? current.computerId : null;
            
            RemoteManagementState newState = RemoteManagementState.fromSavedState(state, computerId);
            remoteManagementStates.put(turtleId, newState);
            
            LOGGER.debug("Updated remote management state from turtle cache {}: pos={}, fuel={}, wake={}, computerId={}",
                        turtleId, state.lastChunkPos, state.fuelLevel, state.wakeOnWorldLoad, computerId);
        } else {
            remoteManagementStates.remove(turtleId);
            LOGGER.debug("Removed turtle {} from remote management state", turtleId);
        }
    }

    /**
     * Get cached turtle state (may be dormant turtle)
     */
    public synchronized ChunkLoaderPeripheral.SavedState getCachedTurtleState(UUID turtleId) {
        RemoteManagementState state = remoteManagementStates.get(turtleId);
        return state != null ? state.toSavedState() : null;
    }

    /**
     * Remove turtle from state cache (called when turtle is completely removed)
     */
    public synchronized void removeTurtleFromCache(UUID turtleId) {
        remoteManagementStates.remove(turtleId);
        LOGGER.debug("Removed turtle {} from state cache", turtleId);
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
     * Emergency cleanup - remove all chunk loaders but preserve turtle state cache
     * IMPORTANT: World operations are done OUTSIDE synchronized blocks to prevent deadlocks
     * NOTE: This method preserves turtle state cache to prevent permanent data loss
     */
    public void clearAll() {
        // PHASE 1: Get all chunks to unforce under lock (fast, non-blocking)
        Set<ChunkPos> chunksToUnforce;
        synchronized (this) {
            chunksToUnforce = Set.copyOf(chunkLoaders.keySet());
            chunkLoaders.clear();
            
            // Clear active chunk tracking but preserve turtle existence
            for (UUID turtleId : turtleChunks.keySet()) {
                turtleChunks.put(turtleId, ConcurrentHashMap.newKeySet());
            }
            // DON'T clear remoteManagementStates - preserve turtle data
        }

        // PHASE 2: Unforce chunks WITHOUT holding locks (can block safely)
        for (ChunkPos chunk : chunksToUnforce) {
            world.setChunkForced(chunk.x, chunk.z, false);
        }
        LOGGER.info("Emergency cleanup: cleared {} chunk loaders for world {} (turtle data preserved)",
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
     * Permanently remove a turtle from all tracking (only for UUID changes/peripheral removal)
     * This is the ONLY method that should completely remove turtle data
     */
    public void permanentlyRemoveTurtle(UUID turtleId) {
        LOGGER.info("PERMANENTLY removing turtle {} from all tracking", turtleId);
        
        // Remove all chunks first
        removeAllChunks(turtleId);
        
        synchronized (this) {
            // Now actually remove from tracking maps
            turtleChunks.remove(turtleId);
            remoteManagementStates.remove(turtleId);
            
            // Remove from computer ID tracking
            Integer computerId = computerTracker.getComputerForUUID(turtleId);
            if (computerId != null) {
                computerTracker.remove(turtleId);
                LOGGER.debug("Removed UUID {} from computer ID {} tracking", turtleId, computerId);
            }
        }
        
        LOGGER.info("Turtle {} permanently removed from ChunkManager", turtleId);
    }
    
    /**
     * Static method to permanently remove a turtle from a specific world
     */
    public static void permanentlyRemoveTurtleFromWorld(ServerWorld world, UUID turtleId) {
        ChunkManager manager = MANAGERS.get(world);
        if (manager != null) {
            manager.permanentlyRemoveTurtle(turtleId);
        }
    }

    /**
     * Get all turtles that should be bootstrapped for this world
     * Replaces ChunkLoaderRegistry.getTurtlesToBootstrap()
     */
    public synchronized Map<UUID, BootstrapData> getTurtlesToBootstrap(RegistryKey<World> worldKey) {
        Map<UUID, BootstrapData> toBootstrap = new HashMap<>();
        
        for (Map.Entry<UUID, RemoteManagementState> entry : remoteManagementStates.entrySet()) {
            UUID turtleId = entry.getKey();
            RemoteManagementState state = entry.getValue();
            
            // Only include turtles that should wake on world load and have fuel
            if (state.wakeOnWorldLoad && state.lastKnownFuel > 0 && state.lastKnownPosition != null) {
                BootstrapData bootstrapData = new BootstrapData(
                    worldKey, state.lastKnownPosition, state.lastKnownFuel, state.wakeOnWorldLoad
                );
                toBootstrap.put(turtleId, bootstrapData);
            }
        }
        
        return toBootstrap;
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
     * SIMPLIFIED: Only save essential data needed for bootstrap:
     * - UUID (turtle identifier)
     * - lastChunkPos (where to load the turtle)
     * - fuelLevel (current fuel for bootstrap checks)
     * - wakeOnWorldLoad (whether turtle should auto-wake)
     * All other turtle configuration is stored in the turtle's own upgrade NBT
     */
    public synchronized NbtCompound serializeToNbt() {
        NbtCompound nbt = new NbtCompound();
        NbtList turtleStates = new NbtList();

        for (UUID turtleId : turtleChunks.keySet()) {
            ChunkPos lastPosition = null;
            int fuelLevel = -1;
            boolean wakeOnWorldLoad = false;
            boolean isActive = false;
            
            // PRIMARY SOURCE: Use remote management state as source of truth
            RemoteManagementState remoteState = remoteManagementStates.get(turtleId);
            if (remoteState != null) {
                lastPosition = remoteState.lastKnownPosition;
                fuelLevel = remoteState.lastKnownFuel;
                wakeOnWorldLoad = remoteState.wakeOnWorldLoad;
            }
            
            // Get wake preference from active peripheral or cache
            ChunkLoaderPeripheral chunkLoader = ChunkLoaderRegistry.getPeripheral(turtleId);
            if (chunkLoader != null) {
                ChunkLoaderPeripheral.SavedState state = chunkLoader.getSavedState();
                if (state != null) {
                    wakeOnWorldLoad = state.wakeOnWorldLoad;
                    isActive = true;
                    // Update remote management state from active peripheral
                    Integer computerId = remoteState != null ? remoteState.computerId : computerTracker.getComputerForUUID(turtleId);
                    updateRemoteManagementState(turtleId, state.lastChunkPos, state.fuelLevel, state.wakeOnWorldLoad, computerId);
                    
                    // Update local variables for serialization
                    if (state.lastChunkPos != null) {
                        lastPosition = state.lastChunkPos;
                    }
                    fuelLevel = state.fuelLevel;
                    wakeOnWorldLoad = state.wakeOnWorldLoad;
                }
            } else {
                // Data already loaded from remoteState above
                if (remoteState != null) {
                    LOGGER.debug("Using remote management state for dormant turtle {}", turtleId);
                } else {
                    LOGGER.debug("No state data available for dormant turtle {}", turtleId);
                }
            }
            
            // Handle missing data with defaults and loud logging
            if (lastPosition == null) {
                LOGGER.error("CRITICAL: Turtle {} has no position data even in persistent tracking! This should never happen. Skipping serialization.", turtleId);
                continue; // Skip this turtle entirely if no position
            }
            
            if (fuelLevel < 0) {
                LOGGER.error("CRITICAL: Turtle {} has no fuel data even in persistent tracking! Defaulting to fuel=1 to prevent data loss.", turtleId);
                fuelLevel = 1; // Default to 1 fuel as requested
            }
            
            // Save essential bootstrap data including wakeOnWorldLoad status and computer ID
            NbtCompound stateData = new NbtCompound();
            stateData.putString("uuid", turtleId.toString());
            stateData.putInt("lastChunkX", lastPosition.x);
            stateData.putInt("lastChunkZ", lastPosition.z);
            stateData.putInt("fuelLevel", fuelLevel);
            stateData.putBoolean("wakeOnWorldLoad", wakeOnWorldLoad);
            
            // CRITICAL: Save computer ID for UUID lifecycle management
            Integer computerId = computerTracker.getComputerForUUID(turtleId);
            if (computerId != null) {
                stateData.putInt("computerId", computerId);
            }
            
            turtleStates.add(stateData);
            LOGGER.debug("Serialized essential data for turtle {}: active={}, pos=({},{}), fuel={}, wake={}",
                        turtleId, isActive, lastPosition.x, lastPosition.z, fuelLevel, wakeOnWorldLoad);
        }

        nbt.put("turtleStates", turtleStates);
        
        // Radius overrides are now part of remoteManagementStates - no separate serialization needed
        
        LOGGER.info("Serialized {} turtle bootstrap records to NBT ({} tracked turtles)", 
                   turtleStates.size(), turtleChunks.size());
        return nbt;
    }

    /**
     * Bidirectional computer ID to UUID mapping tracker
     * Consolidates computerIdToUUIDs + uuidToComputerId into atomic operations
     */
    public static class ComputerUUIDTracker {
        private final Map<Integer, Set<UUID>> computerToUUIDs = new ConcurrentHashMap<>();
        private final Map<UUID, Integer> uuidToComputer = new ConcurrentHashMap<>();
        
        /**
         * Register UUID for computer ID atomically
         */
        public synchronized void register(int computerId, UUID uuid) {
            computerToUUIDs.computeIfAbsent(computerId, k -> ConcurrentHashMap.newKeySet()).add(uuid);
            uuidToComputer.put(uuid, computerId);
        }
        
        /**
         * Remove UUID atomically from both mappings
         */
        public synchronized void remove(UUID uuid) {
            Integer computerId = uuidToComputer.remove(uuid);
            if (computerId != null) {
                Set<UUID> computerUUIDs = computerToUUIDs.get(computerId);
                if (computerUUIDs != null) {
                    computerUUIDs.remove(uuid);
                    if (computerUUIDs.isEmpty()) {
                        computerToUUIDs.remove(computerId);
                    }
                }
            }
        }
        
        /**
         * Get all UUIDs for a computer ID
         */
        public Set<UUID> getUUIDsForComputer(int computerId) {
            return Set.copyOf(computerToUUIDs.getOrDefault(computerId, Set.of()));
        }
        
        /**
         * Get computer ID for a UUID
         */
        public Integer getComputerForUUID(UUID uuid) {
            return uuidToComputer.get(uuid);
        }
        
        /**
         * Get all computer IDs
         */
        public Set<Integer> getAllComputerIds() {
            return Set.copyOf(computerToUUIDs.keySet());
        }
        
        /**
         * Validate UUIDs for a computer - remove any that aren't in the given set
         */
        public synchronized Set<UUID> validateAndCleanup(int computerId, Set<UUID> currentlyEquippedUUIDs) {
            Set<UUID> storedUUIDs = computerToUUIDs.get(computerId);
            if (storedUUIDs == null) return Set.of();

            Set<UUID> toRemove = new HashSet<>(storedUUIDs);
            toRemove.removeAll(currentlyEquippedUUIDs);
            
            for (UUID orphanedUUID : toRemove) {
                remove(orphanedUUID);
            }
            
            return toRemove;
        }
        
        /**
         * Get statistics
         */
        public synchronized Map<String, Object> getStats() {
            Map<String, Object> stats = new HashMap<>();
            stats.put("totalComputers", computerToUUIDs.size());
            stats.put("totalUUIDs", uuidToComputer.size());
            stats.put("orphanedUUIDs", uuidToComputer.size() - computerToUUIDs.values().stream().mapToInt(Set::size).sum());
            return stats;
        }
        
        /**
         * Clear all mappings
         */
        public synchronized void clear() {
            computerToUUIDs.clear();
            uuidToComputer.clear();
        }
    }

    /**
     * Minimal data needed to bootstrap a turtle on world load
     * Moved from ChunkLoaderRegistry to eliminate redundancy
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
     * Unified state for remote management of offline turtles
     * Contains essential data needed when turtle is not loaded
     */
    public static class RemoteManagementState {
        public final ChunkPos lastKnownPosition;
        public final int lastKnownFuel;
        public final boolean wakeOnWorldLoad;
        public final Integer computerId; // null if not determined yet
        public final Double radiusOverride; // null if no override set

        public RemoteManagementState(ChunkPos lastKnownPosition, int lastKnownFuel, boolean wakeOnWorldLoad, Integer computerId, Double radiusOverride) {
            this.lastKnownPosition = lastKnownPosition;
            this.lastKnownFuel = lastKnownFuel;
            this.wakeOnWorldLoad = wakeOnWorldLoad;
            this.computerId = computerId;
            this.radiusOverride = radiusOverride;
        }
        
        // Convenience constructor for backward compatibility
        public RemoteManagementState(ChunkPos lastKnownPosition, int lastKnownFuel, boolean wakeOnWorldLoad, Integer computerId) {
            this(lastKnownPosition, lastKnownFuel, wakeOnWorldLoad, computerId, null);
        }

        /**
         * Create from SavedState (turtle's own NBT data)
         */
        public static RemoteManagementState fromSavedState(ChunkLoaderPeripheral.SavedState savedState, Integer computerId) {
            return new RemoteManagementState(
                savedState.lastChunkPos,
                savedState.fuelLevel,
                savedState.wakeOnWorldLoad,
                computerId,
                null // No radius override from saved state
            );
        }

        /**
         * Convert to SavedState format for bootstrap
         */
        public ChunkLoaderPeripheral.SavedState toSavedState() {
            return new ChunkLoaderPeripheral.SavedState(
                0.0, // radius - loaded from turtle's own NBT
                lastKnownPosition,
                0.0, // fuelDebt - loaded from turtle's own NBT  
                wakeOnWorldLoad,
                false, // randomTickEnabled - loaded from turtle's own NBT
                lastKnownFuel
            );
        }
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
    
    /**
     * A simple record to hold the results of bootstrap attempts.
     */
    public static class BootstrapResult {
        public final boolean success;
        public final String errorCode;
        public final String message;

        public BootstrapResult(boolean success, String errorCode, String message) {
            this.success = success;
            this.errorCode = errorCode;
            this.message = message;
        }
        
        public static BootstrapResult success() {
            return new BootstrapResult(true, "SUCCESS", "Turtle successfully bootstrapped");
        }
        
        public static BootstrapResult alreadyActive() {
            return new BootstrapResult(true, "ALREADY_ACTIVE", "Turtle is already active");
        }
        
        public static BootstrapResult noData() {
            return new BootstrapResult(false, "NO_DATA", "No cached data available for turtle");
        }
        
        public static BootstrapResult timeout() {
            return new BootstrapResult(false, "TIMEOUT", "Bootstrap timed out - turtle may still be loading");
        }
    }

    public synchronized DeserializationResult deserializeFromNbt(NbtCompound nbt) {
        chunkLoaders.clear();
        turtleChunks.clear();
        computerTracker.clear();
        // DON'T clear remoteManagementStates - merge with existing data to preserve any runtime state

        if (nbt.contains("turtleStates")) {
            NbtList turtleStatesNbt = nbt.getList("turtleStates", 10);
            for (int i = 0; i < turtleStatesNbt.size(); i++) {
                NbtCompound stateData = turtleStatesNbt.getCompound(i);
                try {
                    UUID turtleId = UUID.fromString(stateData.getString("uuid"));
                    ChunkPos lastChunkPos = null;
                    int fuelLevel = -1;
                    boolean wakeOnWorldLoad = false;
                    
                    // Handle both old format (with complex data) and new format (essential only)
                    if (stateData.contains("lastChunkX") && stateData.contains("lastChunkZ")) {
                        lastChunkPos = new ChunkPos(stateData.getInt("lastChunkX"), stateData.getInt("lastChunkZ"));
                    }
                    if (stateData.contains("fuelLevel")) {
                        fuelLevel = stateData.getInt("fuelLevel");
                    }
                    if (stateData.contains("wakeOnWorldLoad")) {
                        wakeOnWorldLoad = stateData.getBoolean("wakeOnWorldLoad");
                    }

                    // Create bootstrap state with essential data including wake preference
                    ChunkLoaderPeripheral.SavedState bootstrapState = new ChunkLoaderPeripheral.SavedState(
                        0.0, // radius - will be loaded from turtle's own NBT
                        lastChunkPos, 
                        0.0, // fuelDebt - will be loaded from turtle's own NBT
                        wakeOnWorldLoad, // CRITICAL: Preserve wake preference!
                        false, // randomTickEnabled - will be loaded from turtle's own NBT
                        fuelLevel
                    );

                    // Track turtle for bootstrap purposes
                    turtleChunks.put(turtleId, ConcurrentHashMap.newKeySet());
                    restoredTurtleStates.put(turtleId, bootstrapState);
                    
                    // CRITICAL: Update remote management state with loaded data
                    Integer computerIdForState = null;
                    if (stateData.contains("computerId")) {
                        computerIdForState = stateData.getInt("computerId");
                    }
                    updateRemoteManagementState(turtleId, lastChunkPos, fuelLevel >= 0 ? fuelLevel : 1, wakeOnWorldLoad, computerIdForState);
                    
                    // CRITICAL: Restore computer ID mapping if available
                    if (stateData.contains("computerId")) {
                        int computerIdFromNbt = stateData.getInt("computerId");
                        computerTracker.register(computerIdFromNbt, turtleId);
                        LOGGER.debug("Restored computer ID mapping: UUID {} -> Computer {}", turtleId, computerIdFromNbt);
                    }
                    
                    LOGGER.debug("Restored turtle bootstrap data {}: pos=({},{}), fuel={}, wake={}", 
                                turtleId, lastChunkPos != null ? lastChunkPos.x : "null", 
                                lastChunkPos != null ? lastChunkPos.z : "null", fuelLevel, wakeOnWorldLoad);
                } catch (Exception e) {
                    LOGGER.error("Failed to restore turtle bootstrap data from NBT.", e);
                }
            }
        }

        // Radius overrides are now part of remoteManagementStates - legacy handling for old saves
        if (nbt.contains("radiusOverrides")) {
            NbtCompound radiusOverridesNbt = nbt.getCompound("radiusOverrides");
            for (String uuidString : radiusOverridesNbt.getKeys()) {
                try {
                    UUID turtleId = UUID.fromString(uuidString);
                    double radius = radiusOverridesNbt.getDouble(uuidString);
                    // Apply to existing remote management state if it exists
                    RemoteManagementState current = remoteManagementStates.get(turtleId);
                    if (current != null) {
                        RemoteManagementState updated = new RemoteManagementState(
                            current.lastKnownPosition, current.lastKnownFuel, current.wakeOnWorldLoad, current.computerId, radius
                        );
                        remoteManagementStates.put(turtleId, updated);
                        LOGGER.debug("Migrated legacy radius override for turtle {}: {}", turtleId, radius);
                    }
                } catch (Exception e) {
                    LOGGER.error("Failed to migrate legacy radius override for UUID {}: {}", uuidString, e.getMessage());
                }
            }
            LOGGER.info("Migrated {} legacy radius overrides to unified state", radiusOverridesNbt.getKeys().size());
        }

        // Count how many turtles should wake on world load
        int totalCount = restoredTurtleStates.size();
        int toWakeCount = (int) restoredTurtleStates.values().stream()
            .filter(state -> state.wakeOnWorldLoad)
            .count();
        
        LOGGER.info("Deserialized {} turtle bootstrap records from NBT ({} to wake on world load)", 
                   totalCount, toWakeCount);
        LOGGER.info("Restored computer ID mappings for {} computers with {} total UUIDs", 
                   computerTracker.getAllComputerIds().size(), computerTracker.getStats().get("totalUUIDs"));
        return new DeserializationResult(totalCount, toWakeCount);
    }

    /**
     * Bootstrap a specific turtle on-demand for remote operations
     * Returns BootstrapResult with success status and error information
     */
    public synchronized BootstrapResult bootstrapTurtleOnDemand(UUID turtleId) {
        LOGGER.info("Attempting on-demand bootstrap for turtle {}", turtleId);
        
        // Check if turtle is already active
        if (ChunkLoaderRegistry.getPeripheral(turtleId) != null) {
            LOGGER.debug("Turtle {} is already active, no bootstrap needed", turtleId);
            return BootstrapResult.alreadyActive();
        }
        
        // Debug: Show what data we have for this turtle
        boolean inTurtleChunks = turtleChunks.containsKey(turtleId);
        boolean inRemoteState = remoteManagementStates.containsKey(turtleId);
        boolean inRestoredStates = restoredTurtleStates.containsKey(turtleId);
        LOGGER.info("Turtle {} tracking status: chunks={}, remote={}, restored={}", 
                   turtleId, inTurtleChunks, inRemoteState, inRestoredStates);
        
        // Check if we have cached state for this turtle
        ChunkLoaderPeripheral.SavedState cachedState = getCachedTurtleState(turtleId);
        if (cachedState == null) {
            LOGGER.info("No cached state available for turtle {}", turtleId);
        }
        
        if (cachedState == null) {
            LOGGER.warn("Cannot bootstrap turtle {} - no cached state available (chunks={}, remote={})", 
                       turtleId, inTurtleChunks, inRemoteState);
            return BootstrapResult.noData();
        }
        
        if (cachedState.lastChunkPos == null) {
            LOGGER.warn("Cannot bootstrap turtle {} - no position available. State: radius={}, fuel={}, wake={}", 
                       turtleId, cachedState.radius, cachedState.fuelLevel, cachedState.wakeOnWorldLoad);
            return BootstrapResult.noData();
        }
        
        // Note: We don't check fuel here - just load the turtle and let it handle its own fuel logic
        // The turtle will disable chunk loading itself if it runs out of fuel
        
        LOGGER.info("Bootstrapping turtle {} at chunk {} with {} fuel", 
                   turtleId, cachedState.lastChunkPos, cachedState.fuelLevel);
        
        // No need to create temporary bootstrap data - we have it in remoteManagementStates
        
        // Force-load the turtle's chunk to wake it up
        world.setChunkForced(cachedState.lastChunkPos.x, cachedState.lastChunkPos.z, true);
        LOGGER.info("Force-loaded chunk {} to bootstrap turtle {}", cachedState.lastChunkPos, turtleId);
        
        // Wait for turtle to initialize (give it a few server ticks)
        final int MAX_WAIT_TICKS = 20; // Increased from 10 to 20 for 1000ms total
        final int TICK_DELAY_MS = 50; // 20 ticks per second
        
        for (int i = 0; i < MAX_WAIT_TICKS; i++) {
            try {
                Thread.sleep(TICK_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            
            // Check if turtle peripheral is now available
            if (ChunkLoaderRegistry.getPeripheral(turtleId) != null) {
                LOGGER.info("Successfully bootstrapped turtle {} after {}ms", turtleId, i * TICK_DELAY_MS);
                return BootstrapResult.success();
            }
        }
        
        LOGGER.info("Bootstrap timeout for turtle {} - peripheral not available after {}ms (turtle may still be loading)", 
                   turtleId, MAX_WAIT_TICKS * TICK_DELAY_MS);
        return BootstrapResult.timeout();
    }

    /**
     * Wake turtles that are marked for `wakeOnWorldLoad`.
     * This is called AFTER the world has loaded and registration is complete.
     * It only wakes turtles that have explicitly opted-in and have fuel.
     */
    public void wakeTurtlesOnLoad() {
        int restoredCount = remoteManagementStates.size();
        if (restoredCount == 0) {
            return;
        }

        long turtlesToWake = remoteManagementStates.values().stream()
            .filter(state -> state.wakeOnWorldLoad)
            .count();

        if (turtlesToWake == 0) {
            LOGGER.info("No restored turtles marked to wake on load.");
            return;
        }

        LOGGER.info("Attempting to wake {}/{} restored turtles that have opted-in...", turtlesToWake, restoredCount);

        for (Map.Entry<UUID, RemoteManagementState> entry : remoteManagementStates.entrySet()) {
            UUID turtleId = entry.getKey();
            RemoteManagementState state = entry.getValue();
            
            // Convert to SavedState for compatibility
            ChunkLoaderPeripheral.SavedState savedState = state.toSavedState();

            // Wake the turtle only if it has opted-in
            if (savedState.wakeOnWorldLoad) {
                // AND check if it has fuel
                if (savedState.fuelLevel == 0) {
                    LOGGER.warn("Skipping wake for opt-in turtle {} because it has no fuel.", turtleId);
                    continue;
                }

                // Bootstrap data already available in remoteManagementStates
                if (savedState.lastChunkPos != null) {
                    LOGGER.info("Turtle {} available for bootstrap wakeup", turtleId);
                } else {
                    LOGGER.warn("Cannot bootstrap turtle {} - no last known position", turtleId);
                }
            }
        }
    }

    /**
     * Register a UUID for a specific computer ID
     */
    public void registerUUIDForComputer(int computerId, UUID turtleId) {
        computerTracker.register(computerId, turtleId);
        LOGGER.debug("Registered UUID {} for computer ID {}", turtleId, computerId);
    }

    /**
     * Get all UUIDs associated with a computer ID
     */
    public Set<UUID> getUUIDsForComputer(int computerId) {
        return computerTracker.getUUIDsForComputer(computerId);
    }

    /**
     * Get computer ID for a UUID
     */
    public Integer getComputerIdForUUID(UUID turtleId) {
        return computerTracker.getComputerForUUID(turtleId);
    }

    /**
     * Validate UUIDs for a computer - remove any that aren't currently equipped
     * Only call this when the turtle is confirmed loaded and active
     */
    public void validateUUIDsForComputer(int computerId, Set<UUID> currentlyEquippedUUIDs) {
        Set<UUID> orphanedUUIDs = computerTracker.validateAndCleanup(computerId, currentlyEquippedUUIDs);
        
        for (UUID orphanedUUID : orphanedUUIDs) {
            LOGGER.info("Removing orphaned UUID {} from computer {} (no longer equipped)", orphanedUUID, computerId);
            permanentlyRemoveTurtle(orphanedUUID);
        }
    }

    /**
     * Get all computer IDs that have registered UUIDs
     */
    public Set<Integer> getAllComputerIds() {
        return computerTracker.getAllComputerIds();
    }

    /**
     * Get statistics about computer ID tracking
     */
    public Map<String, Object> getComputerIdStats() {
        return computerTracker.getStats();
    }

}
