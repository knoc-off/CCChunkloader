package ccchunkloader.niko.ink;

import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.turtle.AbstractTurtleUpgrade;
import dan200.computercraft.api.turtle.ITurtleAccess;
import dan200.computercraft.api.turtle.TurtleSide;
import dan200.computercraft.api.turtle.TurtleUpgradeType;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.UUID;

public class ChunkLoaderUpgrade extends AbstractTurtleUpgrade {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkLoaderUpgrade.class);
    private static final String TURTLE_UUID_KEY = "TurtleUUID";

    public ChunkLoaderUpgrade(Identifier id, ItemStack stack) {
        super(id, TurtleUpgradeType.PERIPHERAL, stack);
    }

    @Override
    public IPeripheral createPeripheral(ITurtleAccess turtle, TurtleSide side) {
        // Get the UUID for this peripheral - this will create one if it doesn't exist
        UUID turtleId = getTurtleUUID(turtle, side);
        
        // Check if we need to clean up an old UUID from this turtle position
        if (turtle.getLevel() instanceof ServerWorld serverWorld) {
            cleanupOldTurtleUUIDs(turtle, side, turtleId, serverWorld);
        }
        
        ChunkLoaderPeripheral peripheral = new ChunkLoaderPeripheral(turtle, side);

        // Check if this turtle should bootstrap its chunk loading from saved state
        ChunkLoaderRegistry.BootstrapData bootstrapData = ChunkLoaderRegistry.getBootstrapData(turtleId);

        if (bootstrapData != null && bootstrapData.wakeOnWorldLoad && turtle.getFuelLevel() >= bootstrapData.lastKnownFuelLevel) {
            LOGGER.info("Bootstrapping turtle {} chunk loading on wakeup", turtleId);

            // Clear bootstrap data since turtle is now active
            ChunkLoaderRegistry.clearBootstrapData(turtleId);

            // The peripheral will automatically restore its state from upgrade NBT when created
            // If it had chunk loading enabled, it will resume on the next update tick
        }

        return peripheral;
    }
    
    /**
     * Clean up old turtle UUIDs that might be at this position from previous peripheral attachments
     * This handles the case where a turtle peripheral is unequipped and re-equipped
     */
    private void cleanupOldTurtleUUIDs(ITurtleAccess turtle, TurtleSide side, UUID currentUUID, ServerWorld serverWorld) {
        // Check if there are any other turtle UUIDs that might be associated with this turtle position
        // that are different from the current UUID (indicating peripheral was re-equipped)
        
        ChunkManager chunkManager = ChunkManager.get(serverWorld);
        
        // Get all tracked turtles and check if any are at the same position with different UUIDs
        Set<UUID> trackedTurtles = chunkManager.getRestoredTurtleIds();
        
        for (UUID trackedId : trackedTurtles) {
            if (!trackedId.equals(currentUUID)) {
                ChunkLoaderPeripheral.SavedState state = chunkManager.getCachedTurtleState(trackedId);
                if (state != null && state.lastChunkPos != null) {
                    // Check if this old turtle was at the same position as the current turtle
                    if (state.lastChunkPos.equals(new net.minecraft.util.math.ChunkPos(turtle.getPosition()))) {
                        LOGGER.info("Found old turtle UUID {} at current position, permanently removing (replaced by {})", 
                                   trackedId, currentUUID);
                        
                        // Permanently remove the old turtle data
                        ChunkManager.permanentlyRemoveTurtleFromWorld(serverWorld, trackedId);
                        ChunkLoaderRegistry.permanentlyRemoveTurtle(trackedId);
                    }
                }
            }
        }
    }

    @Override
    public void update(ITurtleAccess turtle, TurtleSide side) {
        // Only update on server side
        if (!(turtle.getLevel() instanceof ServerWorld)) return;

        IPeripheral peripheral = turtle.getPeripheral(side);
        if (peripheral instanceof ChunkLoaderPeripheral chunkLoaderPeripheral) {
            chunkLoaderPeripheral.updateChunkLoading();
        }
    }

    /**
     * Get or create the persistent UUID for this turtle upgrade
     */
    public static UUID getTurtleUUID(ITurtleAccess turtle, TurtleSide side) {
        NbtCompound upgradeData = turtle.getUpgradeNBTData(side);

        UUID uuid;
        if (upgradeData.containsUuid(TURTLE_UUID_KEY)) {
            uuid = upgradeData.getUuid(TURTLE_UUID_KEY);
            LOGGER.debug("Retrieved existing UUID from upgrade NBT: {}", uuid);
        } else {
            uuid = UUID.randomUUID();
            upgradeData.putUuid(TURTLE_UUID_KEY, uuid);
            turtle.updateUpgradeNBTData(side);
            LOGGER.info("Generated new UUID and saved to upgrade NBT: {}", uuid);
        }

        return uuid;
    }

    /**
     * Store the turtle UUID in upgrade NBT
     */
    public static void setTurtleUUID(ITurtleAccess turtle, TurtleSide side, UUID uuid) {
        NbtCompound upgradeData = turtle.getUpgradeNBTData(side);
        upgradeData.putUuid(TURTLE_UUID_KEY, uuid);
        turtle.updateUpgradeNBTData(side);
        LOGGER.info("Set UUID in upgrade NBT: {}", uuid);
    }

    @Override
    public NbtCompound getPersistedData(NbtCompound data) {
        // Persist all upgrade data when the turtle is picked up
        // This ensures the entire state is carried over to the turtle item
        NbtCompound persistedData = data.copy();

        if (data.containsUuid(TURTLE_UUID_KEY)) {
            LOGGER.debug("Persisting turtle UUID to item NBT: {}", data.getUuid(TURTLE_UUID_KEY));
        }

        return persistedData;
    }
}
