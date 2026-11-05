package net.chauvedev.woodencog.block.electricstove;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.blockEntity.behaviour.CenteredSideValueBoxTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 电磁炉的数值框。除了摆位置，还负责老大要的<b>分段上色</b>。
 *
 * <h2>两段式的视觉</h2>
 *
 * <ul>
 *   <li><b>未升级</b>：刻度 0~200（0~2000 °C），整条正常色。</li>
 *   <li><b>已升级</b>：刻度放到 0~250（0~2500 °C）。多出的 200~250 就是右边接上的一段；
 *       0~200 那一段显示为灰色，让人一眼看出"老上限 2000 在哪"。</li>
 * </ul>
 *
 * <p>★ 为什么"接一段"而不是把整条均匀拉长：Create 的面板宽度由 {@code between} 的 max
 * 直接决定（{@code ValueSettingsBoard(label, max, 10, ...)}）。max 从 200 变 250，
 * 格子多 50 个，右边就自然多出一截。
 * 早期版本把 1~2000 直接塞进 between，面板变成 2000 格，条比整个屏幕还长 —— 老大实测过。
 *
 * <h2>颜色怎么给</h2>
 *
 * <p>Create 的 {@code ValueBox} 从 {@link #getOverrideColor()} 取颜色（{@code -1} = 不覆盖）。
 * 它没有参数，拿不到方块状态，但 {@link #transform} 在它之前被调用、并且自带状态，
 * 所以这里在 {@code transform} 里把状态记下来给 {@code getOverrideColor} 用。
 * 两条调用都在渲染线程、且一帧内成对出现，一个字段足够。
 */
public class ElectricStoveValueBox extends CenteredSideValueBoxTransform {

    /**
     * 灰色：升级后给 0~2000 °C 那一段用。
     * {@code 0xFF808080} 的 alpha 是 ff，保证不透明；换成半透明会让框看起来像渲染故障。
     */
    private static final int GREY = 0xFF808080;

    /** 最近一次 {@link #transform} 收到的方块状态是否为"已升级"。 */
    private boolean renderingUpgraded = false;

    public ElectricStoveValueBox() {
        super((blockState, direction) -> direction == blockState.getValue(ElectricStoveBlock.FACING));
    }

    @Override
    protected boolean isSideActive(BlockState state, Direction direction) {
        return direction == state.getValue(ElectricStoveBlock.FACING);
    }

    @Override
    public void transform(LevelAccessor level, BlockPos pos, BlockState state, PoseStack ms) {
        // Create 在画框之前一定先调用这个，顺手把状态记下来。
        this.renderingUpgraded = state.hasProperty(ElectricStoveBlock.UPGRADED)
            && state.getValue(ElectricStoveBlock.UPGRADED);
        super.transform(level, pos, state, ms);
    }

    /**
     * 升级后整条框用灰色；没升级返回 {@code -1}，交给 Create 用默认颜色。
     *
     * <p>读的是上面 {@code transform} 记下的标记，避免每帧去 {@code level.getBlockState}
     * （那会在渲染线程里查方块实体，没必要）。
     */
    @Override
    public int getOverrideColor() {
        return renderingUpgraded ? GREY : -1;
    }
}
