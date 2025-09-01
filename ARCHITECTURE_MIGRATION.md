# Architecture Migration Guide

## 🎯 **Overview: From Broken to Bulletproof**

This document outlines how to migrate from the old, bug-prone state management system to the new unified architecture that completely eliminates the radius override bug and similar issues.

## 🏗️ **New Architecture Components**

### **1. TurtleStateManager** - Single Source of Truth
```java
// OLD: Multiple fragmented state locations
Map<UUID, RemoteManagementState> remoteManagementStates;
Map<UUID, ChunkLoaderPeripheral.SavedState> restoredTurtleStates;
// + various other maps and caches

// NEW: One authoritative state manager
TurtleStateManager stateManager = new TurtleStateManager();
// Handles ALL turtle state in one place
```

### **2. TurtleCommandQueue** - Robust Command Handling
```java
// OLD: Simple override flags that get destroyed
Double radiusOverride; // Lost during state updates!

// NEW: Persistent command queue with retry logic
TurtleCommandQueue commandQueue = new TurtleCommandQueue();
commandQueue.queueCommand(turtleId, new SetRadiusCommand(3.0, 60000, "admin"));
// Commands survive all state operations until successfully executed
```

### **3. TurtleStateEvents** - Automatic Coordination
```java
// OLD: Manual state synchronization
updateChunkManagerCache(); // Called in 50+ places, easy to miss
updateRemoteManagementStates(); // Manual coordination

// NEW: Event-driven automatic coordination
TurtleStateEvents eventSystem = new TurtleStateEvents();
// Events automatically coordinate all components
```

## 📋 **Migration Steps**

### **Phase 1: Install New Architecture (Parallel)**

1. **Add the new classes** (already created):
   - `TurtleStateManager.java`
   - `TurtleCommandQueue.java` 
   - `TurtleStateEvents.java`

2. **Initialize new system** in main mod class:
```java
public class CCChunkloader {
    // New unified system
    private static TurtleStateManager stateManager;
    private static TurtleCommandQueue commandQueue;
    private static TurtleStateEvents eventSystem;
    private static DefaultTurtleEventHandlers eventHandlers;
    
    @Override
    public void onInitialize() {
        // Initialize new architecture
        stateManager = new TurtleStateManager();
        commandQueue = new TurtleCommandQueue();
        eventSystem = new TurtleStateEvents();
        eventHandlers = new DefaultTurtleEventHandlers(stateManager, commandQueue);
        
        // Register event handlers for automatic coordination
        eventHandlers.registerDefaultHandlers(eventSystem);
        
        LOGGER.info("New turtle state architecture initialized");
    }
    
    // Accessors for other components
    public static TurtleStateManager getStateManager() { return stateManager; }
    public static TurtleCommandQueue getCommandQueue() { return commandQueue; }
    public static TurtleStateEvents getEventSystem() { return eventSystem; }
}
```

### **Phase 2: Migrate ChunkManager Integration**

3. **Add compatibility layer** to ChunkManager:
```java
public class ChunkManager {
    // Keep existing methods for compatibility, but delegate to new system
    private TurtleStateManager stateManager = CCChunkloader.getStateManager();
    private TurtleCommandQueue commandQueue = CCChunkloader.getCommandQueue();
    private TurtleStateEvents eventSystem = CCChunkloader.getEventSystem();
    
    // MIGRATION: Replace broken updateTurtleStateCache
    public synchronized void updateTurtleStateCache(UUID turtleId, ChunkLoaderPeripheral.SavedState state) {
        // NEW: Use state manager that preserves commands
        Integer computerId = getCurrentComputerId(turtleId);
        stateManager.updateFromPeripheral(turtleId, state, computerId, world.getRegistryKey());
        
        LOGGER.debug("MIGRATION: Updated turtle cache via new state manager: {}", turtleId);
    }
    
    // MIGRATION: Replace broken radius override methods
    public void setRadiusOverride(UUID turtleId, double radius) {
        // NEW: Use state manager instead of direct map manipulation
        stateManager.setRadiusOverride(turtleId, radius);
        
        LOGGER.info("MIGRATION: Set radius override via new system: {} -> {}", turtleId, radius);
    }
    
    public Double getRadiusOverride(UUID turtleId) {
        // NEW: Get from state manager
        return stateManager.getRadiusOverride(turtleId);
    }
    
    public void clearRadiusOverride(UUID turtleId) {
        // NEW: Clear via state manager
        stateManager.clearRadiusOverride(turtleId);
    }
    
    public boolean hasRadiusOverride(UUID turtleId) {
        // NEW: Check via state manager
        return stateManager.hasRadiusOverride(turtleId);
    }
}
```

### **Phase 3: Migrate ChunkLoaderPeripheral**

4. **Update peripheral to use new architecture**:
```java
public class ChunkLoaderPeripheral {
    private TurtleStateManager stateManager = CCChunkloader.getStateManager();
    private TurtleStateEvents eventSystem = CCChunkloader.getEventSystem();
    
    public ChunkLoaderPeripheral(ITurtleAccess turtle, TurtleSide side) {
        this.turtle = turtle;
        this.side = side;
        this.turtleId = ChunkLoaderUpgrade.getTurtleUUID(turtle, side);

        // MIGRATION: Load state using new system
        loadStateFromNewArchitecture();

        // MIGRATION: Fire turtle loaded event
        if (turtle.getLevel() instanceof ServerWorld serverWorld) {
            TurtleStateManager.TurtleState state = stateManager.getState(turtleId);
            eventSystem.fireEvent(new TurtleStateEvents.TurtleLoadedEvent(turtleId, this, state));
        }
    }
    
    private void loadStateFromNewArchitecture() {
        // Load from NBT as before
        NbtCompound upgradeData = turtle.getUpgradeNBTData(side);
        double nbtRadius = upgradeData.getDouble(RADIUS_KEY);
        // ... load other NBT data
        
        if (turtle.getLevel() instanceof ServerWorld serverWorld) {
            // Create saved state from NBT
            ChunkLoaderPeripheral.SavedState savedState = new SavedState(
                nbtRadius, lastChunkPos, fuelDebt, wakeOnWorldLoad, randomTickEnabled, turtle.getFuelLevel()
            );
            
            // Update state manager (this preserves any pending commands!)
            Integer computerId = getTurtleComputerId();
            stateManager.updateFromPeripheral(turtleId, savedState, computerId, serverWorld.getRegistryKey());
            
            // Get effective values (includes any overrides)
            TurtleStateManager.TurtleState state = stateManager.getState(turtleId);
            this.radius = state.getEffectiveRadius(); // Uses override if present!
            this.wakeOnWorldLoad = state.getEffectiveWakeOnWorldLoad();
            
            LOGGER.info("MIGRATION: Loaded turtle state - NBT radius: {}, Effective radius: {}", 
                       nbtRadius, this.radius);
        }
    }
    
    @Override
    protected void cleanup() {
        // MIGRATION: Fire turtle unloaded event
        if (turtle.getLevel() instanceof ServerWorld) {
            TurtleStateManager.TurtleState state = stateManager.getState(turtleId);
            eventSystem.fireEvent(new TurtleStateEvents.TurtleUnloadedEvent(turtleId, state, "cleanup"));
        }
        
        // Rest of cleanup...
    }
}
```

### **Phase 4: Migrate Remote Management**

5. **Update ChunkloaderManagerPeripheral**:
```java
public class ChunkloaderManagerPeripheral {
    private TurtleCommandQueue commandQueue = CCChunkloader.getCommandQueue();
    private TurtleStateEvents eventSystem = CCChunkloader.getEventSystem();
    
    @LuaFunction
    public final boolean setTurtleRadius(String turtleIdString, double radius) throws LuaException {
        UUID turtleId = parseUUID(turtleIdString);
        
        // MIGRATION: Use command queue instead of direct override
        TurtleCommandQueue.SetRadiusCommand command = new TurtleCommandQueue.SetRadiusCommand(
            radius, 300000, "remote-admin" // 5 minute timeout
        );
        
        commandQueue.queueCommand(turtleId, command);
        
        // Fire event for immediate processing if turtle is active
        eventSystem.fireEvent(new TurtleStateEvents.CommandQueuedEvent(turtleId, command, "remote-admin"));
        
        LOGGER.info("MIGRATION: Queued radius command for turtle {}: {}", turtleId, radius);
        return true; // Command is queued, will be processed when turtle is available
    }
}
```

## 🔧 **Key Migration Benefits**

### **Before (Broken System)**
```java
// Fragile state management
setRadiusOverride(uuid, 1.0);           // Set override
updateTurtleStateCache(uuid, state);    // DESTROYS override! 💥
getRadiusOverride(uuid);                // Returns null ❌
```

### **After (Fixed System)**  
```java
// Robust state management
stateManager.setRadiusOverride(uuid, 1.0);     // Set override
stateManager.updateFromPeripheral(uuid, state); // Preserves override! ✅
stateManager.getRadiusOverride(uuid);           // Returns 1.0 ✅
```

## ✅ **Migration Verification**

### **Test the Fix**
```java
@Test
void verifyRadiusOverrideBugIsFixed() {
    UUID turtleId = UUID.randomUUID();
    
    // Set override
    stateManager.setRadiusOverride(turtleId, 3.0);
    assert stateManager.hasRadiusOverride(turtleId);
    
    // Simulate peripheral state update (the old bug trigger)
    SavedState savedState = new SavedState(0.0, pos, 0.0, false, false, 100);
    stateManager.updateFromPeripheral(turtleId, savedState, 123, worldKey);
    
    // Override should survive!
    assert stateManager.hasRadiusOverride(turtleId);
    assert stateManager.getRadiusOverride(turtleId) == 3.0;
    
    // Effective radius should use override
    TurtleState state = stateManager.getState(turtleId);
    assert state.getEffectiveRadius() == 3.0; // Uses override
    assert state.getRadius() == 0.0;          // Core radius from NBT
}
```

### **Expected Log Output (After Migration)**
```log
[INFO] MIGRATION: Set radius override via new system: uuid -> 3.0
[INFO] MIGRATION: Loaded turtle state - NBT radius: 0.0, Effective radius: 3.0
[INFO] Successfully created ChunkLoaderPeripheral with UUID: uuid (restored radius: 3.0) ✅
[INFO] Turtle uuid resumed chunk loading with 5 chunks at radius 3.0 ✅
```

## 🚀 **Long-term Architecture Vision**

### **Phase 5: Full Modernization (Future)**
After the migration is complete and stable, consider these improvements:

1. **Remove Legacy Code**
   - Delete old state management maps
   - Remove manual synchronization calls
   - Clean up redundant methods

2. **Enhanced Features**
   - Web dashboard for turtle monitoring
   - Advanced command scheduling
   - Performance analytics
   - Automatic optimization

3. **Extended Command Types**
   - Fuel management commands
   - Position adjustment commands
   - Batch operations
   - Conditional commands

## 📋 **Migration Checklist**

- [ ] Install new architecture classes
- [ ] Initialize new system in mod main
- [ ] Update ChunkManager compatibility layer
- [ ] Migrate ChunkLoaderPeripheral to new loading
- [ ] Update ChunkloaderManagerPeripheral to use commands
- [ ] Run integration tests to verify fix
- [ ] Test with real turtles in development environment
- [ ] Deploy to production
- [ ] Monitor logs for successful operation
- [ ] Plan phase 2 improvements

## 🔍 **Troubleshooting Migration**

### **Common Issues**
1. **"Old overrides not working"** - Ensure compatibility layer properly delegates to new system
2. **"Commands not executing"** - Verify event handlers are registered
3. **"Memory usage increased"** - Normal during parallel operation, will decrease after cleanup
4. **"Tests failing"** - Update test mocks to work with new architecture

### **Verification Commands**
```bash
# Check new system is working
/ccchunkloader debug stats  # Should show new architecture statistics

# Test radius override
/ccchunkloader debug turtle <uuid>  # Should show pending commands

# Monitor logs
tail -f logs/latest.log | grep MIGRATION
```

This migration completely eliminates the radius override bug while providing a foundation for future improvements!