package net.chauvedev.woodencog.block.electricstove;

/**
 * 电阻炉的「烫伤」规则。
 *
 * <h2>老大 2026-11-17 定的</h2>
 *
 * <ul>
 *   <li>站在电阻炉<b>正上方</b>的玩家会被烫</li>
 *   <li>温度越高伤越重，<b>最高 12 血/秒</b>（6 颗心/秒）</li>
 *   <li>同时<b>着火</b></li>
 * </ul>
 *
 * <h2>伤害曲线</h2>
 *
 * <p>从「开始烫」到「满伤」做线性插值：
 *
 * <pre>
 *   温度 &lt; BURN_START_TEMPERATURE(200)  → 不烫
 *   温度 200 ~ BURN_FULL_TEMPERATURE(2000) → 伤害从 0 线性升到 12 血/秒
 *   温度 &gt; 2000                          → 恒定 12 血/秒
 * </pre>
 *
 * <p>为什么起点定在 200 度：低于这个温度炉子连 SMOULDERING 都算不上
 * （见 {@link ElectricStoveBlockEntity#NONE_THRESHOLD}），站上去不该挨烫。
 *
 * <h2>★ 伤害按「每秒」还是「每 tick」给</h2>
 *
 * <p>Minecraft 的伤害没法真的「每秒 12 血」直接施加 —— 那会是一次性掉 12。
 * 这里改用<b>定时器</b>：每 {@code DAMAGE_INTERVAL_TICKS}（10 tick = 0.5 秒）扣一次
 * 「半秒的量」。这样累计起来正好是每秒 12 血，而且不会每 tick 都刷伤害和击退。
 *
 * <p>半秒给一次还有个好处：玩家能反应过来跳开。每 tick 给 0.6 血会让人感觉
 * 是「血条被抽走」，半秒一次则是明确的「烫一下、再烫一下」。
 */
public final class StoveBurn {

    /** 低于这个温度不烫。 */
    public static final double BURN_START_TEMPERATURE = 200.0D;

    /** 达到这个温度就是满伤。 */
    public static final double BURN_FULL_TEMPERATURE = 2000.0D;

    /** 满伤时每秒掉多少血。 */
    public static final double MAX_DAMAGE_PER_SECOND = 12.0D;

    /** 每多少 tick 结算一次伤害。10 tick = 0.5 秒。 */
    public static final int DAMAGE_INTERVAL_TICKS = 10;

    /** 达到这个温度就会把玩家点着。 */
    public static final double IGNITE_TEMPERATURE = 400.0D;

    /** 点着多少秒。 */
    public static final int IGNITE_SECONDS = 3;

    private StoveBurn() {
    }

    /**
     * 某一温度下，每次结算应该造成的伤害。
     *
     * @return 伤害点数（半颗心 = 1 点）；0 表示不烫
     */
    public static float damagePerInterval(double temperature) {
        double perSecond = damagePerSecond(temperature);
        if (perSecond <= 0.0D) {
            return 0.0F;
        }
        return (float) (perSecond * DAMAGE_INTERVAL_TICKS / 20.0D);
    }

    /** 某一温度下每秒掉多少血。 */
    public static double damagePerSecond(double temperature) {
        if (temperature < BURN_START_TEMPERATURE) {
            return 0.0D;
        }
        if (temperature >= BURN_FULL_TEMPERATURE) {
            return MAX_DAMAGE_PER_SECOND;
        }
        double t = (temperature - BURN_START_TEMPERATURE)
            / (BURN_FULL_TEMPERATURE - BURN_START_TEMPERATURE);
        return MAX_DAMAGE_PER_SECOND * t;
    }

    /** 这个温度该不该把玩家点着。 */
    public static boolean shouldIgnite(double temperature) {
        return temperature >= IGNITE_TEMPERATURE;
    }
}
