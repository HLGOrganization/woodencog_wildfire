package net.chauvedev.woodencog.block.electricstove;

/**
 * 电阻炉火焰层贴图的命名与选择。
 *
 * <h2>贴图从哪来</h2>
 *
 * <p>2026-11-12 老大给的新模型带来 9 张手持/发光层贴图：
 * <ul>
 *   <li>{@code resistance_furnace_hand_0} —— 32×32 静态（不发热）</li>
 *   <li>{@code _hand_1 / _2 / _3} —— 32×64，各 2 帧动画（小火）</li>
 *   <li>{@code _hand_4 ~ _8} —— 32×480，各 15 帧动画（大火）</li>
 * </ul>
 *
 * <h2>★ 它们不是"手持物品"</h2>
 *
 * <p>文件名里的 {@code _hand_} 是 Blockbench 的默认命名，<b>实际用途是方块上的发光层</b>。
 * 新模型 {@code resistance_furnace.json} 里 {@code "4"} 槽直接引用了 {@code _hand_8}，
 * 就是把它当顶面发光贴图用的。
 *
 * <h2>本类只做一件事</h2>
 *
 * <p>把「热档位」翻译成「该用哪张贴图名」。不碰渲染管线 —— 渲染那层交给方块模型 JSON 里的
 * {@code textures} 引用和 {@code .mcmeta} 自己播动画。
 */
public final class ResistanceFurnaceFlame {

    /** 贴图名前缀：完整资源路径是 {@code woodencog:block/electric_stove/<前缀><n>}。 */
    public static final String TEXTURE_PREFIX = "resistance_furnace_hand_";

    /** 不发热时用这张（静态，无动画）。 */
    public static final String TEXTURE_IDLE = TEXTURE_PREFIX + "0";

    /** 有动画的贴图序号范围。 */
    public static final int ANIMATED_MIN = 1;
    public static final int ANIMATED_MAX = 8;

    /** 预建好字符串，避免渲染线程每帧拼接。 */
    private static final String[] LIT = new String[ANIMATED_MAX + 1];

    static {
        for (int i = ANIMATED_MIN; i <= ANIMATED_MAX; i++) {
            LIT[i] = TEXTURE_PREFIX + i;
        }
    }

    private ResistanceFurnaceFlame() {
    }

    /**
     * 按热档位选贴图。
     *
     * @param heatLevel Create 的 {@code BlazeBurnerBlock.HeatLevel} 序号：
     *                  {@code NONE=0 / SMOULDERING=1 / FADING=2 / KINDLED=3 / SEETHING=4}
     * @return 贴图短名（不含命名空间与目录）；不发热时返回 {@link #TEXTURE_IDLE}
     */
    public static String textureFor(int heatLevel) {
        if (heatLevel <= 0) {
            return TEXTURE_IDLE;
        }
        // 档位 1~4 → 贴图 2/4/6/8，留出中间几档给以后细分用。
        int idx = Math.min(ANIMATED_MAX, heatLevel * 2);
        return LIT[idx];
    }

    /**
     * 该档位对应的贴图是否带动画。
     *
     * <p>用来给渲染层决定要不要走「按 tick 播帧」那条路；{@code .mcmeta} 存在时会自动播，
     * 这里只是给需要手动干预的地方一个判据。
     */
    public static boolean isAnimated(int heatLevel) {
        return heatLevel > 0;
    }
}
