package net.chauvedev.woodencog.block.electricstove;

import com.google.common.collect.ImmutableList;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueBoxTransform;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBoard;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsFormatter;
import com.simibubi.create.foundation.blockEntity.behaviour.ValueSettingsBehaviour.ValueSettings;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollValueBehaviour;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 温度调节用的滚动值组件。
 *
 * <h2>为什么必须自己写一个</h2>
 *
 * <p>Create 的 {@link ScrollValueBehaviour} 有<b>两条</b>显示路径，它们互不相干：
 *
 * <ol>
 *   <li><b>看向方块时的悬浮框</b> —— 走 {@link #formatValue()}，会用到
 *       {@code withFormatter(...)} 设的格式化器。<b>这条本来就是对的。</b></li>
 *   <li><b>右键弹出的设置面板</b> —— 走 {@link #createBoard(...)}。
 *       Create 的原实现是这样的（反编译 {@code ScrollValueBehaviour.createBoard}）：</li>
 * </ol>
 *
 * <pre>
 *   new ValueSettingsBoard(
 *       label,                                        // 标题
 *       max,                                          // 格子数
 *       10,
 *       ImmutableList.of(Component.literal("Value")), // ← 行名写死 "Value"
 *       new ValueSettingsFormatter(v -&gt; ...)          // ← 默认只把刻度原样打印
 *   );
 * </pre>
 *
 * <p>★★ 所以 {@code withFormatter(...)} <b>只影响悬浮框，影响不到右键面板</b>。
 * 2026-11-05 老大实测到的两个现象就是这么来的：
 * <ul>
 *   <li>面板左上角显示 {@code Value} —— 因为行名是写死的英文字面量；</li>
 *   <li>数字比悬浮框<b>少一个 0</b>（显示 100 而不是 1000 °C）——
 *       因为面板打印的是<b>刻度</b>，而悬浮框打印的是 {@code 刻度 × 10}。</li>
 * </ul>
 *
 * <p>修法就是覆盖 {@code createBoard}，把行名和格式化器都换成我们自己的。
 * 这样两条路径显示同一个数字，也就不会出现"悬浮框 1000 °C / 面板 100"的分裂。
 */
public class StoveTemperatureBehaviour extends ScrollValueBehaviour {

    /** 面板每行下面那行小字的名字。 */
    private static final Component ROW_LABEL = Component.translatable("woodencog.electric_stove.temperature");

    /**
     * 当前的下限刻度。
     *
     * <p>★ 为什么不用父类的 {@code min} 字段：它是包可见的（package-private），
     * 子类在别的包里读不到，编译会报「min 在 ScrollValueBehaviour 中不是公共的」。
     * 所以这里自己存一份，由 {@link #between(int, int)} 同步更新 —— 只要所有人都通过
     * {@code between} 改范围（这是父类的标准用法），这份副本就不会和父类不一致。
     */
    private int stoveMin;

    public StoveTemperatureBehaviour(Component label, SmartBlockEntity be, ValueBoxTransform slot) {
        super(label, be, slot);
    }

    @Override
    public ScrollValueBehaviour between(int min, int max) {
        this.stoveMin = min;
        return super.between(min, max);
    }

    /** 当前下限刻度（面板换算的依据，也方便调试）。 */
    public int getStoveMin() {
        return stoveMin;
    }

    /**
     * 右键弹出的设置面板。
     *
     * <p>★ {@code maxValue} 传的是 <b>{@code max - min}</b>，不是 {@code max}。
     *
     * <p>原因：Create 的面板格子<b>天生从 0 数到 maxValue</b>，它不知道有 min 这回事。
     * 如果传 200（未升级时的上限刻度），面板就会画出 0~200 格 —— 最左边那格写着 0，
     * 玩家以为能调到 0 °C（老大实测就是这个感受）。
     * 但真的点下去会被 {@code setValue} 的 clamp 夹回 50，于是出现"面板能拖到 0、实际是 500"的割裂。
     *
     * <p>所以这里把面板改成 <b>0 ~ (max-min)</b> 的相对刻度，
     * 再在 {@link #setValueSettings} 里加上 min 映射回真实刻度。
     * 两种状态都是 150 格，条一样长：
     * <pre>
     *   未升级：面板 0~150  ⇒ 真实 50~200 刻度 ⇒ 500~2000 °C
     *   升级后：面板 0~150  ⇒ 真实 100~250 刻度 ⇒ 1000~2500 °C
     * </pre>
     */
    @Override
    public ValueSettingsBoard createBoard(Player player, BlockHitResult hitResult) {
        return new ValueSettingsBoard(
            label,
            max - stoveMin,
            10,
            ImmutableList.of(ROW_LABEL),
            // ★ 必须用 settings.value()，不是 formatValue()：
            //   formatValue() 读的是【当前选中值】，而面板上每一格都要显示它自己的数字。
            //   用错了会导致整条面板每格都显示同一个数。
            // ★ 面板给的是相对刻度，要加回 min 才是真实刻度。
            new ValueSettingsFormatter(settings ->
                Component.literal(formatTicks(settings.value() + stoveMin)))
        );
    }

    /**
     * 面板点一下之后回传的也是<b>相对刻度</b>（0~150），这里加 min 还原成真实刻度再交给父类。
     *
     * <p>父类的 {@code setValueSettings} 内部会调 {@code setValue(...)}，
     * 而 {@code setValue} 里有 {@code Mth.clamp(value, min, max)}，所以这里已经满足下限，
     * 夹一次只是保险。
     */
    @Override
    public void setValueSettings(Player player, ValueSettings valueSetting, boolean ctrlHeld) {
        if (valueSetting == null) {
            return;
        }
        ValueSettings real = new ValueSettings(valueSetting.row(), valueSetting.value() + stoveMin);
        super.setValueSettings(player, real, ctrlHeld);
    }

    /**
     * 面板要高亮"当前值在哪一格"，报的也得是相对刻度，否则高亮会跑到最右边外面。
     */
    @Override
    public ValueSettings getValueSettings() {
        return new ValueSettings(0, value - stoveMin);
    }

    /** 刻度和 °C 的换算表由方块实体持有，这里只负责取。 */
    private String formatTicks(int ticks) {
        return (ticks * ElectricStoveBlockEntity.DEGREES_PER_TICK) + " °C";
    }
}
