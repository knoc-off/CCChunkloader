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

    private static final String RADIUS_KEY = "Radius";
    private static final String FUEL_DEBT_KEY = "FuelDebt";
    private static final String WAKE_ON_WORLD_LOAD_KEY = "WakeOnWorldLoad";
    private static final String RANDOM_TICK_ENABLED_KEY = "RandomTickEnabled";
    private static final String LAST_CHUNK_POS_KEY = "LastChunkPos";
    
    // Numeric constants
    private static final double FUEL_DEBT_THRESHOLD = 1.0; // Minimum fuel debt to consume fuel
    private static final double FUEL_DEBT_EPSILON = 0.001; // Threshold for detecting fuel debt changes
    private static final int CHUNK_SEARCH_PADDING = 1; // Extra chunks to search when computing radius

    private final ITurtleAccess turtle;
    private final TurtleSide side;
    private final UUID turtleId;
    private double radius = 0.0;
    private ChunkPos lastChunkPos = null;
    private double fuelDebt = 0.0; // Accumulated fractional fuel debt
    private boolean wakeOnWorldLoad = false; // Whether to auto-activate on world load
    private boolean randomTickEnabled = false; // Whether random ticking is enabled for this turtle's chunks
    private boolean computerIdRegistered = false; // Whether UUID has been registered with computer ID

    public ChunkLoaderPeripheral(ITurtleAccess turtle, TurtleSide side) {
        this.turtle = turtle;
        this.side = side;
        this.turtleId = ChunkLoaderUpgrade.getTurtleUUID(turtle, side);

        loadStateFromUpgradeNBT();

        LOGGER.info("Successfully created ChunkLoaderPeripheral with UUID: {} (restored radius: {})", turtleId, radius);

        // Register this peripheral in the global registry for remote management
        ChunkLoaderRegistry.register(turtleId, this);

        // IMPORTANT: Register this turtle in the ChunkManager immediately, even with radius=0
        // This ensures it gets tracked for persistence purposes
        if (turtle.getLevel() instanceof ServerWorld serverWorld) {
            // Immediately set the last known position
            this.lastChunkPos = new ChunkPos(turtle.getPosition());

            ChunkManager manager = ChunkManager.get(serverWorld);
            if (!manager.isTurtleTracked(turtleId)) {
                manager.touch(turtleId);
            }
            
            // CRITICAL: Update persistent tracking immediately to ensure data is always available
            manager.updateTurtlePosition(turtleId, this.lastChunkPos);
            manager.updateTurtleFuel(turtleId, turtle.getFuelLevel());
            
            // Computer ID registration deferred until ServerComputer is available (see updateChunkLoading)
            
            // CRITICAL: Check for radius override and apply it (for chunk load/unload cycles)
            Double radiusOverride = manager.getAndClearRadiusOverride(turtleId);
            if (radiusOverride != null) {
                LOGGER.info("Applying radius override for turtle {}: {} -> {}", turtleId, this.radius, radiusOverride);
                this.radius = radiusOverride;
            }
            
            // CRITICAL: Save initial state to cache immediately to ensure position/fuel data is available
            saveStateToUpgradeNBT();

            // Turtle is now active - no need to clear bootstrap data since we don't use registry anymore

            // If turtle was restored with radius > 0, immediately resume chunk loading
            if (radius > 0.0) {
                LOGGER.info("Turtle {} restored with radius {}, immediately resuming chunk loading", turtleId, radius);

                // Schedule chunk loading for next tick to ensure turtle is fully initialized
                serverWorld.getServer().execute(() -> {
                    resumeChunkLoading(serverWorld);
                });
            }
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
     * @param newRadius The radius in chunks (0.0 to disable, max Config.MAX_RADIUS)
     * @return The new radius value
     * @throws LuaException if radius is out of valid range
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
                manager.removeAllChunks(turtleId);
            }

            if (newRadius > 0.0) {
                ChunkPos currentChunk = new ChunkPos(turtle.getPosition());
                Set<ChunkPos> chunksToLoad = computeChunks(currentChunk, newRadius);
                manager.addChunksFromSet(turtleId, chunksToLoad);
                lastChunkPos = currentChunk;
                LOGGER.debug("Turtle {} async radius change complete: {} chunks loaded",
                        turtleId, chunksToLoad.size());
            } else {
                lastChunkPos = null;
                LOGGER.debug("Turtle {} async radius change complete: chunks cleared", turtleId);
            }

            // Save state after radius change
            saveStateToUpgradeNBT();
        } catch (Exception e) {
            LOGGER.error("Failed to set radius asynchronously for turtle {}: {}", turtleId, e.getMessage());
        }
    }

    /**
     * Get the current chunk loading radius.
     * @return The current radius in chunks
     */
    @LuaFunction
    public final double getRadius() {
        return this.radius;
    }

    /**
     * Get the fuel consumption rate per tick for the current configuration.
     * @return The fuel cost per tick
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
     * @param wake true to enable auto-wake, false to disable
     */
    @LuaFunction
    public final void setWakeOnWorldLoad(boolean wake) {
        this.wakeOnWorldLoad = wake;
        saveStateToUpgradeNBT();

        // Touch the chunk manager to ensure this state change is persisted
        if (turtle.getLevel() instanceof ServerWorld serverWorld) {
            ChunkManager.get(serverWorld).touch(turtleId);
        }


    }

    @LuaFunction
    public final boolean getWakeOnWorldLoad() {
        return this.wakeOnWorldLoad;
    }

    /**
     * Enable or disable random ticking for loaded chunks.
     * Random ticking increases fuel cost but allows plants to grow, etc.
     * @param enabled true to enable random ticking, false to disable
     * @throws LuaException if radius is too large for random ticking
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

        int searchRadius = (int) Math.ceil(radius) + CHUNK_SEARCH_PADDING;
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

    public void updateChunkLoading() {
        if (!(turtle.getLevel() instanceof ServerWorld serverWorld)) return;

        ChunkManager manager = ChunkManager.get(serverWorld);
        ChunkPos currentChunk = new ChunkPos(turtle.getPosition());

        boolean moved = this.lastChunkPos == null || !this.lastChunkPos.equals(currentChunk);
        boolean stateChanged = false;

        // Update position tracking
        if (moved) {
            this.lastChunkPos = currentChunk;
            stateChanged = true;
        }
        
        // Always update persistent tracking data
        updatePersistentState(manager, currentChunk);

        // Register computer ID if needed
        tryRegisterComputerId(manager, serverWorld);

        // Handle chunk loading based on radius
        if (radius > 0.0) {
            stateChanged |= updateActiveChunkLoading(manager, currentChunk, moved);
        } else {
            cleanupInactiveChunks(manager);
        }

        // Save state if anything changed
        if (stateChanged) {
            saveStateToUpgradeNBT();
        }
    }

    private void updatePersistentState(ChunkManager manager, ChunkPos currentChunk) {
        manager.updateTurtlePosition(turtleId, currentChunk);
        manager.updateTurtleFuel(turtleId, turtle.getFuelLevel());
    }

    private void tryRegisterComputerId(ChunkManager manager, ServerWorld serverWorld) {
        if (!computerIdRegistered) {
            Integer computerId = getTurtleComputerId();
            if (computerId != null) {
                manager.registerUUIDForComputer(computerId, turtleId);
                LOGGER.info("Registered turtle {} (UUID: {}) with computer ID {} [DEFERRED]", 
                           turtle.getPosition(), turtleId, computerId);
                
                validateComputerUUIDs(manager, computerId, serverWorld);
                computerIdRegistered = true;
            }
        }
    }

    private boolean updateActiveChunkLoading(ChunkManager manager, ChunkPos currentChunk, boolean moved) {
        boolean stateChanged = false;
        
        // Update chunks if the turtle moved or if it should have chunks loaded but doesn't
        if (moved || !manager.hasLoadedChunks(turtleId)) {
            double fuelCostPerTick = calculateFuelCost();
            if (turtle.getFuelLevel() > 0 && fuelCostPerTick > 0) {
                Set<ChunkPos> chunksToLoad = computeChunks(currentChunk, radius);
                manager.addChunksFromSet(turtleId, chunksToLoad);
            } else {
                stateChanged = disableChunkLoading(manager, "cannot afford chunk loading");
            }
        }

        // Consume fuel
        stateChanged |= processFuelConsumption(manager);
        
        return stateChanged;
    }

    private boolean processFuelConsumption(ChunkManager manager) {
        double oldFuelDebt = fuelDebt;
        fuelDebt += calculateFuelCost();
        
        if (fuelDebt >= FUEL_DEBT_THRESHOLD) {
            int fuelToConsume = (int) Math.floor(fuelDebt);
            if (turtle.getFuelLevel() >= fuelToConsume && turtle.consumeFuel(fuelToConsume)) {
                fuelDebt -= fuelToConsume;
                return true;
            } else {
                return disableChunkLoading(manager, "ran out of fuel");
            }
        } else if (Math.abs(fuelDebt - oldFuelDebt) > FUEL_DEBT_EPSILON) {
            return true;
        }
        
        return false;
    }

    private boolean disableChunkLoading(ChunkManager manager, String reason) {
        this.radius = 0.0;
        this.fuelDebt = 0.0;
        manager.removeAllChunks(turtleId);
        LOGGER.debug("Turtle {} {}, disabling chunk loading.", turtleId, reason);
        return true;
    }

    private void cleanupInactiveChunks(ChunkManager manager) {
        // If radius is 0, ensure no chunks are loaded as a cleanup measure
        if (manager.hasLoadedChunks(turtleId)) {
            manager.removeAllChunks(turtleId);
        }
    }


    public void cleanup() {
        // Save final state to upgrade NBT and cache before cleanup
        saveStateToUpgradeNBT();

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
     * Get maximum radius for this turtle
     */
    public double getMaxRadius() {
        return Config.MAX_RADIUS;
    }

    /**
     * Allow remote control of this turtle's chunk loading radius
     * Used by ChunkloaderManagerPeripheral for remote management
     */
    public boolean setRadiusRemote(double newRadius) {
        try {
            setRadius(newRadius);
            return true;
        } catch (Exception e) {
            LOGGER.warn("Remote radius change failed for turtle {}: {}", turtleId, e.getMessage());
            return false;
        }
    }

    /**
     * Get turtle information for remote monitoring
     */
    public TurtleInfo getTurtleInfo() {
        if (turtle.getLevel() instanceof ServerWorld serverWorld) {
            ChunkManager manager = ChunkManager.get(serverWorld);
            return new TurtleInfo(
                turtleId,
                turtle.getPosition(),
                turtle.getFuelLevel(),
                radius,
                manager.getLoadedChunkCount(turtleId),
                calculateFuelCost()
            );
        }
        return new TurtleInfo(turtleId, turtle.getPosition(), turtle.getFuelLevel(), radius, 0, 0.0);
    }





    /**
     * Get current state for saving to NBT
     */
    public ChunkLoaderState getSavedState() {
        return new ChunkLoaderState(radius, lastChunkPos, fuelDebt, wakeOnWorldLoad, randomTickEnabled, turtle.getFuelLevel());
    }

    /**
     * Restore state from saved data (used when reviving turtle after world load)
     */
    public void restoreState(ChunkLoaderState state) {
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
        saveStateToUpgradeNBT();
        LOGGER.debug("Random ticks {} for turtle {} (radius: {})",
                    enabled ? "enabled" : "disabled", turtleId, radius);
    }

    /**
     * Load state from upgrade NBT data
     */
    private void loadStateFromUpgradeNBT() {
        NbtCompound upgradeData = turtle.getUpgradeNBTData(side);

        this.radius = upgradeData.getDouble(RADIUS_KEY);
        this.fuelDebt = upgradeData.getDouble(FUEL_DEBT_KEY);
        this.wakeOnWorldLoad = upgradeData.getBoolean(WAKE_ON_WORLD_LOAD_KEY);
        this.randomTickEnabled = upgradeData.getBoolean(RANDOM_TICK_ENABLED_KEY);

        if (upgradeData.contains(LAST_CHUNK_POS_KEY)) {
            NbtCompound chunkPosNbt = upgradeData.getCompound(LAST_CHUNK_POS_KEY);
            this.lastChunkPos = new ChunkPos(chunkPosNbt.getInt("x"), chunkPosNbt.getInt("z"));
        }


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


    }

    
    /**
     * Update ChunkManager cache with current state for persistence
     */
    private void updateChunkManagerCache() {
        if (turtle.getLevel() instanceof ServerWorld serverWorld) {
            ChunkManager manager = ChunkManager.get(serverWorld);
            ChunkLoaderState currentState = getSavedState();
            manager.updateTurtleStateCache(turtleId, currentState);
        }
    }

    /**
     * Resume chunk loading after turtle restoration
     */
    private void resumeChunkLoading(ServerWorld serverWorld) {
        if (radius <= 0.0) {

            return;
        }

        // Check fuel before resuming
        if (turtle.getFuelLevel() <= 0) {
            LOGGER.warn("Turtle {} has no fuel, cannot resume chunk loading", turtleId);
            this.radius = 0.0;
            saveStateToUpgradeNBT();
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

            // Save updated state
            saveStateToUpgradeNBT();

        } catch (Exception e) {
            LOGGER.error("Failed to resume chunk loading for turtle {}: {}", turtleId, e.getMessage());
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

            }
            
            // Check RIGHT side
            var rightUpgrade = turtleEntity.getUpgrade(dan200.computercraft.api.turtle.TurtleSide.RIGHT);
            if (rightUpgrade instanceof ChunkLoaderUpgrade) {
                UUID rightUUID = ChunkLoaderUpgrade.getTurtleUUID(turtle, dan200.computercraft.api.turtle.TurtleSide.RIGHT);
                currentlyEquippedUUIDs.add(rightUUID);

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
