package ccchunkloader.niko.ink;

import dan200.computercraft.api.peripheral.PeripheralLookup;
import dan200.computercraft.api.turtle.TurtleUpgradeSerialiser;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.BuiltinRegistries;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.BlockPos;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;

@SuppressWarnings("unchecked")
public class CCChunkloader implements ModInitializer {
	public static final String MOD_ID = "ccchunkloader";
	private static final Logger LOGGER = LoggerFactory.getLogger(CCChunkloader.class);

	// New unified state management system
	private static TurtleStateManager stateManager;
	private static TurtleCommandQueue commandQueue;
	private static TurtleStateEvents eventSystem;

	public static final Item CHUNKLOADER_UPGRADE = new Item(new Item.Settings());

	// Chunkloader Manager Block
	public static final Block CHUNKLOADER_MANAGER_BLOCK = new ChunkloaderManagerBlock(
		AbstractBlock.Settings.create().strength(2.0f, 6.0f).sounds(net.minecraft.sound.BlockSoundGroup.STONE));
	public static final Item CHUNKLOADER_MANAGER_ITEM = new BlockItem(CHUNKLOADER_MANAGER_BLOCK, new Item.Settings());
	public static BlockEntityType<ChunkloaderManagerBlockEntity> CHUNKLOADER_MANAGER_BLOCK_ENTITY;

	public static final TurtleUpgradeSerialiser<ChunkLoaderUpgrade> CHUNKLOADER_UPGRADE_SERIALISER =
		TurtleUpgradeSerialiser.simpleWithCustomItem(ChunkLoaderUpgrade::new);

	@Override
	public void onInitialize() {
		// Item registration
		Registry.register(Registries.ITEM, Identifier.of(MOD_ID, "chunkloader_upgrade"), CHUNKLOADER_UPGRADE);

		// Block registration
		Registry.register(Registries.BLOCK, Identifier.of(MOD_ID, "chunkloader_manager"), CHUNKLOADER_MANAGER_BLOCK);
		Registry.register(Registries.ITEM, Identifier.of(MOD_ID, "chunkloader_manager"), CHUNKLOADER_MANAGER_ITEM);

		// Block Entity registration
		CHUNKLOADER_MANAGER_BLOCK_ENTITY = Registry.register(
			Registries.BLOCK_ENTITY_TYPE,
			Identifier.of(MOD_ID, "chunkloader_manager"),
			BlockEntityType.Builder.create(ChunkloaderManagerBlockEntity::new, CHUNKLOADER_MANAGER_BLOCK).build(null)
		);

		// Get the turtle upgrade serializer registry from the root registry
		Registry<TurtleUpgradeSerialiser<?>> turtleUpgradeSerialisers =
			(Registry<TurtleUpgradeSerialiser<?>>) Registries.REGISTRIES.get(TurtleUpgradeSerialiser.registryId().getValue());


		Registry.register(
			turtleUpgradeSerialisers,
			Identifier.of(MOD_ID, "chunkloader"),
			CHUNKLOADER_UPGRADE_SERIALISER
		);

		// Register peripheral capability for Chunkloader Manager block
		PeripheralLookup.get().registerForBlockEntity(
			(blockEntity, direction) -> {
				if (blockEntity instanceof ChunkloaderManagerBlockEntity) {
					ChunkloaderManagerBlockEntity entity = (ChunkloaderManagerBlockEntity) blockEntity;
					return entity.getPeripheral(direction);
				}
				return null;
			},
			CHUNKLOADER_MANAGER_BLOCK_ENTITY
		);

		// Add items to creative tab
		ItemGroupEvents.modifyEntriesEvent(ItemGroups.REDSTONE).register(entries -> {
			entries.add(CHUNKLOADER_UPGRADE);
			entries.add(CHUNKLOADER_MANAGER_ITEM);
		});

		// Register lifecycle events
		ServerLifecycleEvents.SERVER_STOPPING.register(this::onServerStopping);
		ServerWorldEvents.LOAD.register(this::onWorldLoad);
		ServerWorldEvents.UNLOAD.register(this::onWorldUnload);
		ServerChunkEvents.CHUNK_UNLOAD.register(this::onChunkUnload);
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> ChunkloaderCommand.register(dispatcher));

		// Initialize RandomTickOrchestrator for turtle random ticking
		RandomTickOrchestrator.getInstance().initialize();
		
		// Initialize new unified state management system
		initializeNewArchitecture();
	}


	private void onServerStopping(MinecraftServer server) {
		saveAllChunkManagerStates(server);

		ChunkManager.cleanupAll();
		ChunkLoaderRegistry.clearAll();
	}


	private void onWorldLoad(MinecraftServer server, ServerWorld world) {
        // Get the persistent state for the world.
        ChunkManagerPersistentState persistentState = ChunkManagerPersistentState.getWorldState(world);

        if (persistentState.hasData()) {
            ChunkManager chunkManager = ChunkManager.get(world);
            NbtCompound savedData = persistentState.getChunkManagerData();

            ChunkManager.DeserializationResult result = chunkManager.deserializeFromNbt(savedData);

            populateRegistryWithRestoredTurtles(world, chunkManager);

            int dormantCount = result.total - result.toWake;
            LOGGER.info("Loaded turtle states for {}: {} total ({} to wake, {} dormant).",
                        world.getRegistryKey().getValue(), result.total, result.toWake, dormantCount);
        } else {
            LOGGER.info("No saved turtle states found for dimension: {}.", world.getRegistryKey().getValue());
        }
    }

	private void onWorldUnload(MinecraftServer server, ServerWorld world) {
        saveChunkManagerState(world);
    }

	/**
	 * Clean Slate Implementation: Remove peripherals when their chunks unload
	 */
	private void onChunkUnload(ServerWorld world, net.minecraft.world.chunk.WorldChunk chunk) {
		ChunkPos chunkPos = chunk.getPos();
		
		// Find any active peripherals in this chunk and clean them up immediately
		Map<UUID, ChunkLoaderPeripheral> activePeripherals = ChunkLoaderRegistry.getAllPeripherals();
		
		for (Map.Entry<UUID, ChunkLoaderPeripheral> entry : activePeripherals.entrySet()) {
			UUID turtleId = entry.getKey();
			ChunkLoaderPeripheral peripheral = entry.getValue();
			
			// Check if this peripheral's turtle is in the unloading chunk
			if (peripheral.getTurtleLevel() == world) {
				BlockPos turtlePos = peripheral.getTurtlePosition();
				if (turtlePos != null) {
					ChunkPos turtleChunk = new ChunkPos(turtlePos);
					if (turtleChunk.equals(chunkPos)) {
						LOGGER.debug("Chunk {} unloaded, removing turtle {} peripheral", chunkPos, turtleId);
						
						// Clean up the peripheral immediately - no more zombie peripherals!
						peripheral.cleanup();
						
						// Fire unload event for coordination
						TurtleStateManager.TurtleState state = stateManager.getState(turtleId);
						eventSystem.fireEvent(new TurtleStateEvents.TurtleUnloadedEvent(turtleId, state, "chunk_unload"));
					}
				}
			}
		}
	}

	/**
     * Save ChunkManager state for a specific world and log a summary.
     */
    private void saveChunkManagerState(ServerWorld world) {
        ChunkManager chunkManager = ChunkManager.get(world);
        ChunkManagerPersistentState persistentState = ChunkManagerPersistentState.getWorldState(world);

        NbtCompound serializedData = chunkManager.serializeToNbt();
        persistentState.saveChunkManagerData(serializedData);

        int savedCount = serializedData.getList("turtleStates", 10).size();
        LOGGER.info("Saved {} turtle states for dimension: {}.", savedCount, world.getRegistryKey().getValue());
    }

	/**
	 * Save all ChunkManager states across all worlds
	 */
	private void saveAllChunkManagerStates(MinecraftServer server) {

		for (ServerWorld world : server.getWorlds()) {
			saveChunkManagerState(world);
		}

	}

	/**
	 * Populate ChunkLoaderRegistry with restored turtles from ChunkManager and selectively wake them
	 */
	private void populateRegistryWithRestoredTurtles(ServerWorld world, ChunkManager chunkManager) {
		Map<UUID, ChunkLoaderPeripheral.SavedState> restoredStates = chunkManager.getAllRestoredTurtleStates();

		int wokenCount = 0;
		for (Map.Entry<UUID, ChunkLoaderPeripheral.SavedState> entry : restoredStates.entrySet()) {
			UUID turtleId = entry.getKey();
			ChunkLoaderPeripheral.SavedState savedState = entry.getValue();

			// Check if turtle is already active (shouldn't happen)
			if (ChunkLoaderRegistry.getPeripheral(turtleId) != null) {
				continue;
			}

			// Bootstrap data is now handled directly in ChunkManager
			if (savedState.lastChunkPos != null) {

				if (savedState.wakeOnWorldLoad) {
					wokenCount++;
					LOGGER.debug("Registered turtle {} for bootstrap wake-up at position {}", 
								turtleId, savedState.lastChunkPos);
				} else {
					LOGGER.debug("Registered dormant turtle {} at position {} (wakeOnWorldLoad = false)", 
								turtleId, savedState.lastChunkPos);
				}
			} else {
				LOGGER.debug("Registered dormant turtle {} at position {} (saved: radius={}, fuelDebt={})",
						   turtleId, savedState.lastChunkPos, savedState.radius, savedState.fuelDebt);
			}
		}

		LOGGER.info("ChunkLoaderRegistry populated: {} total turtle states, {} woken, {} dormant",
				   restoredStates.size(), wokenCount, restoredStates.size() - wokenCount);

		// Force bootstrap turtles now that bootstrap data is available
		if (wokenCount > 0) {
			LOGGER.info("Triggering force-bootstrap for {} woken turtles", wokenCount);
			ChunkManager.forceBootstrap(world);
		}
	}

	/**
	 * Initialize the new unified state management architecture
	 */
	private void initializeNewArchitecture() {
		stateManager = new TurtleStateManager();
		commandQueue = new TurtleCommandQueue();
		eventSystem = new TurtleStateEvents();
		
		// Register default event handlers for automatic coordination
		DefaultTurtleEventHandlers eventHandlers = new DefaultTurtleEventHandlers(stateManager, commandQueue);
		eventHandlers.registerDefaultHandlers(eventSystem);
		
		LOGGER.info("Initialized new turtle state architecture - radius override bug fix active!");
	}
	
	// Public accessors for the new architecture
	public static TurtleStateManager getStateManager() {
		return stateManager;
	}
	
	public static TurtleCommandQueue getCommandQueue() {
		return commandQueue;
	}
	
	public static TurtleStateEvents getEventSystem() {
		return eventSystem;
	}
}
