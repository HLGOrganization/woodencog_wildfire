package net.chauvedev.woodencog.block.tunnelheater;

import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 隧道加热炉的物品形态，负责「放下去的时候顺手把下面那条传送带封上工业铁机壳」。
 *
 * <p>这是照 Create 自己的 {@code BeltTunnelItem} 抄的：黄铜隧道放到传送带上，下面的带子会被
 * 封成黄铜机壳；安山隧道封成安山机壳。它们靠的正是 {@code BlockItem#placeBlock} 的那个放置后
 * 回调，拿 {@code BeltHelper.getSegmentBE(pos.below())} 找到带子的方块实体，再
 * {@code setCasingType}。
 *
 * <p>★ 两条规则跟 Create 保持一致，别改：
 * <ul>
 *   <li><b>只在服务端做</b>（{@code !level.isClientSide}）：方块实体是服务端权威的，
 *       客户端那份会在同步时被覆盖，白改。</li>
 *   <li><b>只封还是 {@code NONE} 的带子</b>：玩家已经拿机壳封过的带子不该被我们覆盖掉，
 *       Create 的黄铜隧道也是这个规矩。</li>
 * </ul>
 *
 * <p>机壳类型是 {@code INDUSTRIAL_IRON}（{@code createcasing} 加的），外观配合资源包
 * {@code Tunnel_Heater_Casing} 换成老大给的那张图；带子那圈「支撑」由
 * {@code TunnelHeaterBlock#clearBeltCovered} 压掉。
 */
public class TunnelHeaterItem extends BlockItem {

    public TunnelHeaterItem(Block block, Properties properties) {
        super(block, properties);
    }

    /**
     * 放置完成后的回调（{@code BlockItem#placeBlock} 的 protected 钩子）。
     *
     * <p>先让父类把方块放好，再去看下面那条传送带。返回父类的结果，不要吞掉它——
     * 那个布尔值决定手里的物品要不要减一。
     */
    @Override
    protected boolean placeBlock(BlockPlaceContext context, BlockState state) {
        boolean placed = super.placeBlock(context, state);
        if (!placed) {
            return false;
        }

        Level level = context.getLevel();
        if (level.isClientSide) {
            return true;
        }

        // 加热炉自己占的那一格，带子在它正下方。
        BlockPos beltPos = context.getClickedPos().below();
        BeltBlockEntity belt = BeltHelper.getSegmentBE(level, beltPos);
        if (belt == null) {
            return true;
        }
        if (belt.casing != BeltBlockEntity.CasingType.NONE) {
            return true;
        }

        BeltBlockEntity.CasingType casing = industrialIronCasing();
        if (casing != null) {
            belt.setCasingType(casing);
        }
        return true;
    }

    /**
     * 取 {@code CasingType.INDUSTRIAL_IRON}，取不到返回 {@code null}。
     *
     * <p>★ 2026-11-01 改回 {@code INDUSTRIAL_IRON}（中间试过 {@code BRASS}，但老大要的是工业铁
     * 机壳配他自己的那张贴图，所以改回来）。工业铁是 {@code createcasing}（Create Encased）用
     * mixin 加进枚举的，编译期不存在这个常量，所以只能 {@code valueOf} + try/catch。
     *
     * <p>★ 它的两个附带问题都已经处理掉，别再当成拦路虎：
     * <ul>
     *   <li><b>多出来的那圈顶盖</b>（{@code createcasing} 的 {@code belt_cover}，
     *       几何 {@code from=[0,11,0] to=[16,16,16]}）：资源包
     *       {@code Tunnel_Heater_Casing} 把 {@code belt_cover/industrial_iron.png} 覆盖成全透明，
     *       顶盖就不画了。</li>
     *   <li><b>带子画「支撑」</b>：那是 Create 自己的 {@code covered} 逻辑，跟机壳类型无关，
     *       由 {@code TunnelHeaterBlock#clearBeltCovered} 压掉。</li>
     * </ul>
     */
    private static BeltBlockEntity.CasingType industrialIronCasing() {
        try {
            return BeltBlockEntity.CasingType.valueOf("INDUSTRIAL_IRON");
        } catch (IllegalArgumentException e) {
            // 没装 Create Encased：这个机壳类型不存在。加热炉照常能放，只是不封带子。
            return null;
        }
    }
}
