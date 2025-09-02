package ccchunkloader.niko.ink;

import net.minecraft.util.math.ChunkPos;

/**
 * Data class for saving/loading turtle chunk loader state.
 * Used for persistence across world load/unload cycles.
 */
public class ChunkLoaderState {
    public final double radius;
    public final ChunkPos lastChunkPos;
    public final double fuelDebt;
    public final boolean wakeOnWorldLoad;
    public final boolean randomTickEnabled;
    public final int fuelLevel;

    public ChunkLoaderState(double radius, ChunkPos lastChunkPos, double fuelDebt, boolean wakeOnWorldLoad, boolean randomTickEnabled, int fuelLevel) {
        this.radius = radius;
        this.lastChunkPos = lastChunkPos;
        this.fuelDebt = fuelDebt;
        this.wakeOnWorldLoad = wakeOnWorldLoad;
        this.randomTickEnabled = randomTickEnabled;
        this.fuelLevel = fuelLevel;
    }
}