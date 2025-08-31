# Turtle Persistence Test Plan

## Overview
This document outlines the test plan to verify that the turtle persistence fix works correctly.

## Issue Fixed
Previously, dormant turtles (radius=0 or no active peripheral) were not being saved to world NBT, causing them to be lost across logout/login cycles.

## Solution Implemented
1. **Turtle State Cache**: Added `turtleStateCache` to ChunkManager to preserve turtle data even when peripherals are cleaned up
2. **Enhanced Serialization**: Modified `serializeToNbt()` to use cached data for dormant turtles
3. **Cache Updates**: ChunkLoaderPeripheral now updates the cache whenever state changes
4. **Preservation During Cleanup**: Turtle states are preserved in cache during peripheral cleanup

## Test Scenarios

### Scenario 1: Active Turtle Persistence
1. Place a turtle with chunkloader upgrade
2. Set radius > 0 and wakeOnWorldLoad = true
3. Logout and login
4. **Expected**: Turtle resumes chunk loading

### Scenario 2: Dormant Turtle Persistence  
1. Place a turtle with chunkloader upgrade
2. Set radius = 0 but wakeOnWorldLoad = true
3. Wait for periodic cleanup (or force cleanup)
4. Logout and login
5. **Expected**: Turtle state is preserved and can be reactivated

### Scenario 3: Long-term Dormant Turtle
1. Place a turtle with chunkloader upgrade
2. Set radius = 0, wakeOnWorldLoad = false
3. Multiple logout/login cycles over time
4. Activate turtle later
5. **Expected**: Turtle maintains its UUID and can be activated

## Key Log Messages to Check
- `Serialized X turtle states to NBT (Y tracked turtles)` - Should show all turtles, not just active ones
- `Using cached state for dormant turtle UUID` - Confirms cache is being used
- `Cleaned up turtle UUID but preserved state in cache` - During cleanup
- `Updated turtle state cache for turtle UUID` - When state changes

## Files Modified
- `ChunkManager.java`: Added cache and updated serialization
- `ChunkLoaderPeripheral.java`: Added cache update calls