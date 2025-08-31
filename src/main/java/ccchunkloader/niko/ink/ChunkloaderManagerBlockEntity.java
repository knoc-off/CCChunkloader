package ccchunkloader.niko.ink;

import dan200.computercraft.api.peripheral.IPeripheral;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Block Entity for the Chunkloader Manager block.
 * Provides peripheral capability to CC:Tweaked computers.
 */
public class ChunkloaderManagerBlockEntity extends BlockEntity {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkloaderManagerBlockEntity.class);

    private ChunkloaderManagerPeripheral peripheral;

    public ChunkloaderManagerBlockEntity(BlockPos pos, BlockState state) {
        super(CCChunkloader.CHUNKLOADER_MANAGER_BLOCK_ENTITY, pos, state);
    }

    /**
     * Get peripheral for the specified side
     */
    @Nullable
    public IPeripheral getPeripheral(Direction side) {
        if (peripheral == null) {
            peripheral = new ChunkloaderManagerPeripheral(this.world);
        }
        return peripheral;
    }

}
