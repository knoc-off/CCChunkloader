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

import java.util.HashSet;
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
     * Clean up old turtle UUIDs using computer ID-based validation
     * This handles the case where a turtle peripheral is unequipped and re-equipped
     */
    private void cleanupOldTurtleUUIDs(ITurtleAccess turtle, TurtleSide side, UUID currentUUID, ServerWorld serverWorld) {
        try {
            // Get the turtle's computer ID
            var blockEntity = serverWorld.getBlockEntity(turtle.getPosition());
            if (!(blockEntity instanceof dan200.computercraft.shared.turtle.blocks.TurtleBlockEntity turtleEntity)) {
                LOGGER.warn("Cannot cleanup UUIDs - block entity is not a TurtleBlockEntity");
                return;
            }

            var computer = turtleEntity.getServerComputer();
            if (computer == null) {
                LOGGER.warn("Cannot cleanup UUIDs - no ServerComputer instance");
                return;
            }

            int computerId = computer.getID();
            ChunkManager chunkManager = ChunkManager.get(serverWorld);
            
            // Get all UUIDs currently stored for this computer
            Set<UUID> storedUUIDs = chunkManager.getUUIDsForComputer(computerId);
            
            // Detect currently equipped chunkloaders
            Set<UUID> currentlyEquippedUUIDs = new HashSet<>();
            
            // Check LEFT side
            var leftUpgrade = turtleEntity.getUpgrade(TurtleSide.LEFT);
            if (leftUpgrade instanceof ChunkLoaderUpgrade) {
                UUID leftUUID = getTurtleUUID(turtle, TurtleSide.LEFT);
                currentlyEquippedUUIDs.add(leftUUID);
            }
            
            // Check RIGHT side  
            var rightUpgrade = turtleEntity.getUpgrade(TurtleSide.RIGHT);
            if (rightUpgrade instanceof ChunkLoaderUpgrade) {
                UUID rightUUID = getTurtleUUID(turtle, TurtleSide.RIGHT);
                currentlyEquippedUUIDs.add(rightUUID);
            }

            LOGGER.info("Computer {} cleanup: stored UUIDs={}, currently equipped={}", 
                       computerId, storedUUIDs.size(), currentlyEquippedUUIDs.size());

            // Remove any stored UUIDs that aren't currently equipped
            Set<UUID> toRemove = new HashSet<>(storedUUIDs);
            toRemove.removeAll(currentlyEquippedUUIDs);
            
            for (UUID orphanedUUID : toRemove) {
                if (!orphanedUUID.equals(currentUUID)) { // Don't remove the UUID we're currently creating
                    LOGGER.info("Computer {} cleanup: removing orphaned UUID {} (no longer equipped)", 
                               computerId, orphanedUUID);
                    ChunkManager.permanentlyRemoveTurtleFromWorld(serverWorld, orphanedUUID);
                    ChunkLoaderRegistry.permanentlyRemoveTurtle(orphanedUUID);
                }
            }
            
        } catch (Exception e) {
            LOGGER.error("Failed to cleanup UUIDs for turtle {}: {}", currentUUID, e.getMessage());
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
