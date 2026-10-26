package net.chauvedev.woodencog.block.hotairchamber;

import com.simibubi.create.foundation.block.IBE;
import net.chauvedev.woodencog.block.WoodencogBlockEntityTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.fluids.FluidUtil;

/**
 * The block of a hot air chamber: a solid casing with a 2000 mB tank inside it.
 *
 * <p>It is a heat source for a Create fan and belongs where a fan's lava would go, in the column of
 * air the fan moves. Air has to be able to pass through it for that to work at all - a full cube
 * would otherwise stop the fan's airflow at its own face - so the block is listed in Create's own
 * {@code create:fan_transparent} block tag, which is the tag Create checks before it lets an air
 * current pass through a solid block.
 *
 * <p>It is deliberately not a catalyst by tag: whether the chamber heats is decided by the fluid in
 * the tank at the time, which no tag can express.
 */
public class HotAirChamberBlock extends Block implements IBE<HotAirChamberBlockEntity> {

    public HotAirChamberBlock(Properties properties) {
        super(properties);
    }

    @Override
    public Class<HotAirChamberBlockEntity> getBlockEntityClass() {
        return HotAirChamberBlockEntity.class;
    }

    @Override
    public BlockEntityType<? extends HotAirChamberBlockEntity> getBlockEntityType() {
        return WoodencogBlockEntityTypes.HOT_AIR_CHAMBER.get();
    }

    /**
     * Buckets, TFC fluid containers and anything else carrying an item fluid handler fill and empty
     * the tank through the same capability Create's pipes and pumps use.
     */
    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        if (!(level.getBlockEntity(pos) instanceof HotAirChamberBlockEntity)) {
            return InteractionResult.PASS;
        }
        if (FluidUtil.interactWithFluidHandler(player, hand, level, pos, hitResult.getDirection())) {
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return InteractionResult.PASS;
    }
}
