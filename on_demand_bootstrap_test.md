# On-Demand Bootstrap Test Guide

## Overview
This document provides test scenarios to verify that the on-demand bootstrap system works correctly, eliminating the "turtle is not active" error.

## What Was Fixed

### Previous Problem
When trying to remotely control dormant turtles via ChunkloaderManagerPeripheral, users would get "turtle is not active" errors because:
- The turtle's chunk wasn't loaded
- The ChunkLoaderPeripheral didn't exist
- Remote operations would fail immediately

### Solution Implemented
1. **On-Demand Bootstrap**: Added `bootstrapTurtleOnDemand()` to ChunkManager that:
   - Checks if turtle exists in cache
   - Temporarily force-loads the turtle's chunk
   - Waits for turtle peripheral to initialize
   - Returns success/failure

2. **Automatic Retry**: Updated ChunkloaderManagerPeripheral methods to:
   - Try bootstrap before throwing errors
   - Provide better error messages
   - Support both active and dormant turtles

3. **Enhanced Diagnostics**: Added diagnostic tools to understand turtle state

## Test Scenarios

### Scenario 1: Remote Control of Dormant Turtle
**Setup:**
1. Place a turtle with chunkloader upgrade
2. Set radius > 0 to establish the turtle in the system
3. Set radius = 0 (turtle becomes dormant)
4. Wait for cleanup cycle or move away (turtle's chunks unload)

**Test:**
```lua
-- From chunkloader manager block
manager = peripheral.find("chunkloader_manager")

-- This should now work instead of throwing "turtle is not active"
success = manager.setTurtleRadius("turtle-uuid-here", 3.0)
print("Set radius result: " .. tostring(success))

-- Verify turtle info is accessible
info = manager.getTurtleInfo("turtle-uuid-here")
print("Turtle fuel: " .. info.fuelLevel)
print("Turtle radius: " .. info.radius)
```

**Expected Result:**
- Bootstrap happens automatically
- Turtle chunk gets force-loaded temporarily  
- Turtle peripheral initializes
- Remote operations succeed
- Turtle takes over chunk loading at new radius

### Scenario 2: Remote Control After Server Restart
**Setup:**
1. Place turtle, set radius > 0, set wakeOnWorldLoad = false
2. Restart server (turtle becomes dormant on restart)

**Test:**
```lua
manager = peripheral.find("chunkloader_manager")

-- Check diagnostic first
diagnostic = manager.getTurtleDiagnostic("turtle-uuid-here")
print("Diagnostic: " .. diagnostic)

-- Remote control should work
manager.setTurtleRadius("turtle-uuid-here", 2.0)
```

**Expected Result:**
- Diagnostic shows: "active=false, bootstrap=false, cached=true"
- Bootstrap succeeds using cached data
- Remote operations work

### Scenario 3: Long-term Dormant Turtle
**Setup:**
1. Place turtle, interact with it, then set radius = 0
2. Multiple server restarts over time
3. Turtle has been dormant for extended period

**Test:**
```lua
-- Try to wake up old dormant turtle
manager = peripheral.find("chunkloader_manager")
success = manager.setTurtleWakeOnWorldLoad("old-turtle-uuid", true)
```

**Expected Result:**
- Bootstrap from cache succeeds
- Turtle state is preserved from months ago
- Remote operations work

### Scenario 4: Error Cases
**Test non-existent turtle:**
```lua
manager.getTurtleInfo("fake-uuid-12345")
```
**Expected:** Clear error message (not "turtle is not active")

**Test out-of-fuel turtle:**
```lua
-- If turtle exists in cache but has 0 fuel
manager.setTurtleRadius("no-fuel-turtle-uuid", 2.0)
```
**Expected:** "turtle may be out of fuel" error, not "not active"

## Key Log Messages to Watch

### Successful Bootstrap:
```
[INFO] Attempting on-demand bootstrap for turtle <uuid>
[INFO] Bootstrapping turtle <uuid> at chunk <pos> with <fuel> fuel
[INFO] Force-loaded chunk <pos> to bootstrap turtle <uuid>
[INFO] Successfully bootstrapped turtle <uuid> after <time>ms
```

### Bootstrap Failures:
```
[WARN] Cannot bootstrap turtle <uuid> - no cached state or position available
[WARN] Cannot bootstrap turtle <uuid> - no fuel available (fuel: 0)
[WARN] Bootstrap timeout for turtle <uuid> - peripheral not available after <time>ms
```

## API Enhancements

### New Methods Added:
1. `getTurtleDiagnostic(turtleId)` - Shows turtle status (active/cached/bootstrap)
2. Better error messages in all existing methods
3. Automatic bootstrap attempts before failures

### Behavior Changes:
- `getTurtleInfo()` - Now works with dormant turtles
- `setTurtleRadius()` - Now works with dormant turtles  
- `setTurtleWakeOnWorldLoad()` - Now works with dormant turtles
- `getTurtleWakeOnWorldLoad()` - Now works with dormant turtles

## Success Criteria

✅ **No more "turtle is not active" errors for cached turtles**
✅ **Remote operations work on dormant turtles**  
✅ **Bootstrap happens transparently to user**
✅ **Turtle takes over chunk loading after bootstrap**
✅ **Clear error messages for truly missing turtles**
✅ **Performance - bootstrap only when needed**

The system should feel seamless - users can interact with any turtle that has ever been in the system, regardless of whether it's currently active or dormant.