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
    // Cache of turtle states for persistence (survives peripheral cleanup)
    private final Map<UUID, ChunkLoaderPeripheral.SavedState> turtleStateCache = new ConcurrentHashMap<>();
    // Persistent position tracking (source of truth for turtle positions)
    private final Map<UUID, ChunkPos> persistentPositions = new ConcurrentHashMap<>();
    // Persistent fuel tracking (source of truth for turtle fuel levels)
    private final Map<UUID, Integer> persistentFuelLevels = new ConcurrentHashMap<>();
    // Radius overrides for turtles (applied when peripheral is created, cleared after use)
    private final Map<UUID, Double> radiusOverrides = new ConcurrentHashMap<>();

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
            // DON'T remove from lastTouch - keep turtle tracked for persistence
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
     * Update persistent position tracking for a turtle (source of truth)
     */
    public void updateTurtlePosition(UUID turtleId, ChunkPos position) {
        persistentPositions.put(turtleId, position);
        touch(turtleId); // Also update last activity
        LOGGER.debug("Updated persistent position for turtle {}: {}", turtleId, position);
    }
    
    /**
     * Update persistent fuel tracking for a turtle (source of truth)
     */
    public void updateTurtleFuel(UUID turtleId, int fuelLevel) {
        persistentFuelLevels.put(turtleId, fuelLevel);
        LOGGER.debug("Updated persistent fuel for turtle {}: {}", turtleId, fuelLevel);
    }
    
    /**
     * Get persistent position for a turtle (always available)
     */
    public ChunkPos getPersistentPosition(UUID turtleId) {
        return persistentPositions.get(turtleId);
    }
    
    /**
     * Get persistent fuel level for a turtle (always available)
     */
    public Integer getPersistentFuelLevel(UUID turtleId) {
        return persistentFuelLevels.get(turtleId);
    }
    
    /**
     * Set a radius override for a turtle (persisted in world NBT)
     * This will be applied when the turtle's peripheral is created and then cleared
     */
    public void setRadiusOverride(UUID turtleId, double radius) {
        radiusOverrides.put(turtleId, radius);
        LOGGER.info("Set radius override for turtle {}: {}", turtleId, radius);
        // Save to world immediately to persist across chunk loads
        markDirty();
    }
    
    /**
     * Get and clear radius override for a turtle
     * Returns null if no override exists
     */
    public Double getAndClearRadiusOverride(UUID turtleId) {
        Double override = radiusOverrides.remove(turtleId);
        if (override != null) {
            LOGGER.info("Retrieved and cleared radius override for turtle {}: {}", turtleId, override);
            markDirty();
        }
        return override;
    }
    
    /**
     * Check if a turtle has a radius override pending
     */
    public boolean hasRadiusOverride(UUID turtleId) {
        return radiusOverrides.containsKey(turtleId);
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
            turtleStateCache.put(turtleId, state);
            LOGGER.debug("Updated turtle state cache for turtle {}: radius={}, fuel={}, wake={}",
                        turtleId, state.radius, state.fuelLevel, state.wakeOnWorldLoad);
        } else {
            turtleStateCache.remove(turtleId);
            LOGGER.debug("Removed turtle {} from state cache", turtleId);
        }
    }

    /**
     * Get cached turtle state (may be dormant turtle)
     */
    public synchronized ChunkLoaderPeripheral.SavedState getCachedTurtleState(UUID turtleId) {
        return turtleStateCache.get(turtleId);
    }

    /**
     * Remove turtle from state cache (called when turtle is completely removed)
     */
    public synchronized void removeTurtleFromCache(UUID turtleId) {
        turtleStateCache.remove(turtleId);
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
     * Cleanup inactive chunk loaders (called periodically)
     * IMPORTANT: World operations are done OUTSIDE synchronized blocks to prevent deadlocks
     * NOTE: This method now preserves turtle state instead of removing inactive turtles
     */
    public void cleanup(long maxInactiveTime) {
        long currentTime = System.currentTimeMillis();

        // PHASE 1: Identify inactive turtles under lock (fast, non-blocking)
        Set<UUID> inactiveTurtles = ConcurrentHashMap.newKeySet();
        synchronized (this) {
            for (Map.Entry<UUID, Long> entry : lastTouch.entrySet()) {
                UUID turtleId = entry.getKey();
                long lastActivity = entry.getValue();

                if (currentTime - lastActivity > maxInactiveTime) {
                    inactiveTurtles.add(turtleId);
                }
            }
            // DON'T remove from lastTouch - keep turtles tracked for persistence
        }

        // PHASE 2: Clean up inactive turtles WITHOUT holding locks (can block safely)
        // Preserve turtle state in cache and only remove active chunk loading
        for (UUID turtleId : inactiveTurtles) {
            LOGGER.info("Deactivating inactive turtle chunk loader: {} (preserving state)", turtleId);
            
            // Try to preserve state from active peripheral before cleanup
            ChunkLoaderPeripheral peripheral = ChunkLoaderRegistry.getPeripheral(turtleId);
            if (peripheral != null) {
                ChunkLoaderPeripheral.SavedState state = peripheral.getSavedState();
                if (state != null) {
                    updateTurtleStateCache(turtleId, state);
                    LOGGER.debug("Preserved state for inactive turtle {} in cache", turtleId);
                }
            }
            
            // Only remove chunk loading, not turtle tracking
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
            // DON'T clear lastTouch, turtleStateCache, persistentPositions, or persistentFuelLevels - preserve turtle data
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
            lastTouch.remove(turtleId);
            turtleStateCache.remove(turtleId);
            restoredTurtleStates.remove(turtleId);
            persistentPositions.remove(turtleId);
            persistentFuelLevels.remove(turtleId);
            radiusOverrides.remove(turtleId);
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
            
            // PRIMARY SOURCE: Use persistent tracking data as source of truth
            lastPosition = persistentPositions.get(turtleId);
            Integer persistentFuel = persistentFuelLevels.get(turtleId);
            if (persistentFuel != null) {
                fuelLevel = persistentFuel;
            }
            
            // Get wake preference from active peripheral or cache
            ChunkLoaderPeripheral chunkLoader = ChunkLoaderRegistry.getPeripheral(turtleId);
            if (chunkLoader != null) {
                ChunkLoaderPeripheral.SavedState state = chunkLoader.getSavedState();
                if (state != null) {
                    wakeOnWorldLoad = state.wakeOnWorldLoad;
                    isActive = true;
                    // Update cache with latest state from active peripheral
                    turtleStateCache.put(turtleId, state);
                    
                    // Update persistent data from active peripheral if available
                    if (state.lastChunkPos != null) {
                        lastPosition = state.lastChunkPos;
                        persistentPositions.put(turtleId, state.lastChunkPos);
                    }
                    fuelLevel = state.fuelLevel;
                    persistentFuelLevels.put(turtleId, state.fuelLevel);
                }
            } else {
                // Fallback to cached state for wake preference only
                ChunkLoaderPeripheral.SavedState cachedState = turtleStateCache.get(turtleId);
                if (cachedState != null) {
                    wakeOnWorldLoad = cachedState.wakeOnWorldLoad;
                    LOGGER.debug("Using cached wake preference for dormant turtle {}", turtleId);
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
            
            // Save essential bootstrap data including wakeOnWorldLoad status
            NbtCompound stateData = new NbtCompound();
            stateData.putString("uuid", turtleId.toString());
            stateData.putInt("lastChunkX", lastPosition.x);
            stateData.putInt("lastChunkZ", lastPosition.z);
            stateData.putInt("fuelLevel", fuelLevel);
            stateData.putBoolean("wakeOnWorldLoad", wakeOnWorldLoad);
            turtleStates.add(stateData);
            LOGGER.debug("Serialized essential data for turtle {}: active={}, pos=({},{}), fuel={}, wake={}",
                        turtleId, isActive, lastPosition.x, lastPosition.z, fuelLevel, wakeOnWorldLoad);
        }

        nbt.put("turtleStates", turtleStates);
        
        // Serialize radius overrides
        if (!radiusOverrides.isEmpty()) {
            NbtCompound radiusOverridesNbt = new NbtCompound();
            for (Map.Entry<UUID, Double> entry : radiusOverrides.entrySet()) {
                radiusOverridesNbt.putDouble(entry.getKey().toString(), entry.getValue());
            }
            nbt.put("radiusOverrides", radiusOverridesNbt);
            LOGGER.info("Serialized {} radius overrides to NBT", radiusOverrides.size());
        }
        
        LOGGER.info("Serialized {} turtle bootstrap records to NBT ({} tracked turtles)", 
                   turtleStates.size(), turtleChunks.size());
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
        // DON'T clear turtleStateCache - merge with existing data to preserve any runtime state

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
                    lastTouch.put(turtleId, System.currentTimeMillis());
                    restoredTurtleStates.put(turtleId, bootstrapState);
                    turtleStateCache.put(turtleId, bootstrapState);
                    
                    // CRITICAL: Update persistent tracking with loaded data
                    if (lastChunkPos != null) {
                        persistentPositions.put(turtleId, lastChunkPos);
                    }
                    if (fuelLevel >= 0) {
                        persistentFuelLevels.put(turtleId, fuelLevel);
                    }
                    
                    LOGGER.debug("Restored turtle bootstrap data {}: pos=({},{}), fuel={}, wake={}", 
                                turtleId, lastChunkPos != null ? lastChunkPos.x : "null", 
                                lastChunkPos != null ? lastChunkPos.z : "null", fuelLevel, wakeOnWorldLoad);
                } catch (Exception e) {
                    LOGGER.error("Failed to restore turtle bootstrap data from NBT.", e);
                }
            }
        }

        // Deserialize radius overrides
        if (nbt.contains("radiusOverrides")) {
            NbtCompound radiusOverridesNbt = nbt.getCompound("radiusOverrides");
            for (String uuidString : radiusOverridesNbt.getKeys()) {
                try {
                    UUID turtleId = UUID.fromString(uuidString);
                    double radius = radiusOverridesNbt.getDouble(uuidString);
                    radiusOverrides.put(turtleId, radius);
                    LOGGER.debug("Restored radius override for turtle {}: {}", turtleId, radius);
                } catch (Exception e) {
                    LOGGER.error("Failed to restore radius override for UUID {}: {}", uuidString, e.getMessage());
                }
            }
            LOGGER.info("Deserialized {} radius overrides from NBT", radiusOverrides.size());
        }

        // Count how many turtles should wake on world load
        int totalCount = restoredTurtleStates.size();
        int toWakeCount = (int) restoredTurtleStates.values().stream()
            .filter(state -> state.wakeOnWorldLoad)
            .count();
        
        LOGGER.info("Deserialized {} turtle bootstrap records from NBT ({} to wake on world load)", 
                   totalCount, toWakeCount);
        return new DeserializationResult(totalCount, toWakeCount);
    }

    /**
     * Bootstrap a specific turtle on-demand for remote operations
     * Returns true if turtle was successfully bootstrapped and is now active
     */
    public synchronized boolean bootstrapTurtleOnDemand(UUID turtleId) {
        LOGGER.info("Attempting on-demand bootstrap for turtle {}", turtleId);
        
        // Check if turtle is already active
        if (ChunkLoaderRegistry.getPeripheral(turtleId) != null) {
            LOGGER.debug("Turtle {} is already active, no bootstrap needed", turtleId);
            return true;
        }
        
        // Debug: Show what data we have for this turtle
        boolean inTurtleChunks = turtleChunks.containsKey(turtleId);
        boolean inStateCache = turtleStateCache.containsKey(turtleId);
        boolean inRestoredStates = restoredTurtleStates.containsKey(turtleId);
        LOGGER.info("Turtle {} tracking status: chunks={}, cache={}, restored={}", 
                   turtleId, inTurtleChunks, inStateCache, inRestoredStates);
        
        // Check if we have cached state for this turtle
        ChunkLoaderPeripheral.SavedState cachedState = turtleStateCache.get(turtleId);
        if (cachedState == null) {
            // Check restored states as fallback
            cachedState = restoredTurtleStates.get(turtleId);
            if (cachedState != null) {
                LOGGER.info("Using restored state for turtle {}", turtleId);
            }
        }
        
        if (cachedState == null) {
            LOGGER.warn("Cannot bootstrap turtle {} - no cached state available (chunks={}, cache={}, restored={})", 
                       turtleId, inTurtleChunks, inStateCache, inRestoredStates);
            return false;
        }
        
        if (cachedState.lastChunkPos == null) {
            LOGGER.warn("Cannot bootstrap turtle {} - no position available. State: radius={}, fuel={}, wake={}", 
                       turtleId, cachedState.radius, cachedState.fuelLevel, cachedState.wakeOnWorldLoad);
            return false;
        }
        
        // Note: We don't check fuel here - just load the turtle and let it handle its own fuel logic
        // The turtle will disable chunk loading itself if it runs out of fuel
        
        LOGGER.info("Bootstrapping turtle {} at chunk {} with {} fuel", 
                   turtleId, cachedState.lastChunkPos, cachedState.fuelLevel);
        
        // Create temporary bootstrap data
        ChunkLoaderRegistry.updateBootstrapData(
            turtleId, 
            world.getRegistryKey(), 
            cachedState.lastChunkPos, 
            cachedState.fuelLevel, 
            true  // Temporarily set wake=true for bootstrap
        );
        
        // Force-load the turtle's chunk to wake it up
        world.setChunkForced(cachedState.lastChunkPos.x, cachedState.lastChunkPos.z, true);
        LOGGER.info("Force-loaded chunk {} to bootstrap turtle {}", cachedState.lastChunkPos, turtleId);
        
        // Wait for turtle to initialize (give it a few server ticks)
        final int MAX_WAIT_TICKS = 10;
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
                return true;
            }
        }
        
        LOGGER.warn("Bootstrap timeout for turtle {} - peripheral not available after {}ms", 
                   turtleId, MAX_WAIT_TICKS * TICK_DELAY_MS);
        return false;
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
