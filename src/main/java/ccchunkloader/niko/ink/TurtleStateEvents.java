package ccchunkloader.niko.ink;

import net.minecraft.util.math.ChunkPos;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Event-driven system for turtle state management.
 * This eliminates manual state synchronization and provides automatic coordination
 * between all components when turtle state changes occur.
 */
public class TurtleStateEvents {
    private static final Logger LOGGER = LoggerFactory.getLogger(TurtleStateEvents.class);
    
    // Event listeners by event type
    private final Map<Class<? extends TurtleEvent>, List<Consumer<? extends TurtleEvent>>> listeners = new ConcurrentHashMap<>();
    
    // === Event Definitions ===
    
    /**
     * Base class for all turtle events
     */
    public abstract static class TurtleEvent {
        public final UUID turtleId;
        public final long timestamp;
        
        protected TurtleEvent(UUID turtleId) {
            this.turtleId = turtleId;
            this.timestamp = System.currentTimeMillis();
        }
        
        public abstract String getDescription();
    }
    
    /**
     * Fired when a turtle peripheral is created/loaded
     */
    public static class TurtleLoadedEvent extends TurtleEvent {
        public final ChunkLoaderPeripheral peripheral;
        public final TurtleStateManager.TurtleState state;
        
        public TurtleLoadedEvent(UUID turtleId, ChunkLoaderPeripheral peripheral, TurtleStateManager.TurtleState state) {
            super(turtleId);
            this.peripheral = peripheral;
            this.state = state;
        }
        
        @Override
        public String getDescription() {
            return String.format("Turtle %s loaded (radius: %.1f, fuel: %d)", 
                                turtleId, state.getRadius(), state.getFuelLevel());
        }
    }
    
    /**
     * Fired when a turtle peripheral is unloaded/destroyed
     */
    public static class TurtleUnloadedEvent extends TurtleEvent {
        public final TurtleStateManager.TurtleState lastState;
        public final String reason;
        
        public TurtleUnloadedEvent(UUID turtleId, TurtleStateManager.TurtleState lastState, String reason) {
            super(turtleId);
            this.lastState = lastState;
            this.reason = reason;
        }
        
        @Override
        public String getDescription() {
            return String.format("Turtle %s unloaded (%s)", turtleId, reason);
        }
    }
    
    /**
     * Fired when turtle state changes
     */
    public static class TurtleStateChangedEvent extends TurtleEvent {
        public final TurtleStateManager.TurtleState oldState;
        public final TurtleStateManager.TurtleState newState;
        public final String changeType;
        
        public TurtleStateChangedEvent(UUID turtleId, TurtleStateManager.TurtleState oldState, 
                                     TurtleStateManager.TurtleState newState, String changeType) {
            super(turtleId);
            this.oldState = oldState;
            this.newState = newState;
            this.changeType = changeType;
        }
        
        @Override
        public String getDescription() {
            return String.format("Turtle %s state changed: %s", turtleId, changeType);
        }
    }
    
    /**
     * Fired when a command is queued for a turtle
     */
    public static class CommandQueuedEvent extends TurtleEvent {
        public final TurtleCommandQueue.TurtleCommand command;
        public final String requestedBy;
        
        public CommandQueuedEvent(UUID turtleId, TurtleCommandQueue.TurtleCommand command, String requestedBy) {
            super(turtleId);
            this.command = command;
            this.requestedBy = requestedBy;
        }
        
        @Override
        public String getDescription() {
            return String.format("Command queued for turtle %s: %s (by %s)", 
                                turtleId, command.getDescription(), requestedBy);
        }
    }
    
    /**
     * Fired when a command is successfully executed
     */
    public static class CommandExecutedEvent extends TurtleEvent {
        public final TurtleCommandQueue.TurtleCommand command;
        public final TurtleStateManager.TurtleState resultingState;
        
        public CommandExecutedEvent(UUID turtleId, TurtleCommandQueue.TurtleCommand command, 
                                  TurtleStateManager.TurtleState resultingState) {
            super(turtleId);
            this.command = command;
            this.resultingState = resultingState;
        }
        
        @Override
        public String getDescription() {
            return String.format("Command executed for turtle %s: %s", turtleId, command.getDescription());
        }
    }
    
    /**
     * Fired when a command fails to execute
     */
    public static class CommandFailedEvent extends TurtleEvent {
        public final TurtleCommandQueue.TurtleCommand command;
        public final String errorMessage;
        public final boolean willRetry;
        
        public CommandFailedEvent(UUID turtleId, TurtleCommandQueue.TurtleCommand command, 
                                String errorMessage, boolean willRetry) {
            super(turtleId);
            this.command = command;
            this.errorMessage = errorMessage;
            this.willRetry = willRetry;
        }
        
        @Override
        public String getDescription() {
            return String.format("Command failed for turtle %s: %s - %s %s", 
                                turtleId, command.getDescription(), errorMessage,
                                willRetry ? "(will retry)" : "(abandoned)");
        }
    }
    
    /**
     * Fired when turtle moves to a new chunk
     */
    public static class TurtleMovedEvent extends TurtleEvent {
        public final ChunkPos oldPosition;
        public final ChunkPos newPosition;
        public final RegistryKey<World> worldKey;
        
        public TurtleMovedEvent(UUID turtleId, ChunkPos oldPosition, ChunkPos newPosition, RegistryKey<World> worldKey) {
            super(turtleId);
            this.oldPosition = oldPosition;
            this.newPosition = newPosition;
            this.worldKey = worldKey;
        }
        
        @Override
        public String getDescription() {
            return String.format("Turtle %s moved: %s -> %s", turtleId, oldPosition, newPosition);
        }
    }
    
    /**
     * Fired when turtle runs out of fuel
     */
    public static class TurtleFuelExhaustedEvent extends TurtleEvent {
        public final double previousRadius;
        public final int fuelLevel;
        
        public TurtleFuelExhaustedEvent(UUID turtleId, double previousRadius, int fuelLevel) {
            super(turtleId);
            this.previousRadius = previousRadius;
            this.fuelLevel = fuelLevel;
        }
        
        @Override
        public String getDescription() {
            return String.format("Turtle %s fuel exhausted (was radius %.1f, fuel %d)", 
                                turtleId, previousRadius, fuelLevel);
        }
    }
    
    // === Event System API ===
    
    /**
     * Register an event listener
     */
    @SuppressWarnings("unchecked")
    public <T extends TurtleEvent> void addEventListener(Class<T> eventType, Consumer<T> listener) {
        listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add((Consumer<? extends TurtleEvent>) listener);
        LOGGER.debug("Registered event listener for {}: {}", eventType.getSimpleName(), listener.getClass().getSimpleName());
    }
    
    /**
     * Fire an event to all registered listeners
     */
    @SuppressWarnings("unchecked")
    public <T extends TurtleEvent> void fireEvent(T event) {
        List<Consumer<? extends TurtleEvent>> eventListeners = listeners.get(event.getClass());
        if (eventListeners != null && !eventListeners.isEmpty()) {
            LOGGER.debug("Firing event: {}", event.getDescription());
            
            for (Consumer<? extends TurtleEvent> listener : eventListeners) {
                try {
                    ((Consumer<T>) listener).accept(event);
                } catch (Exception e) {
                    LOGGER.error("Event listener failed for {}: {}", event.getClass().getSimpleName(), e.getMessage(), e);
                }
            }
        }
    }
    
    /**
     * Remove event listener
     */
    public <T extends TurtleEvent> void removeEventListener(Class<T> eventType, Consumer<T> listener) {
        List<Consumer<? extends TurtleEvent>> eventListeners = listeners.get(eventType);
        if (eventListeners != null) {
            eventListeners.remove(listener);
            LOGGER.debug("Removed event listener for {}", eventType.getSimpleName());
        }
    }
    
    /**
     * Get statistics about event system
     */
    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new HashMap<>();
        
        int totalListeners = listeners.values().stream().mapToInt(List::size).sum();
        
        stats.put("eventTypes", listeners.keySet().size());
        stats.put("totalListeners", totalListeners);
        
        Map<String, Integer> listenersByType = new HashMap<>();
        for (Map.Entry<Class<? extends TurtleEvent>, List<Consumer<? extends TurtleEvent>>> entry : listeners.entrySet()) {
            listenersByType.put(entry.getKey().getSimpleName(), entry.getValue().size());
        }
        stats.put("listenersByType", listenersByType);
        
        return stats;
    }
}

/**
 * Default event handlers that provide automatic system coordination
 */
class DefaultTurtleEventHandlers {
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultTurtleEventHandlers.class);
    
    private final TurtleStateManager stateManager;
    private final TurtleCommandQueue commandQueue;
    
    public DefaultTurtleEventHandlers(TurtleStateManager stateManager, TurtleCommandQueue commandQueue) {
        this.stateManager = stateManager;
        this.commandQueue = commandQueue;
    }
    
    /**
     * Register all default event handlers
     */
    public void registerDefaultHandlers(TurtleStateEvents eventSystem) {
        // Handle turtle loaded events - process pending commands
        eventSystem.addEventListener(TurtleStateEvents.TurtleLoadedEvent.class, this::onTurtleLoaded);
        
        // Handle turtle unloaded events - mark as inactive
        eventSystem.addEventListener(TurtleStateEvents.TurtleUnloadedEvent.class, this::onTurtleUnloaded);
        
        // Handle command queued events - try immediate execution if turtle is active
        eventSystem.addEventListener(TurtleStateEvents.CommandQueuedEvent.class, this::onCommandQueued);
        
        // Handle state changed events - update chunk loading
        eventSystem.addEventListener(TurtleStateEvents.TurtleStateChangedEvent.class, this::onStateChanged);
        
        // Handle turtle moved events - update chunk loading
        eventSystem.addEventListener(TurtleStateEvents.TurtleMovedEvent.class, this::onTurtleMoved);
        
        // Handle fuel exhausted events - disable chunk loading
        eventSystem.addEventListener(TurtleStateEvents.TurtleFuelExhaustedEvent.class, this::onFuelExhausted);
        
        // Start periodic command processing (safety net for stuck commands)
        startPeriodicCommandProcessing();
        
        LOGGER.info("Registered default turtle event handlers with periodic command processing");
    }
    
    private void onTurtleLoaded(TurtleStateEvents.TurtleLoadedEvent event) {
        // Mark turtle as active
        stateManager.setTurtleActive(event.turtleId, true);
        
        // Process any pending command when turtle loads
        int pendingCount = commandQueue.getPendingCommandCount(event.turtleId);
        if (pendingCount > 0) {
            boolean processed = commandQueue.processLatestCommand(event.turtleId, event.state);
            if (processed) {
                LOGGER.debug("Turtle {} loaded and processed pending command", event.turtleId);
            }
        }
        
        LOGGER.debug("Handled turtle loaded: {}", event.getDescription());
    }
    
    private void onTurtleUnloaded(TurtleStateEvents.TurtleUnloadedEvent event) {
        // Mark turtle as inactive
        stateManager.setTurtleActive(event.turtleId, false);
        
        LOGGER.debug("Handled turtle unloaded: {}", event.getDescription());
    }
    
    private void onCommandQueued(TurtleStateEvents.CommandQueuedEvent event) {
        // Try immediate processing if turtle is loaded
        ChunkLoaderPeripheral peripheral = ChunkLoaderRegistry.getPeripheral(event.turtleId);
        TurtleStateManager.TurtleState state = stateManager.getState(event.turtleId);
        
        if (peripheral != null && state != null) {
            boolean processed = commandQueue.processLatestCommand(event.turtleId, state);
            if (!processed) {
                LOGGER.debug("Command for turtle {} cannot execute immediately", event.turtleId);
            }
        }
    }
    
    private void onStateChanged(TurtleStateEvents.TurtleStateChangedEvent event) {
        // Could trigger chunk loading updates, fuel monitoring, etc.
        LOGGER.debug("Handled state changed: {}", event.getDescription());
    }
    
    private void onTurtleMoved(TurtleStateEvents.TurtleMovedEvent event) {
        // Update state with new position
        TurtleStateManager.TurtleState state = stateManager.getState(event.turtleId);
        if (state != null) {
            state.setPosition(event.newPosition);
            LOGGER.debug("Updated turtle {} position: {} -> {}", event.turtleId, event.oldPosition, event.newPosition);
        }
    }
    
    private void onFuelExhausted(TurtleStateEvents.TurtleFuelExhaustedEvent event) {
        // Disable chunk loading when fuel runs out
        TurtleStateManager.TurtleState state = stateManager.getState(event.turtleId);
        if (state != null) {
            state.setRadius(0.0);
            LOGGER.info("Disabled chunk loading for turtle {} due to fuel exhaustion", event.turtleId);
        }
    }
    
    /**
     * Start periodic command processing to catch any commands that might get stuck
     */
    private void startPeriodicCommandProcessing() {
        // Create a simple timer thread for periodic processing
        Thread periodicProcessor = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(10000); // Check every 10 seconds
                    processStuckCommands();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    LOGGER.error("Error in periodic command processing: {}", e.getMessage());
                }
            }
        });
        
        periodicProcessor.setDaemon(true);
        periodicProcessor.setName("TurtleCommandProcessor");
        periodicProcessor.start();
        
        LOGGER.info("Started periodic command processing thread");
    }
    
    /**
     * Process any commands that might be stuck in queues
     */
    private void processStuckCommands() {
        try {
            // Clean Slate Logic: Only process commands for active (loaded) peripherals
            Map<UUID, ChunkLoaderPeripheral> activePeripherals = ChunkLoaderRegistry.getAllPeripherals();
            
            int totalProcessed = 0;
            for (UUID turtleId : activePeripherals.keySet()) {
                int pendingCount = commandQueue.getPendingCommandCount(turtleId);
                if (pendingCount > 0) {
                    // If peripheral exists, turtle is definitely loaded - try processing latest command
                    TurtleStateManager.TurtleState state = stateManager.getState(turtleId);
                    if (state != null) {
                        boolean processed = commandQueue.processLatestCommand(turtleId, state);
                        if (processed) {
                            totalProcessed++;
                            LOGGER.debug("🔄 PERIODIC PROCESSING: Processed command for turtle {}", turtleId);
                        }
                    }
                }
            }
            
            if (totalProcessed > 0) {
                LOGGER.debug("Periodic processing: {} commands processed", totalProcessed);
            }
        } catch (Exception e) {
            LOGGER.error("Error processing stuck commands: {}", e.getMessage());
        }
    }
    
}