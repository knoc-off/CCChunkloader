package ccchunkloader.niko.ink;

import net.minecraft.block.Block;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.HorizontalFacingBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.PickaxeItem;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

/**
 * Chunkloader Manager block - allows remote control of turtle chunk loaders
 */
public class ChunkloaderManagerBlock extends HorizontalFacingBlock implements BlockEntityProvider {

    public static final DirectionProperty FACING = HorizontalFacingBlock.FACING;

    public ChunkloaderManagerBlock(Settings settings) {
        super(settings.nonOpaque());
        this.setDefaultState(this.stateManager.getDefaultState().with(FACING, Direction.NORTH));
    }

    @Nullable
    @Override
    public BlockState getPlacementState(ItemPlacementContext ctx) {
        return this.getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new ChunkloaderManagerBlockEntity(pos, state);
    }

    @Override
    public float calcBlockBreakingDelta(BlockState state, net.minecraft.entity.player.PlayerEntity player, net.minecraft.world.BlockView world, BlockPos pos) {
        if (player.getMainHandStack().getItem() instanceof net.minecraft.item.PickaxeItem) {
            return super.calcBlockBreakingDelta(state, player, world, pos) * 4.0f;
        }
        // Normal breaking speed for other tools
        return super.calcBlockBreakingDelta(state, player, world, pos);
    }
}
