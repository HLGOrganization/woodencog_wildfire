package net.chauvedev.woodencog.mixin.heat;

import net.dries007.tfc.common.blockentities.CharcoalForgeBlockEntity;
import net.dries007.tfc.common.blocks.devices.CharcoalForgeBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Promotes a TFC charcoal forge to the eighth, superheated heat tier once its device temperature
 * reaches 2300 degrees.
 *
 * <p>{@link MixinTFCBlockStateProperties} raises the maximum of the {@code heat_level} property
 * from 7 to 8, but TFC's own {@code serverTick} still computes its tier with
 * {@code Mth.clamp(..., 1, 7)} and writes it whenever it differs from the current value. Writing
 * tier 8 directly from a second place would therefore fight with TFC every tick and make the model
 * flicker between two states.
 *
 * <p>The two injections below cooperate with TFC instead of competing with it:
 * <ol>
 *   <li>{@code woodencog$presentEighthTierAsTfcMaximum} rewrites the {@code BlockState} argument at
 *       the very start of {@code serverTick}, so TFC sees a forge at its own maximum (7) whenever
 *       the real state is 8. TFC then computes 7 as well and performs no block update at all.</li>
 *   <li>{@code woodencog$applyEighthTier} runs at return and is the single writer of tier 8: it
 *       promotes a lit forge to 8 while the temperature is at or above the threshold, and demotes a
 *       stale 8 back to TFC's maximum once the forge has cooled down or burned out.</li>
 * </ol>
 *
 * <p>Because the state can only ever be 8 while the temperature is above the threshold (the value
 * is only ever written there) and because TFC's clamp saturates at 7 for any temperature at or
 * above 1866.7 degrees, the masked value and TFC's own computed value are identical in every state
 * the forge can actually reach. The result is a stable tier 8 with exactly one block update per
 * transition.
 *
 * <p>This is a native port of the heat handling that used to live in the Wildfires mod's
 * {@code first.wildfires.mixin.tfc.CharcoalForgeBlockEntityMixin}. Wildfires' own
 * {@code unrestricted_charcoal_forge} extends {@link CharcoalForgeBlock} and calls this very same
 * ticker, so both furnace variants get the tier.
 */
@Mixin(value = CharcoalForgeBlockEntity.class, remap = false)
public abstract class MixinCharcoalForgeBlockEntity {

    /**
     * Highest tier TFC itself is able to compute and write.
     */
    private static final int WOODENCOG_TFC_MAX_HEAT_LEVEL = 7;

    /**
     * The superheated tier added by this mod.
     */
    private static final int WOODENCOG_SUPERHEATED_HEAT_LEVEL = 8;

    /**
     * Device temperature at which the forge counts as superheated, in degrees.
     *
     * <p>Must stay in sync with {@code create.recipe.heat_requirement.superheated} in
     * {@code CogUtil.tempToHeatLevel} and with the {@code blaze_burner_seething} config value.
     */
    private static final float WOODENCOG_SUPERHEATED_TEMPERATURE = 2300.0F;

    /**
     * Hides the superheated tier from TFC's ticker by presenting it as TFC's own maximum, so the
     * vanilla clamp does not overwrite it.
     */
    @ModifyVariable(method = "serverTick", at = @At("HEAD"), argsOnly = true, index = 2)
    private static BlockState woodencog$presentEighthTierAsTfcMaximum(BlockState state) {
        if (state.hasProperty(CharcoalForgeBlock.HEAT)
                && state.getValue(CharcoalForgeBlock.HEAT) == WOODENCOG_SUPERHEATED_HEAT_LEVEL) {
            return state.setValue(CharcoalForgeBlock.HEAT, WOODENCOG_TFC_MAX_HEAT_LEVEL);
        }
        return state;
    }

    /**
     * The only writer of the superheated tier: promotes a lit forge at or above the threshold,
     * demotes anything TFC cannot represent back to its maximum.
     */
    @Inject(method = "serverTick", at = @At("RETURN"))
    private static void woodencog$applyEighthTier(Level level, BlockPos pos, BlockState state,
                                                  CharcoalForgeBlockEntity blockEntity, CallbackInfo ci) {
        BlockState currentState = level.getBlockState(pos);
        if (!(currentState.getBlock() instanceof CharcoalForgeBlock)
                || !currentState.hasProperty(CharcoalForgeBlock.HEAT)) {
            return;
        }

        int currentHeatLevel = currentState.getValue(CharcoalForgeBlock.HEAT);
        int targetHeatLevel = currentHeatLevel > 0
                && blockEntity.getTemperature() >= WOODENCOG_SUPERHEATED_TEMPERATURE
                ? WOODENCOG_SUPERHEATED_HEAT_LEVEL
                : Math.min(currentHeatLevel, WOODENCOG_TFC_MAX_HEAT_LEVEL);
        if (currentHeatLevel != targetHeatLevel) {
            level.setBlockAndUpdate(pos, currentState.setValue(CharcoalForgeBlock.HEAT, targetHeatLevel));
        }
    }
}
