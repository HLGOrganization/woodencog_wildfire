package net.chauvedev.woodencog.block.tunnelheater;

import com.simibubi.create.content.kinetics.belt.behaviour.BeltProcessingBehaviour;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;

/**
 * The belt hook of a tunnel heater, and only that: a {@link BeltProcessingBehaviour} whose two callbacks
 * are the same answer given twice, wired up in one place.
 *
 * <p>It exists as its own class for one reason, and it is not style. A tunnel heater sits one block above
 * the belt - it is a tunnel the belt runs through, the same way a brass tunnel sits - and Create looks for
 * a {@code BeltProcessingBehaviour} <em>two</em> blocks above the belt, because its own machines (the
 * deployer, the press, the saw) are placed with the working gap left free below them. So the behaviour of a
 * tunnel heater is not found by Create at all.
 *
 * <p>{@code MixinBeltInventory} teaches the belt's own lookup to also look one block lower, but only for
 * this type. That is what the subclass is for: it is the exact, checkable answer to "is the behaviour
 * standing in the belt's gap one of ours?", so the patch can never hand Create a behaviour belonging to
 * somebody else's block. Hanging a plain {@code BeltProcessingBehaviour} here would make that check
 * impossible to write.
 *
 * <p>The callbacks resolve the same way they do on Create's own machines: the belt asks, every tick, and
 * this block answers {@code HOLD} to keep the item under it or {@code PASS} to let the belt carry on.
 */
public class TunnelHeaterProcessingBehaviour extends BeltProcessingBehaviour {

    /**
     * @param be       the tunnel heater this belongs to
     * @param callback the one question, asked both when an item arrives and on every tick while it is held
     */
    public TunnelHeaterProcessingBehaviour(SmartBlockEntity be, ProcessingCallback callback) {
        super(be);
        this.whenItemEnters(callback);
        this.whileItemHeld(callback);
    }
}
