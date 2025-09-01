# CC Chunk Loader System Design

## **System Overview**

The CC Chunk Loader mod provides persistent chunk loading capabilities for ComputerCraft turtles through peripheral upgrades. The system is designed to maintain turtle state permanently until peripherals are explicitly unequipped, supporting multiple chunk loaders per turtle.

## **Core Components**

### **1. ChunkManager** (`ChunkManager.java`)
**Purpose:** Central state management and chunk loading coordination

**Key Responsibilities:**
- Track all turtle UUIDs and their associated chunks
- Manage chunk force-loading with reference counting
- Persist turtle state across world saves/loads
- Handle UUID lifecycle based on computer ID mappings

**State Tracking (OPTIMIZED):**
- `chunkLoaders: Map<ChunkPos, Set<UUID>>` - Which turtles are loading each chunk
- `turtleChunks: Map<UUID, Set<ChunkPos>>` - Which chunks each turtle is loading
- `remoteManagementStates: Map<UUID, RemoteManagementState>` - **UNIFIED** remote management state for offline turtles (position, fuel, wake preference, computer ID, radius override)
- `computerTracker: ComputerUUIDTracker` - **ATOMIC** bidirectional computer ID ↔ UUID mapping

**Optimization Results:**
- **Reduced from 9 maps to 4 core structures** (~55% reduction)
- **Eliminated bootstrap registry redundancy**: ChunkLoaderRegistry.BOOTSTRAP_DATA removed, bootstrap logic queries ChunkManager directly
- **Consolidated bidirectional mappings**: `computerIdToUUIDs` + `uuidToComputerId` replaced with atomic `ComputerUUIDTracker` 
- **Integrated radius overrides**: No separate `radiusOverrides` map, now part of `RemoteManagementState`
- **Single source of truth**: All turtle state (active + dormant + overrides) managed in unified structures

**State Design Principles:**
- **Turtle's own NBT data**: Complete peripheral state (radius, fuelDebt, randomTickEnabled) restored when peripheral loads
- **Remote management data**: Essential state (position, fuel, wake preference, computer ID, radius override) for managing offline turtles

### **2. ChunkLoaderPeripheral** (`ChunkLoaderPeripheral.java`)
**Purpose:** Individual turtle peripheral implementation

**Key Responsibilities:**
- Provide Lua API for turtle chunk loading control
- Track turtle position, fuel consumption, and radius
- Register with computer ID for lifecycle management
- Save/restore state to/from turtle's upgrade NBT

**State Variables:**
- `radius` - Current chunk loading radius
- `fuelDebt` - Accumulated fractional fuel cost
- `wakeOnWorldLoad` - Auto-activation preference
- `randomTickEnabled` - Random tick preference
- `computerIdRegistered` - Whether UUID is registered with computer ID

### **3. ChunkLoaderUpgrade** (`ChunkLoaderUpgrade.java`)
**Purpose:** Peripheral factory and UUID management

**Key Responsibilities:**
- Create peripheral instances when upgrade is equipped
- Generate unique UUIDs for each peripheral
- Clean up orphaned UUIDs when peripherals are unequipped
- Validate computer equipment on peripheral creation

### **4. ChunkLoaderRegistry** (`ChunkLoaderRegistry.java`) - **SIMPLIFIED**
**Purpose:** Lightweight active peripheral tracking (stateless for bootstrap)

**Key Responsibilities:**
- Track active peripheral instances by UUID (for method dispatch only)
- Provide diagnostic information for troubleshooting
- Bootstrap coordination moved to ChunkManager for single source of truth

### **5. Computer ID Tracking System**
**Purpose:** UUID lifecycle management based on turtle computer IDs

**How It Works:**
1. When peripheral created, register UUID with turtle's computer ID
2. Track all UUIDs associated with each computer ID
3. When turtle loads, validate current equipment vs stored UUIDs
4. Remove orphaned UUIDs when peripherals are unequipped
5. Persist computer ID mappings across world saves/loads

## **UUID Lifecycle**

### **Creation**
1. Turtle equips chunkloader upgrade
2. `ChunkLoaderUpgrade.createPeripheral()` called
3. UUID generated if not exists (stored in upgrade NBT)
4. `ChunkLoaderPeripheral` created and registered

### **Registration (Deferred)**
1. Peripheral creation triggers state tracking
2. Computer ID registration deferred until `ServerComputer` available
3. First `updateChunkLoading()` call registers UUID with computer ID
4. Validation runs to clean up orphaned UUIDs from same computer

### **Persistence**
1. State saved to upgrade NBT on every change
2. Essential data (position, fuel, computer ID) saved to world NBT
3. Computer ID mappings persisted across world saves/loads

### **Cleanup**
1. Only occurs when turtle is loaded and active
2. Compare current equipment vs stored UUIDs for computer ID
3. Remove UUIDs that are no longer equipped
4. Manual admin override available via debug commands

## **Bootstrap System**

### **Purpose**
Handle dormant turtles that need to be reactivated when world loads or chunks load.

### **Process**
1. World load → Read NBT → Populate bootstrap registry
2. Chunk load → Check for dormant turtles → Bootstrap if needed
3. Bootstrap → Create peripheral → Apply radius override → Resume operation

### **Data Flow**
```
NBT → remoteManagementStates → bootstrap registry → peripheral creation → active operation
```

## **Fuel System**

### **Cost Calculation**
- Base cost per chunk per tick: `BASE_FUEL_COST_PER_CHUNK`
- Distance scaling: `DISTANCE_MULTIPLIER ^ distance`
- Random tick scaling: `RANDOM_TICK_FUEL_MULTIPLIER`

### **Fuel Consumption**
- Fractional costs accumulated in `fuelDebt`
- Integer fuel consumed when `fuelDebt >= 1.0`
- Radius automatically set to 0 when fuel insufficient

## **Administrative Interface**

### **Configuration Commands**
- `/ccchunkloader get <key>` - Get config value
- `/ccchunkloader set <key> <value>` - Set config value
- `/ccchunkloader list` - Show all config values

### **Debug Commands**
- `/ccchunkloader debug uuids` - List all tracked UUIDs by computer
- `/ccchunkloader debug computer <id>` - Show UUIDs for specific computer
- `/ccchunkloader debug orphans` - List orphaned UUIDs
- `/ccchunkloader debug purge <uuid>` - Force remove UUID
- `/ccchunkloader debug stats` - Show tracking statistics

## **Key Design Principles**

1. **Permanent Persistence** - Turtle data never lost due to dormancy
2. **Computer ID Lifecycle** - UUIDs tied to computer IDs, not positions
3. **Safe Cleanup** - Only remove UUIDs when turtle loaded and equipment confirmed
4. **Multiple Peripherals** - Support up to 2 chunkloaders per turtle
5. **Graceful Timing** - Handle ServerComputer availability timing issues
6. **Admin Transparency** - Comprehensive debug commands for troubleshooting