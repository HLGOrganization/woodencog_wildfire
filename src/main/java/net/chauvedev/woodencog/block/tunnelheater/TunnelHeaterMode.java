package net.chauvedev.woodencog.block.tunnelheater;

import com.simibubi.create.foundation.blockEntity.behaviour.CenteredSideValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.INamedIconOptions;
import com.simibubi.create.foundation.gui.AllIcons;
import net.createmod.catnip.lang.Lang;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What a tunnel heater does with the item the belt below it is carrying.
 *
 * <p>The three modes differ only in the temperature the item is pushed towards, and in whether the
 * belt is held while that temperature is still climbing:
 *
 * <ul>
 *   <li>{@link #HEAT} is the original behaviour of the block and has not been changed: the ceiling is
 *       the item's own welding temperature, or {@code fan.no_recipe_ceiling} for an item TFC can heat
 *       but not weld. The belt is never held, so an item passes under the heater and comes out as hot
 *       as the heater could get it in the time it had.</li>
 *   <li>{@link #WORKABLE} and {@link #WELDABLE} stop the item under the heater until it has reached a
 *       temperature a little above the one TFC needs, and only then let it move on. The margin is
 *       {@code fan.tunnel_heater_target_margin}, 1.2 out of the box: TFC's anvil and welding both test
 *       {@code temperature >= getWorkingTemperature()}, so aiming slightly above the threshold is what
 *       keeps an item that cools on its way to the anvil from arriving just short.</li>
 * </ul>
 *
 * <p><b>An item with no such temperature is never held.</b> A food, a ceramic, a metal TFC only gives
 * a melting point - anything whose {@code getWorkingTemperature()} or {@code getWeldingTemperature()}
 * is 0 - can never reach a target of 0 in these two modes, so holding it would stall the belt for
 * good. Those items are not heated and not held: they travel on exactly as they would with the mode
 * off. The mode is therefore a filter on what the belt does, not a gate everything has to pass.
 *
 * <p>Which mode a heater is in is a property of the group, like the tank and the redstone answer: the
 * mode of the group's leader is the mode of every member of it. See
 * {@code TunnelHeaterBlockEntity#alignModeWithLeader()} for how the members are kept in step and
 * {@code TunnelHeaterBlockEntity#onModeChanged(TunnelHeaterMode)} for what a player turning the value
 * box does.
 */
public enum TunnelHeaterMode implements INamedIconOptions {

    /** The original behaviour: heat to the welding temperature, let the belt run. */
    HEAT(AllIcons.I_ACTIVE),

    /** Hold the item until it is a little above its working temperature. */
    WORKABLE(AllIcons.I_TARGET),

    /** Hold the item until it is a little above its welding temperature. */
    WELDABLE(AllIcons.I_CONFIRM);

    private final String translationKey;
    private final AllIcons icon;

    TunnelHeaterMode(AllIcons icon) {
        this.icon = icon;
        this.translationKey = "woodencog.tunnel_heater.mode." + Lang.asId(this.name());
    }

    @Override
    public AllIcons getIcon() {
        return this.icon;
    }

    @Override
    public String getTranslationKey() {
        return this.translationKey;
    }

    /**
     * The mode of a value box's raw value, with anything unreadable falling back to {@link #HEAT}.
     *
     * <p>Nothing should be able to produce an out of range value - {@code ScrollOptionBehaviour} clamps
     * its value to the constants of this enum - but a mode that is read on the way to holding a belt
     * must not be able to throw, and the original behaviour is the safe answer for anything unknown.
     */
    public static TunnelHeaterMode byOrdinal(int ordinal) {
        TunnelHeaterMode[] modes = values();
        return ordinal < 0 || ordinal >= modes.length ? HEAT : modes[ordinal];
    }

    /**
     * The value box a player turns to switch modes: the same Create slot a brass tunnel carries, but
     * centred on the roof of the tunnel instead of on one of its sides.
     *
     * <p>Only the top face is allowed. A tunnel heater is a tunnel over a belt with a bore at each end
     * and walls down its sides, and the roof is the one face a player can always see and click while
     * standing beside the belt; a slot on a side face would be buried in the wall in one of the two
     * belt directions. Create's own value box renderer places the box at
     * {@code CenteredSideValueBoxTransform}'s south point, {@code VecHelper.voxelSpace(8, 8, 15.5)},
     * rotated onto the side the player is looking at, which turns that south point into
     * {@code (8, 15.5, 8)} for the top face - the middle of the roof, a little above it.
     */
    public static class ModeValueBox extends CenteredSideValueBoxTransform {

        public ModeValueBox() {
            super((blockState, direction) -> direction == Direction.UP);
        }

        @Override
        protected boolean isSideActive(BlockState state, Direction direction) {
            return direction == Direction.UP;
        }
    }
}
