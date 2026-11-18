package net.chauvedev.woodencog.block.electricstove;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.ForgeCapabilities;

import java.util.ArrayList;
import java.util.List;

/**
 * 判断电阻炉哪几个面接了电。
 *
 * <h2>用途（老大 2026-11-17 要求）</h2>
 *
 * <p>模型里那套 {@code resistance_furnace_port}（16×16 接口面板）要<b>只出现在有电线的面</b>上。
 * 没接线的那几个面保持原样，接了线的那一面多一块接口板。
 *
 * <h2>怎么判断「接了线」</h2>
 *
 * <p>看这个面的邻方块有没有 {@link ForgeCapabilities#ENERGY} 能力。
 * 电线（IE 的线缆、Create 的转换器……）都会通过这个能力对外提供电，
 * 所以不用针对某个模组写特判 —— <b>能查能力就够了</b>。
 *
 * <p>★ 只查<b>水平四面</b>：和组连接规则保持一致（上下不连，也不显示接口）。
 */
public final class StovePorts {

    private static final Direction[] HORIZONTAL = {
        Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    private StovePorts() {
    }

    /**
     * 返回该炉子哪些水平面接了电线。
     *
     * <p>返回的是<b>世界方向</b>（NORTH/EAST/SOUTH/WEST），不是局部朝向。
     */
    public static List<Direction> connectedFaces(Level level, BlockPos pos) {
        List<Direction> faces = new ArrayList<>(4);
        if (level == null || level.isClientSide) {
            return faces;
        }
        for (Direction dir : HORIZONTAL) {
            BlockPos neighbor = pos.relative(dir);
            if (!level.isLoaded(neighbor)) {
                continue;
            }
            BlockEntity be = level.getBlockEntity(neighbor);
            if (be == null) {
                continue;
            }
            // ★ 另一个电阻炉不算「接线」——那种情况由组共享负责，不该长接口板。
            if (be instanceof ElectricStoveBlockEntity) {
                continue;
            }
            // 邻方块朝向我们这一面能不能供电。
            if (be.getCapability(ForgeCapabilities.ENERGY, dir.getOpposite()).isPresent()) {
                faces.add(dir);
            }
        }
        return faces;
    }

    /** 四个水平面在世界坐标里的顺序：北/南/西/东 —— 和位掩码的 0/1/2/3 位对应。 */
    public static final Direction[] MASK_ORDER = {
        Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    /**
     * 把四个水平面的接线状态压成一个 0~15 的位掩码。
     *
     * <p>给方块状态 {@code PORTS} 用，blockstate 靠它选模型。
     */
    public static int mask(Level level, BlockPos pos) {
        int m = 0;
        for (int i = 0; i < MASK_ORDER.length; i++) {
            if (hasPort(level, pos, MASK_ORDER[i])) {
                m |= 1 << i;
            }
        }
        return m;
    }

    /**
     * 这个面是否接了电线（单面查询，给渲染层用）。
     *
     * <h2>★ 2026-11-17 老大修正</h2>
     *
     * <p>之前本鲸把「邻方块是另一个电阻炉」排除在接线之外，理由是「那种情况交给组共享」。
     * <b>老大说这是错的</b>：相邻也是另一个电阻炉时，<b>同样要显示接口板</b>，
     * 因为炉子之间本来就在互相供电/共用电。
     *
     * <p>所以现在的判据很简单：<b>这个面的邻方块是任何一种"能供电"的电器</b>就显示口，
     * 包括：
     * <ul>
     *   <li>别的电阻炉（{@link ElectricStoveBlockEntity}）</li>
     *   <li>IE 的接线器（{@code immersiveengineering:connector_lv} 之类）</li>
     *   <li>任何对外暴露 {@code ForgeCapabilities.ENERGY} 且能输出的方块实体</li>
     * </ul>
     */
    public static boolean hasPort(Level level, BlockPos pos, Direction worldFace) {
        if (!worldFace.getAxis().isHorizontal()) {
            return false;
        }
        BlockPos neighbor = pos.relative(worldFace);
        if (level == null || !level.isLoaded(neighbor)) {
            return false;
        }
        BlockEntity be = level.getBlockEntity(neighbor);
        if (be == null) {
            return false;
        }
        // ① 另一个电阻炉：算接上了（老大 2026-11-17 明确要求）
        if (be instanceof ElectricStoveBlockEntity) {
            return true;
        }
        // ② 其它能供电的方块：看它朝向我们这一面有没有能量能力
        return be.getCapability(ForgeCapabilities.ENERGY, worldFace.getOpposite()).isPresent();
    }
}
