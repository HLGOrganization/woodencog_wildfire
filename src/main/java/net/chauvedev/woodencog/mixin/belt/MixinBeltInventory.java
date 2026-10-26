package net.chauvedev.woodencog.mixin.belt;

import com.simibubi.create.content.kinetics.belt.behaviour.BeltProcessingBehaviour;
import com.simibubi.create.content.kinetics.belt.transport.BeltInventory;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import net.chauvedev.woodencog.block.tunnelheater.TunnelHeaterProcessingBehaviour;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Lets a tunnel heater be found by the belt it stands on.
 *
 * <h2>Why this is needed</h2>
 * Create finds the machine that stops or processes an item on a belt here:
 *
 * <pre>
 * BeltInventory.getBeltProcessingAtSegment(int seg) =
 *     BlockEntityBehaviour.get(level,
 *         BeltHelper.getPositionForOffset(belt, seg).above(2),   // &lt;- two blocks above the belt
 *         BeltProcessingBehaviour.TYPE)
 * </pre>
 *
 * <p>Verified in the jar the game actually runs ({@code create-1.20.1-6.0.8.jar}):
 * {@code 15: iconst_2 / 16: invokevirtual BlockPos.m_6630_(I)}, {@code m_6630_} being {@code above(int)}.
 * The same two instructions are in the jar this mod is compiled against, so it is not a version accident.
 *
 * <p>Two blocks is right for Create's own machines: a deployer, a press or a saw is placed with the
 * block above the belt left free, so the gap the item travels through is clear and the machine's working
 * part reaches down into it. A tunnel heater is not that shape. It is a tunnel <em>over</em> the belt - the
 * belt runs through it - so it stands one block above the belt, and at two blocks above the belt there is
 * nothing. Its behaviour was therefore never found, never asked, and never able to hold an item: the
 * failure was silent, because a belt with no machine on a segment is a perfectly ordinary belt.
 *
 * <h2>What the patch does</h2>
 * It redirects the one lookup inside {@code getBeltProcessingAtSegment} and asks the same question twice:
 * first exactly where Create asked, and, only if that comes back empty, one block lower. A behaviour found
 * one block lower is used <b>only</b> when it is a {@link TunnelHeaterProcessingBehaviour}. Everything else
 * is returned untouched:
 *
 * <ul>
 *   <li>a machine at two blocks above the belt wins, unchanged and first - which also settles what happens
 *       when a player stacks a deployer on top of a tunnel heater: the deployer is the one that acts;</li>
 *   <li>nothing at either height gives back the same {@code null} Create would have got;</li>
 *   <li>somebody else's behaviour one block above the belt (a brass funnel, another addon's block) is not
 *       ours, so it is not returned and Create's answer is the same as before.</li>
 * </ul>
 *
 * <p>{@code @Redirect} rather than an injection at the head of the method with the belt read out of a
 * shadowed field, and the reason is a fact about the target class rather than a preference:
 * {@code BeltInventory.belt} is <b>package-private</b> ({@code javap -p} prints
 * {@code final BeltBlockEntity belt;} with no modifier), so a {@code @Shadow} for it cannot be written from
 * this package and an {@code @Accessor} would be needed purely to re-derive a position this patch is
 * handed for free. Redirecting the call uses the very {@link BlockPos} Create computed, so "one block
 * lower" stays correct even if Create ever changes how it works that position out. Nothing but this one
 * call site in this one method is touched; {@code getTransportedItemStackHandlerAtSegment}, which looks at
 * the belt's own block and is the other half of the pair, is deliberately left alone.
 */
@Mixin(value = BeltInventory.class, remap = false)
public abstract class MixinBeltInventory {

    /**
     * The belt's lookup for a machine on a segment: asked where Create asks, then one block lower.
     *
     * <p>{@code pos} is Create's own {@code getPositionForOffset(belt, seg).above(2)}, so {@code pos.below()}
     * is the block directly above the belt - where a tunnel heater stands. Both the null guards are for
     * looks, not for a known crash: an unloaded or headless level is not a state this should throw in, and
     * returning what Create would have got is the safe answer either way.
     *
     * <h2>Why this handler is an instance method</h2>
     * Mixin compares the handler's {@code static} flag against the <b>method being injected into</b> -
     * here the instance method {@code getBeltProcessingAtSegment} - not against the redirected call. That
     * it happens to redirect a {@code static} call ({@code BlockEntityBehaviour.get}) is beside the point.
     * From {@code org.spongepowered.asm.mixin.injection.code.Injector.checkTargetModifiers}, which every
     * injector runs with {@code exactMatch = true}:
     *
     * <pre>
     * if (exactMatch &amp;&amp; target.isStatic != this.isStatic)
     *     throw new InvalidInjectionException(..., "'static' modifier of handler method does not match target in %s");
     * </pre>
     *
     * <p>{@code this.isStatic} is the handler's ({@code Injector}: {@code this.isStatic = Bytecode.isStatic(this.methodNode)});
     * {@code target.isStatic} is the target <em>method's</em> ({@code Target}: {@code this.isStatic = Bytecode.isStatic(method)}).
     * {@code exactMatch} being true, the two must be equal, so a {@code static} handler here refuses to
     * apply - which is what happened in game on 2026-10-15 14:41, and it failed <em>late</em> rather than
     * at startup because {@code BeltInventory} is only loaded when something first reaches for it.
     */
    @Redirect(
            method = "getBeltProcessingAtSegment",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/simibubi/create/foundation/blockEntity/behaviour/BlockEntityBehaviour;"
                            + "get(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;"
                            + "Lcom/simibubi/create/foundation/blockEntity/behaviour/BehaviourType;)"
                            + "Lcom/simibubi/create/foundation/blockEntity/behaviour/BlockEntityBehaviour;"),
            remap = false)
    private BlockEntityBehaviour woodencog$findTunnelHeaterOnBelt(BlockGetter level, BlockPos pos,
                                                                 BehaviourType<?> type) {
        BlockEntityBehaviour found = BlockEntityBehaviour.get(level, pos, type);
        if (found != null) {
            return found;
        }
        if (level == null || pos == null) {
            return null;
        }
        BlockEntityBehaviour lower = BlockEntityBehaviour.get(level, pos.below(), type);
        return lower instanceof TunnelHeaterProcessingBehaviour ? lower : null;
    }
}
