package net.chauvedev.woodencog.mixin;

import com.simibubi.create.content.kinetics.fan.processing.AllFanProcessingTypes;
import net.chauvedev.woodencog.compat.create.IAirHeated;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes a charged air heated block of this mod count as Create's blasting heat source.
 *
 * <p>Create decides what a fan does one block of airflow at a time: {@code AirCurrent.rebuild} asks
 * {@code FanProcessingType.getAt(level, pos)} for every block in the column and carries the first
 * answer it gets to the rest of the column. Only a heat source answers there, and every vanilla answer
 * is data driven - lava is a fluid tag, fire and campfire are block tags, a blaze burner is a block
 * with a heat level. A block that is a heat source only while it holds a hot fluid cannot be written
 * as a tag, so it needs this one condition, and nothing else in that path is touched.
 *
 * <p>The condition used to name {@code HotAirChamberBlockEntity} and, before that, check that the
 * block at the position was a {@code HotAirChamberBlock} as well. Both checks are gone in favour of
 * {@link IAirHeated}, and they are equivalent: a block entity of one of these types only ever exists
 * under its own block, because the type registers exactly that block through
 * {@code BlockEntityEntry.validBlocks}, so a position whose block entity implements the interface is a
 * position whose block is one of this mod's heated blocks. The reverse held before too - a chamber
 * block always carries a chamber block entity once its chunk is in memory - so the pair of checks was
 * already one check.
 *
 * <p>The injection only ever raises Create's own answer from false to true. For every other block, and
 * for a switched off or empty heated block, the vanilla answer stands: this mixin does not decide what
 * lava, fire, campfires, firepits or water fans do, and a heated block that does not heat is as
 * transparent to the airflow as air. The cheap {@code hasBlockEntity} guard keeps the common case of
 * plain air - which is most of a column - away from the block entity lookup, the same way the old
 * block class check did.
 *
 * <p>Shaped after {@link MixinSmokingType}, which extends the same call the same way for a lit TFC
 * firepit, and it is what lets a heated block use the mod's existing blasting path in
 * {@link MixinFanProcessing}: the temperature that path heats towards is then taken from the block
 * instead of from the lava config, through {@code FanContext.heatSourceCeiling}.
 */
@Mixin(value = AllFanProcessingTypes.BlastingType.class, remap = false)
public class MixinBlastingType {

    @Inject(method = "isValidAt", at = @At("RETURN"), cancellable = true, remap = false)
    private void woodencog$hotAirChamber(Level level, BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() || !level.getBlockState(pos).hasBlockEntity()) {
            return;
        }
        if (level.getBlockEntity(pos) instanceof IAirHeated heater && heater.isHeating()) {
            cir.setReturnValue(true);
        }
    }
}
