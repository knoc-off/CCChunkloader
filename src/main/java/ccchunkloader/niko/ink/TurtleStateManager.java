package ccchunkloader.niko.ink;

import net.minecraft.util.math.ChunkPos;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unified state manager for all turtle chunk loader state.
 * This is the single source of truth that eliminates cache coherency bugs
 * by maintaining all turtle state in one authoritative location.
 */
public class TurtleStateManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(TurtleStateManager.class);
    
    // Thread-safe storage for all turtle states
    private final Map<UUID, TurtleState> allTurtleStates = new ConcurrentHashMap<>();
    
    // Thread-safe reference tracking
    private final Object stateLock = new Object();
    
    /**
     * Unified turtle state containing both persistent data and pending commands.
     * This design prevents the cache coherency bugs that plagued the old system.
     */
    public static class TurtleState {
        // === Core Turtle Data (from NBT/Peripheral) ===
        private double radius;
        private double fuelDebt;
        private ChunkPos position;
        private int fuelLevel;
        private boolean wakeOnWorldLoad;
        private boolean randomTickEnabled;
        private RegistryKey<World> worldKey;
        
        // === Remote Management Commands (NEVER overwritten by core data) ===
        private Double pendingRadiusOverride;  // The fix for the radius override bug!
        private Boolean pendingWakeOverride;
        
        // === Administrative Data ===
        private Integer computerId;
        private long lastSeenTimestamp;
        private Set<ChunkPos> loadedChunks;
        private boolean isActive; // Whether peripheral is currently loaded
        
        // === Constructors ===
        public TurtleState() {
            this.loadedChunks = new HashSet<>();
            this.lastSeenTimestamp = System.currentTimeMillis();
            this.isActive = false;
        }
        
        public TurtleState(ChunkLoaderPeripheral.SavedState savedState, Integer computerId, RegistryKey<World> worldKey) {
            this();
            this.radius = savedState.radius;
            this.fuelDebt = savedState.fuelDebt;
            this.position = savedState.lastChunkPos;
            this.fuelLevel = savedState.fuelLevel;
            this.wakeOnWorldLoad = savedState.wakeOnWorldLoad;
            this.randomTickEnabled = savedState.randomTickEnabled;
            this.computerId = computerId;
            this.worldKey = worldKey;
            this.lastSeenTimestamp = System.currentTimeMillis();
        }
        
        // === Core Data Getters/Setters ===
        public double getRadius() { return radius; }
        public void setRadius(double radius) { 
            this.radius = radius; 
            this.lastSeenTimestamp = System.currentTimeMillis();
        }
        
        public double getFuelDebt() { return fuelDebt; }
        public void setFuelDebt(double fuelDebt) { 
            this.fuelDebt = fuelDebt; 
            this.lastSeenTimestamp = System.currentTimeMillis();
        }
        
        public ChunkPos getPosition() { return position; }
        public void setPosition(ChunkPos position) { 
            this.position = position; 
            this.lastSeenTimestamp = System.currentTimeMillis();
        }
        
        public int getFuelLevel() { return fuelLevel; }
        public void setFuelLevel(int fuelLevel) { 
            this.fuelLevel = fuelLevel; 
            this.lastSeenTimestamp = System.currentTimeMillis();
        }
        
        public boolean isWakeOnWorldLoad() { return wakeOnWorldLoad; }
        public void setWakeOnWorldLoad(boolean wakeOnWorldLoad) { 
            this.wakeOnWorldLoad = wakeOnWorldLoad; 
            this.lastSeenTimestamp = System.currentTimeMillis();
        }
        
        public boolean isRandomTickEnabled() { return randomTickEnabled; }
        public void setRandomTickEnabled(boolean randomTickEnabled) { 
            this.randomTickEnabled = randomTickEnabled; 
            this.lastSeenTimestamp = System.currentTimeMillis();
        }
        
        public RegistryKey<World> getWorldKey() { return worldKey; }
        public void setWorldKey(RegistryKey<World> worldKey) { this.worldKey = worldKey; }
        
        // === Command Getters/Setters (Separate from core data) ===
        public Double getPendingRadiusOverride() { return pendingRadiusOverride; }
        public void setPendingRadiusOverride(Double pendingRadiusOverride) { 
            this.pendingRadiusOverride = pendingRadiusOverride; 
            this.lastSeenTimestamp = System.currentTimeMillis();
        }
        
        public Boolean getPendingWakeOverride() { return pendingWakeOverride; }
        public void setPendingWakeOverride(Boolean pendingWakeOverride) { 
            this.pendingWakeOverride = pendingWakeOverride; 
            this.lastSeenTimestamp = System.currentTimeMillis();
        }
        
        // === Administrative Getters/Setters ===
        public Integer getComputerId() { return computerId; }
        public void setComputerId(Integer computerId) { 
            this.computerId = computerId; 
            this.lastSeenTimestamp = System.currentTimeMillis();
        }
        
        public long getLastSeenTimestamp() { return lastSeenTimestamp; }
        
        public Set<ChunkPos> getLoadedChunks() { return new HashSet<>(loadedChunks); }
        public void setLoadedChunks(Set<ChunkPos> loadedChunks) { 
            this.loadedChunks = new HashSet<>(loadedChunks); 
            this.lastSeenTimestamp = System.currentTimeMillis();
        }
        
        public boolean isActive() { return isActive; }
        public void setActive(boolean active) { 
            this.isActive = active; 
            this.lastSeenTimestamp = System.currentTimeMillis();
        }
        
        // === Command Processing ===
        /**
         * Check if there are any pending commands to process
         */
        public boolean hasPendingCommands() {
            return pendingRadiusOverride != null || pendingWakeOverride != null;
        }
        
        /**
         * Apply pending radius override if present, returning the effective radius
         */
        public double getEffectiveRadius() {
            return pendingRadiusOverride != null ? pendingRadiusOverride : radius;
        }
        
        /**
         * Apply pending wake override if present, returning the effective wake setting
         */
        public boolean getEffectiveWakeOnWorldLoad() {
            return pendingWakeOverride != null ? pendingWakeOverride : wakeOnWorldLoad;
        }
        
        /**
         * Clear a specific command after successful application
         */
        public void clearRadiusOverride() {
            this.pendingRadiusOverride = null;
            this.lastSeenTimestamp = System.currentTimeMillis();
        }
        
        public void clearWakeOverride() {
            this.pendingWakeOverride = null;
            this.lastSeenTimestamp = System.currentTimeMillis();
        }
        
        // === Utility Methods ===
        /**
         * Update core turtle data from peripheral state (preserves commands!)
         */
        public void updateFromPeripheralState(ChunkLoaderPeripheral.SavedState savedState) {
            // Update core data
            this.radius = savedState.radius;
            this.fuelDebt = savedState.fuelDebt;
            this.position = savedState.lastChunkPos;
            this.fuelLevel = savedState.fuelLevel;
            this.wakeOnWorldLoad = savedState.wakeOnWorldLoad;
            this.randomTickEnabled = savedState.randomTickEnabled;
            this.lastSeenTimestamp = System.currentTimeMillis();
            
            // CRITICAL: DO NOT touch pending commands - they are preserved!
            LOGGER.debug("Updated turtle state from peripheral data - commands preserved: radiusOverride={}, wakeOverride={}", 
                        pendingRadiusOverride, pendingWakeOverride);
        }
        
        /**
         * Convert to RemoteManagementState for compatibility with existing code
         */
        public ChunkManager.RemoteManagementState toRemoteManagementState() {
            return new ChunkManager.RemoteManagementState(
                position, fuelLevel, wakeOnWorldLoad, computerId, pendingRadiusOverride
            );
        }
        
        @Override
        public String toString() {
            return String.format("TurtleState{radius=%.1f, position=%s, fuel=%d, active=%s, radiusOverride=%s, wakeOverride=%s, computerId=%d}", 
                               radius, position, fuelLevel, isActive, pendingRadiusOverride, pendingWakeOverride, computerId);
        }
    }
    
    // === Public API ===
    
    /**
     * Get turtle state, creating it if it doesn't exist
     */
    public TurtleState getOrCreateState(UUID turtleId) {
        return allTurtleStates.computeIfAbsent(turtleId, id -> {
            LOGGER.debug("Created new turtle state for {}", id);
            return new TurtleState();
        });
    }
    
    /**
     * Get turtle state if it exists, null otherwise
     */
    public TurtleState getState(UUID turtleId) {
        return allTurtleStates.get(turtleId);
    }
    
    /**
     * Update turtle state from peripheral (preserves pending commands!)
     */
    public void updateFromPeripheral(UUID turtleId, ChunkLoaderPeripheral.SavedState savedState, 
                                   Integer computerId, RegistryKey<World> worldKey) {
        synchronized (stateLock) {
            TurtleState state = getOrCreateState(turtleId);
            
            // Store the pending commands before update
            Double preservedRadiusOverride = state.getPendingRadiusOverride();
            Boolean preservedWakeOverride = state.getPendingWakeOverride();
            
            // Update from peripheral data
            state.updateFromPeripheralState(savedState);
            
            // Update administrative data
            state.setComputerId(computerId);
            state.setWorldKey(worldKey);
            state.setActive(true);
            
            // Restore preserved commands (this is the fix!)
            state.setPendingRadiusOverride(preservedRadiusOverride);
            state.setPendingWakeOverride(preservedWakeOverride);
            
            LOGGER.debug("Updated turtle {} state from peripheral - preserved commands: radius={}, wake={}", 
                        turtleId, preservedRadiusOverride, preservedWakeOverride);
        }
    }
    
    /**
     * Set radius override command (the new, safe way)
     */
    public void setRadiusOverride(UUID turtleId, double radius) {
        synchronized (stateLock) {
            TurtleState state = getOrCreateState(turtleId);
            state.setPendingRadiusOverride(radius);
            
            LOGGER.info("Set radius override for turtle {}: {} (state: {})", turtleId, radius, state);
        }
    }
    
    /**
     * Get radius override (for compatibility with existing code)
     */
    public Double getRadiusOverride(UUID turtleId) {
        TurtleState state = getState(turtleId);
        Double override = state != null ? state.getPendingRadiusOverride() : null;
        
        LOGGER.debug("Get radius override for turtle {}: {} (state exists: {})", turtleId, override, state != null);
        return override;
    }
    
    /**
     * Clear radius override after successful application
     */
    public void clearRadiusOverride(UUID turtleId) {
        synchronized (stateLock) {
            TurtleState state = getState(turtleId);
            if (state != null) {
                Double oldOverride = state.getPendingRadiusOverride();
                state.clearRadiusOverride();
                LOGGER.info("Cleared radius override for turtle {}: {} -> null", turtleId, oldOverride);
            }
        }
    }
    
    /**
     * Check if turtle has pending radius override
     */
    public boolean hasRadiusOverride(UUID turtleId) {
        TurtleState state = getState(turtleId);
        return state != null && state.getPendingRadiusOverride() != null;
    }
    
    /**
     * Mark turtle as active (peripheral loaded)
     */
    public void setTurtleActive(UUID turtleId, boolean active) {
        synchronized (stateLock) {
            TurtleState state = getOrCreateState(turtleId);
            state.setActive(active);
            LOGGER.debug("Set turtle {} active status: {}", turtleId, active);
        }
    }
    
    /**
     * Remove turtle state completely
     */
    public void removeTurtle(UUID turtleId) {
        synchronized (stateLock) {
            TurtleState removed = allTurtleStates.remove(turtleId);
            if (removed != null) {
                LOGGER.info("Removed turtle state: {}", turtleId);
            }
        }
    }
    
    /**
     * Get all turtle UUIDs
     */
    public Set<UUID> getAllTurtleIds() {
        return new HashSet<>(allTurtleStates.keySet());
    }
    
    /**
     * Get all active turtles
     */
    public Set<UUID> getActiveTurtles() {
        return allTurtleStates.entrySet().stream()
            .filter(entry -> entry.getValue().isActive())
            .map(Map.Entry::getKey)
            .collect(HashSet::new, HashSet::add, HashSet::addAll);
    }
    
    /**
     * Get all turtles with pending commands
     */
    public Set<UUID> getTurtlesWithPendingCommands() {
        return allTurtleStates.entrySet().stream()
            .filter(entry -> entry.getValue().hasPendingCommands())
            .map(Map.Entry::getKey)
            .collect(HashSet::new, HashSet::add, HashSet::addAll);
    }
    
    /**
     * Get statistics for admin commands
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalTurtles", allTurtleStates.size());
        stats.put("activeTurtles", getActiveTurtles().size());
        stats.put("pendingCommands", getTurtlesWithPendingCommands().size());
        stats.put("memoryUsageKB", allTurtleStates.size() * 1); // Rough estimate
        return stats;
    }
    
    /**
     * Validate state consistency (for debugging)
     */
    public List<String> validateConsistency() {
        List<String> issues = new ArrayList<>();
        
        for (Map.Entry<UUID, TurtleState> entry : allTurtleStates.entrySet()) {
            UUID id = entry.getKey();
            TurtleState state = entry.getValue();
            
            if (state.getRadius() < 0) {
                issues.add("Turtle " + id + " has negative radius: " + state.getRadius());
            }
            if (state.getFuelLevel() < 0) {
                issues.add("Turtle " + id + " has negative fuel: " + state.getFuelLevel());
            }
            if (state.getPosition() == null && state.isActive()) {
                issues.add("Active turtle " + id + " has null position");
            }
        }
        
        return issues;
    }
}