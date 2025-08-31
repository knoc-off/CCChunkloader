# On-Demand Bootstrap Implementation Summary

## Problem Solved
**Issue**: Remote operations on dormant turtles failed with "turtle is not active" errors, even for turtles that should be manageable remotely.

**Root Cause**: When turtles become dormant (radius=0 or chunks unloaded), their ChunkLoaderPeripheral instances are cleaned up, making them inaccessible for remote management.

## Solution Architecture

### 1. On-Demand Bootstrap System
**File**: `ChunkManager.java`
- **New Method**: `bootstrapTurtleOnDemand(UUID turtleId)`
- **Function**: Temporarily force-loads dormant turtle's chunk to wake it up
- **Process**:
  1. Check if turtle is already active (fast path)
  2. Look for cached turtle state (position, fuel level)
  3. Validate turtle has fuel for operations
  4. Create temporary bootstrap data
  5. Force-load turtle's chunk
  6. Wait for peripheral initialization (up to 500ms)
  7. Return success/failure status

### 2. Automatic Retry Logic
**File**: `ChunkloaderManagerPeripheral.java`
- **Modified Methods**: All Lua API methods (`getTurtleInfo`, `setTurtleRadius`, etc.)
- **New Behavior**: Before throwing "not active" errors, attempt bootstrap
- **New Method**: `getTurtleDiagnostic()` for debugging turtle status

### 3. Enhanced Diagnostics
**File**: `ChunkLoaderRegistry.java`
- **New Methods**: 
  - `canBootstrap()` - Check if turtle can be bootstrapped
  - `getTurtleDiagnostic()` - Get detailed turtle status info
- **Information Provided**: active status, bootstrap data availability, cached state

## Code Flow

### Before Fix:
```
Remote API Call → Check Registry → Peripheral Not Found → "turtle is not active" Error
```

### After Fix:
```
Remote API Call → Check Registry → Peripheral Not Found → 
Try Bootstrap → Force Load Chunk → Wait for Peripheral → 
Peripheral Available → Perform Operation
```

## Key Implementation Details

### Bootstrap Timeout
- **Duration**: 500ms maximum (10 ticks × 50ms)
- **Reason**: Allows turtle time to initialize after chunk loading
- **Fallback**: Clear error message if bootstrap fails

### Cache Integration
- **Source**: Uses existing `turtleStateCache` from persistence system
- **Fallback**: Also checks `restoredTurtleStates` from world NBT
- **Requirements**: Must have position and fuel > 0

### Error Handling
- **Before**: Generic "turtle is not active"
- **After**: Specific errors:
  - "turtle may have been removed, is out of fuel, or bootstrap failed"
  - "no cached state or position available"
  - "bootstrap timeout - peripheral not available"

## Files Modified

1. **ChunkManager.java**
   - Added `bootstrapTurtleOnDemand()` method
   - Enhanced with timeout and retry logic

2. **ChunkloaderManagerPeripheral.java**
   - Updated all Lua API methods to try bootstrap
   - Added `getTurtleDiagnostic()` method
   - Improved error messages

3. **ChunkLoaderRegistry.java**
   - Added `canBootstrap()` helper method
   - Added `getTurtleDiagnostic()` for status info

## Testing Guide
See `on_demand_bootstrap_test.md` for comprehensive test scenarios.

## Benefits

### For Users
✅ **No more "turtle is not active" errors** for manageable turtles
✅ **Seamless remote operations** on dormant turtles
✅ **Better error messages** when operations truly can't work
✅ **Persistent turtle management** across server restarts

### For Developers  
✅ **Clean separation of concerns** - bootstrap logic in ChunkManager
✅ **Transparent operation** - API behavior unchanged, just more reliable
✅ **Good diagnostics** - easy to debug turtle state issues
✅ **Performance conscious** - only bootstraps when needed

## Backward Compatibility
- **API**: No breaking changes to existing Lua methods
- **Behavior**: More operations succeed that previously failed
- **Storage**: Uses existing persistence mechanisms
- **Performance**: Minimal overhead for active turtles (fast path)