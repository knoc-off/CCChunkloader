package ccchunkloader.niko.ink;

import net.minecraft.util.math.BlockPos;

import java.util.UUID;

/**
 * Data class for turtle information used in remote monitoring and management.
 */
public class TurtleInfo {
    public final UUID turtleId;
    public final BlockPos position;
    public final int fuelLevel;
    public final double radius;
    public final int loadedChunks;
    public final double fuelRate;

    public TurtleInfo(UUID turtleId, BlockPos position, int fuelLevel, double radius, int loadedChunks, double fuelRate) {
        this.turtleId = turtleId;
        this.position = position;
        this.fuelLevel = fuelLevel;
        this.radius = radius;
        this.loadedChunks = loadedChunks;
        this.fuelRate = fuelRate;
    }
}