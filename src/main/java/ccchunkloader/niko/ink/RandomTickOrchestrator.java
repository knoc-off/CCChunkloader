package ccchunkloader.niko.ink;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.FluidState;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.GameRules;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.ChunkSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Orchestrates random ticking for turtle-loaded chunks using public APIs.
 * Respects GameRules.RANDOM_TICK_SPEED and applies configurable budgets.
 */
public class RandomTickOrchestrator {
    private static final Logger LOGGER = LoggerFactory.getLogger(RandomTickOrchestrator.class);

    // Configuration constants
    private static final int MAX_CHUNKS_PER_WORLD_PER_TICK = 2048; // Global budget per world per tick.
    private static final boolean DEBUG_LOGGING = false; // Set to true for detailed debug logs

    private static RandomTickOrchestrator instance;

    private RandomTickOrchestrator() {
        // Private constructor for singleton
    }

    public static RandomTickOrchestrator getInstance() {
        if (instance == null) {
            instance = new RandomTickOrchestrator();
        }
        return instance;
    }

    /**
     * Initialize the orchestrator by registering server tick events
     */
    public void initialize() {
        ServerTickEvents.END_WORLD_TICK.register(this::onWorldTick);
        LOGGER.info("RandomTickOrchestrator initialized with budget: {} chunks per world per tick",
                   MAX_CHUNKS_PER_WORLD_PER_TICK);
    }

    /**
     * Handle world tick event - apply random ticks to turtle chunks
     */
    private void onWorldTick(ServerWorld world) {
        // Get the random tick speed from game rules
        int randomTickSpeed = world.getGameRules().getInt(GameRules.RANDOM_TICK_SPEED);
        if (randomTickSpeed <= 0) {
            return; // Random ticks disabled globally
        }

        // Get all active turtle chunk loaders with random ticks enabled for this world
        Map<UUID, ChunkLoaderPeripheral> allPeripherals = ChunkLoaderRegistry.getAllPeripherals();
        List<ChunkLoaderPeripheral> randomTickLoaders = new ArrayList<>();

        for (ChunkLoaderPeripheral loader : allPeripherals.values()) {
            // Only process loaders in this world and with random ticks enabled
            if (loader.getWorldKey() != null &&
                loader.getWorldKey().equals(world.getRegistryKey()) &&
                loader.isRandomTickEnabled()) {
                randomTickLoaders.add(loader);
            }
        }

        if (randomTickLoaders.isEmpty()) {
            return; // No random tick loaders in this world
        }

        // Apply budget: gather all chunks from all loaders, then limit total
        Set<ChunkPos> allRandomTickChunks = java.util.concurrent.ConcurrentHashMap.newKeySet();
        for (ChunkLoaderPeripheral loader : randomTickLoaders) {
            allRandomTickChunks.addAll(loader.getLoadedChunks());
        }

        if (allRandomTickChunks.isEmpty()) {
            return; // No chunks to tick
        }

        // Apply global budget limit
        int chunksToTick = Math.min(allRandomTickChunks.size(), MAX_CHUNKS_PER_WORLD_PER_TICK);

        if (DEBUG_LOGGING) {
            LOGGER.debug("World {} random tick: {} loaders, {} total chunks, ticking {} chunks (speed={})",
                        world.getRegistryKey().getValue(), randomTickLoaders.size(),
                        allRandomTickChunks.size(), chunksToTick, randomTickSpeed);
        }

        // Convert to array for indexed access and apply budget
        ChunkPos[] chunkArray = allRandomTickChunks.toArray(new ChunkPos[0]);

        for (int i = 0; i < chunksToTick; i++) {
            ChunkPos chunkPos = chunkArray[i];

            // Ensure chunk is loaded before ticking
            if (!world.isChunkLoaded(chunkPos.x, chunkPos.z)) {
                continue;
            }

            WorldChunk chunk = world.getChunk(chunkPos.x, chunkPos.z);
            applyRandomTicksToChunk(world, chunk, randomTickSpeed);
        }
    }

    /**
     * Apply random ticks to a specific chunk using public APIs
     */
    private void applyRandomTicksToChunk(ServerWorld world, WorldChunk chunk, int randomTickSpeed) {
        ChunkSection[] sections = chunk.getSectionArray();

        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            ChunkSection section = sections[sectionIndex];

            // Skip empty sections or sections that don't need random ticks
            if (section == null || section.isEmpty() || !section.hasRandomTicks()) {
                continue;
            }

            // Calculate section Y coordinate
            int sectionY = chunk.sectionIndexToCoord(sectionIndex);
            int baseY = sectionY << 4; // Convert section Y to block Y

            // Apply random ticks to this section
            for (int tick = 0; tick < randomTickSpeed; tick++) {
                // Pick random position within the chunk section
                int localX = world.random.nextInt(16);
                int localY = world.random.nextInt(16);
                int localZ = world.random.nextInt(16);

                int worldX = (chunk.getPos().x << 4) + localX;
                int worldY = baseY + localY;
                int worldZ = (chunk.getPos().z << 4) + localZ;

                BlockPos pos = new BlockPos(worldX, worldY, worldZ);

                // Apply random tick to block
                BlockState blockState = section.getBlockState(localX, localY, localZ);
                if (blockState.hasRandomTicks()) {
                    try {
                        blockState.randomTick(world, pos, world.random);
                    } catch (Exception e) {
                        if (DEBUG_LOGGING) {
                            LOGGER.warn("Error during block random tick at {}: {}", pos, e.getMessage());
                        }
                    }
                }

                // Apply random tick to fluid
                FluidState fluidState = section.getFluidState(localX, localY, localZ);
                if (fluidState.hasRandomTicks()) {
                    try {
                        fluidState.onRandomTick(world, pos, world.random);
                    } catch (Exception e) {
                        if (DEBUG_LOGGING) {
                            LOGGER.warn("Error during fluid random tick at {}: {}", pos, e.getMessage());
                        }
                    }
                }
            }
        }

        if (DEBUG_LOGGING) {
            LOGGER.debug("Applied {} random ticks to chunk {}", randomTickSpeed, chunk.getPos());
        }
    }

    /**
     * Get current configuration for debugging
     */
    public String getConfigInfo() {
        return String.format("RandomTickOrchestrator: max_chunks_per_world=%d, debug=%b",
                           MAX_CHUNKS_PER_WORLD_PER_TICK, DEBUG_LOGGING);
    }
}
