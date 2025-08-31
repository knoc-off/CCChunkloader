package ccchunkloader.niko.ink;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.world.PersistentState;
import net.minecraft.world.World;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Persistent state wrapper for ChunkManager serialization.
 * Stores complete ChunkManager state per world dimension.
 * Ensures no turtle is ever lost - even those with radius=0.
 */
public class ChunkManagerPersistentState extends PersistentState {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkManagerPersistentState.class);

    private NbtCompound chunkManagerData = new NbtCompound();

    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        if (!chunkManagerData.isEmpty()) {
            nbt.put("chunkManagerData", chunkManagerData);
        }
        return nbt;
    }

    public static ChunkManagerPersistentState createFromNbt(NbtCompound nbt) {
        ChunkManagerPersistentState state = new ChunkManagerPersistentState();
        if (nbt.contains("chunkManagerData")) {
            state.chunkManagerData = nbt.getCompound("chunkManagerData");
        }
        return state;
    }

    /**
     * Get or create the ChunkManager persistent state for a world
     */
    public static ChunkManagerPersistentState getWorldState(ServerWorld world) {
        String modId = "ccchunkloader_chunkmanager_" + world.getRegistryKey().getValue().toString().replace(":", "_");

        ChunkManagerPersistentState state = world.getPersistentStateManager()
            .getOrCreate(ChunkManagerPersistentState::createFromNbt, ChunkManagerPersistentState::new, modId);

        state.markDirty(); // Ensure it gets saved
        return state;
    }

    /**
     * Save ChunkManager state data
     */
    public void saveChunkManagerData(NbtCompound data) {
        this.chunkManagerData = data;
        markDirty();
    }

    /**
     * Get saved ChunkManager state data
     */
    public NbtCompound getChunkManagerData() {
        return chunkManagerData;
    }

    /**
     * Check if we have saved data
     */
    public boolean hasData() {
        return !chunkManagerData.isEmpty();
    }

    /**
     * Clear saved data
     */
    public void clearData() {
        chunkManagerData = new NbtCompound();
        markDirty();
    }
}
