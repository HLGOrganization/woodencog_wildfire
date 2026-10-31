package net.chauvedev.woodencog.config;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WoodenCogCommonConfigs {
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.ConfigValue<Boolean> HANDLE_TEMPERATURE;
    public static final ForgeConfigSpec.ConfigValue<Boolean> DEPLOYER_COPY_TEMPERATURE;

    public static final ForgeConfigSpec.ConfigValue<Double> BLAZE_BURNER_NONE;
    public static final ForgeConfigSpec.ConfigValue<Double> BLAZE_BURNER_SMOULDERING;
    public static final ForgeConfigSpec.ConfigValue<Double> BLAZE_BURNER_FADING;
    public static final ForgeConfigSpec.ConfigValue<Double> BLAZE_BURNER_KINDLED;
    public static final ForgeConfigSpec.ConfigValue<Double> BLAZE_BURNER_SEETHING;

    public static final ForgeConfigSpec.DoubleValue FAN_BLASTING_TEMPERATURE;
    public static final ForgeConfigSpec.DoubleValue FAN_SMOKING_TEMPERATURE;
    public static final ForgeConfigSpec.DoubleValue FAN_OVERHEAT_MULTIPLIER;
    public static final ForgeConfigSpec.DoubleValue FAN_NO_RECIPE_CEILING;
    public static final ForgeConfigSpec.DoubleValue FAN_SPEED_STEP_RPM;
    public static final ForgeConfigSpec.DoubleValue FAN_SPEED_STEP_AMOUNT;
    public static final ForgeConfigSpec.DoubleValue FAN_DECAY_PER_BLOCK;
    public static final ForgeConfigSpec.DoubleValue FAN_COOLING_RATE;
    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> FAN_HOT_AIR_CHAMBER_FLUIDS;
    public static final ForgeConfigSpec.DoubleValue FAN_HOT_AIR_CHAMBER_CONSUMPTION;
    public static final ForgeConfigSpec.DoubleValue TUNNEL_HEATER_TEMPERATURE;
    public static final ForgeConfigSpec.DoubleValue TUNNEL_HEATER_ENERGY_PER_TICK;
    public static final ForgeConfigSpec.IntValue TUNNEL_HEATER_ENERGY_CAPACITY;
    public static final ForgeConfigSpec.DoubleValue FAN_TUNNEL_HEATER_HEATING_RATE;
    public static final ForgeConfigSpec.DoubleValue FAN_TUNNEL_HEATER_TARGET_MARGIN;
    public static final ForgeConfigSpec.BooleanValue FAN_BURNT_FOOD_ENABLED;
    public static final ForgeConfigSpec.DoubleValue FAN_BURNT_FOOD_TEMPERATURE;
    public static final ForgeConfigSpec.IntValue FAN_BURNT_FOOD_DURATION;
    public static final ForgeConfigSpec.ConfigValue<String> FAN_BURNT_FOOD_RESULT;
    public static final ForgeConfigSpec.ConfigValue<String> FAN_BURNT_FOOD_TAG;

    public static final ForgeConfigSpec.ConfigValue<List<? extends String>> BROKEN_ITEM_REPLACEMENTS;

    /** The hot air chamber table as shipped; used to build the config and to read it too early. */
    private static final List<String> DEFAULT_HOT_AIR_CHAMBER_FLUIDS =
            List.of("minecraft:lava=1500", "minecraft:water=0");

    /** The burnt food values as shipped; used to build the config and to read them too early. */
    private static final String DEFAULT_BURNT_FOOD_RESULT = "kubejs:burnt_food_residue";
    private static final String DEFAULT_BURNT_FOOD_TAG = "tfc:foods";

    /** The broken item table as shipped; used to build the config and to read it too early. */
    private static final List<String> DEFAULT_BROKEN_ITEM_REPLACEMENTS =
            List.of("kubejs:bismuth_bronze_tong=kubejs:bismuth_bronze_damaged_tong");

    /** The raw table the parsed map below was built from, so a config reload is noticed. */
    private static List<? extends String> parsedBrokenItemReplacementsFrom = List.of();
    private static Map<ResourceLocation, ResourceLocation> parsedBrokenItemReplacements = Map.of();

    /** The raw table the parsed map below was built from, so a config reload is noticed. */
    private static List<? extends String> parsedHotAirChamberFluidsFrom = List.of();
    private static Map<ResourceLocation, Float> parsedHotAirChamberFluids = Map.of();

    public static final ForgeConfigSpec.ConfigValue<Boolean> NETHERITE_RESKIN;

    public static final ForgeConfigSpec.ConfigValue<Integer> CT_TRANSFORMER_IMPACT;
    public static final ForgeConfigSpec.ConfigValue<Integer> WOODEN_GENERATOR_BASE_SU;
    public static final ForgeConfigSpec.ConfigValue<Integer> WOODEN_GENERATOR_WIND_FACTOR;
    public static final ForgeConfigSpec.ConfigValue<Integer> WOODEN_GENERATOR_SPEED_FACTOR;

    public static final Map<String, ForgeConfigSpec.ConfigValue<List<Integer>>> MATERIAL_PROPERTIES = new HashMap<>();

    static {
        BUILDER.push("woodencog");

        BUILDER.push("temperature");
            HANDLE_TEMPERATURE = BUILDER
                .comment("Should create handle temperature ?")
                .define("handle_temperature", true);

            DEPLOYER_COPY_TEMPERATURE = BUILDER
                .comment("Should deploying copy input item temperature (ignored if handle temperature disabled)")
                .define("deployer_copy_temperature",true);

            BUILDER.comment("Blaze Burner Temperature");

            BLAZE_BURNER_NONE = BUILDER.define("blaze_burner_none", 0.0D);
            BLAZE_BURNER_SMOULDERING = BUILDER.define("blaze_burner_smouldering", 80.0D);
            BLAZE_BURNER_FADING = BUILDER.define("blaze_burner_fading", 750.0D);
            BLAZE_BURNER_KINDLED = BUILDER.define("blaze_burner_kindled", 1350.0D);
            BLAZE_BURNER_SEETHING = BUILDER
                    .comment("Seething is Create's superheated burner state. 2300.0 matches Create's own",
                            "superheated temperature and is the reference temperature of the eighth",
                            "charcoal forge heat tier: a forge that reaches this value is treated as",
                            "superheated and drives the basins and mixers above it.")
                    .define("blaze_burner_seething", 2300.0D);

        BUILDER.pop();

        BUILDER.comment("Fan heating. A fan blows whatever stands behind it: lava blasts, fire and a lit",
                        "firepit smoke, water splashes. The two temperatures below are the ceilings the",
                        "blasting and smoking cases heat an item towards - a fan never heats an item past",
                        "them, however long the item stays in the stream.",
                        "The overheat guard destroys an item that is thrown into a fan already hotter than",
                        "it can survive, which is the only way the fan can still consume an item.").push("fan");
            FAN_BLASTING_TEMPERATURE = BUILDER
                    .comment("Ceiling for a blasting fan (a plain lava block behind the fan).",
                            "The upstream value was 1700, this mod shipped 1500, and it is 500 now: raw lava",
                            "behind a fan is only warm air. 500C cannot melt anything - every TFC metal melts",
                            "at 1.25 * its welding temperature, and the coolest of them (copper, welding 864)",
                            "needs 1080C - and it cannot cook anything either, which is deliberate: the heat",
                            "worth having comes from feeding a hot air combustion chamber instead.",
                            "Note that this is the ceiling at the heat source: fan.decay_per_block lowers it",
                            "for every block of airflow the item sits downstream of the lava.")
                    .defineInRange("blasting_temperature", 500.0D, 0.0D, 100000.0D);

            FAN_SMOKING_TEMPERATURE = BUILDER
                    .comment("Ceiling for a smoking fan (fire, campfire or a lit TFC firepit behind the fan).",
                            "The upstream value was 200.")
                    .defineInRange("smoking_temperature", 600.0D, 0.0D, 100000.0D);

            FAN_OVERHEAT_MULTIPLIER = BUILDER
                    .comment("An item that carries a welding temperature is destroyed once it passes",
                            "welding_temperature * this value.",
                            "Do not go below 1.25: every TFC metal melts at 1.25 * its welding temperature,",
                            "so a smaller value would destroy the metal before it is able to melt into a",
                            "fluid. A metal that has a heating recipe is converted by that recipe first, so",
                            "no value at or above the ratio can destroy it early.")
                    .defineInRange("overheat_multiplier", 1.5D, 1.25D, 10.0D);

            FAN_NO_RECIPE_CEILING = BUILDER
                    .comment("An item with no welding temperature and no heating recipe is destroyed once it",
                            "passes this temperature; nothing else would ever stop it.",
                            "The hottest TFC heating recipe needs 1540C and the hottest food destroy recipe",
                            "900C, so real melting and cooking still happen first.",
                            "A blasting fan alone stops at blasting_temperature above, so this clause only",
                            "fires for an item that was already above this value when it entered the fan.",
                            "Set to 0.0 to let such an item heat without any limit.")
                    .defineInRange("no_recipe_ceiling", 1600.0D, 0.0D, 100000.0D);

            FAN_SPEED_STEP_RPM = BUILDER
                    .comment("RPM that buys one step of the fan speed bonus.",
                            "A faster fan heats and cools faster. At the default 32, a fan turning below",
                            "32 RPM works at 1x, 32 to 63 at 1 + speed_step_amount, and Create's maximum",
                            "of 256 RPM reaches 1 + 8 * speed_step_amount.",
                            "Speed is read from the fan block entity once per tick, so a reversed fan",
                            "counts the same: the sign is ignored on purpose.")
                    .defineInRange("speed_step_rpm", 32.0D, 1.0D, 100000.0D);

            FAN_SPEED_STEP_AMOUNT = BUILDER
                    .comment("What one speed step adds. At the default 0.5: 32 RPM is 1.5x, 64 RPM 2x,",
                            "128 RPM 3x and 256 RPM 5x.",
                            "This scales the per tick temperature change TFC itself applies, so 1x is",
                            "exactly as fast as a TFC device - a heat capacity aware rate, not a flat",
                            "number of degrees. The ceiling itself does not scale with speed.",
                            "Set to 0.0 to make speed irrelevant.")
                    .defineInRange("speed_step_amount", 0.5D, 0.0D, 100.0D);

            FAN_DECAY_PER_BLOCK = BUILDER
                    .comment("Fraction of the ceiling lost for every block of airflow past the heat source.",
                            "A fan is an air column, not a point: the hottest block is the one sitting right",
                            "at the lava, fire or firepit, and everything downstream is cooler. The ceiling is",
                            "therefore measured from the first block of airflow that carries heat, with each",
                            "further block multiplying it by (1 - this value).",
                            "At the default 0.10 a 1300C lava fan gives 1300C at the lava, 1170C one block",
                            "out, 1053C two, 948C three, 853C four, 768C five and 453C ten blocks out.",
                            "Note how quickly that eats into melting: copper melts at 1080C, so it stops",
                            "melting two blocks past the lava.",
                            "Set to 0.0 for one flat ceiling along the whole airflow.")
                    .defineInRange("decay_per_block", 0.10D, 0.0D, 1.0D);

            FAN_COOLING_RATE = BUILDER
                    .comment("Degrees per tick a splashing fan pulls an item towards 0C, before the speed",
                            "bonus. This is the value the mod already used when it was hard coded.",
                            "The cooling is not routed through TFC's heat capacity, so it is a flat number",
                            "of degrees: that is what the shipped behaviour did, and changing it is a",
                            "separate decision.")
                    .defineInRange("cooling_rate", 8.0D, 0.0D, 100000.0D);

            FAN_HOT_AIR_CHAMBER_FLUIDS = BUILDER
                    .comment("What a hot air chamber heats the air to, one fluid per entry, written as",
                            "<fluid id>=<temperature in C>, the scale TFC heat uses. A fan whose airflow",
                            "runs through a chamber filled with one of these blows air at that",
                            "temperature, and the chamber is drawn down while it does.",
                            "The temperature is the ceiling at the chamber itself: fan.decay_per_block",
                            "lowers it for every block of airflow an item sits downstream, and the fan",
                            "speed bonus applies on top, exactly as it does at a lava block.",
                            "0 means no heating at all, which is what makes water the same to a fan as",
                            "an empty chamber. A fluid that is not listed here does not heat either, so",
                            "adding another fluid needs no code: add its id and temperature here.",
                            "At the default 1500 the chamber is hotter than every TFC bronze and still",
                            "below wrought iron, whose welding temperature is 1535C and which therefore",
                            "melts above it (1.25 * 1535).")
                    .defineList("hot_air_chamber_fluids", DEFAULT_HOT_AIR_CHAMBER_FLUIDS,
                            entry -> entry instanceof String);

            FAN_HOT_AIR_CHAMBER_CONSUMPTION = BUILDER
                    .comment("Millibuckets a hot air chamber loses per tick while a fan draws its air",
                            "through the chamber. The default 0.01 is 1 mB every 5 seconds, so the",
                            "2000 mB tank feeds a fan for 200000 ticks - about 2.8 hours.",
                            "Nothing is taken while the fan stands still, while the chamber is empty or",
                            "while its fluid is one that does not heat.")
                    .defineInRange("hot_air_chamber_consumption", 0.01D, 0.0D, 1000.0D);

            TUNNEL_HEATER_TEMPERATURE = BUILDER
                    .comment("What a tunnel heater heats to, in degrees. It runs on Forge Energy now,",
                            "not on a fluid, so the temperature is one number instead of a table:",
                            "1500 is what a tank of lava used to give it, and it is hot enough to",
                            "take every TFC metal to its welding temperature.",
                            "A tunnel heater uses this temperature two ways: a fan whose airflow runs",
                            "through it blows air at this temperature, and an item sitting on the belt",
                            "underneath it is heated towards it directly. 0 means no heating at all.")
                    .defineInRange("tunnel_heater_temperature", 1500.0D, 0.0D, 100000.0D);

            TUNNEL_HEATER_ENERGY_PER_TICK = BUILDER
                    .comment("Forge Energy a tunnel heater draws per tick while it is switched on,",
                            "whether that is a fan drawing its air through it or the heater working on",
                            "its own tick. The default 20 FE/t lets one IE low voltage wire (256 FE/t)",
                            "run twelve heaters at once.",
                            "A tunnel heater shares one battery with every tunnel heater it is connected",
                            "to, so a group of twelve is one 48000 FE battery that draws 240 FE/t and",
                            "only needs the wire attached to any one member.")
                    .defineInRange("tunnel_heater_energy_per_tick", 20.0D, 0.0D, 100000.0D);

            TUNNEL_HEATER_ENERGY_CAPACITY = BUILDER
                    .comment("Forge Energy one block of a tunnel heater adds to the battery its whole",
                            "connected run shares. The group of N blocks therefore buffers",
                            "N x this, which is how a run keeps working for a moment after the wire is",
                            "cut. The default 4000 holds 200 ticks (ten seconds) of one block's draw.")
                    .defineInRange("tunnel_heater_energy_capacity", 4000, 1, 10000000);

            FAN_TUNNEL_HEATER_HEATING_RATE = BUILDER
                    .comment("Degrees per tick a tunnel heater pushes the item on the belt below it",
                            "towards that item's ceiling. This is the same 'b' that TFC's own",
                            "HeatCapability.addTemp takes, and what is actually added is",
                            "((itemCoolingModifier - 1) + b * itemHeatingModifier) / heatCapacity,",
                            "so the heating speed is linear in this number: the default of 12.0 is",
                            "four times the 3.0 that addTemp(IHeat, float) passes on its own.",
                            "That (itemCoolingModifier - 1) term is a constant drag, which is why",
                            "quadrupling this number heats a little over four times as fast rather",
                            "than exactly four times. This is the one knob for heating speed: to",
                            "get exactly four times, solve b = 0.8 + 4 * (3.0 - 0.8).",
                            "The ceiling is never passed: a metal with a welding temperature is",
                            "stopped at it, and an item without one is stopped at fan.no_recipe_ceiling.")
                    .defineInRange("tunnel_heater_heating_rate", 12.0D, 0.0D, 100000.0D);

            FAN_TUNNEL_HEATER_TARGET_MARGIN = BUILDER
                    .comment("How far above the temperature TFC asks for a tunnel heater pushes the item",
                            "on the belt below it when its mode is 'workable' or 'weldable'. TFC's anvil",
                            "and TFC's welding both test 'temperature >= getWorkingTemperature()', so 1.0",
                            "would stop the item the moment it is barely usable and it might arrive a few",
                            "degrees short after cooling on the way. 1.2 out of the box overshoots by a",
                            "fifth, which is a few dozen degrees on bronze and still well under the melting",
                            "point. Values below 1.0 are refused: they would ask for a temperature the item",
                            "is not yet usable at.",
                            "The 'heat' mode does not read this key at all.")
                    .defineInRange("tunnel_heater_target_margin", 1.2D, 1.0D, 100.0D);

            FAN_BURNT_FOOD_ENABLED = BUILDER
                    .comment("Should a food item held at or above burnt_food_temperature for",
                            "burnt_food_duration ticks in a row turn into burnt_food_result ?",
                            "Only items carrying the tag in burnt_food_tag are affected, so this is",
                            "off for every item that is not food. The count lives in the item's own NBT,",
                            "so it survives moving the item between inventories and saving the world,",
                            "and it is reset the moment the item drops back below the temperature.")
                    .define("burnt_food_enabled", true);

            FAN_BURNT_FOOD_TEMPERATURE = BUILDER
                    .comment("Temperature at or above which the count runs, in degrees, the scale TFC",
                            "heat uses. The default 500 is exactly what a plain lava blasting fan heats",
                            "an item to, so food left in the airflow of a lava fan chars - and because",
                            "fan.decay_per_block lowers the ceiling downstream, only the block right at",
                            "the lava is hot enough. A fire fan (smoking_temperature, 600 by default)",
                            "and a hot air chamber (hot_air_chamber_fluids) pass this value too, since",
                            "the trigger is the temperature of the item and not the type of the fan.")
                    .defineInRange("burnt_food_temperature", 500.0D, 0.0D, 100000.0D);

            FAN_BURNT_FOOD_DURATION = BUILDER
                    .comment("Ticks of exposure at or above burnt_food_temperature before the food",
                            "turns into burnt_food_result. The default 1200 is 60 seconds.",
                            "The exposure has to be continuous: a single tick below the temperature",
                            "starts the count over, which is what stops an item that is only briefly",
                            "hot - a fan that is switched off and on again, say - from burning.")
                    .defineInRange("burnt_food_duration", 1200, 1, 1000000);

            FAN_BURNT_FOOD_RESULT = BUILDER
                    .comment("The item a burnt food item turns into, by id. It is looked up in the item",
                            "registry at runtime and never referenced from code, so the id is allowed to",
                            "belong to a mod this one does not build against - the default is registered",
                            "by KubeJS in the modpack. While the id is not a registered item the food",
                            "does not burn at all: nothing is destroyed and nothing is logged per tick.")
                    .define("burnt_food_result", DEFAULT_BURNT_FOOD_RESULT);

            FAN_BURNT_FOOD_TAG = BUILDER
                    .comment("The item tag that marks an item as food for the rule above.",
                            "The default tfc:foods is TFC's own general food tag, which is the file",
                            "data/tfc/tags/items/foods.json in TFC and the field",
                            "net.dries007.tfc.common.TFCTags.Items.FOODS in its code. Note the plural:",
                            "TFC ships no 'tfc:food' tag, so a config written as tfc:food would match",
                            "nothing at all. Any other item tag works just as well, which is what makes",
                            "this usable for a modpack that keeps its own list of food.")
                    .define("burnt_food_tag", DEFAULT_BURNT_FOOD_TAG);
        BUILDER.pop();

        BUILDER.comment("Items that are replaced rather than simply disappearing when their durability runs out")
                .push("broken_items");
            BROKEN_ITEM_REPLACEMENTS = BUILDER
                    .comment("Table of '<broken item>=<replacement item>' entries, one per line.",
                            "When a player's item in hand is destroyed by running out of durability and",
                            "the id of that item is listed here, the replacement is put into that same",
                            "hand instead of the item vanishing.",
                            "Both ids are read from the item registry at runtime, never from code, so an",
                            "entry is allowed to name an item that this mod is not built against - the",
                            "shipped default is a KubeJS item of the TerraFirmaCraft Wild Fire modpack.",
                            "While a replacement id is not a registered item nothing happens at all: the",
                            "item is not taken away early and nothing is logged per break.",
                            "An item that is not listed here keeps exactly the behaviour it had before,",
                            "and the replacement itself is not listed on the left of any entry.",
                            "Also note which breaks reach this at all: a tool that breaks while mining a",
                            "block is replaced, but the melee attack path clears the hand right after the",
                            "event, and an item damaged by a script or a machine never fires the event.")
                    .defineList("replacements", DEFAULT_BROKEN_ITEM_REPLACEMENTS,
                            entry -> entry instanceof String);
        BUILDER.pop();

        BUILDER.push("stress");
            BUILDER.comment("Stress impact of the transformer");
            CT_TRANSFORMER_IMPACT = BUILDER.define("ct_transformer_impact", 64);
            BUILDER.comment("Stress generation factors, this values are multiplied");
            WOODEN_GENERATOR_BASE_SU = BUILDER.define("wooden_generator_base_su", 64);
            WOODEN_GENERATOR_WIND_FACTOR = BUILDER.define("wooden_generator_wind_factor", 4);
            WOODEN_GENERATOR_SPEED_FACTOR = BUILDER.define("wooden_generator_speed_factor", 16);
        BUILDER.pop();

        NETHERITE_RESKIN = BUILDER.comment("Change netherite divingGear to look like red steel armor. (wearing netherite pants disables re-skin)").define("netherite_reskin", true);

        BUILDER.comment("Density [kg/m3] - HeatCapacity [J/(kg∙K)]").push("materials");
        addDensityConfig("allthemodium",20000,1500); //Not real
        addDensityConfig("aluminium",2700,897);
        addDensityConfig("aluminum",2700,897);
        addDensityConfig("americium",13670,116);
        addDensityConfig("annealed_copper",8940,385);
        addDensityConfig("antimony",6697,210);
        addDensityConfig("beryllium",1848,1825);
        addDensityConfig("bisalloy_400",7850,466);
        addDensityConfig("blasted_iron",7870,449); //~~
        addDensityConfig("blue_alloy",4000,300); //Not real
        addDensityConfig("borosilicate_glass",2230,830);
        addDensityConfig("chrome",7140,450);
        addDensityConfig("chromium",7140,450);
        addDensityConfig("cobalt",8900,420);
        addDensityConfig("cobalt_brass",8900,420); //~~
        addDensityConfig("conductive_alloy",2900,600); //Not real
        addDensityConfig("constantan",8900,377);
        addDensityConfig("copper_alloy",8800,385); //~~
        addDensityConfig("cupronickel",8900,385);
        addDensityConfig("damascus_steel",7850,466); //~~
        addDensityConfig("dark_chocolate",1325,2600);
        addDensityConfig("dark_steel",7850,466); //~~
        addDensityConfig("darmstadtium",26500,130); //Theoretic - N/A
        addDensityConfig("duranium",2800,897); //Not real
        addDensityConfig("electrum",2900,700); //Not real
        addDensityConfig("end_steel",6700,530); //Not real
        addDensityConfig("enderium",3000,100); //Not real
        addDensityConfig("energetic_alloy",2700,500); //Not real
        addDensityConfig("enriched_naquadah",32400,400); //Not real
        addDensityConfig("enriched_naquadah_trinium_europium_duranide",34000,200); //Not real
        addDensityConfig("epoxy",1250,1100);
        addDensityConfig("europium",5244,180);
        addDensityConfig("fiberglass",2600,787);
        addDensityConfig("fluorite",3180,867);
        addDensityConfig("gallium",5904,371);
        addDensityConfig("gallium_arsenide",5317,330);
        addDensityConfig("graphene",1800,710); //??
        addDensityConfig("graphite",1600,710);
        addDensityConfig("hastelloy_c_276",8890,380); //~~
        addDensityConfig("hastelloy_x",8220,380); //~~
        addDensityConfig("incoloy_ma_956",7250,460); //~~
        addDensityConfig("indium",7290,234);
        addDensityConfig("indium_gallium_phosphide",4810,430);//~~
        addDensityConfig("indium_tin_barium_titanium_cuprate",5500,500); //??
        addDensityConfig("infinity_matter",82000,3400); //Not real
        addDensityConfig("invar",8050,280);
        addDensityConfig("iridium",22562,130);
        addDensityConfig("iron",7870,449);
        addDensityConfig("kanthal",7100,460);
        addDensityConfig("lead",11300,127);
        addDensityConfig("lumium",4300,129); //Not real
        addDensityConfig("magnalium",1900,962); //??
        addDensityConfig("manganese",7430,480);
        addDensityConfig("molybdenum",10280,250);
        addDensityConfig("naquadah",32500,450); //Not real
        addDensityConfig("naquadah_alloy",32100,390); //Not real
        addDensityConfig("nickel",8900,445);
        addDensityConfig("niobium",8570,265);
        addDensityConfig("nitinol",6450,322);
        addDensityConfig("osmium",22587,130);
        addDensityConfig("palladium",12023,244);
        addDensityConfig("platinum",21400,130);
        addDensityConfig("plutonium",19816,35);
        addDensityConfig("red_alloy",4000,300); //Not real
        addDensityConfig("silicon",2330,700);
        addDensityConfig("stainless_steel",8000,466); //~~
        addDensityConfig("tantalum",16650,140);
        addDensityConfig("tin",7400,228);
        addDensityConfig("titanium",4500,520);
        addDensityConfig("tungsten",19250,130);
        addDensityConfig("wolframium",19250,130);
        addDensityConfig("uranium",19050,120);
        addDensityConfig("vibranium", 2566,1600); //Not real
        addDensityConfig("wrought_iron",7870,449);
        BUILDER.pop();

        SPEC = BUILDER.build();
    }

    private static void addDensityConfig(String itemId, int density, int heatCapacity) {
        MATERIAL_PROPERTIES.put(itemId, BUILDER.define(itemId, Arrays.asList(density,heatCapacity)));
    }

    /**
     * Reads one of the fan values. The fan asks for these once per tick per item, so the
     * ConfigValue is not cached, but a value read before the common config was loaded must
     * not take the fan down: the built-in default is returned instead.
     */
    private static float readFanValue(ForgeConfigSpec.DoubleValue value, float fallback) {
        try {
            return value.get().floatValue();
        } catch (IllegalStateException notLoadedYet) {
            return fallback;
        }
    }

    /** Same as {@link #readFanValue}, for the fan values that are not degrees. */
    private static boolean readFanFlag(ForgeConfigSpec.BooleanValue value, boolean fallback) {
        try {
            return value.get();
        } catch (IllegalStateException notLoadedYet) {
            return fallback;
        }
    }

    private static int readFanInt(ForgeConfigSpec.IntValue value, int fallback) {
        try {
            return value.get();
        } catch (IllegalStateException notLoadedYet) {
            return fallback;
        }
    }

    private static String readFanString(ForgeConfigSpec.ConfigValue<String> value, String fallback) {
        try {
            return value.get();
        } catch (IllegalStateException notLoadedYet) {
            return fallback;
        }
    }

    public static float fanBlastingTemperature() {
        return readFanValue(FAN_BLASTING_TEMPERATURE, 500.0F);
    }

    public static float fanSmokingTemperature() {
        return readFanValue(FAN_SMOKING_TEMPERATURE, 600.0F);
    }

    public static float fanOverheatMultiplier() {
        return readFanValue(FAN_OVERHEAT_MULTIPLIER, 1.5F);
    }

    public static float fanNoRecipeCeiling() {
        return readFanValue(FAN_NO_RECIPE_CEILING, 1600.0F);
    }

    public static float fanSpeedStepRpm() {
        return readFanValue(FAN_SPEED_STEP_RPM, 32.0F);
    }

    public static float fanSpeedStepAmount() {
        return readFanValue(FAN_SPEED_STEP_AMOUNT, 0.5F);
    }

    public static float fanDecayPerBlock() {
        return readFanValue(FAN_DECAY_PER_BLOCK, 0.10F);
    }

    public static float fanCoolingRate() {
        return readFanValue(FAN_COOLING_RATE, 8.0F);
    }

    /** Whether food at or above {@link #fanBurntFoodTemperature()} chars after a while. */
    public static boolean fanBurntFoodEnabled() {
        return readFanFlag(FAN_BURNT_FOOD_ENABLED, true);
    }

    /** Temperature at or above which the burnt food count runs, in degrees. */
    public static float fanBurntFoodTemperature() {
        return readFanValue(FAN_BURNT_FOOD_TEMPERATURE, 500.0F);
    }

    /** Ticks of continuous exposure needed before the food turns into the residue. */
    public static int fanBurntFoodDuration() {
        return readFanInt(FAN_BURNT_FOOD_DURATION, 1200);
    }

    /** The id of the item a burnt food item turns into, which may belong to another mod. */
    public static String fanBurntFoodResult() {
        return readFanString(FAN_BURNT_FOOD_RESULT, DEFAULT_BURNT_FOOD_RESULT);
    }

    /** The id of the item tag that marks an item as food for this rule. */
    public static String fanBurntFoodTag() {
        return readFanString(FAN_BURNT_FOOD_TAG, DEFAULT_BURNT_FOOD_TAG);
    }

    /**
     * Reads the fan values that are not numbers. Like {@link #readFanValue}, a list read before the
     * common config was loaded falls back to the built-in default rather than failing.
     */
    private static List<? extends String> readFanList(ForgeConfigSpec.ConfigValue<List<? extends String>> value) {
        return readConfigList(value, DEFAULT_HOT_AIR_CHAMBER_FLUIDS);
    }

    /** Same as {@link #readFanValue}, for a config list with its own built-in default. */
    private static List<? extends String> readConfigList(ForgeConfigSpec.ConfigValue<List<? extends String>> value,
                                                        List<? extends String> fallback) {
        try {
            return value.get();
        } catch (IllegalStateException notLoadedYet) {
            return fallback;
        }
    }

    /**
     * The item a broken item turns into, or null when that item has no entry in the table.
     *
     * <p>Both sides are ids and nothing else: this reads the table and answers with an id, so the item
     * the player is holding when the lookup runs is the one the registry knows, and an entry naming an
     * item of another mod works the moment that mod registers it.
     *
     * <p>The table is re-parsed whenever the config hands back a different list, the same way
     * {@link #hotAirChamberTemperature(ResourceLocation)} handles its fluid table, so editing the
     * config file and reloading it takes effect without a restart.
     */
    public static ResourceLocation brokenItemReplacement(ResourceLocation brokenItem) {
        List<? extends String> entries = readConfigList(BROKEN_ITEM_REPLACEMENTS, DEFAULT_BROKEN_ITEM_REPLACEMENTS);
        if (!entries.equals(parsedBrokenItemReplacementsFrom)) {
            parsedBrokenItemReplacements = parseBrokenItemReplacements(entries);
            parsedBrokenItemReplacementsFrom = entries;
        }
        return parsedBrokenItemReplacements.get(brokenItem);
    }

    /**
     * Turns the config table into broken item - replacement pairs. A line that is not
     * {@code <item id>=<item id>} is left out rather than taken as a reason to fail: a typo in the
     * config must not take the game down, and it must not silently replace the wrong item either.
     */
    private static Map<ResourceLocation, ResourceLocation> parseBrokenItemReplacements(List<? extends String> entries) {
        Map<ResourceLocation, ResourceLocation> replacements = new HashMap<>();
        for (String entry : entries) {
            int separator = entry.indexOf('=');
            if (separator < 1) {
                continue;
            }
            ResourceLocation broken = ResourceLocation.tryParse(entry.substring(0, separator).trim());
            ResourceLocation replacement = ResourceLocation.tryParse(entry.substring(separator + 1).trim());
            if (broken == null || replacement == null) {
                continue;
            }
            replacements.put(broken, replacement);
        }
        return replacements;
    }

    /**
     * The temperature a hot air chamber filled with {@code fluid} heats a fan's air to, or 0 when the
     * fluid does not heat at all - it is not in the table, or the table lists it at 0 such as water.
     *
     * <p>The table is re-parsed whenever the config hands back a different list, so editing the
     * config file and reloading it takes effect without a restart, and the common case of reading the
     * same table is an identity check on a list of one or two strings.
     */
    public static float hotAirChamberTemperature(ResourceLocation fluid) {
        List<? extends String> entries = readFanList(FAN_HOT_AIR_CHAMBER_FLUIDS);
        if (!entries.equals(parsedHotAirChamberFluidsFrom)) {
            parsedHotAirChamberFluids = parseHotAirChamberFluids(entries);
            parsedHotAirChamberFluidsFrom = entries;
        }
        return parsedHotAirChamberFluids.getOrDefault(fluid, 0.0F);
    }

    /** Millibuckets per tick a hot air chamber loses while a fan is running on it. */
    public static float fanHotAirChamberConsumption() {
        return readFanValue(FAN_HOT_AIR_CHAMBER_CONSUMPTION, 0.01F);
    }

    /**
     * The temperature a tunnel heater heats to, or 0 when it heats nothing.
     *
     * <p>One number and not a per-fluid table any more: the heater runs on Forge Energy, so there is no
     * fluid to look a temperature up by. 1500 is what a tank of lava used to give it.
     */
    public static float tunnelHeaterTemperature() {
        return readFanValue(TUNNEL_HEATER_TEMPERATURE, 1500.0F);
    }

    /**
     * Forge Energy a tunnel heater draws per tick while it is switched on, whether that is a fan running
     * on it or the heater working on its own tick.
     */
    public static float tunnelHeaterEnergyPerTick() {
        return readFanValue(TUNNEL_HEATER_ENERGY_PER_TICK, 20.0F);
    }

    /**
     * Forge Energy one block of a run adds to the battery the whole run shares.
     *
     * <p>Read through {@code readFanValue} rather than straight off the key so that a config file that
     * cannot be read yet - the same window every other value here guards against - falls back to the
     * shipped number instead of zero, which would make a battery that holds nothing.
     */
    public static int tunnelHeaterEnergyCapacityPerBlock() {
        return Math.max(1, readFanInt(TUNNEL_HEATER_ENERGY_CAPACITY, 4000));
    }

    /**
     * Degrees per tick a tunnel heater heats the item on the belt below it, before heat capacity. The
     * fallback mirrors the default of the key, which is four times what TFC's own
     * {@code addTemp(IHeat, float)} steps by.
     */
    public static float tunnelHeaterHeatingRate() {
        return readFanValue(FAN_TUNNEL_HEATER_HEATING_RATE, 12.0F);
    }

    /**
     * What the 'workable' and 'weldable' modes of a tunnel heater multiply their target temperature
     * by. Two guards on top of the config key, because this number is used to decide whether a belt
     * item is released and a bad one would hold the belt for good: a value that cannot be read falls
     * back to 1.2, and one that came back below 1.0 is raised to 1.0 rather than asked for a
     * temperature below the one TFC needs. The key itself already refuses anything under 1.0, so the
     * clamp only matters for a config file that was hand-edited past the spec.
     */
    public static float tunnelHeaterTargetMargin() {
        return Math.max(1.0F, readFanValue(FAN_TUNNEL_HEATER_TARGET_MARGIN, 1.2F));
    }

    /**
     * Turns the config table into fluid - temperature pairs. A line that is not
     * {@code <fluid id>=<temperature>} is left out rather than taken as a reason to fail: a typo in
     * the config must not take the fan down. Temperatures at or below 0 are left out too, because
     * they mean the same to a fan as an empty chamber does.
     */
    private static Map<ResourceLocation, Float> parseHotAirChamberFluids(List<? extends String> entries) {
        Map<ResourceLocation, Float> temperatures = new HashMap<>();
        for (String entry : entries) {
            int separator = entry.indexOf('=');
            if (separator < 1) {
                continue;
            }
            ResourceLocation fluid = ResourceLocation.tryParse(entry.substring(0, separator).trim());
            if (fluid == null) {
                continue;
            }
            try {
                float temperature = Float.parseFloat(entry.substring(separator + 1).trim());
                if (temperature > 0.0F) {
                    temperatures.put(fluid, temperature);
                }
            } catch (NumberFormatException notANumber) {
                // Same as an unknown fluid: this entry heats nothing.
            }
        }
        return temperatures;
    }

    public static void register(FMLJavaModLoadingContext ctx) {
        ctx.registerConfig(ModConfig.Type.COMMON, WoodenCogCommonConfigs.SPEC, "woodencog-common.toml");
    }
}

