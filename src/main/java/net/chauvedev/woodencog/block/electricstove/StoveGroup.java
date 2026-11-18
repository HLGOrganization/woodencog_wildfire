package net.chauvedev.woodencog.block.electricstove;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 电阻炉的「连通组」：水平四面相邻、且都没被扳手拆开的炉子，算一组。
 *
 * <h2>老大 2026-11-17 定的规则</h2>
 *
 * <ul>
 *   <li><b>只连水平四面</b>（东西南北），上下不连 —— 叠起来的炉子各算各的。</li>
 *   <li><b>电量共享</b>：整组共用一个电池池。</li>
 *   <li><b>温度共享</b>：组内所有炉子的 {@code currentTemperature} 永远相等。</li>
 *   <li><b>扳手可拆</b>：右键把一台标记成独立，它就脱离组；再右键加回去。</li>
 *   <li>被拆开的炉子<b>不参与</b>连通扫描 —— 它既不会「连」到别人，别人也不会穿过它。</li>
 * </ul>
 *
 * <h2>为什么不缓存组</h2>
 *
 * <p>组会因为放/拆方块、扳手切换而随时变化，缓存就要维护失效，很容易漏。
 * 这里每次需要时现扫一遍：炉子组的规模一般就几台，DFS 到 20 格上限的开销可以忽略。
 *
 * <p>★ 但<b>不能</b>每台炉子每 tick 都各扫一遍（N 台就是 N 次 DFS）。
 * 所以 {@link ElectricStoveBlockEntity} 里用「组长优先」的方式：只有组长负责整组的
 * 收电/扣电/算温，其他人只读组长的结果。
 */
public final class StoveGroup {

    /** 连通扫描的格数上限，防呆：避免玩家砌一整面墙把服务器拖死。 */
    public static final int MAX_GROUP_SIZE = 32;

    /** 只连这四个方向。 */
    private static final Direction[] HORIZONTAL = {
        Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    private StoveGroup() {
    }

    /**
     * 收集从 {@code start} 出发、水平连通的所有电阻炉。
     *
     * @return 组内所有炉子；<b>第一个元素永远是 {@code start} 本身</b>，方便调用方判断自己是不是组长。
     */
    public static List<ElectricStoveBlockEntity> collect(Level level, BlockPos start) {
        List<ElectricStoveBlockEntity> result = new ArrayList<>();
        ElectricStoveBlockEntity self = beAt(level, start);
        if (self == null) {
            return result;
        }

        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        visited.add(start);
        queue.add(start);

        while (!queue.isEmpty() && result.size() < MAX_GROUP_SIZE) {
            BlockPos cur = queue.poll();
            ElectricStoveBlockEntity be = beAt(level, cur);
            if (be == null) {
                continue;
            }
            result.add(be);
            // ★ 独立炉子既不融入别人的组，也不让别人穿过它 —— 它就在这里断链。
            if (be.isStandalone()) {
                continue;
            }
            for (Direction dir : HORIZONTAL) {
                BlockPos next = cur.relative(dir);
                if (visited.contains(next)) {
                    continue;
                }
                if (beAt(level, next) == null) {
                    continue;
                }
                visited.add(next);
                queue.add(next);
            }
        }
        return result;
    }

    /**
     * 选组长：组内坐标最小的那台。
     *
     * <p>★ 必须是<b>确定性</b>的选法。用「遍历顺序第一个」会随机，因为 DFS 从谁开始取决于
     * 哪台先 tick，那样每 tick 组长都在换，组的池子和温度就会来回跳。
     */
    public static ElectricStoveBlockEntity leaderOf(List<ElectricStoveBlockEntity> group) {
        ElectricStoveBlockEntity best = null;
        for (ElectricStoveBlockEntity be : group) {
            if (best == null || be.getBlockPos().asLong() < best.getBlockPos().asLong()) {
                best = be;
            }
        }
        return best;
    }

    /** 取某个位置的电阻炉方块实体；不是电阻炉就返回 {@code null}。 */
    public static ElectricStoveBlockEntity beAt(Level level, BlockPos pos) {
        if (level == null || !level.isLoaded(pos)) {
            return null;
        }
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ElectricStoveBlock)) {
            return null;
        }
        return level.getBlockEntity(pos) instanceof ElectricStoveBlockEntity be ? be : null;
    }
}
