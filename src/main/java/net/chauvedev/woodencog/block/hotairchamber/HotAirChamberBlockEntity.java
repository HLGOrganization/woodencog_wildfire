package net.chauvedev.woodencog.block.hotairchamber;

import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.chauvedev.woodencog.compat.create.IAirHeated;
import net.chauvedev.woodencog.config.WoodenCogCommonConfigs;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.templates.FluidTank;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The fluid tank of a hot air chamber.
 *
 * <p>The chamber is a heat source for a Create fan: the air the fan draws through it comes out at the
 * temperature the config gives the fluid in this tank, and the tank is drawn down while the fan runs.
 * Everything about that is asked of this block entity by {@code HotAirChamberHeat} on the fan's side;
 * the entity itself only holds the fluid, hands it to pipes and buckets through the fluid capability
 * and takes the millibuckets the fan consumed.
 *
 * <p>An empty tank, a fluid that is not in the config table and a fluid the table lists at 0 (water)
 * are all the same thing to a fan: {@link #getAirTemperature()} is 0, nothing is consumed, and the
 * airflow keeps whatever type it would have had without the chamber.
 *
 * <p>The three methods the fan side asks for are the {@link IAirHeated} contract; that interface was
 * extracted from this class and {@link #consumptionPerTick()} answers with the very config value that
 * used to be read at the call site, so nothing about the chamber changed when the fan chain stopped
 * naming this class.
 */
public class HotAirChamberBlockEntity extends SmartBlockEntity implements IAirHeated {

    /** The single tank of the chamber. A full one feeds a fan for about 10000 seconds by default. */
    public static final int TANK_CAPACITY = 2000;

    private static final String TANK_KEY = "Tank";
    private static final String PENDING_CONSUMPTION_KEY = "PendingConsumption";

    private final FluidTank tank = new FluidTank(TANK_CAPACITY) {
        @Override
        protected void onContentsChanged() {
            HotAirChamberBlockEntity.this.onTankChanged();
        }
    };

    /** The chamber is filled and emptied from every side, so every side gets the same tank. */
    private final LazyOptional<IFluidHandler> tankHandler = LazyOptional.of(() -> this.tank);

    /**
     * Millibuckets owed to the fan that are not yet taken out of the tank. A tank counts whole
     * millibuckets and the configured rate is a fraction of one per tick, so the fraction is
     * collected here until a whole millibucket is due.
     */
    private double pendingConsumption;

    public HotAirChamberBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        // A plain tank needs no behaviour: nothing to add.
    }

    /**
     * What the fluid in the tank heats a fan's air to, or 0 when the chamber heats nothing at all -
     * an empty tank, an unknown fluid, or a fluid the config table lists at 0 such as water.
     */
    @Override
    public float getAirTemperature() {
        FluidStack fluid = this.tank.getFluid();
        if (fluid.isEmpty()) {
            return 0.0F;
        }
        ResourceLocation fluidId = ForgeRegistries.FLUIDS.getKey(fluid.getFluid());
        return fluidId == null ? 0.0F : WoodenCogCommonConfigs.hotAirChamberTemperature(fluidId);
    }

    /** Whether a fan drawing its air through this chamber would pick up any heat at all. */
    @Override
    public boolean isHeating() {
        return this.getAirTemperature() > 0.0F;
    }

    /**
     * Takes {@code millibuckets} out of the tank. Called by the fan that is running on this chamber,
     * once per tick it turns; a stopped fan, an empty chamber and a chamber holding a fluid that does
     * not heat never get here, so nothing is lost while the chamber is idle.
     */
    @Override
    public void consume(double millibuckets) {
        if (millibuckets <= 0.0D) {
            return;
        }
        this.pendingConsumption += millibuckets;
        int due = (int) this.pendingConsumption;
        if (due <= 0) {
            return;
        }
        int drained = this.tank.drain(due, IFluidHandler.FluidAction.EXECUTE).getAmount();
        // A tank that could not pay the whole amount has run dry: nothing is owed from here on.
        this.pendingConsumption = drained >= due ? this.pendingConsumption - due : 0.0D;
    }

    /** What one tick of a fan running on this chamber costs, straight from the fan's config. */
    @Override
    public double consumptionPerTick() {
        return WoodenCogCommonConfigs.fanHotAirChamberConsumption();
    }

    private void onTankChanged() {
        this.setChanged();
        // The client runs the same air current logic as the server, so it has to know how hot the
        // chamber is: the fluid decides the colour of the airflow and what it does to a player.
        this.sendData();
    }

    @Override
    protected void write(CompoundTag compound, boolean clientPacket) {
        super.write(compound, clientPacket);
        compound.put(TANK_KEY, this.tank.writeToNBT(new CompoundTag()));
        compound.putDouble(PENDING_CONSUMPTION_KEY, this.pendingConsumption);
    }

    @Override
    protected void read(CompoundTag compound, boolean clientPacket) {
        super.read(compound, clientPacket);
        this.tank.readFromNBT(compound.getCompound(TANK_KEY));
        this.pendingConsumption = compound.getDouble(PENDING_CONSUMPTION_KEY);
    }

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction side) {
        if (capability == ForgeCapabilities.FLUID_HANDLER) {
            return this.tankHandler.cast();
        }
        return super.getCapability(capability, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        this.tankHandler.invalidate();
    }
}
