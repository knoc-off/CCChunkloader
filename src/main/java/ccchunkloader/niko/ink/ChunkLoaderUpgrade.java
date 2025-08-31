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

import java.util.UUID;

public class ChunkLoaderUpgrade extends AbstractTurtleUpgrade {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkLoaderUpgrade.class);
    private static final String TURTLE_UUID_KEY = "TurtleUUID";

    public ChunkLoaderUpgrade(Identifier id, ItemStack stack) {
        super(id, TurtleUpgradeType.PERIPHERAL, stack);
    }

    @Override
    public IPeripheral createPeripheral(ITurtleAccess turtle, TurtleSide side) {
        ChunkLoaderPeripheral peripheral = new ChunkLoaderPeripheral(turtle, side);

        // Check if this turtle should bootstrap its chunk loading from saved state
        UUID turtleId = peripheral.getTurtleId();
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
