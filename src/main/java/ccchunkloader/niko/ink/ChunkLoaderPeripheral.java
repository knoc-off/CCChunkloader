package ccchunkloader.niko.ink;

import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.lua.LuaFunction;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.turtle.ITurtleAccess;
import dan200.computercraft.api.turtle.TurtleSide;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class ChunkLoaderPeripheral implements IPeripheral {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkLoaderPeripheral.class);

    // NBT Keys
    private static final String RADIUS_KEY = "Radius";
    private static final String FUEL_DEBT_KEY = "FuelDebt";
    private static final String WAKE_ON_WORLD_LOAD_KEY = "WakeOnWorldLoad";
    private static final String RANDOM_TICK_ENABLED_KEY = "RandomTickEnabled";
    private static final String LAST_CHUNK_POS_KEY = "LastChunkPos";
    
    // Fuel and calculation constants
    private static final double FUEL_DEBT_THRESHOLD = 1.0;
    private static final double FUEL_DEBT_EPSILON = 0.001;
    private static final int CHUNK_SEARCH_PADDING = 1;

    private final ITurtleAccess turtle;
    private final TurtleSide side;
    private final UUID turtleId;
    private double radius = 0.0;
    private ChunkPos lastChunkPos = null;
    private double fuelDebt = 0.0; // Accumulated fractional fuel debt
    private boolean wakeOnWorldLoad = false; // Whether to auto-activate on world load
    private boolean randomTickEnabled = false; // Whether random ticking is enabled for this turtle's chunks
    private boolean computerIdRegistered = false; // Whether UUID has been registered with computer ID
    private boolean isDirty = false; // Whether state has unsaved changes

    public ChunkLoaderPeripheral(ITurtleAccess turtle, TurtleSide side) {
        this.turtle = turtle;
        this.side = side;
        this.turtleId = ChunkLoaderUpgrade.getTurtleUUID(turtle, side);

        // Load state using new architecture
        loadStateFromNewArchitecture();
        
        // Register this peripheral in the global registry for remote management
        ChunkLoaderRegistry.register(turtleId, this);
        
        LOGGER.info("ChunkLoaderPeripheral created for turtle {} using new bug-free architecture", turtleId);

        // Register this turtle in the ChunkManager immediately, even with radius=0
        if (turtle.getLevel() instanceof ServerWorld serverWorld) {
            this.lastChunkPos = new ChunkPos(turtle.getPosition());

            ChunkManager manager = ChunkManager.get(serverWorld);
            
            if (!manager.isTurtleTracked(turtleId)) {
                manager.touch(turtleId);
            }
            
            // Update persistent tracking
            manager.updateTurtlePosition(turtleId, this.lastChunkPos);
            manager.updateTurtleFuel(turtleId, turtle.getFuelLevel());

            // Fire turtle loaded event for new architecture coordination
            TurtleStateManager stateManager = CCChunkloader.getStateManager();
            TurtleStateEvents eventSystem = CCChunkloader.getEventSystem();
            TurtleStateManager.TurtleState state = stateManager.getState(turtleId);
            eventSystem.fireEvent(new TurtleStateEvents.TurtleLoadedEvent(turtleId, this, state));

            // Clean Slate: Single log message, single state save
            LOGGER.info("Successfully created ChunkLoaderPeripheral with UUID: {} (restored radius: {})", turtleId, radius);

            // Save state once after all updates
            markDirty();
            forceSaveState();

            // Execute chunk loading and override cleanup on server thread
            serverWorld.getServer().execute(() -> {
                if (radius > 0.0) {
                    resumeChunkLoading(serverWorld);
                }
                confirmAndClearRadiusOverride(serverWorld);
            });
        } else {
            LOGGER.error("ERROR: World is not ServerWorld! Type: {}", turtle.getLevel() != null ? turtle.getLevel().getClass().getName() : "NULL");
        }
    }

    @Override
    @NotNull
    public String getType() {
        return "chunkloader";
    }

    @Override
    public boolean equals(IPeripheral other) {
        return this == other;
    }

    /**
     * Set the chunk loading radius for this turtle.
     * @param newRadius The radius in chunks (0.0 to Config.MAX_RADIUS)
     * @return The actual radius that was set
     * @throws LuaException If radius is outside valid range
     */
    @LuaFunction
    public final double setRadius(double newRadius) throws LuaException {
        if (newRadius < 0.0 || newRadius > Config.MAX_RADIUS) {
            throw new LuaException("Radius must be between 0.0 and " + Config.MAX_RADIUS);
        }

        if (turtle.getLevel() instanceof ServerWorld serverWorld) {
            double oldRadius = this.radius;
            this.radius = newRadius;

            // Queue chunk operations asynchronously to prevent hangs
            serverWorld.getServer().execute(() -> {
                setRadiusAsync(serverWorld, oldRadius, newRadius);
            });

            LOGGER.info("🎯 COMMAND EFFECT: Turtle {} radius change {} -> {} queued for execution", turtleId, oldRadius, newRadius);
        }

        return newRadius;
    }

    /**
     * Async implementation of radius setting that can block safely
     */
    private void setRadiusAsync(ServerWorld serverWorld, double oldRadius, double newRadius) {
        try {
            ChunkManager manager = ChunkManager.get(serverWorld);

            if (oldRadius > 0.0) {
                int removedCount = manager.getLoadedChunkCount(turtleId);
                manager.removeAllChunks(turtleId);
                LOGGER.info("🔄 CHUNK UNLOAD: Turtle {} removed {} force-loaded chunks (radius {} -> {})", 
                           turtleId, removedCount, oldRadius, newRadius);
            }

            if (newRadius > 0.0) {
                ChunkPos currentChunk = new ChunkPos(turtle.getPosition());
                Set<ChunkPos> chunksToLoad = computeChunks(currentChunk, newRadius);
                manager.addChunksFromSet(turtleId, chunksToLoad);
                lastChunkPos = currentChunk;
                LOGGER.info("🔄 CHUNK LOAD: Turtle {} force-loaded {} chunks at radius {} (center: {})",
                           turtleId, chunksToLoad.size(), newRadius, currentChunk);
            } else {
                lastChunkPos = null;
                LOGGER.info("🔄 CHUNK CLEAR: Turtle {} cleared all force-loaded chunks (radius set to 0)", turtleId);
            }

            // Force save state after critical radius change
            markDirty();
            forceSaveState();
        } catch (Exception e) {
            LOGGER.error("Failed to set radius asynchronously for turtle {}: {}", turtleId, e.getMessage());
        }
    }

    /**
     * Get the current chunk loading radius.
     * @return Current radius in chunks
     */
    @LuaFunction
    public final double getRadius() {
        return this.radius;
    }

    /**
     * Calculate the fuel consumption rate per tick based on current settings.
     * @return Fuel consumed per server tick
     */
    @LuaFunction
    public final double getFuelRate() {
        return calculateFuelCost();
    }


    // Helper methods (internal use)
    public int getLoadedChunkCount() {
        if (turtle.getLevel() instanceof ServerWorld serverWorld) {
            ChunkManager manager = ChunkManager.get(serverWorld);
            return manager.getLoadedChunkCount(turtleId);
        }
        return 0;
    }

    public boolean isActive() {
        return radius > 0.0;
    }

    /**
     * Set whether this turtle should automatically resume chunk loading when the world loads.
     * @param wake True to auto-resume, false to stay dormant
     */
    @LuaFunction
    public final void setWakeOnWorldLoad(boolean wake) {
        this.wakeOnWorldLoad = wake;
        markDirty();
        forceSaveState();

        // Touch the chunk manager to ensure this state change is persisted
        if (turtle.getLevel() instanceof ServerWorld serverWorld) {
            ChunkManager.get(serverWorld).touch(turtleId);
        }

        LOGGER.debug("Turtle {} wake on world load set to {}", turtleId, wake);
    }

    @LuaFunction
    public final boolean getWakeOnWorldLoad() {
        return this.wakeOnWorldLoad;
    }

    /**
     * Enable or disable random ticking for loaded chunks.
     * @param enabled True to enable random ticks (costs more fuel)
     * @throws LuaException If radius exceeds MAX_RANDOM_TICK_RADIUS when enabling
     */
    @LuaFunction
    public final void setRandomTick(boolean enabled) throws LuaException {
        if (enabled && radius > Config.MAX_RANDOM_TICK_RADIUS) {
            throw new LuaException("Cannot enable random ticks - radius " + radius + " exceeds maximum " + Config.MAX_RANDOM_TICK_RADIUS);
        }
        setRandomTickEnabled(enabled);
    }

    @LuaFunction
    public final boolean getRandomTick() {
        return isRandomTickEnabled();
    }

    public double calculateFuelCost() {
        if (radius <= 0.0) return 0.0;

        ChunkPos centerChunk = new ChunkPos(turtle.getPosition());
        Set<ChunkPos> chunks = computeChunks(centerChunk, radius);
        double totalCost = 0.0;

        for (ChunkPos chunkPos : chunks) {
            int x = chunkPos.x - centerChunk.x;
            int z = chunkPos.z - centerChunk.z;
            double distance = Math.sqrt(x * x + z * z);
            double chunkCost = Config.BASE_FUEL_COST_PER_CHUNK * Math.pow(Config.DISTANCE_MULTIPLIER, distance);
            totalCost += chunkCost;
        }

        if (randomTickEnabled) {
            totalCost *= Config.RANDOM_TICK_FUEL_MULTIPLIER;
        }

        return totalCost;
    }

    private Set<ChunkPos> computeChunks(ChunkPos centerChunk, double radius) {
        Set<ChunkPos> chunks = new HashSet<>();
        if (radius <= 0.0) return chunks;

        int searchRadius = (int) Math.ceil(radius) + 1;
        for (int x = -searchRadius; x <= searchRadius; x++) {
            for (int z = -searchRadius; z <= searchRadius; z++) {
                double distance = Math.sqrt(x * x + z * z);
                if (distance < radius) {
                    chunks.add(new ChunkPos(centerChunk.x + x, centerChunk.z + z));
                }
            }
        }
        return chunks;
    }

    /**
     * Cleanup chunks when radius is 0 to ensure no chunks remain loaded
     */
    private void cleanupInactiveChunks(ChunkManager manager) {
        if (manager.hasLoadedChunks(turtleId)) {
            manager.removeAllChunks(turtleId);
        }
    }

    /**
     * Update persistent state tracking and detect movement
     * @param manager ChunkManager for this world
     * @param currentChunk Current turtle chunk position
     * @return true if turtle moved, false otherwise
     */
    private boolean updatePersistentState(ChunkManager manager, ChunkPos currentChunk) {
        boolean moved = this.lastChunkPos == null || !this.lastChunkPos.equals(currentChunk);
        
        if (moved) {
            this.lastChunkPos = currentChunk;
        }
        
        // CRITICAL: Always update persistent tracking data as source of truth
        manager.updateTurtlePosition(turtleId, currentChunk);
        manager.updateTurtleFuel(turtleId, turtle.getFuelLevel());
        
        return moved;
    }

    /**
     * Ensure computer ID is registered when ServerComputer becomes available
     * @param manager ChunkManager for this world  
     * @param serverWorld The server world for validation
     */
    private void ensureComputerRegistration(ChunkManager manager, ServerWorld serverWorld) {
        if (!computerIdRegistered) {
            Integer computerId = getTurtleComputerId();
            if (computerId != null) {
                manager.registerUUIDForComputer(computerId, turtleId);
                LOGGER.info("Registered turtle {} (UUID: {}) with computer ID {} [DEFERRED]", 
                           turtle.getPosition(), turtleId, computerId);
                
                // Validate UUIDs for this computer to clean up orphaned UUIDs
                validateComputerUUIDs(manager, computerId, serverWorld);
                computerIdRegistered = true;
            }
        }
    }

    /**
     * Process chunk loading when radius > 0
     * @param manager ChunkManager for this world
     * @param currentChunk Current turtle chunk position
     * @param moved Whether turtle moved this tick
     * @return true if state changed, false otherwise
     */
    private boolean processChunkLoading(ChunkManager manager, ChunkPos currentChunk, boolean moved) {
        // Update chunks if the turtle moved or if it should have chunks loaded but doesn't.
        if (moved || !manager.hasLoadedChunks(turtleId)) {
            double fuelCostPerTick = calculateFuelCost();
            if (turtle.getFuelLevel() > 0 && fuelCostPerTick > 0) {
                Set<ChunkPos> chunksToLoad = computeChunks(currentChunk, radius);
                manager.addChunksFromSet(turtleId, chunksToLoad);
            } else {
                this.radius = 0.0;
                this.fuelDebt = 0.0;
                manager.removeAllChunks(turtleId);
                LOGGER.debug("Turtle {} cannot afford chunk loading, disabling.", turtleId);
                // Critical state change - force save immediately
                markDirty();
                forceSaveState();
                return true; // State changed
            }
        }
        return false; // No state change
    }

    /**
     * Process fuel consumption and debt accumulation
     * @param manager ChunkManager for this world
     * @return true if state changed, false otherwise
     */
    private boolean processFuelConsumption(ChunkManager manager) {
        double oldFuelDebt = fuelDebt;
        fuelDebt += calculateFuelCost();
        
        if (fuelDebt >= FUEL_DEBT_THRESHOLD) {
            int fuelToConsume = (int) Math.floor(fuelDebt);
            if (turtle.getFuelLevel() >= fuelToConsume && turtle.consumeFuel(fuelToConsume)) {
                fuelDebt -= fuelToConsume;
                return true; // State changed
            } else {
                this.radius = 0.0;
                this.fuelDebt = 0.0;
                manager.removeAllChunks(turtleId);
                LOGGER.debug("Turtle {} ran out of fuel, disabling chunk loading.", turtleId);
                // Critical state change - force save immediately
                markDirty();
                forceSaveState();
                return true; // State changed
            }
        } else if (Math.abs(fuelDebt - oldFuelDebt) > FUEL_DEBT_EPSILON) {
            return true; // State changed
        }
        
        return false; // No state change
    }

    /**
     * Mark state as dirty (has unsaved changes)
     */
    private void markDirty() {
        this.isDirty = true;
    }

    /**
     * Save state to NBT if any changes occurred (lazy persistence)
     * Only saves on critical state changes, not routine position/fuel updates
     */
    private void saveStateIfChanged(boolean stateChanged) {
        if (stateChanged) {
            markDirty();
        }
    }

    /**
     * Force save state to NBT immediately
     */
    private void forceSaveState() {
        if (isDirty) {
            saveStateToUpgradeNBT();
            isDirty = false;
        }
    }

    public void updateChunkLoading() {
        if (!(turtle.getLevel() instanceof ServerWorld serverWorld)) return;

        ChunkManager manager = ChunkManager.get(serverWorld);
        
        ChunkPos currentChunk = new ChunkPos(turtle.getPosition());

        boolean moved = updatePersistentState(manager, currentChunk);
        ensureComputerRegistration(manager, serverWorld);

        boolean stateChanged = moved;
        if (radius > 0.0) {
            stateChanged |= processChunkLoading(manager, currentChunk, moved);
            stateChanged |= processFuelConsumption(manager);
        } else {
            cleanupInactiveChunks(manager);
        }

        saveStateIfChanged(stateChanged);
    }


    public void cleanup() {
        // Fire turtle unloaded event for new architecture coordination
        if (turtle.getLevel() instanceof ServerWorld) {
            TurtleStateManager stateManager = CCChunkloader.getStateManager();
            TurtleStateEvents eventSystem = CCChunkloader.getEventSystem();
            TurtleStateManager.TurtleState state = stateManager.getState(turtleId);
            eventSystem.fireEvent(new TurtleStateEvents.TurtleUnloadedEvent(turtleId, state, "cleanup"));
        }
        
        // Force save final state to upgrade NBT and cache before cleanup
        markDirty();
        forceSaveState();

        // Unregister from active peripherals
        ChunkLoaderRegistry.unregister(turtleId);

        // Remove any chunks this peripheral was loading
        // BUT keep turtle state in cache for persistence
        if (turtle.getLevel() instanceof ServerWorld serverWorld) {
            ChunkManager manager = ChunkManager.get(serverWorld);
            manager.removeAllChunks(turtleId);
            // Don't remove from cache here - turtle might come back later
            LOGGER.debug("Cleaned up turtle {} but preserved state in cache", turtleId);
        }
    }

    /**
     * Get the unique UUID of this chunk loader
     */
    public UUID getTurtleId() {
        return turtleId;
    }

    /**
     * Get the unique ID of this chunk loader as string (for Lua)
     */
    @LuaFunction
    public final String getTurtleIdString() {
        if (turtleId == null) {
            LOGGER.error("ERROR: turtleId is NULL!");
            return "NULL_TURTLE_ID";
        }

        return turtleId.toString();
    }


    /**
     * Check if this turtle has a UUID assigned (for Lua)
     */
    @LuaFunction
    public final boolean hasUUID() {
        return turtleId != null;
    }


    /**
     * Get the turtle's level/world (for internal use)
     */
    public net.minecraft.world.World getTurtleLevel() {
        return turtle.getLevel();
    }
    
    /**
     * Get the turtle's position (for internal use)
     */
    public net.minecraft.util.math.BlockPos getTurtlePosition() {
        return turtle.getPosition();
    }

    /**
     * Get maximum radius for this turtle
     */
    public double getMaxRadius() {
        return Config.MAX_RADIUS;
    }

    /**
     * Get current fuel level
     */
    public int getFuelLevel() {
        return turtle.getFuelLevel();
    }

    /**
     * Allow remote control of this turtle's chunk loading radius
     * Used by ChunkloaderManagerPeripheral for remote management
     */
    public boolean setRadiusRemote(double newRadius) {
        try {
            // Always set radius override for persistence across dormancy cycles
            if (turtle.getLevel() instanceof ServerWorld serverWorld) {
                ChunkManager manager = ChunkManager.get(serverWorld);
                manager.setRadiusOverride(turtleId, newRadius);
                LOGGER.debug("Set radius override {} for turtle {} during remote control", newRadius, turtleId);
            }
            
            // Then apply immediately to active peripheral
            setRadius(newRadius);
            return true;
        } catch (Exception e) {
            LOGGER.warn("Remote radius change failed for turtle {}: {}", turtleId, e.getMessage());
            return false;
        }
    }



    /**
     * Data class for saving/loading turtle chunk loader state
     */
    public static class SavedState {
        public final double radius;
        public final ChunkPos lastChunkPos;
        public final double fuelDebt;
        public final boolean wakeOnWorldLoad;
        public final boolean randomTickEnabled;
        public final int fuelLevel;

        public SavedState(double radius, ChunkPos lastChunkPos, double fuelDebt, boolean wakeOnWorldLoad, boolean randomTickEnabled, int fuelLevel) {
            this.radius = radius;
            this.lastChunkPos = lastChunkPos;
            this.fuelDebt = fuelDebt;
            this.wakeOnWorldLoad = wakeOnWorldLoad;
            this.randomTickEnabled = randomTickEnabled;
            this.fuelLevel = fuelLevel;
        }
    }

    /**
     * Get current state for saving to NBT
     */
    public SavedState getSavedState() {
        return new SavedState(radius, lastChunkPos, fuelDebt, wakeOnWorldLoad, randomTickEnabled, turtle.getFuelLevel());
    }

    /**
     * Restore state from saved data (used when reviving turtle after world load)
     */
    public void restoreState(SavedState state) {
        this.radius = state.radius;
        this.lastChunkPos = state.lastChunkPos;
        this.fuelDebt = state.fuelDebt;
        this.wakeOnWorldLoad = state.wakeOnWorldLoad;
        this.randomTickEnabled = state.randomTickEnabled;

        LOGGER.debug("Restored state for turtle {}: radius={}, lastChunk={}, fuelDebt={}, wakeOnWorldLoad={}, randomTick={}",
                    turtleId, radius, lastChunkPos, fuelDebt, wakeOnWorldLoad, randomTickEnabled);
    }

    // ======== Additional Methods ========

    /**
     * Get the world key for this turtle
     */
    public RegistryKey<World> getWorldKey() {
        if (turtle.getLevel() instanceof ServerWorld serverWorld) {
            return serverWorld.getRegistryKey();
        }
        return null;
    }

    /**
     * Get loaded chunks for this turtle
     */
    public Set<ChunkPos> getLoadedChunks() {
        if (radius > 0.0 && turtle.getLevel() != null) {
            return computeChunks(new ChunkPos(turtle.getPosition()), radius);
        }
        return Set.of();
    }

    /**
     * Check if random ticks are enabled
     */
    public boolean isRandomTickEnabled() {
        return randomTickEnabled;
    }
    

    /**
     * Set random tick enabled state
     */
    public void setRandomTickEnabled(boolean enabled) {
        if (enabled && radius > Config.MAX_RANDOM_TICK_RADIUS) {
            LOGGER.warn("Cannot enable random ticks for turtle {} - radius {} exceeds maximum {}",
                       turtleId, radius, Config.MAX_RANDOM_TICK_RADIUS);
            return;
        }
        this.randomTickEnabled = enabled;
        markDirty();
        forceSaveState();
        LOGGER.debug("Random ticks {} for turtle {} (radius: {})",
                    enabled ? "enabled" : "disabled", turtleId, radius);
    }

    /**
     * Load state using new architecture - preserves all pending commands!
     */
    private void loadStateFromNewArchitecture() {
        // Load from NBT as before
        NbtCompound upgradeData = turtle.getUpgradeNBTData(side);
        double nbtRadius = upgradeData.getDouble(RADIUS_KEY);
        this.fuelDebt = upgradeData.getDouble(FUEL_DEBT_KEY);
        this.wakeOnWorldLoad = upgradeData.getBoolean(WAKE_ON_WORLD_LOAD_KEY);
        this.randomTickEnabled = upgradeData.getBoolean(RANDOM_TICK_ENABLED_KEY);

        if (upgradeData.contains(LAST_CHUNK_POS_KEY)) {
            NbtCompound chunkPosNbt = upgradeData.getCompound(LAST_CHUNK_POS_KEY);
            this.lastChunkPos = new ChunkPos(chunkPosNbt.getInt("x"), chunkPosNbt.getInt("z"));
        }

        if (turtle.getLevel() instanceof ServerWorld serverWorld) {
            // Create saved state from NBT
            SavedState savedState = new SavedState(
                nbtRadius, lastChunkPos, fuelDebt, wakeOnWorldLoad, randomTickEnabled, turtle.getFuelLevel()
            );
            
            // Update state manager (this preserves any pending commands!)
            TurtleStateManager stateManager = CCChunkloader.getStateManager();
            Integer computerId = getTurtleComputerId();
            stateManager.updateFromPeripheral(turtleId, savedState, computerId, serverWorld.getRegistryKey());
            
            // Get effective values (includes any overrides)
            TurtleStateManager.TurtleState state = stateManager.getState(turtleId);
            this.radius = state.getEffectiveRadius(); // Uses override if present!
            this.wakeOnWorldLoad = state.getEffectiveWakeOnWorldLoad();
            
            LOGGER.info("Loaded turtle state via new architecture - NBT radius: {}, Effective radius: {} (preserves all commands!)", 
                       nbtRadius, this.radius);
        } else {
            // Client-side or other contexts
            this.radius = nbtRadius;
        }

        LOGGER.debug("Loaded state: radius={}, fuelDebt={}, wake={}, randomTick={}, lastChunk={}",
                    radius, fuelDebt, wakeOnWorldLoad, randomTickEnabled, lastChunkPos);
    }

    /**
     * Save current state to upgrade NBT data
     */
    private void saveStateToUpgradeNBT() {
        NbtCompound upgradeData = turtle.getUpgradeNBTData(side);

        upgradeData.putDouble(RADIUS_KEY, radius);
        upgradeData.putDouble(FUEL_DEBT_KEY, fuelDebt);
        upgradeData.putBoolean(WAKE_ON_WORLD_LOAD_KEY, wakeOnWorldLoad);
        upgradeData.putBoolean(RANDOM_TICK_ENABLED_KEY, randomTickEnabled);

        if (lastChunkPos != null) {
            NbtCompound chunkPosNbt = new NbtCompound();
            chunkPosNbt.putInt("x", lastChunkPos.x);
            chunkPosNbt.putInt("z", lastChunkPos.z);
            upgradeData.put(LAST_CHUNK_POS_KEY, chunkPosNbt);
        }

        turtle.updateUpgradeNBTData(side);

        // Update ChunkManager cache for persistence of dormant turtles
        updateChunkManagerCache();

        // Clear dirty flag after successful save
        isDirty = false;

        LOGGER.debug("Saved state to upgrade NBT: radius={}, fuelDebt={}, wake={}, randomTick={}, lastChunk={}",
                    radius, fuelDebt, wakeOnWorldLoad, randomTickEnabled, lastChunkPos);
    }

    
    /**
     * Update ChunkManager cache with current state for persistence
     */
    private void updateChunkManagerCache() {
        if (turtle.getLevel() instanceof ServerWorld serverWorld) {
            ChunkManager manager = ChunkManager.get(serverWorld);
            SavedState currentState = getSavedState();
            LOGGER.info("DEBUG: updateChunkManagerCache for turtle {} - Saving state: radius={}, fuel={} at {}", 
                       turtleId, currentState.radius, currentState.fuelLevel, System.currentTimeMillis());
            manager.updateTurtleStateCache(turtleId, currentState);
        }
    }

    /**
     * Resume chunk loading after turtle restoration
     */
    private void resumeChunkLoading(ServerWorld serverWorld) {
        if (radius <= 0.0) {
            LOGGER.debug("Turtle {} radius is 0, skipping chunk loading resume", turtleId);
            return;
        }

        // Check fuel before resuming
        if (turtle.getFuelLevel() <= 0) {
            LOGGER.warn("Turtle {} has no fuel, cannot resume chunk loading", turtleId);
            this.radius = 0.0;
            markDirty();
            forceSaveState();
            return;
        }

        try {
            ChunkManager manager = ChunkManager.get(serverWorld);
            ChunkPos currentChunk = new ChunkPos(turtle.getPosition());
            Set<ChunkPos> chunksToLoad = computeChunks(currentChunk, radius);

            manager.addChunksFromSet(turtleId, chunksToLoad);
            this.lastChunkPos = currentChunk;

            LOGGER.info("Turtle {} resumed chunk loading with {} chunks at radius {}",
                       turtleId, chunksToLoad.size(), radius);

            // Force save updated state after resuming chunk loading
            markDirty();
            forceSaveState();

        } catch (Exception e) {
            LOGGER.error("Failed to resume chunk loading for turtle {}: {}", turtleId, e.getMessage());
        }
    }

    /**
     * Confirm radius override was successfully applied and clear it from ChunkManager
     * This prevents override from being lost on subsequent loads
     */
    private void confirmAndClearRadiusOverride(ServerWorld serverWorld) {
        try {
            ChunkManager manager = ChunkManager.get(serverWorld);
            Double pendingOverride = manager.getRadiusOverride(turtleId);
            
            if (pendingOverride != null) {
                // Schedule delayed confirmation check using server execute with delay simulation
                serverWorld.getServer().execute(() -> {
                    // Create a simple delay mechanism using a separate thread
                    new Thread(() -> {
                        try {
                            // Wait 5 seconds for turtle to fully initialize
                            Thread.sleep(5000);
                            
                            // Execute the confirmation check on the server thread
                            serverWorld.getServer().execute(() -> {
                                try {
                                    // Check if turtle successfully applied the override
                                    if (Math.abs(this.radius - pendingOverride) < 0.001) {
                                        // Success! Override was applied correctly
                                        if (radius > 0.0 && getLoadedChunkCount() > 0) {
                                            // Turtle is actively loading chunks with override radius - clear the override
                                            manager.clearRadiusOverride(turtleId);
                                            LOGGER.info("Radius override {} successfully confirmed and cleared for turtle {}", pendingOverride, turtleId);
                                        } else if (radius == 0.0 && pendingOverride == 0.0) {
                                            // Override was to set radius to 0 - also successful
                                            manager.clearRadiusOverride(turtleId);
                                            LOGGER.info("Zero radius override confirmed and cleared for turtle {}", turtleId);
                                        } else {
                                            // Turtle has the right radius but isn't loading chunks yet - keep override for now
                                            LOGGER.debug("Turtle {} has override radius {} but isn't loading chunks yet - keeping override", turtleId, pendingOverride);
                                        }
                                    } else {
                                        // Turtle doesn't have expected radius - keep override for retry
                                        LOGGER.warn("Turtle {} has radius {} but expected {} - keeping override for retry", 
                                                   turtleId, radius, pendingOverride);
                                    }
                                } catch (Exception e) {
                                    LOGGER.error("Failed to confirm radius override for turtle {}: {}", turtleId, e.getMessage());
                                }
                            });
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            LOGGER.debug("Confirmation delay interrupted for turtle {}", turtleId);
                        }
                    }, "ChunkLoader-Override-Confirm-" + turtleId).start();
                });
            }
        } catch (Exception e) {
            LOGGER.error("Failed to check radius override for turtle {}: {}", turtleId, e.getMessage());
        }
    }

    /**
     * Validate UUIDs for a computer by checking currently equipped chunkloaders
     * This removes orphaned UUIDs when peripherals are unequipped
     */
    private void validateComputerUUIDs(ChunkManager manager, int computerId, ServerWorld serverWorld) {
        try {
            // Get the turtle block entity to check currently equipped upgrades
            var blockEntity = serverWorld.getBlockEntity(turtle.getPosition());
            if (!(blockEntity instanceof dan200.computercraft.shared.turtle.blocks.TurtleBlockEntity turtleEntity)) {
                LOGGER.warn("Cannot validate computer {} UUIDs - not a TurtleBlockEntity", computerId);
                return;
            }

            // Detect currently equipped chunkloaders
            Set<UUID> currentlyEquippedUUIDs = new HashSet<>();
            
            // Check LEFT side
            var leftUpgrade = turtleEntity.getUpgrade(dan200.computercraft.api.turtle.TurtleSide.LEFT);
            if (leftUpgrade instanceof ChunkLoaderUpgrade) {
                UUID leftUUID = ChunkLoaderUpgrade.getTurtleUUID(turtle, dan200.computercraft.api.turtle.TurtleSide.LEFT);
                currentlyEquippedUUIDs.add(leftUUID);
                LOGGER.debug("Computer {} has chunkloader on LEFT side with UUID {}", computerId, leftUUID);
            }
            
            // Check RIGHT side
            var rightUpgrade = turtleEntity.getUpgrade(dan200.computercraft.api.turtle.TurtleSide.RIGHT);
            if (rightUpgrade instanceof ChunkLoaderUpgrade) {
                UUID rightUUID = ChunkLoaderUpgrade.getTurtleUUID(turtle, dan200.computercraft.api.turtle.TurtleSide.RIGHT);
                currentlyEquippedUUIDs.add(rightUUID);
                LOGGER.debug("Computer {} has chunkloader on RIGHT side with UUID {}", computerId, rightUUID);
            }

            LOGGER.info("Computer {} validation: found {} currently equipped chunkloaders", 
                       computerId, currentlyEquippedUUIDs.size());

            // Validate stored UUIDs against currently equipped ones
            manager.validateUUIDsForComputer(computerId, currentlyEquippedUUIDs);
            
        } catch (Exception e) {
            LOGGER.error("Failed to validate UUIDs for computer {}: {}", computerId, e.getMessage());
        }
    }

    /**
     * Get the computer ID for this turtle (used for UUID lifecycle management)
     */
    private Integer getTurtleComputerId() {
        if (turtle.getLevel() instanceof ServerWorld serverWorld) {
            try {
                // Access the turtle's block entity to get the computer ID
                var blockEntity = serverWorld.getBlockEntity(turtle.getPosition());
                if (blockEntity instanceof dan200.computercraft.shared.turtle.blocks.TurtleBlockEntity turtleEntity) {
                    var computer = turtleEntity.getServerComputer();
                    if (computer != null) {
                        return computer.getID();
                    } else {
                        LOGGER.debug("Turtle {} has no ServerComputer instance (computer not ready yet)", turtleId);
                    }
                } else {
                    LOGGER.warn("Block entity at turtle position {} is not a TurtleBlockEntity: {}", 
                               turtle.getPosition(), blockEntity != null ? blockEntity.getClass().getName() : "null");
                }
            } catch (Exception e) {
                LOGGER.debug("Failed to get computer ID for turtle {} (computer not ready): {}", turtleId, e.getMessage());
            }
        }
        return null;
    }
}
