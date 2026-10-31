package net.chauvedev.woodencog.block.tunnelheater;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.belt.BeltBlock;
import com.simibubi.create.content.kinetics.belt.BeltSlope;
import com.simibubi.create.content.kinetics.belt.behaviour.BeltProcessingBehaviour.ProcessingResult;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import com.simibubi.create.foundation.blockEntity.SyncedBlockEntity;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import com.simibubi.create.foundation.blockEntity.behaviour.scrollValue.ScrollOptionBehaviour;
import net.chauvedev.woodencog.compat.create.IAirHeated;
import net.chauvedev.woodencog.config.WoodenCogCommonConfigs;
import net.dries007.tfc.common.capabilities.heat.HeatCapability;
import net.dries007.tfc.common.capabilities.heat.IHeat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.energy.EnergyStorage;
import net.minecraftforge.energy.IEnergyStorage;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The block entity of a tunnel heater: it holds Forge Energy, and every tick it heats the item the
 * Create belt below it is carrying to that item's welding temperature.
 *
 * <p><b>It burns Forge Energy, not a fluid.</b> Immersive Engineering's wires, or any other Forge
 * Energy source, charge the battery a connected run of heaters shares; the heater then draws
 * {@code tunnel_heater_energy_per_tick} every tick it is switched on. There is no tank and no fuel item
 * any more, so the temperature is one number ({@code tunnel_heater_temperature}, 1500 by default, which
 * is what a tank of lava used to give it) rather than a per-fluid table.
 *
 * <h2>What it heats to</h2>
 *
 * <p>The ceiling is per item and comes from TFC: an item with a heat capability is heated towards its
 * own welding temperature, which is what TFC's anvil and TFC's welding need and is 0.8 of the metal's
 * melting point (copper 864, bronze 760, wrought iron 1228, steel 1232). An item TFC knows how to heat
 * but which has no welding temperature - food, ceramics, anything that is not a weldable metal - is
 * heated towards {@code fan.no_recipe_ceiling} instead, the same number the fan uses as its ceiling for
 * an item that has no recipe either. An item TFC does not know at all, or one whose ceiling is 0 or
 * less, is not touched.
 *
 * <p>The temperature itself is written through {@code HeatCapability.addTemp(IHeat, float, float)},
 * whose first float is an absolute ceiling rather than an amount of degrees: it computes the new
 * temperature, clamps it to that argument and only ever warms the item. Reaching the welding
 * temperature and stopping there is therefore the same call the flat ceil of a fan does, and the item
 * can never be pushed past it however long it sits under the heater. The rate is
 * {@code fan.tunnel_heater_heating_rate}, 12 degrees per tick out of the box - four times the 3 that
 * TFC's own one-argument {@code addTemp} passes, which is where the number comes from. What TFC
 * actually adds is {@code ((itemCoolingModifier - 1) + rate * itemHeatingModifier) / heatCapacity}, so
 * that constant {@code itemCoolingModifier - 1} drag keeps four times the number from being exactly
 * four times the speed.
 *
 * <p><b>A heater that is on draws power, and it is off until redstone turns it on.</b> The property
 * defaults to false, so a heater put down away from any signal does nothing; a redstone signal is what
 * starts it. A signal on any one member of a group is enough for the whole group: every heater of a
 * connected run works out "is my group on" from the same set of signals and reaches the same answer, so
 * one lever switches a line of heaters on together. Every tick the server runs, a heater
 * that has a signal and a battery with charge in it spends {@code tunnel_heater_energy_per_tick} out of
 * the battery the group shares and pushes the item below it towards its ceiling. That holds whether or
 * not the belt is carrying anything - a running machine keeps drawing - so the power bill of a tunnel
 * is its length, and the belt it heats is what the power buys.
 *
 * <p>A heater whose group has no signal, or whose battery is flat, draws nothing and heats
 * nothing, and a fan drawing its air through it picks up no heat from it either: an idle heater is not
 * a heater that runs for free, it is one that is not running. Note the direction of the flags: the
 * {@code POWERED} block state means "this block is signalled", the {@code ACTIVE} one means "this
 * block's group is working", and behaviour always asks {@link #isActive()}, so every test is written for
 * the signal rather than against it and a member that only works because a neighbour is signalled is lit
 * too.
 *
 * <p><b>One share per tick.</b> The same block is also a heat source of the fan air chain
 * ({@link IAirHeated}), and a fan whose airflow runs through it pays for the energy it took. Both
 * of those run inside one server tick and both go through {@link #consume(double)}, which is what keeps
 * the block from paying twice for the same tick: see the field {@link #lastConsumedTick}.
 *
 * <h2>Sharing charge between neighbours</h2>
 *
 * <p>Tunnel heaters that sit next to each other are one machine: they share a single battery of
 * {@code tunnel_heater_energy_capacity} per member, 4000 FE out of the box, so two of them hold 8000 FE
 * together. A fan or an Immersive Engineering wire that touches any member uses that one battery.
 *
 * <p>Which block entities belong to a group is decided by a breadth first walk over the four horizontal
 * neighbours, not by a facing property: the axis the block does carry is only which way its bore opens,
 * taken from the belt below it, and two heaters that touch are one machine whether their bores line up or
 * not. That is a deliberate widening of the brass tunnel's
 * {@code connectedLeft}/{@code connectedRight} skeleton, which walks one axis; a group here is the
 * connected component of the block, and on a straight line of heaters it is the same set the tunnel
 * rule would give.
 *
 * <p><b>The leader.</b> Every member answers through the same battery, and that battery lives in the leader:
 * the member of the group with the smallest position, compared x, then y, then z. The comparison is a
 * total order over a finite set, so every member computes the same leader from the same group without
 * talking to the others, and no member has to be elected or agree to anything. There is no chain of
 * delegation either - a member delegates to the leader and the leader delegates to nobody - so no cycle
 * can form, and the walk itself is breadth first with a visited set, so it terminates on any shape a
 * player can build, including a ring of heaters, and each member is looked at once.
 *
 * <p><b>The group is on when any member is signalled.</b> That is resolved in the same walk that picks
 * the leader ({@link #rebuildGroup()} folds the members' {@code POWERED} states together), so it needs no
 * message and no election either: two members of a group see the same connected component and the same
 * block states, so they cannot disagree. The cache therefore holds three things - the leader, the size
 * and the group's signal answer - and a signal that changes drops the cache of every member of the group
 * (the block writes {@code POWERED} in {@code TunnelHeaterBlock#neighborChanged}, which then asks every
 * member to {@link #invalidateGroup()}).
 *
 * <p><b>When the group is recomputed.</b> Each member caches the leader's position, the group size and
 * whether the group is signalled. The cache is dropped for <em>every</em> member of a group when a block
 * is placed or broken beside
 * one of them ({@code TunnelHeaterBlock#neighborChanged} asks each of them to
 * {@link #invalidateGroup()}), and for a whole group at once on the first tick after a member is
 * loaded ({@link #initialize()}). Asking every member and not only the block that changed is what makes
 * a group of three stay right when the middle one is broken: the two ends were never neighbours of each
 * other, and only a walk over the group as it was can reach both of them. A member whose cached leader
 * is gone also rebuilds before it answers, so a stale cache can delay a recomputation by at most the
 * tick it would have been invalidated in, never give a wrong leader.
 *
 * <p><b>No charge is lost or invented when the group changes size.</b> Every member keeps its own copy
 * of the group's charge, not only the leader, and the copies are kept in step:
 * <ul>
 *   <li>the leader writes its charge into every loaded member of the group on every change
 *       ({@link #onEnergyChanged()}), so when the leader is broken the member that takes over already
 *       holds the right amount;</li>
 *   <li>every copy carries the same revision counter, bumped by the leader on each change and copied
 *       along with the charge, and a group recomputation adopts the state with the highest revision. A
 *       freshly placed, empty heater that happens to hold the smallest position therefore cannot wipe
 *       the battery of the group it was placed into, and a member returning from an unloaded chunk cannot
 *       roll the group back to what it last saw. Ties go to the smallest position, so the winner is
 *       still a function of the positions alone.</li>
 *   <li>the physical capacity of the battery is {@code max(capacity * group size, what is stored)}. A group
 *       that shrinks keeps whatever it holds - the excess is not thrown away, it is simply more than the
 *       new share and becomes usable again if the group grows - and Forge's own {@code EnergyStorage#receiveEnergy}
 *       reads that same capacity field, so a receive can never compute a negative room and can never
 *       overwrite what is stored. Only a group of one with more than 4000 FE, and a group that keeps
 *       shrinking, can stay over its share.</li>
 * </ul>
 *
 * <p><b>Known limit.</b> The copies live in the block entities, so a member in an unloaded chunk cannot
 * be told about a change. If the leader is broken while another member's chunk is unloaded, that member
 * keeps the amount it was last told, which is what it will save and what it will lead with. The amount
 * is therefore exact whenever the old and the new leader are both loaded at the moment of the handover,
 * which is the case a player can see, and can be off by what was spent while one of them was not
 * loaded. Storing the battery outside the block entities - as one record per group in the level's saved
 * data - would remove that case and is deliberately not done here.
 *
 * <p><b>The same boundary applies to the group's signal.</b> A member in an unloaded chunk is not part of
 * the walk - there is no block entity there and its state is not read - so a signalled heater whose chunk
 * is unloaded does not switch the loaded part of its run on; the part that is loaded answers for itself
 * and works only if one of the members the walk can reach is signalled. When that chunk loads again,
 * {@link #initialize()} drops the cache of the whole group and every member reads the same set of signals
 * once more, so the run turns on within a tick of the chunk becoming readable. Nothing is stored about it
 * in between, which is why this is a boundary and not a bug: the answer is always a function of the
 * loaded group, never of what some member remembered.
 *
 * <h2>Telling the client</h2>
 *
 * <p>The item on the belt is a real item stack, and heating it changes its NBT in place, so the server's
 * copy is already correct; the item a client sees on the belt is the belt's own copy of that stack,
 * which only moves when the belt sends it. The belt is asked to with {@code notifyUpdate()}, which is
 * {@code setChanged()} plus {@code sendData()} and writes the belt's inventory - the items it carries -
 * into the packet. It is asked at most once every {@link #CLIENT_SYNC_INTERVAL_TICKS} ticks while the
 * temperature is climbing, plus once immediately when the item under the heater changes and once when
 * heating stops, so the client ends up with the ceiling exactly and not with the last value of a
 * throttle window. Nothing of TFC's or Create's draws an item's temperature, so the client copy matters
 * for the tooltip and for the item a player picks up, not for what is on screen. A hold is the one thing
 * here that is synced without the throttle, because a client that is one packet behind would be drawing
 * an item that is moving while the server is holding it.
 *
 * <h2>The mode</h2>
 *
 * <p>What the heater aims at, and whether it stops the belt until it gets there, is the mode. It is
 * chosen with a Create value box on the roof of the block, the same slot a brass tunnel uses to choose
 * what it does with the items it takes in, and it is saved by that value box as the {@code ScrollValue}
 * key of this block entity's tag - an int holding a {@link TunnelHeaterMode} ordinal. Modes are
 * {@link TunnelHeaterMode#HEAT}, which is what this block did before modes existed and is the default,
 * and the two that hold the belt: {@link TunnelHeaterMode#WORKABLE} and {@link TunnelHeaterMode#WELDABLE}.
 *
 * <p>Like the battery and the redstone answer, the mode is a property of the group: every member of a group
 * reports the leader's mode, turning one heater's value box turns the whole connected run, and a member
 * that joins a group - placed, loaded, or left behind when a group splits - is brought onto the group's
 * mode as the group is resolved. The scope is {@link #collectGroup()}, the same four-horizontal-neighbour
 * connected component that shares the battery, so the heaters that switch together are exactly the ones that
 * draw on one battery.
 *
 * <p>The mode does not touch anything the fan sees: {@link #getAirTemperature()} and the power draw are
 * the same in all three. It only decides what happens to the item on the belt below.
 */
public class TunnelHeaterBlockEntity extends SmartBlockEntity implements IAirHeated {

    /**
     * The share of the shared battery one tunnel heater adds, in Forge Energy.
     *
     * <p>Kept as the shipped number only; what a battery actually holds is
     * {@code tunnel_heater_energy_capacity} times the size of the group, read through
     * {@link WoodenCogCommonConfigs#tunnelHeaterEnergyCapacityPerBlock()}.
     */
    public static final int ENERGY_CAPACITY_PER_BLOCK = 4000;

    private static final String ENERGY_KEY = "Energy";
    private static final String PENDING_CONSUMPTION_KEY = "PendingConsumption";
    private static final String ENERGY_REVISION_KEY = "EnergyRevision";

    /**
     * How often the belt is asked to send its inventory while a temperature is climbing. Four packets a
     * second per heater: often enough that a tooltip never shows a value from more than a quarter of a
     * second ago, cheap enough that a long tunnel of heaters is not a network problem.
     */
    private static final int CLIENT_SYNC_INTERVAL_TICKS = 5;

    private final GroupBattery battery = new GroupBattery();
    private final LazyOptional<IEnergyStorage> energyHandler = LazyOptional.of(GroupEnergyHandler::new);

    /** Forge Energy owed but not yet taken, the same fractional carry an air chamber keeps. */
    private double pendingConsumption;

    /** Bumped by the leader whenever the shared charge changes; the newest state of a group wins. */
    private int energyRevision;

    /** The leader of the group this member last resolved, how many members it had, and whether any of them is signalled. */
    @Nullable
    private BlockPos cachedLeader;
    private int cachedGroupSize;
    private boolean cachedGroupActive;

    /** The stack the heater heated last tick, by identity, so a new item is synced at once. */
    private ItemStack lastHeatedStack = ItemStack.EMPTY;

    private boolean heatedLastTick;
    private int syncCooldown;

    /**
     * The one item this heater has already heated to its target, and the target it reached, both by
     * identity. This is what lets the belt go again: see {@link #holdDecisionFor(TunnelHeaterMode,
     * ItemStack)} for why a state and not the temperature itself.
     *
     * <p>An item's temperature is not a value that stays put. TFC's {@code HeatHandler.getTemperature()}
     * is the stored temperature minus the cooling that happened since it was last written - {@code
     * itemCoolingModifier / heatCapacity} per tick, 0.28 for an ingot of black steel or wrought iron
     * (heat_capacity 2.857, itemCoolingModifier 0.8) - so a number read from it is only true for the tick
     * it was written in. The heater writes exactly the target, and the belt asks its question in its own
     * tick, which may come before or after this block's: whenever the belt asks first, the item reads as
     * one tick short of the target and a comparison against that target holds it forever. Calling a number
     * that is on its way down "not there yet" is the bug this pair of fields replaces.
     *
     * <p>Identity, not contents: the belt hands out the live {@code TransportedItemStack.stack} object
     * ({@code ItemHandlerBeltSegment.getStackInSlot} returns that field, no copy), so the same item is the
     * same object on every tick and through both of the paths that ask this question. Two identical items
     * passing one after the other are two objects, which is the point.
     *
     * <p>{@link #satisfiedTarget} is checked along with the item so that changing the mode - or the
     * margin, or the item's own temperatures - drops the claim by itself: the target this item reached is
     * not the target it now has to reach, so it waits for the new one. No extra clearing is needed for
     * that case.
     *
     * <p>Neither field is saved, on purpose. A chunk that unloads and loads again forgets the claim, and
     * the item under the heater is judged from its temperature once more - which can hold an item that has
     * since cooled down (never release one that has not arrived), and it is set again on the first tick
     * that reaches the target. Saving it would need a new tag key and would outlive the item it is about.
     */
    private ItemStack satisfiedStack = ItemStack.EMPTY;
    private float satisfiedTarget;

    /**
     * The game tick this block last paid its power for, so that it can never pay twice in one tick.
     *
     * <p>Two things charge the same block within one server tick: its own {@link #tick()}, because a
     * heater that is on draws, and {@code HotAirChamberHeat#consume}, because a fan whose airflow runs
     * through the block pays for the air it took. Both of them go through {@link #consume(double)},
     * which compares this field against {@code Level#getGameTime()}: that number is raised once per
     * server tick and both callers run inside the same tick, so the second caller of the tick finds the
     * tick already paid for and returns without draining anything.
     *
     * <p>The field is deliberately not saved and not shared with the rest of the group. It belongs to
     * the block, not to the battery: two heaters of one group each draw their own share, and only the
     * attempts of a single block are folded together. Nothing has to be remembered across a save either,
     * because the guard is only meant to hold within one tick.
     */
    private long lastConsumedTick = Long.MIN_VALUE;

    /**
     * The mode of this heater, as a Create value box on its roof. The value and the block are
     * {@code ScrollOptionBehaviour}'s: the box is clicked and scrolled by Create's own
     * {@code ValueSettingsInputHandler}, the choice travels to the server in Create's
     * {@code ValueSettingsPacket}, and it is saved as the {@code ScrollValue} key of this block
     * entity's tag, an int holding a {@link TunnelHeaterMode} ordinal.
     *
     * <p>Null only before {@link #addBehaviours(List)} has run, which is during construction; every
     * write and every read of it below goes through a null check for that reason, so a block entity
     * that was handed a tag before its behaviour existed cannot throw.
     */
    @Nullable
    private ScrollOptionBehaviour<TunnelHeaterMode> modeBehaviour;

    /**
     * What stops the belt's item under this heater, and what lets it go again. This is Create's own
     * behaviour for exactly this job - the one a deployer, a press and a saw hang on themselves - and it
     * is what makes the hold survive both sides of the game, by writing Create's own
     * {@code TransportedItemStack.locked} flag rather than a flag of ours.
     *
     * <p>It is resolved by the belt, not by us, but not at the height Create looks for by default: a
     * tunnel heater stands one block above the belt (the belt runs through it) where Create's own machines
     * stand two blocks above it, so this is a {@link TunnelHeaterProcessingBehaviour} and
     * {@code MixinBeltInventory} is what teaches the belt's lookup to look one block lower for exactly that
     * type. Nothing here registers anything: the belt asks, this block answers.
     *
     * <p>And once a behaviour has been found, {@code MixinBeltProcessingBehaviour} is the other half: the
     * belt's "is the space above me free" test reads the tunnel's own collision shape as an obstruction, so
     * without that patch the belt would find this behaviour and then never ask it anything.
     */
    private TunnelHeaterProcessingBehaviour processingBehaviour;

    public TunnelHeaterBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    /**
     * Hangs the mode value box on this block. It sits on the roof, and its callback is what makes one
     * of a group of heaters carry the rest with it when a player turns it.
     *
     * <p>There is deliberately no {@code onlyActiveWhen}: the box works whether or not the heater is
     * switched on, so a mode can be chosen before the power or the redstone is there. Create's
     * {@code onlyActiveWhen} would otherwise make the box refuse to open on a block that is off, which
     * is exactly the state a freshly placed heater is in.
     */
    @Override
    public void addBehaviours(List<BlockEntityBehaviour> behaviours) {
        // The mode box. The block is filled through its own energy capability and switched on with plain
        // redstone, so there is no filter and no inventory to hang here.
        this.modeBehaviour = new ScrollOptionBehaviour<>(TunnelHeaterMode.class,
                Component.translatable("woodencog.tunnel_heater.mode"), this, new TunnelHeaterMode.ModeValueBox());
        this.modeBehaviour.withCallback(ordinal -> this.onModeChanged(TunnelHeaterMode.byOrdinal(ordinal)));
        behaviours.add(this.modeBehaviour);
        // The default of a fresh behaviour is already 0, the original mode; saying so out loud is what
        // documents that the default is load-bearing rather than incidental.
        this.modeBehaviour.setValue(TunnelHeaterMode.HEAT.ordinal());

        // And the belt hook. Both callbacks are the same question - "is this item still short of what
        // this mode wants?" - and the answer decides between holding it where it is and letting the belt
        // carry on. `whenItemEnters` runs when the item first reaches this segment, `whileItemHeld` runs
        // every tick afterwards for as long as it is being held, and answering HOLD again from there is
        // what keeps it held; answering PASS is what releases it - on the tick this item is found to have
        // reached the target (see #satisfiedStack) or the tick the machine stops being able to heat it.
        this.processingBehaviour = new TunnelHeaterProcessingBehaviour(this,
                (transported, handler) -> this.holdDecision(transported));
        behaviours.add(this.processingBehaviour);
    }

    /**
     * The belt's question, answered: {@link ProcessingResult#HOLD} to stop this item under the heater
     * until it is hot enough, {@link ProcessingResult#PASS} to let the belt carry it on.
     *
     * <p>Runs on the server only - Create calls these callbacks from the server half of its belt tick -
     * and Create is what turns a HOLD into a lock and a PASS into a release, so this method writes no
     * flag and keeps no state of its own: it only answers the question again. The one thing it reads
     * besides the item is this heater's claim that the item has already reached its target
     * ({@link #satisfiedStack}), which is what makes the answer stop changing once it is "go".
     *
     * <p>A machine that cannot heat right now answers PASS, which is what makes the hold end when the
     * redstone goes away, the power runs out or the mode is turned back to {@code heat}. So does an item
     * with no heat capability, or one whose mode has no target for it - the two ways an item that has
     * nothing to wait for is let through instead of being parked under the heater for good.
     */
    private ProcessingResult holdDecision(TransportedItemStack transported) {
        TunnelHeaterMode mode = this.groupMode();
        if (mode == TunnelHeaterMode.HEAT || !this.isActive() || !this.isHeating()) {
            return ProcessingResult.PASS;
        }
        return this.holdDecisionFor(mode, transported).hold()
                ? ProcessingResult.HOLD
                : ProcessingResult.PASS;
    }

    /**
     * Called once, on the first tick. The group this block belongs to is resolved then rather than as it
     * is loaded, so that loading a chunk never reaches into the block entities of its neighbours before
     * they exist; {@code SmartBlockEntity} calls this only for a block entity that has a level.
     */
    @Override
    public void initialize() {
        super.initialize();
        // The value box has been read by now - SmartBlockEntity reads the tag before it initialises - so
        // this is the first moment this block knows both its own mode and the group it belongs to. The
        // rebuild that follows is what puts the two together.
        invalidateGroup();
    }

    // ---------------------------------------------------------------------------------------------
    // Tick: draw this tick's power and heat the item the belt below is carrying
    // ---------------------------------------------------------------------------------------------

    /**
     * One tick of this heater. On the server it is the one entry point of "on means drawing"; on the
     * client it does nothing at all and returns, because everything it used to do there is Create's job
     * now: the belt's own tick stops a held item on both sides, from the lock Create wrote into the item
     * on the server.
     *
     * <p>What this tick does to the belt is the heating half only - pushing the item under it towards the
     * mode's target. Stopping the item is not here: it is {@link #holdDecision}, which the belt's
     * processing callback asks, and which answers the same target. Splitting them this way is what lets
     * the hold run on Create's own flag instead of one of ours.
     *
     * <p>The one thing this tick does for the stopping half is remember the item it found at the target
     * ({@link #rememberSatisfied(TunnelHeaterMode, BeltDecision)}) and drop the item it remembered before
     * that when the belt has moved it on ({@link #forgetSatisfiedIfBeltMovedOn()}). Both are here because
     * the tick is the only place that sees the ceiling and the item in the same breath, and because a
     * claim has to be recorded on the very tick the item arrives - the belt may have asked its question
     * earlier in the same tick, when the item was still a fraction below the target.
     *
     * <p>The order on the server is the point. A running heater pays its share of the power <em>before</em> it
     * looks at the belt, so it pays on a tick when the belt is empty, when the item on it has no heat
     * capability and when the item has already reached its target - exactly like a furnace that is kept
     * lit. Then the item is pushed towards the target the mode gives it.
     *
     * <p>Both halves sit behind the same condition: the heater's group is on, and the battery holds charge
     * that actually heats. A heater whose group has no signal and an empty one are the same thing here, a
     * machine that is not running, and the block is placed without a signal - so a fresh tunnel heater
     * does nothing until it, or a heater connected to it, is switched on. A machine that is off holds
     * nothing: the belt belongs to the player while the heater is not running.
     */
    @Override
    public void tick() {
        super.tick();
        if (this.level == null) {
            return;
        }

        TunnelHeaterMode mode = this.groupMode();

        if (this.level.isClientSide) {
            // The client has nothing to do here any more. Holding is Create's own `locked` flag now, set on
            // the server by the belt processing callback below and carried to this side inside the belt's
            // saved item ("Locked" in TransportedItemStack.write/read), and the client's belt tick already
            // honours that flag - so a held item stays put here without this block doing anything. Running
            // and heating were never the client's job. The `lockedExternally` route this replaces did not
            // survive the same trip, which is why the item was not actually stopped in game.
            return;
        }

        // The item this heater last claimed has arrived - is it still the one under this heater? If the
        // belt has carried it on, taken it away or replaced it with another, the claim is about an item
        // that is no longer here and is dropped before anything can be let through on it. This runs every
        // server tick, whatever the mode, the redstone and the power are, and it costs one belt lookup only
        // while a claim is outstanding.
        this.forgetSatisfiedIfBeltMovedOn();

        // Whether the group is on is a property of the group, not of this block: one signalled member
        // turns the whole connected run on. The block state only carries what is shown, so the answer is
        // mirrored into it here, on the server, and only when it differs.
        //
        // ★ What is mirrored is the WORKING state and not the signal: a heater runs only when its group
        // is signalled AND the battery the group shares has charge, so a signalled heater with a flat
        // battery shows as off. isHeating() is the single place that answers that conjunction (see
        // getAirTemperature), which is why the shown state and the work below cannot disagree.
        boolean running = this.isHeating();
        this.syncActiveState(running);

        // The belt hangs off the same answer: a heater that is not running holds nothing either - the
        // belt belongs to the player while the machine is off - which is the first of the two ways a
        // held item is released.

        // What this tick wants to do with the item: whether it is to be stopped where it is, and what
        // temperature it is pushed towards. One answer for the hold and the heating, so the two cannot
        // disagree about the item or about the number.
        BeltDecision decision = running ? this.decideFor(mode) : BeltDecision.NONE;

        if (running) {
            // The share of this block, out of the battery the whole group shares. Goes through consume()
            // and not consumeLocally(), so that a fan that also draws on this block this tick finds the
            // tick already paid for and does not charge it a second time. A lit heater draws whether or
            // not there is an item under it, which is unchanged.
            this.consume(this.consumptionPerTick());

            // ★ The draw above can spend the very last of the charge, and the state mirrored at the top
            // of this tick was worked out before that happened. Without this re-read a heater that has
            // just run dry would keep the lit model for one more tick. Reading the same answer again and
            // mirroring it here puts the model back on the unlit set on the tick the battery empties,
            // with no block update and no neighbour involved. `running` is narrowed too, so an item that
            // could not be paid for is not heated on this tick either.
            boolean stillRunning = this.isHeating();
            if (stillRunning != running) {
                this.syncActiveState(stillRunning);
                running = stillRunning;
            }
        }

        boolean heat = running && this.heatBeltStack(decision.ceiling());

        // Right after the heating, and in this tick only, is where "this item has arrived" can be read
        // reliably: `heatBeltStack` refuses to write anything once the item is at the ceiling, so an item
        // that reads as reached here either was just written to exactly the ceiling or arrived that hot.
        // It has to be read here rather than inside the decision, because the belt may ask its question
        // before this block ticks - then it sees a temperature one tick short of the target, and the tick
        // that finally reaches the target is this one.
        if (running) {
            this.rememberSatisfied(mode, decision);
        }

        if (heat) {
            this.heatedLastTick = true;
            if (this.syncCooldown > 0) {
                this.syncCooldown--;
            } else {
                this.syncBelt();
            }
            return;
        }

        // The temperature stopped climbing: the item is gone, it has nothing TFC can heat, it has
        // reached its target, or this heater is holding it. That last case is the interesting one - the
        // client is owed the exact ceiling, which a plain throttle could have missed - so this one sync
        // is not throttled.
        if (this.heatedLastTick) {
            this.heatedLastTick = false;
            syncBelt();
        }
        this.syncCooldown = 0;
    }

    /**
     * What this tick does to the item the belt below is carrying: whether to stop it, and how hot it
     * should get. One answer per item per tick, so that the hold and the heating cannot disagree about
     * which item, or about what the target is.
     *
     * @param hold    whether the item is to be stopped under this heater
     * @param ceiling the absolute temperature the item is pushed towards, or {@link #NO_CEILING}
     * @param item    the item the answer is about, the very object the belt stores - which is what lets
     *                the tick record the item it heated to the ceiling by identity, see
     *                {@link #rememberSatisfied(TunnelHeaterMode, BeltDecision)}
     */
    private record BeltDecision(boolean hold, float ceiling, ItemStack item) {

        /** "No hold and nothing to heat": the answer for an idle heater and for an item that arrived. */
        private static final BeltDecision NONE = new BeltDecision(false, NO_CEILING, ItemStack.EMPTY);
    }

    /** A ceiling no item can have, used to mean "do not heat this". */
    private static final float NO_CEILING = -1.0F;

    /**
     * Reads the belt below this heater and answers what this tick should do with the item on it, or
     * {@link BeltDecision#NONE} when there is no belt or no item under this heater.
     *
     * <p>This is only the heating half of the tick. Whether the item is <em>stopped</em> is answered by
     * {@link #holdDecisionFor(TunnelHeaterMode, TransportedItemStack)}, which is what the belt's own
     * processing callback asks; both go through the same target, so the item is never heated past the
     * number it is being held for, and never held for a number it is not being heated to.
     */
    private BeltDecision decideFor(TunnelHeaterMode mode) {
        BlockPos beltPos = this.worldPosition.below();
        BlockState beltState = this.level == null ? null : this.level.getBlockState(beltPos);
        if (beltState == null || !AllBlocks.BELT.has(beltState)
                || beltState.getValue(BeltBlock.SLOPE) != BeltSlope.HORIZONTAL) {
            return BeltDecision.NONE;
        }
        ItemStack stack = beltStack(beltPos);
        if (stack.isEmpty()) {
            return BeltDecision.NONE;
        }
        return holdDecisionFor(mode, stack);
    }

    /**
     * Whether the belt should stop the given item under this heater, and what temperature that wait is
     * for. The answer for {@code mode} and one item, with no belt lookup of its own - it is what both the
     * heating half of the tick and the belt's processing callback run on.
     *
     * <p>The mode decides the target, and the item decides whether that target exists at all:
     *
     * <ul>
     *   <li>{@code heat} is the welding temperature times the margin - or the no-recipe ceiling for an
     *       item that has no welding temperature - and nothing is ever held in it.</li>
     *   <li>{@code workable} and {@code weldable} aim at the item's own working or welding temperature
     *       times {@code fan.tunnel_heater_target_margin}, and hold the item until it has been that hot.</li>
     *   <li>An item with no working temperature, or no welding temperature, has a target of nothing. It
     *       is then neither heated nor held: it has no target to wait for, and holding it would stop the
     *       belt for good. That is the one case in these two modes where the belt is let through, and it
     *       is why the target is tested here rather than only where the item is heated.</li>
     *   <li>The item is let through once it has <em>reached</em> the target, and stays let through: this
     *       heater remembers the item it heated to the target ({@link #satisfiedStack}) instead of
     *       comparing the live temperature every tick. The belt asks again every tick, so the first
     *       answer of "arrived" is what releases it - and it cannot be taken back a tick later, when the
     *       temperature has already cooled below the target it was written to.</li>
     *   <li>A stack TFC gives no heat capability at all is not touched and not held - there is nothing
     *       to heat and nothing to wait for. That is the same answer an item with no target gets.</li>
     * </ul>
     */
    private BeltDecision holdDecisionFor(TunnelHeaterMode mode, ItemStack stack) {
        if (stack.isEmpty()) {
            return BeltDecision.NONE;
        }
        IHeat heat = HeatCapability.get(stack);
        if (heat == null) {
            return BeltDecision.NONE;
        }
        float target = targetFor(mode, heat);
        if (target <= 0.0F) {
            return BeltDecision.NONE;
        }
        // Only the two waiting modes ever hold. In `heat` the item keeps moving whatever its
        // temperature is - that is the original behaviour of the block, and the reason the target being
        // below the item's temperature is not the only thing that decides a hold. Asking for the target
        // in that mode still matters: it is the ceiling the item is heated to.
        //
        // 老大 2026-10-15: the hold is no longer "is the temperature short of the target right now".
        // That number is on its way down from the moment it is written (see #satisfiedStack), so the
        // answer depended on whether the belt asked before or after this block's tick - and with the
        // belt asking first, a held item read as one tick short of its target forever and the belt never
        // moved again. The wait is a state now: this item once reached this target, so it goes.
        boolean reached = mode != TunnelHeaterMode.HEAT && this.reachedTarget(stack, target, heat);
        boolean hold = mode != TunnelHeaterMode.HEAT && !reached;
        return new BeltDecision(hold, target, stack);
    }

    /**
     * Whether this item has reached {@code target} - now, or on an earlier tick. Saying yes now records
     * the claim for the ticks that come after; saying yes from the record is what lets the item go while
     * its temperature has already started to fall.
     *
     * <p>Both halves mean "has been there", not "is there": the difference is the whole point, because
     * the belt's question may arrive on a tick when the item is a fraction of a degree below the target
     * it was written to. A fraction that only ever grows.
     */
    private boolean reachedTarget(ItemStack stack, float target, IHeat heat) {
        if (heat.getTemperature() >= target) {
            this.satisfiedStack = stack;
            this.satisfiedTarget = target;
            return true;
        }
        return this.hasReachedTarget(stack, target);
    }

    /**
     * The claim recorded for the item under this heater: this very object, at this very target.
     *
     * <p>An exact target comparison, not a tolerance. The target comes from {@link #targetFor} for a mode
     * and an item, so it changes exactly when the answer to "what is this item waiting for" changes - a
     * different mode, a different margin, a different item - and that is when the claim should be dropped.
     * Nothing else can move it.
     */
    private boolean hasReachedTarget(ItemStack stack, float target) {
        return stack == this.satisfiedStack && target == this.satisfiedTarget;
    }

    /**
     * Records that the item of this tick's decision has reached the target it was given, so that the next
     * question about it is answered "go" whatever its temperature does.
     *
     * <p>Only the two waiting modes record anything: {@code heat} never holds, so a claim there would
     * only be read after a mode change, and it is the mode change that decides the new target anyway.
     * Nothing is recorded for a decision that has no ceiling, which is the answer for an item TFC cannot
     * heat or one whose mode has no target for it - neither of those is waiting for a number, and the
     * belt is already being let through.
     */
    private void rememberSatisfied(TunnelHeaterMode mode, BeltDecision decision) {
        if (mode == TunnelHeaterMode.HEAT || decision.ceiling() <= 0.0F || decision.item().isEmpty()) {
            return;
        }
        IHeat heat = HeatCapability.get(decision.item());
        if (heat != null && heat.getTemperature() >= decision.ceiling()) {
            this.satisfiedStack = decision.item();
            this.satisfiedTarget = decision.ceiling();
        }
    }

    /**
     * Drops the claim when the item it is about is no longer the item under this heater.
     *
     * <p>That one comparison is every way the claim can go stale: the belt carried the item on, the
     * segment is empty, the item was taken out by a funnel or a player, it was moved to another segment
     * of the belt, or a different item arrived - the belt hands out the live stored object, so anything
     * but "the same object again" means the claimed item is gone. A mode change needs no clearing of its
     * own ({@link #hasReachedTarget} compares the target too), and a broken block needs none either: the
     * claim is a field of this block entity, so it dies with it.
     *
     * <p>Deliberately not cleared by cooling. An item that reached its target and then sat there while
     * the heater was switched off is an item this heater already did its job for; holding it again when
     * the player switches the heater back on would fight the player, and it would re-hold an item the
     * belt is entitled to carry away.
     */
    private void forgetSatisfiedIfBeltMovedOn() {
        if (this.satisfiedStack.isEmpty()) {
            return;
        }
        if (this.beltStack(this.worldPosition.below()) != this.satisfiedStack) {
            this.satisfiedStack = ItemStack.EMPTY;
            this.satisfiedTarget = 0.0F;
        }
    }

    /**
     * The same answer as {@link #holdDecisionFor(TunnelHeaterMode, ItemStack)}, for the item the belt
     * hands the processing callback rather than for the stack this block looks up itself.
     */
    private BeltDecision holdDecisionFor(TunnelHeaterMode mode, TransportedItemStack transported) {
        return holdDecisionFor(mode, transported.stack);
    }

    /**
     * The absolute temperature a mode aims this item at, or {@code 0} when the mode has no target for it.
     *
     * <p>The margin - {@code fan.tunnel_heater_target_margin}, 1.2 - is what every mode that has a
     * temperature of the item's own multiplies by, so that an item is left hot enough for TFC to actually
     * let the player work or weld it rather than sitting exactly on the threshold. In {@code heat} it
     * applies to the item's welding temperature, which is the same number {@code weldable} waits for: the
     * two modes heat to the same place and differ only in whether they stop the belt there.
     *
     * <p>It does <b>not</b> apply to {@code fan.no_recipe_ceiling}, the fallback for an item with no
     * welding temperature of its own. That number is already a ceiling chosen by the config rather than a
     * temperature the item has, and multiplying it would heat food and ceramics to 1.2 of a value that was
     * never theirs - 1600 becomes 1920. So an item with no welding temperature is heated to the fallback
     * exactly, in every mode.
     *
     * <p>{@code workable} and {@code weldable} have no fallback at all, on purpose: an item whose working
     * temperature is 0 has no working temperature, and the no-recipe ceiling is not a stand-in for one,
     * because waiting for it is not what the player asked for and would mean holding an item that TFC will
     * never let them use.
     */
    private static float targetFor(TunnelHeaterMode mode, IHeat heat) {
        float margin = WoodenCogCommonConfigs.tunnelHeaterTargetMargin();
        float weldingTemperature = heat.getWeldingTemperature();
        return switch (mode) {
            // The margin only where the item really has a welding temperature; otherwise the plain
            // fallback, untouched.
            case HEAT -> weldingTemperature > 0.0F
                    ? weldingTemperature * margin
                    : ceilingOf(heat);
            case WORKABLE -> heat.getWorkingTemperature() * margin;
            case WELDABLE -> weldingTemperature * margin;
        };
    }

    /**
     * Pushes the item the belt below is carrying towards {@code ceiling}, and answers whether its
     * temperature moved.
     *
     * <p>Called only by {@link #tick()}, and only for a heater that is drawing, so the redstone state,
     * the power and the mode are already settled: what is left here is the belt, and each step is the
     * cheapest way to find out that there is nothing to do. Every refusal below is such a step and none
     * of them is a reason to hold the belt - whether the belt is held is answered by
     * {@link #holdDecision(TransportedItemStack)}, on the belt's own question.
     */
    private boolean heatBeltStack(float ceiling) {
        BlockPos beltPos = this.worldPosition.below();
        if (this.level == null) {
            return false;
        }
        BlockState beltState = this.level.getBlockState(beltPos);
        if (!AllBlocks.BELT.has(beltState) || beltState.getValue(BeltBlock.SLOPE) != BeltSlope.HORIZONTAL) {
            return false;
        }
        ItemStack stack = beltStack(beltPos);
        if (stack.isEmpty()) {
            return false;
        }
        IHeat heat = HeatCapability.get(stack);
        if (heat == null) {
            return false;
        }
        if (ceiling <= 0.0F) {
            return false;
        }
        float before = heat.getTemperature();
        if (before >= ceiling) {
            return false;
        }
        if (stack != this.lastHeatedStack) {
            // A different item is under the heater now; its first temperature goes out at once.
            this.lastHeatedStack = stack;
            this.syncCooldown = 0;
        }
        HeatCapability.addTemp(heat, ceiling, WoodenCogCommonConfigs.tunnelHeaterHeatingRate());
        return heat.getTemperature() > before;
    }

    /**
     * The temperature this item is heated to and stopped at: its own welding temperature when TFC gives
     * it one, and the config's no-recipe ceiling when it does not.
     */
    private static float ceilingOf(IHeat heat) {
        float weldingTemperature = heat.getWeldingTemperature();
        return weldingTemperature > 0.0F ? weldingTemperature : WoodenCogCommonConfigs.fanNoRecipeCeiling();
    }

    /**
     * The stack the Create belt at {@code beltPos} carries there.
     *
     * <p>A belt answers its item capability with an {@code ItemHandlerBeltSegment}, one slot wide, and
     * that slot is the stack the belt has stored at this position - the very object, not a copy of it,
     * so the heat written into it is written into the belt's inventory. This is the same lookup a brass
     * tunnel uses to read and write the item passing under it.
     */
    private ItemStack beltStack(BlockPos beltPos) {
        BlockEntity belt = this.level == null ? null : this.level.getBlockEntity(beltPos);
        if (belt == null) {
            return ItemStack.EMPTY;
        }
        return belt.getCapability(ForgeCapabilities.ITEM_HANDLER)
                .map(handler -> handler.getSlots() > 0 ? handler.getStackInSlot(0) : ItemStack.EMPTY)
                .orElse(ItemStack.EMPTY);
    }

    /** Tells the belt below to save and to send its inventory, which carries the item's temperature. */
    private void syncBelt() {
        this.syncCooldown = CLIENT_SYNC_INTERVAL_TICKS;
        if (this.level == null) {
            return;
        }
        if (this.level.getBlockEntity(this.worldPosition.below()) instanceof SyncedBlockEntity belt) {
            belt.notifyUpdate();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // The group of tunnel heaters that share one battery
    // ---------------------------------------------------------------------------------------------

    /**
     * Forgets the resolved group of every member of the group this block entity is in, so that the next
     * question rebuilds it. Called when a block is placed or broken beside a member, and on the first
     * tick after a member is loaded.
     */
    public void invalidateGroup() {
        for (TunnelHeaterBlockEntity member : collectGroup()) {
            member.cachedLeader = null;
            member.cachedGroupSize = 0;
            member.cachedGroupActive = false;
        }
    }

    /**
     * Tells the tunnel heaters at {@code pos} and beside it that the shape of their group may have
     * changed. The block at {@code pos} may itself already be gone - a broken heater still notifies its
     * neighbours, and those are the ones that have to look again.
     */
    public static void invalidateGroupsAround(Level level, BlockPos pos) {
        invalidateGroupAt(level, pos);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            invalidateGroupAt(level, pos.relative(direction));
        }
    }

    private static void invalidateGroupAt(Level level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof TunnelHeaterBlockEntity heater) {
            heater.invalidateGroup();
        }
    }

    /**
     * The group this block entity is in, as a breadth first walk over the four horizontal neighbours.
     * Members in chunks that are not loaded are left out: their block entities do not exist, and no
     * charge can be moved into or out of them until they do.
     */
    private List<TunnelHeaterBlockEntity> collectGroup() {
        List<TunnelHeaterBlockEntity> group = new ArrayList<>();
        if (this.level == null) {
            group.add(this);
            return group;
        }

        Set<BlockPos> visited = new HashSet<>();
        Deque<BlockPos> pending = new ArrayDeque<>();
        visited.add(this.worldPosition);
        pending.add(this.worldPosition);
        while (!pending.isEmpty()) {
            BlockPos pos = pending.poll();
            TunnelHeaterBlockEntity member = heaterAt(pos);
            if (member == null) {
                continue;
            }
            group.add(member);
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                BlockPos next = pos.relative(direction);
                if (visited.add(next)) {
                    pending.add(next);
                }
            }
        }
        if (group.isEmpty()) {
            group.add(this);
        }
        return group;
    }

    /** The tunnel heater block entity at {@code pos}, or null when there is none to talk to. */
    @Nullable
    private TunnelHeaterBlockEntity heaterAt(BlockPos pos) {
        if (this.level == null || !this.level.isLoaded(pos)) {
            return null;
        }
        return this.level.getBlockEntity(pos) instanceof TunnelHeaterBlockEntity heater ? heater : null;
    }

    /**
     * Works out the group this block entity is in, points every member at the leader, decides whether the
     * group is signalled, and makes the charge of every member agree with the newest state in the group.
     *
     * <p>Called only when a member has no usable cache, so the walk is not on the per tick path of an
     * idle block. It writes into the other members of the group (their cached leader, their cached size,
     * their cached answer to "is the group on" and, when they are behind, their charge), and it never
     * calls anything that comes back here, so it cannot recurse: the charge is written with
     * {@code GroupBattery#setEnergy}, which does not notify, and the capacity is written through
     * {@link GroupBattery#reconcileCapacity(int)}, which only compares numbers.
     *
     * <p><b>The group is on when any of its members carries a redstone signal.</b> That is read here, off
     * the block states of the members of this very walk, and every member stores the same answer: two
     * members of one group walk the same connected component and read the same signals, so they agree
     * without a message being sent, exactly like the leader they also agree on. A member in a chunk that
     * is not loaded has no block entity and no state to read, so it is not part of the walk and its
     * signal does not count until its chunk does.
     */
    private void rebuildGroup() {
        List<TunnelHeaterBlockEntity> group = collectGroup();

        TunnelHeaterBlockEntity leader = this;
        TunnelHeaterBlockEntity newest = this;
        boolean active = false;
        for (TunnelHeaterBlockEntity member : group) {
            if (isEarlier(member.worldPosition, leader.worldPosition)) {
                leader = member;
            }
            if (member.energyRevision > newest.energyRevision
                    || (member.energyRevision == newest.energyRevision
                        && isEarlier(member.worldPosition, newest.worldPosition))) {
                newest = member;
            }
            active |= member.isPowered();
        }

        for (TunnelHeaterBlockEntity member : group) {
            member.cachedLeader = leader.worldPosition;
            member.cachedGroupSize = group.size();
            member.cachedGroupActive = active;
            if (member != newest && isBehind(member, newest)) {
                member.battery.setEnergy(newest.battery.getEnergyStored());
                member.pendingConsumption = newest.pendingConsumption;
                member.energyRevision = newest.energyRevision;
                member.setChanged();
            }
        }
        leader.battery.reconcileCapacity(
                group.size() * WoodenCogCommonConfigs.tunnelHeaterEnergyCapacityPerBlock());
        leader.setChanged();

        // The mode is a property of the group, and a group that has just changed shape has to be brought
        // onto one answer. This is the last thing the walk does, so it costs the members one comparison
        // each on a path that costs a breadth first walk already.
        alignModeWithLeader(group, leader);
    }

    /**
     * Whether a member's copy of the shared state is out of date. The revision covers the charge itself;
     * the carry is compared on its own because a fan can raise it without a unit of energy being due yet,
     * and the amount is compared because a member that has just been loaded from a stale save is a
     * member of the group all the same. After a member is brought up to date all three are equal to the
     * newest, so this never asks for a second pass.
     */
    private static boolean isBehind(TunnelHeaterBlockEntity member, TunnelHeaterBlockEntity newest) {
        return member.energyRevision != newest.energyRevision
                || member.pendingConsumption != newest.pendingConsumption
                || member.battery.getEnergyStored() != newest.battery.getEnergyStored();
    }

    /** A total order over positions: x, then y, then z. */
    private static boolean isEarlier(BlockPos first, BlockPos second) {
        if (first.getX() != second.getX()) {
            return first.getX() < second.getX();
        }
        if (first.getY() != second.getY()) {
            return first.getY() < second.getY();
        }
        return first.getZ() < second.getZ();
    }

    /** The holder of the group's battery: the smallest of the connected positions, so anyone agrees. */
    private TunnelHeaterBlockEntity leader() {
        BlockPos cached = this.cachedLeader;
        if (cached != null) {
            TunnelHeaterBlockEntity leader = heaterAt(cached);
            if (leader != null && leader.cachedGroupSize > 0) {
                return leader;
            }
        }
        rebuildGroup();
        TunnelHeaterBlockEntity leader = this.cachedLeader == null ? null : heaterAt(this.cachedLeader);
        return leader == null ? this : leader;
    }

    /**
     * Writes the two things the screen needs into the block state: whether the group is working, and
     * which way the belt below runs.
     *
     * <p>Written with {@code Block.UPDATE_CLIENTS} and not with the usual flag 3. That flag is what the
     * redstone property is written with, so that a change of it reaches the neighbours whose signal may
     * depend on it; this one is the opposite: it is an answer derived <em>from</em> the neighbours, so
     * notifying them would only send them looking for a group answer that cannot have changed. Staying
     * off that path is also why this cannot loop - no neighbour notification is raised, so no
     * {@code neighborChanged} and no group rebuild is provoked - and because the value written is the
     * value this tick is about to act on, the next tick finds it already correct and writes nothing.
     *
     * <p>Re-reading the belt's axis here is what saves a heater placed before these properties existed,
     * and one whose belt was turned or replaced under it: {@code getShape} is asked on every frame, and
     * the collision box and the model have to agree.
     */
    private void syncActiveState(boolean active) {
        BlockState state = this.getBlockState();
        BlockState wanted = state;
        boolean changed = false;
        if (state.getValue(TunnelHeaterBlock.ACTIVE) != active) {
            wanted = wanted.setValue(TunnelHeaterBlock.ACTIVE, active);
            changed = true;
        }
        Direction.Axis axis = TunnelHeaterBlock.beltAxis(this.level, this.worldPosition);
        if (state.getValue(TunnelHeaterBlock.HORIZONTAL_AXIS) != axis) {
            wanted = wanted.setValue(TunnelHeaterBlock.HORIZONTAL_AXIS, axis);
            changed = true;
        }
        if (changed) {
            this.level.setBlock(this.worldPosition, wanted, Block.UPDATE_CLIENTS);
        }
    }

    /** Points the leader's battery at the group's share of capacity, without ever going below what it holds. */
    private void reconcileCapacity() {
        TunnelHeaterBlockEntity leader = leader();
        leader.battery.reconcileCapacity(Math.max(1, leader.cachedGroupSize)
                * WoodenCogCommonConfigs.tunnelHeaterEnergyCapacityPerBlock());
    }

    /**
     * The shared charge changed: this member is the leader that owns it, so the change is counted and
     * spread to the rest of the group before anything else looks at it.
     */
    private void onEnergyChanged() {
        this.energyRevision++;
        rebuildGroup();
        this.setChanged();
        this.sendData();
    }

    // ---------------------------------------------------------------------------------------------
    // The mode: one choice per group, made with a value box on the roof
    // ---------------------------------------------------------------------------------------------

    /**
     * The mode this heater works in: the mode of the group's leader.
     *
     * <p>The mode is a property of the whole group, like the battery and the answer to "is the group
     * signalled", and it is kept consistent the same way: every member caches the same leader, and every
     * loaded member of a group holds the same value in its own value box. Asking the leader rather than
     * the member is what makes the answer the same whichever heater is asked, even on the tick between a
     * member being loaded and its value box catching up, and reading through {@link #leader()} means the
     * group is rebuilt first if this member's cache is stale.
     *
     * <p>Falling back to this block's own behaviour is what a member of a group whose real leader is in
     * an unloaded chunk does. The value it holds is the leader's as of the last time that leader was
     * loaded, so it is the best answer available, and the group is realigned as soon as the leader's
     * chunk comes back.
     */
    private TunnelHeaterMode groupMode() {
        if (this.modeBehaviour == null) {
            return TunnelHeaterMode.HEAT;
        }
        TunnelHeaterBlockEntity leader = leader();
        return leader == this ? this.ownMode() : leader.ownMode();
    }

    /** The mode in this block's own value box, which is only meaningful once the group has been built. */
    private TunnelHeaterMode ownMode() {
        return this.modeBehaviour == null
                ? TunnelHeaterMode.HEAT
                : TunnelHeaterMode.byOrdinal(this.modeBehaviour.value);
    }

    /**
     * A player turned this heater's value box: the whole group follows, which is what makes one click on
     * one heater switch a whole tunnel.
     *
     * <p>The walk is {@link #collectGroup()}, the same connected component that shares the battery, so the
     * set of heaters that change mode together is exactly the set that share power. The members are
     * written to directly - into the value box's own value and not through its
     * {@code setValue}, which would fire each of their callbacks and have them walk the group in turn.
     * Nothing is lost by that: every member of the group resolves the same leader, so a member that
     * adopts the value here reports the same mode as the heater the player touched, and the group cannot
     * end up with two answers. A write that changes nothing marks nothing, and the walk is only as long
     * as the group is.
     */
    private void onModeChanged(TunnelHeaterMode mode) {
        for (TunnelHeaterBlockEntity member : this.collectGroup()) {
            if (member.modeBehaviour != null && member.modeBehaviour.value != mode.ordinal()) {
                member.modeBehaviour.value = mode.ordinal();
                member.setChanged();
            }
        }
    }

    /**
     * Points every member of {@code group} at the leader's mode.
     *
     * <p>Called at the end of {@link #rebuildGroup()}, which is where a group is resolved: a heater that
     * has just been placed beside a tunnel, one whose chunk has just been loaded and one whose neighbour
     * was broken all come through here, and all of them have to end up on the mode the group already
     * had. The new member is the one that needs it - its value box starts at the original mode - and the
     * members that already agreed are not written to, because the value is compared before it is set.
     *
     * <p>The group and the leader are handed in rather than looked up again: {@link #leader()} can come
     * back into {@link #rebuildGroup()}, and a rebuild that asked for the leader while rebuilding would
     * be a loop.
     *
     * <p>The members are written to directly, for the same reason as in
     * {@link #onModeChanged(TunnelHeaterMode)}: this runs on the way to resolving a group, and a
     * {@code setValue} that fired callbacks would resolve it again from inside itself.
     */
    private static void alignModeWithLeader(List<TunnelHeaterBlockEntity> group, TunnelHeaterBlockEntity leader) {
        if (leader.modeBehaviour == null) {
            return;
        }
        int leaderValue = leader.modeBehaviour.value;
        for (TunnelHeaterBlockEntity member : group) {
            if (member.modeBehaviour != null && member.modeBehaviour.value != leaderValue) {
                member.modeBehaviour.value = leaderValue;
                // The member's own value box has changed, so it has to be saved and sent - a client that
                // is not told would draw the mode the member was placed with.
                member.setChanged();
                if (member.level != null && !member.level.isClientSide) {
                    member.sendData();
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // IAirHeated: what a fan gets out of this block
    // ---------------------------------------------------------------------------------------------

    /**
     * Whether the battery this block's group shares holds any charge at all.
     *
     * <p>Read through the leader because the battery lives there, and through the capability's own
     * {@code getEnergyStored()} rather than a field of this class, so the number is the same one
     * Immersive Engineering's wire filled. A block with no charge is a block that cannot run, whatever
     * its redstone says.
     */
    private boolean hasCharge() {
        return leader().battery.getEnergyStored() > 0;
    }

    /**
     * Whether this heater is actually working, which takes <b>both</b> conditions at once:
     *
     * <pre>
     * 工作 = 本组有红石信号(isActive) &amp;&amp; 共享电池电量 &gt; 0 (hasCharge)
     * </pre>
     *
     * <p>Either one missing means the heater does not run: no signal is a machine that is switched off,
     * and no charge is a machine that has nothing to run on. The two are kept in one predicate so the
     * shown {@code ACTIVE} state, the belt hold, the heating and the power draw all read the same answer
     * and cannot drift apart.
     */
    private boolean isWorking() {
        return this.isActive() && this.hasCharge();
    }

    /**
     * What this heater heats a fan's air to, or 0 when it heats nothing at all - a heater without a
     * redstone signal, or one whose group's battery is flat. Any member of a group answers with the
     * group's charge, because the battery is the leader's.
     *
     * <p>The signal and the charge are tested here, through {@link #isWorking()}, so that every question
     * about this block's heat goes through this one answer: an idle heater is cold, and the fan air chain
     * ({@code HotAirChamberHeat}) therefore never mistakes one for a heat source. It is the signal of the
     * <em>group</em> ({@link #isActive()}), so a heater that is running because a neighbour of it is
     * signalled is a heat source for a fan as well.
     *
     * <p>The temperature itself is one number - {@code tunnel_heater_temperature}, 1500 by default, which
     * is what a tank of lava used to give it - because the heater draws Forge Energy and there is no
     * fluid left to look a temperature up by.
     */
    @Override
    public float getAirTemperature() {
        if (!this.isWorking()) {
            return 0.0F;
        }
        return WoodenCogCommonConfigs.tunnelHeaterTemperature();
    }

    /** Whether a fan drawing its air through this heater would pick up any heat at all. */
    @Override
    public boolean isHeating() {
        return this.getAirTemperature() > 0.0F;
    }

    /**
     * Takes {@code energy} Forge Energy out of the group's battery for this block, and folds together the
     * two things that can ask for it within one tick.
     *
     * <p>This is the only way into the battery, and it is used by the heater's own tick (a running heater
     * draws {@link #consumptionPerTick()} every tick, belt or no belt) and by
     * {@code HotAirChamberHeat#consume} (a fan whose airflow runs through this block pays for the air it
     * took). {@link #lastConsumedTick} makes the pair add up to one share per tick and not two: the
     * first caller of a tick sets it to the current game time and drains, the second one finds its own
     * tick already paid for and returns. Neither caller is privileged, so it does not matter in which
     * order the two run.
     *
     * <p>A heater whose group is off takes nothing, which the fan side already knows because
     * {@link #isHeating()} is false for it; the check is repeated here so that no path can charge an
     * idle block. A flat battery is refused by the caller, which is the heater's own tick: a battery with
     * nothing in it charges nothing.
     */
    @Override
    public void consume(double energy) {
        if (energy <= 0.0D || !this.isActive() || this.level == null) {
            return;
        }
        long now = this.level.getGameTime();
        if (this.lastConsumedTick == now) {
            // Already paid for this tick: either the heater's own tick got here first, or a fan did.
            return;
        }
        this.lastConsumedTick = now;
        leader().consumeLocally(energy);
    }

    /** What one tick of a fan running on this block costs, from this block's own config key. */
    @Override
    public double consumptionPerTick() {
        return WoodenCogCommonConfigs.tunnelHeaterEnergyPerTick();
    }

    /** Drains the shared battery, carrying the fraction of a unit that is not due yet. */
    private void consumeLocally(double energy) {
        this.pendingConsumption += energy;
        int due = (int) this.pendingConsumption;
        if (due <= 0) {
            return;
        }
        int drained = this.battery.drainInternal(due);
        // A battery that could not pay the whole amount has run flat: nothing is owed from here on.
        this.pendingConsumption = drained >= due ? this.pendingConsumption - due : 0.0D;
        // The drain told the group about the new charge while this correction was still on its way, so
        // the group is told once more: it must never hold a carry that its battery has already paid.
        rebuildGroup();
    }

    /**
     * This block's own redstone signal: true while a signal is present, which is the sense every caller
     * needs, because the block state {@code POWERED} means "signalled", not "disabled". The property
     * defaults to false, so a freshly placed heater is off until something powers it.
     *
     * <p>This is read in exactly one place: {@link #rebuildGroup()}, which folds the signals of a whole
     * group into {@link #cachedGroupActive}. Everything that asks whether this block is <em>running</em>
     * asks {@link #isActive()}, so a signal is turned into work in one place and there is one polarity to
     * get right.
     */
    private boolean isPowered() {
        return this.getBlockState().getValue(TunnelHeaterBlock.POWERED);
    }

    /**
     * Whether the group this heater is in is on, which is the case when any member of it carries a
     * redstone signal: one signalled heater turns the whole connected run on, and they all heat the belt
     * below them, all draw on the battery they share and all give a fan their heat.
     *
     * <p>The answer is resolved together with the leader and cached the same way, so asking per tick is
     * free: a member with an intact cache walks nothing. It is deliberately not read from the block state
     * {@code TunnelHeaterBlock#ACTIVE} either - that state is only the model's copy of this answer, and
     * behaviour must not wait a tick for a block update to catch up with the world.
     */
    private boolean isActive() {
        leader();
        return this.cachedGroupActive;
    }

    // ---------------------------------------------------------------------------------------------
    // Energy capability, saving, and the battery itself
    // ---------------------------------------------------------------------------------------------

    /**
     * Every member of a group offers the same battery, so an Immersive Engineering wire (or any other
     * Forge Energy source) can charge the machine through any one of them. The lookups go through the
     * leader and the leader's battery keeps its capacity in step with the size of the group before it
     * answers, which is why this is an {@code IEnergyStorage} of its own and not the battery itself.
     */
    private final class GroupEnergyHandler implements IEnergyStorage {

        private GroupBattery groupBattery() {
            return TunnelHeaterBlockEntity.this.leader().battery;
        }

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            return groupBattery().receiveEnergy(maxReceive, simulate);
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            return groupBattery().extractEnergy(maxExtract, simulate);
        }

        @Override
        public int getEnergyStored() {
            return groupBattery().getEnergyStored();
        }

        @Override
        public int getMaxEnergyStored() {
            return groupBattery().getMaxEnergyStored();
        }

        @Override
        public boolean canExtract() {
            return groupBattery().canExtract();
        }

        @Override
        public boolean canReceive() {
            return groupBattery().canReceive();
        }
    }

    /**
     * The battery that is shared by a group of tunnel heaters. Its capacity is not a constant: it is the
     * group's share of {@code tunnel_heater_energy_capacity} per member, or what the battery already
     * holds when that is more, so a group that shrinks keeps its charge instead of clipping it away.
     *
     * <p>Forge's {@code EnergyStorage} keeps its capacity in a field and {@code receiveEnergy} reads that
     * field rather than calling {@code getMaxEnergyStored()}, so the field has to be brought up to date
     * before a receive is let through; {@code getMaxEnergyStored} and {@code receiveEnergy} both do that.
     *
     * <p><b>Nothing may pull energy back out.</b> A heater is a consumer, and the wires of Immersive
     * Engineering would otherwise treat the run as a battery and drain it. {@link #extractEnergy} and
     * {@link #canExtract} therefore refuse every caller, and the heater's own draw goes through
     * {@link #drainInternal(int)}, which is not part of the capability.
     */
    private final class GroupBattery extends EnergyStorage {

        GroupBattery() {
            // maxReceive is deliberately huge: the capacity is the only limit worth having, and a
            // connector that pushes a whole tick's worth at once must not be cut short by it.
            super(ENERGY_CAPACITY_PER_BLOCK, Integer.MAX_VALUE / 4, 0);
        }

        /**
         * Sets the physical capacity to the larger of the group's share and what is stored. Never lower
         * than what is stored, which is what keeps a shrinking group from losing charge: a receive then
         * computes a room of zero at worst, and never a negative one that would overwrite the contents.
         */
        void reconcileCapacity(int groupCapacity) {
            this.capacity = Math.max(groupCapacity, this.energy);
        }

        @Override
        public int getMaxEnergyStored() {
            TunnelHeaterBlockEntity.this.reconcileCapacity();
            return this.capacity;
        }

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            TunnelHeaterBlockEntity.this.reconcileCapacity();
            int received = super.receiveEnergy(maxReceive, simulate);
            if (received > 0 && !simulate) {
                TunnelHeaterBlockEntity.this.onEnergyChanged();
            }
            return received;
        }

        /** A heater is a consumer: no caller outside this class may take charge out of it. */
        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            return 0;
        }

        @Override
        public boolean canExtract() {
            return false;
        }

        /** The heater's own draw, which is why it does not go through {@link #extractEnergy}. */
        int drainInternal(int amount) {
            if (amount <= 0) {
                return 0;
            }
            int drained = Math.min(this.energy, amount);
            if (drained <= 0) {
                return 0;
            }
            this.energy -= drained;
            TunnelHeaterBlockEntity.this.onEnergyChanged();
            return drained;
        }

        /** Copies the charge of the newest member onto this one during a group rebuild, silently. */
        void setEnergy(int value) {
            this.energy = Math.max(0, value);
        }
    }

    @Override
    public <T> LazyOptional<T> getCapability(Capability<T> capability, @Nullable Direction side) {
        if (capability == ForgeCapabilities.ENERGY) {
            return this.energyHandler.cast();
        }
        return super.getCapability(capability, side);
    }

    @Override
    public void invalidateCaps() {
        super.invalidateCaps();
        this.energyHandler.invalidate();
    }

    @Override
    protected void write(CompoundTag compound, boolean clientPacket) {
        // The mode is a property of the group, so what is written is the group's answer and not whatever
        // this member's value box happens to hold. The two agree almost always - onModeChanged and the
        // alignment in rebuildGroup keep them that way - and this is the backstop for the case where
        // this member has not been through a rebuild since the group's mode last changed on another
        // member. A save therefore always holds one mode per group, whichever member of it was saved;
        // without this, saving the chunk of a member that never rebuilt would write the mode it was
        // placed with.
        //
        // The leader is looked up through the cache and not through leader(), which would rebuild the
        // group: a rebuild marks members changed and sends them data, and sendData writes this block
        // entity again. A write that cannot see its cached leader leaves the value box alone rather than
        // starting a walk - the group is aligned at the next rebuild anyway.
        if (this.modeBehaviour != null && this.cachedLeader != null) {
            TunnelHeaterBlockEntity leader = this.heaterAt(this.cachedLeader);
            if (leader != null && leader.modeBehaviour != null
                    && this.modeBehaviour.value != leader.modeBehaviour.value) {
                this.modeBehaviour.value = leader.modeBehaviour.value;
            }
        }
        super.write(compound, clientPacket);
        compound.putInt(ENERGY_KEY, this.battery.getEnergyStored());
        compound.putDouble(PENDING_CONSUMPTION_KEY, this.pendingConsumption);
        compound.putInt(ENERGY_REVISION_KEY, this.energyRevision);
    }

    @Override
    protected void read(CompoundTag compound, boolean clientPacket) {
        super.read(compound, clientPacket);
        this.battery.setEnergy(compound.getInt(ENERGY_KEY));
        this.pendingConsumption = compound.getDouble(PENDING_CONSUMPTION_KEY);
        this.energyRevision = compound.getInt(ENERGY_REVISION_KEY);
        // Whatever was known about the group before is not known now: a loaded block entity has to look
        // its group up again, on whichever side this read ran.
        this.cachedLeader = null;
        this.cachedGroupSize = 0;
    }
}
