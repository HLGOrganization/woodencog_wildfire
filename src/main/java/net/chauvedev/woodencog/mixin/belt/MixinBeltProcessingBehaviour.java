package net.chauvedev.woodencog.mixin.belt;

import com.simibubi.create.content.kinetics.belt.behaviour.BeltProcessingBehaviour;
import net.chauvedev.woodencog.block.tunnelheater.TunnelHeaterBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stops a tunnel heater from counting as an obstruction to the belt it stands on.
 *
 * <h2>Why this is needed</h2>
 * Before Create asks a machine anything, it asks whether the space above the belt is free. Verified in
 * {@code BeltInventory.handleBeltProcessingAndCheckIfRemoved}: the machine is looked up first, and if one
 * was found, then
 *
 * <pre>
 * if (BeltProcessingBehaviour.isBlocked(belt.getLevel(),
 *         BeltHelper.getPositionForOffset(belt, seg)))   // &lt;- the belt's own block, no offset
 *     continue;                                          // &lt;- the segment is skipped entirely
 * </pre>
 *
 * <p>and {@code isBlocked} itself reads the block <em>one above the position it is given</em>:
 *
 * <pre>
 * BlockState state = level.getBlockState(pos.above());
 * if (AbstractFunnelBlock.isFunnel(state)) return false;   // a funnel is allowed to be there
 * return !state.getCollisionShape(level, pos.above()).isEmpty();
 * </pre>
 *
 * <p>So the question is about the block directly above the belt - the gap an item sits in - and the answer
 * is "blocked" for anything there with a collision shape. That is exactly right for Create's own machines,
 * which need that gap clear to reach down through it. It is wrong for a tunnel heater, which is a tunnel
 * the belt runs through: its collision shape is the tunnel's own walls and roof (five sixteenths of skirt
 * reaching into the belt block, and a roof over the bore), so the belt would decide its segment was
 * obstructed and skip it. Without this patch {@code MixinBeltInventory} finds the behaviour and then
 * nothing is ever asked of it - the same silent nothing, one step further along.
 *
 * <h2>What the patch does</h2>
 * Returns "not blocked" when the block above the belt is a {@link TunnelHeaterBlock}, and does nothing at
 * all otherwise. Create already makes this same exception for its own belt-attached blocks - a brass funnel
 * standing on a belt is not treated as an obstruction either - so this is that list, extended by one entry,
 * rather than a new rule. No collision shape is changed: the tunnel a player bumps into is still the tunnel
 * they can see.
 *
 * <p>It is scoped to this one block type on purpose. A belt tunnel, a casing, or any other solid block
 * above a belt keeps its original answer, so machines sitting above a tunnel (a deployer two blocks up, for
 * instance) are the only thing this can newly allow, and only through a tunnel heater.
 */
@Mixin(value = BeltProcessingBehaviour.class, remap = false)
public abstract class MixinBeltProcessingBehaviour {

    /**
     * <b>This handler has to be {@code static}</b>, for the same rule that makes the other one in this
     * package an instance method: Mixin compares the handler's {@code static} flag against the method being
     * injected into ({@code Injector.checkTargetModifiers}, with {@code exactMatch = true}), and
     * {@code isBlocked} is a {@code static} method. Flipping this one to an instance method would be the
     * same mistake in the opposite direction and would fail the same way.
     *
     * @param level the level the belt is in
     * @param pos   the belt's own block, as {@code BeltInventory} passes it
     * @param cir   cancelled with {@code false} when a tunnel heater is what stands above the belt
     */
    @Inject(method = "isBlocked", at = @At("HEAD"), cancellable = true, remap = false)
    private static void woodencog$tunnelHeaterIsNotAnObstruction(BlockGetter level, BlockPos pos,
                                                                 CallbackInfoReturnable<Boolean> cir) {
        if (level == null || pos == null) {
            return;
        }
        if (level.getBlockState(pos.above()).getBlock() instanceof TunnelHeaterBlock) {
            cir.setReturnValue(Boolean.FALSE);
        }
    }
}
