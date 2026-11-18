package net.chauvedev.woodencog.block.electricstove;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.foundation.blockEntity.behaviour.CenteredSideValueBoxTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 电阻炉的数值框。除了摆位置，还负责分段上色。
 *
 * <h2>四个侧面都显示（2026-11-12 老大改）</h2>
 *
 * <p>原来只挂在 {@code FACING} 那一面。现在改成<b>四个水平面都挂</b>，
 * 玩家站在哪一边都能右键调温度，不用绕到正面。
 *
 * <h2>位置：底部居中</h2>
 *
 * <p>模型底座是 {@code from [1,0,1] to [15,2,15]}，上沿在 y = 2/16。
 * 框的中心压到 <b>3/16</b> 附近，整个框落在底座范围内、靠底部，
 * 不会和上面的炉体、顶盖打架。
 *
 * <h2>两段式的视觉</h2>
 *
 * <ul>
 *   <li><b>未升级</b>：刻度 0~250（0~2500 °C），整条正常色。</li>
 *   <li><b>已升级</b>：刻度放到 0~300（0~3000 °C）。多出的 250~300 是右边接上的一段；
 *       0~250 那一段显示为灰色，让人一眼看出"老上限 2500 在哪"。</li>
 * </ul>
 *
 * <p>★ 为什么"接一段"而不是把整条均匀拉长：Create 的面板宽度由 {@code between} 的 max
 * 直接决定（{@code ValueSettingsBoard(label, max, 10, ...)}）。max 从 250 变 300，
 * 格子多 50 个，右边就自然多出一截。
 * 早期版本把 1~2500 直接塞进 between，面板变成 2500 格，条比整个屏幕还长 —— 老大实测过。
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
     * 灰色：升级后给 0~2500 °C 那一段用。
     * {@code 0xFF808080} 的 alpha 是 ff，保证不透明；换成半透明会让框看起来像渲染故障。
     */
    private static final int GREY = 0xFF808080;

    /**
     * 框心距底面的高度（单位：方块，1.0 = 整格）。
     *
     * <p>3/16 ≈ 0.1875。底座高 2/16，框本身约 9px 高，
     * 中心放在 3/16 能让框大体压在底座上、贴着地面。
     */
    private static final float BOTTOM_OFFSET = 3.0F / 16.0F;

    /** 最近一次 {@link #transform} 收到的方块状态是否为"已升级"。 */
    private boolean renderingUpgraded = false;

    /** 最近一次 {@link #transform} 收到的朝向，父类要在内部用到。 */
    private Direction facing = Direction.NORTH;

    public ElectricStoveValueBox() {
        // ★ 四个水平面都算"激活面"，不再只认 FACING。
        super((blockState, direction) -> direction.getAxis().isHorizontal());
    }

    /** 四个水平面都挂数值框。 */
    @Override
    protected boolean isSideActive(BlockState state, Direction direction) {
        return direction.getAxis().isHorizontal();
    }

    /**
     * 把框压到方块底部。
     *
     * <p>父类的 {@code getLocalOffset} 给的是"贴在该面中心、向内缩一点"的点。
     * 这里只改垂直高度：y 固定到 {@link #BOTTOM_OFFSET}，x/z 保留父类算好的内缩。
     *
     * <p>★ 为什么不直接改 {@code transform}：{@code CenteredSideValueBoxTransform} 的
     * 旋转与平移是一整套（先转到面、再平移、再缩放），只改其中一步会错位。
     * 父类已经把这个点算好在 {@code getLocalOffset} 里，改它最安全。
     */
    @Override
    public net.minecraft.world.phys.Vec3 getLocalOffset(LevelAccessor level, BlockPos pos, BlockState state) {
        net.minecraft.world.phys.Vec3 offset = super.getLocalOffset(level, pos, state);
        return new net.minecraft.world.phys.Vec3(offset.x, BOTTOM_OFFSET, offset.z);
    }

    @Override
    public void transform(LevelAccessor level, BlockPos pos, BlockState state, PoseStack ms) {
        // Create 在画框之前一定先调用这个，顺手把状态记下来。
        this.renderingUpgraded = state.hasProperty(ElectricStoveBlock.UPGRADED)
            && state.getValue(ElectricStoveBlock.UPGRADED);
        if (state.hasProperty(ElectricStoveBlock.FACING)) {
            this.facing = state.getValue(ElectricStoveBlock.FACING);
        }
        super.transform(level, pos, state, ms);
    }

    /** 当前朝向（调试/排错时用，也避免 facing 字段被当成死代码）。 */
    public Direction getFacing() {
        return facing;
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
