package ccchunkloader.niko.ink;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple fuel-aware command system for turtle operations.
 * Uses latest-command-wins approach with fuel validation and timestamp expiration.
 * 
 * Key principles:
 * - Only one pending command per turtle (latest wins)
 * - Commands expire after 2 seconds
 * - Fuel validation before queuing and execution
 * - Clean handoff between remote commands and turtle control
 */
public class TurtleCommandQueue {
    private static final Logger LOGGER = LoggerFactory.getLogger(TurtleCommandQueue.class);
    
    // Simple command holders per turtle - only latest command matters
    private final Map<UUID, LatestCommandHolder> pendingCommands = new ConcurrentHashMap<>();
    
    // Command expiration time (2 seconds)
    private static final long COMMAND_EXPIRY_MS = 2000;
    
    /**
     * Simple holder for the latest command with timestamp expiration
     */
    private static class LatestCommandHolder {
        final TurtleCommand command;
        final long timestamp;
        final String requestedBy;
        
        LatestCommandHolder(TurtleCommand command, String requestedBy) {
            this.command = command;
            this.requestedBy = requestedBy;
            this.timestamp = System.currentTimeMillis();
        }
        
        boolean isExpired() {
            return (System.currentTimeMillis() - timestamp) > COMMAND_EXPIRY_MS;
        }
        
        long ageMs() {
            return System.currentTimeMillis() - timestamp;
        }
    }
    
    /**
     * Simple interface for turtle commands with fuel validation
     */
    public interface TurtleCommand {
        /**
         * Check if this command can be executed - includes fuel validation
         */
        boolean canExecute(TurtleStateManager.TurtleState state);
        
        /**
         * Execute the command, returning the updated state
         */
        TurtleStateManager.TurtleState execute(UUID turtleId, TurtleStateManager.TurtleState state);
        
        /**
         * Get command description for logging
         */
        String getDescription();
        
        /**
         * Get required fuel level for this command (for pre-queuing validation)
         */
        int getRequiredFuel();
    }
    
    
    /**
     * Simple set radius command with fuel validation
     */
    public static class SetRadiusCommand implements TurtleCommand {
        private final double targetRadius;
        private final String requestedBy;
        
        public SetRadiusCommand(double targetRadius, String requestedBy) {
            this.targetRadius = targetRadius;
            this.requestedBy = requestedBy;
        }
        
        @Override
        public boolean canExecute(TurtleStateManager.TurtleState state) {
            // Fuel validation: Check if turtle has enough fuel for the radius
            if (targetRadius > 0 && state.getFuelLevel() < getRequiredFuel()) {
                return false;
            }
            
            // Position validation: turtle needs a valid position
            return state.getPosition() != null;
        }
        
        @Override
        public TurtleStateManager.TurtleState execute(UUID turtleId, TurtleStateManager.TurtleState state) {
            // Get the actual peripheral and call its setRadius method
            ChunkLoaderPeripheral peripheral = ChunkLoaderRegistry.getPeripheral(turtleId);
            
            if (peripheral != null) {
                // Apply command directly to peripheral for immediate effect
                try {
                    peripheral.setRadius(targetRadius);
                    LOGGER.debug("Turtle {} radius set to {} via peripheral", turtleId, targetRadius);
                } catch (Exception e) {
                    LOGGER.error("Failed to apply SetRadiusCommand to peripheral for turtle {}: {}", turtleId, e.getMessage());
                    throw new RuntimeException("Failed to apply radius change", e);
                }
            } else {
                // Peripheral not active - update state only (turtle will pick up when it loads)
                LOGGER.debug("SetRadiusCommand applied to state only: turtle {} radius set to {} (requested by {})", 
                           turtleId, targetRadius, requestedBy);
            }
            
            // Update state
            state.setRadius(targetRadius);
            state.clearRadiusOverride(); // Clear any pending overrides
            
            return state;
        }
        
        @Override
        public String getDescription() {
            return String.format("SetRadius(%.1f, by:%s)", targetRadius, requestedBy);
        }
        
        @Override
        public int getRequiredFuel() {
            // Rough fuel requirement: need at least some fuel for radius > 0
            return targetRadius > 0 ? 10 : 0; // Minimum 10 fuel for any chunk loading
        }
    }
    
    /**
     * Simple wake on world load command
     */
    public static class SetWakeOnWorldLoadCommand implements TurtleCommand {
        private final boolean wakeEnabled;
        private final String requestedBy;
        
        public SetWakeOnWorldLoadCommand(boolean wakeEnabled, String requestedBy) {
            this.wakeEnabled = wakeEnabled;
            this.requestedBy = requestedBy;
        }
        
        @Override
        public boolean canExecute(TurtleStateManager.TurtleState state) {
            return true; // Always can execute - no fuel required
        }
        
        @Override
        public TurtleStateManager.TurtleState execute(UUID turtleId, TurtleStateManager.TurtleState state) {
            state.setWakeOnWorldLoad(wakeEnabled);
            state.clearWakeOverride();
            
            LOGGER.info("Executed SetWakeOnWorldLoadCommand: turtle {} wake set to {} (requested by {})", 
                       turtleId, wakeEnabled, requestedBy);
            
            return state;
        }
        
        @Override
        public String getDescription() {
            return String.format("SetWake(%s, by:%s)", wakeEnabled, requestedBy);
        }
        
        @Override
        public int getRequiredFuel() {
            return 0; // No fuel required for wake setting
        }
    }
    
    // === Public API ===
    
    /**
     * Queue a command for a turtle with fuel validation
     * Latest command wins - only one pending command per turtle
     */
    public boolean queueCommand(UUID turtleId, TurtleCommand command, String requestedBy) {
        // Pre-queue fuel validation
        TurtleStateManager stateManager = CCChunkloader.getStateManager();
        TurtleStateManager.TurtleState state = stateManager.getState(turtleId);
        
        if (state != null && state.getFuelLevel() < command.getRequiredFuel()) {
            LOGGER.warn("Turtle {} has insufficient fuel ({} < {}) for command: {}", 
                       turtleId, state.getFuelLevel(), command.getRequiredFuel(), command.getDescription());
            return false;
        }
        
        // Store command - latest wins, previous command is discarded
        LatestCommandHolder previousCommand = pendingCommands.put(turtleId, new LatestCommandHolder(command, requestedBy));
        LOGGER.debug("Command queued: {} for turtle {}", command.getDescription(), turtleId);
        
        return true;
    }
    
    /**
     * Process the latest command for a turtle (if any)
     * Returns true if a command was processed, false if none or expired
     */
    public synchronized boolean processLatestCommand(UUID turtleId, TurtleStateManager.TurtleState state) {
        LatestCommandHolder holder = pendingCommands.get(turtleId);
        if (holder == null) {
            return false; // No commands
        }
        
        // Check if command expired
        if (holder.isExpired()) {
            pendingCommands.remove(turtleId);
            LOGGER.debug("Command expired: {} for turtle {} after {}ms", 
                       holder.command.getDescription(), turtleId, holder.ageMs());
            return false;
        }
        
        // Try to execute command
        if (holder.command.canExecute(state)) {
            try {
                holder.command.execute(turtleId, state);
                pendingCommands.remove(turtleId); // Remove after successful execution
                LOGGER.info("Command executed: {} for turtle {}", 
                           holder.command.getDescription(), turtleId);
                return true;
            } catch (Exception e) {
                pendingCommands.remove(turtleId); // Remove failed commands immediately
                LOGGER.error("Command failed: {} for turtle {} - {}", 
                            holder.command.getDescription(), turtleId, e.getMessage());
                return false;
            }
        } else {
            LOGGER.debug("Command waiting: {} for turtle {} cannot execute yet", 
                        holder.command.getDescription(), turtleId);
            return false; // Command waiting for conditions
        }
    }
    
    /**
     * Get the number of pending commands for a turtle (0 or 1)
     */
    public int getPendingCommandCount(UUID turtleId) {
        LatestCommandHolder holder = pendingCommands.get(turtleId);
        return (holder != null && !holder.isExpired()) ? 1 : 0;
    }
    
    /**
     * Get pending command description for a turtle (for debugging)
     */
    public String getPendingCommand(UUID turtleId) {
        LatestCommandHolder holder = pendingCommands.get(turtleId);
        if (holder == null) {
            return null;
        }
        return holder.command.getDescription() + (holder.isExpired() ? " [EXPIRED]" : "");
    }
    
    /**
     * Clear the latest command for a turtle
     */
    public void clearCommand(UUID turtleId) {
        LatestCommandHolder removed = pendingCommands.remove(turtleId);
        if (removed != null) {
            LOGGER.debug("Cleared pending command for turtle {}: {}", turtleId, removed.command.getDescription());
        }
    }
    
    /**
     * Remove all data for a turtle
     */
    public void removeTurtle(UUID turtleId) {
        LatestCommandHolder removed = pendingCommands.remove(turtleId);
        if (removed != null) {
            LOGGER.debug("Removed command data for turtle {}: {}", turtleId, removed.command.getDescription());
        }
    }
    
    /**
     * Get statistics for the command system
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        
        int totalCommands = 0;
        int expiredCommands = 0;
        
        for (LatestCommandHolder holder : pendingCommands.values()) {
            totalCommands++;
            if (holder.isExpired()) {
                expiredCommands++;
            }
        }
        
        stats.put("totalTurtles", pendingCommands.size());
        stats.put("totalPendingCommands", totalCommands);
        stats.put("expiredCommands", expiredCommands);
        stats.put("commandExpiryMs", COMMAND_EXPIRY_MS);
        
        return stats;
    }
    
    /**
     * Clean up expired commands
     */
    public void performMaintenance() {
        int expiredCount = 0;
        
        Iterator<Map.Entry<UUID, LatestCommandHolder>> iterator = pendingCommands.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, LatestCommandHolder> entry = iterator.next();
            if (entry.getValue().isExpired()) {
                iterator.remove();
                expiredCount++;
            }
        }
        
        if (expiredCount > 0) {
            LOGGER.debug("Maintenance: removed {} expired commands", expiredCount);
        }
    }
}