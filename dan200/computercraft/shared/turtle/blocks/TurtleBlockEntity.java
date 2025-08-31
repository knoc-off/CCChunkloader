// Copyright Daniel Ratcliffe, 2011-2022. Do not distribute without permission.
//
// SPDX-License-Identifier: LicenseRef-CCPL

package dan200.computercraft.shared.turtle.blocks;

import com.mojang.authlib.GameProfile;
import dan200.computercraft.api.component.ComputerComponents;
import dan200.computercraft.api.peripheral.IPeripheral;
import dan200.computercraft.api.turtle.ITurtleAccess;
import dan200.computercraft.api.turtle.ITurtleUpgrade;
import dan200.computercraft.api.turtle.TurtleSide;
import dan200.computercraft.core.computer.ComputerSide;
import dan200.computercraft.shared.computer.blocks.AbstractComputerBlockEntity;
import dan200.computercraft.shared.computer.blocks.ComputerPeripheral;
import dan200.computercraft.shared.computer.core.ComputerFamily;
import dan200.computercraft.shared.computer.core.ComputerState;
import dan200.computercraft.shared.computer.core.ServerComputer;
import dan200.computercraft.shared.config.Config;
import dan200.computercraft.shared.container.BasicContainer;
import dan200.computercraft.shared.turtle.core.TurtleBrain;
import dan200.computercraft.shared.turtle.inventory.TurtleMenu;
import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.function.IntSupplier;
import net.minecraft.class_1262;
import net.minecraft.class_1263;
import net.minecraft.class_1657;
import net.minecraft.class_1661;
import net.minecraft.class_1703;
import net.minecraft.class_1799;
import net.minecraft.class_2338;
import net.minecraft.class_2350;
import net.minecraft.class_2371;
import net.minecraft.class_243;
import net.minecraft.class_2487;
import net.minecraft.class_2591;
import net.minecraft.class_2680;
import net.minecraft.class_2960;
import net.minecraft.class_3218;

public class TurtleBlockEntity extends AbstractComputerBlockEntity implements BasicContainer {
    public static final int INVENTORY_SIZE = 16;
    public static final int INVENTORY_WIDTH = 4;
    public static final int INVENTORY_HEIGHT = 4;

    enum MoveState {
        NOT_MOVED,
        IN_PROGRESS,
        MOVED
    }

    private final class_2371<class_1799> inventory = class_2371.method_10213(INVENTORY_SIZE, class_1799.field_8037);
    private final class_2371<class_1799> inventorySnapshot = class_2371.method_10213(INVENTORY_SIZE, class_1799.field_8037);
    private boolean inventoryChanged = false;

    private final IntSupplier fuelLimit;

    private TurtleBrain brain = new TurtleBrain(this);
    private MoveState moveState = MoveState.NOT_MOVED;
    private @Nullable IPeripheral peripheral;
    private @Nullable Runnable onMoved;

    public TurtleBlockEntity(class_2591<? extends TurtleBlockEntity> type, class_2338 pos, class_2680 state, IntSupplier fuelLimit, ComputerFamily family) {
        super(type, pos, state, family);
        this.fuelLimit = fuelLimit;
    }

    boolean hasMoved() {
        return moveState == MoveState.MOVED;
    }

    @Override
    protected ServerComputer createComputer(int id) {
        var computer = new ServerComputer((class_3218) method_10997(), method_11016(), ServerComputer.properties(id, getFamily())
            .label(getLabel())
            .terminalSize(Config.TURTLE_TERM_WIDTH, Config.TURTLE_TERM_HEIGHT)
            .addComponent(ComputerComponents.TURTLE, brain)
        );
        brain.setupComputer(computer);
        return computer;
    }

    @Override
    protected void unload() {
        if (!hasMoved()) super.unload();
    }

    @Override
    protected int getInteractRange() {
        return class_1263.field_42619 + 4;
    }

    @Override
    protected void serverTick() {
        super.serverTick();
        brain.update();
        if (inventoryChanged) {
            var computer = getServerComputer();
            if (computer != null) computer.queueEvent("turtle_inventory");
            inventoryChanged = false;
        }
    }

    protected void clientTick() {
        brain.update();
    }

    @Override
    protected void updateBlockState(ComputerState newState) {
    }

    @Override
    public void neighborChanged(class_2338 neighbour) {
        if (moveState == MoveState.NOT_MOVED) super.neighborChanged(neighbour);
    }

    public void notifyMoveStart() {
        if (moveState == MoveState.NOT_MOVED) moveState = MoveState.IN_PROGRESS;
    }

    public void notifyMoveEnd() {
        // MoveState.MOVED is final
        if (moveState == MoveState.IN_PROGRESS) moveState = MoveState.NOT_MOVED;
    }

    @Override
    public void loadServer(class_2487 nbt) {
        super.loadServer(nbt);

        // Read inventory
        class_1262.method_5429(nbt, inventory);
        for (var i = 0; i < inventory.size(); i++) inventorySnapshot.set(i, inventory.get(i).method_7972());

        // Read state
        brain.readFromNBT(nbt);
    }

    @Override
    public void method_11007(class_2487 nbt) {
        // Write inventory
        class_1262.method_5426(nbt, inventory);

        // Write brain
        nbt = brain.writeToNBT(nbt);

        super.method_11007(nbt);
    }

    @Override
    protected boolean isPeripheralBlockedOnSide(ComputerSide localSide) {
        return hasPeripheralUpgradeOnSide(localSide);
    }

    @Override
    public class_2350 getDirection() {
        return method_11010().method_11654(TurtleBlock.field_11177);
    }

    public void setDirection(class_2350 dir) {
        if (dir.method_10166() == class_2350.class_2351.field_11052) dir = class_2350.field_11043;
        method_10997().method_8501(field_11867, method_11010().method_11657(TurtleBlock.field_11177, dir));

        updateRedstone();
        updateInputsImmediately();

        onTileEntityChange();
    }

    public @Nullable ITurtleUpgrade getUpgrade(TurtleSide side) {
        return brain.getUpgrade(side);
    }

    public int getColour() {
        return brain.getColour();
    }

    public @Nullable class_2960 getOverlay() {
        return brain.getOverlay();
    }

    public ITurtleAccess getAccess() {
        return brain;
    }

    public class_243 getRenderOffset(float f) {
        return brain.getRenderOffset(f);
    }

    public float getRenderYaw(float f) {
        return brain.getVisualYaw(f);
    }

    public float getToolRenderAngle(TurtleSide side, float f) {
        return brain.getToolRenderAngle(side, f);
    }

    void setOwningPlayer(GameProfile player) {
        brain.setOwningPlayer(player);
        onTileEntityChange();
    }

    // IInventory

    @Override
    public class_2371<class_1799> getContents() {
        return inventory;
    }

    public class_1799 getItemSnapshot(int slot) {
        return slot >= 0 && slot < inventorySnapshot.size() ? inventorySnapshot.get(slot) : class_1799.field_8037;
    }

    @Override
    public void method_5431() {
        super.method_5431();

        for (var slot = 0; slot < method_5439(); slot++) {
            var item = method_5438(slot);
            if (class_1799.method_7973(item, inventorySnapshot.get(slot))) continue;

            inventoryChanged = true;
            inventorySnapshot.set(slot, item.method_7972());
        }
    }

    @Override
    public boolean method_5443(class_1657 player) {
        return isUsable(player);
    }

    public void onTileEntityChange() {
        super.method_5431();
    }

    // Networking stuff

    @Override
    public class_2487 method_16887() {
        var nbt = super.method_16887();
        brain.writeDescription(nbt);
        return nbt;
    }

    @Override
    public void loadClient(class_2487 nbt) {
        super.loadClient(nbt);
        brain.readDescription(nbt);
    }

    // Privates

    public int getFuelLimit() {
        return fuelLimit.getAsInt();
    }

    private boolean hasPeripheralUpgradeOnSide(ComputerSide side) {
        ITurtleUpgrade upgrade;
        switch (side) {
            case RIGHT:
                upgrade = getUpgrade(TurtleSide.RIGHT);
                break;
            case LEFT:
                upgrade = getUpgrade(TurtleSide.LEFT);
                break;
            default:
                return false;
        }
        return upgrade != null && upgrade.getType().isPeripheral();
    }

    public void transferStateFrom(TurtleBlockEntity copy) {
        super.transferStateFrom(copy);
        Collections.copy(inventory, copy.inventory);
        Collections.copy(inventorySnapshot, copy.inventorySnapshot);
        inventoryChanged = copy.inventoryChanged;
        brain = copy.brain;
        brain.setOwner(this);

        // Mark the other turtle as having moved, and so its peripheral is dead.
        copy.moveState = MoveState.MOVED;
        if (onMoved != null) onMoved.run();
    }

    @Nullable
    public IPeripheral peripheral() {
        if (hasMoved()) return null;
        if (peripheral != null) return peripheral;
        return peripheral = new ComputerPeripheral("turtle", this);
    }

    public void onMoved(Runnable onMoved) {
        this.onMoved = onMoved;
    }

    @Nullable
    @Override
    public class_1703 createMenu(int id, class_1661 inventory, class_1657 player) {
        return TurtleMenu.ofBrain(id, inventory, brain);
    }
}
