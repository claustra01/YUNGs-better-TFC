package net.claustra01.yungsbettertfc.world.processor;

import com.mojang.serialization.MapCodec;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Set;
import javax.annotation.Nullable;
import net.claustra01.yungsbettertfc.ModStructureProcessors;
import net.claustra01.yungsbettertfc.access.StructureTemplateIdAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.slf4j.Logger;

/**
 * Replaces certain vanilla blocks in jigsaw structures with TerraFirmaCraft equivalents.
 *
 * <p>This focuses on blocks where TFC differs significantly from vanilla, and on blocks that have TFC variants
 * (stone/wood/metal/soil/plants/decor).</p>
 */
public final class TfcBlockReplacementProcessor extends StructureProcessor {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean LOGGED_FIRST_REPLACEMENT = new AtomicBoolean(false);

    public static final TfcBlockReplacementProcessor INSTANCE = new TfcBlockReplacementProcessor();
    public static final MapCodec<TfcBlockReplacementProcessor> CODEC = MapCodec.unit(INSTANCE);

    private static final String NS_MINECRAFT = "minecraft";
    private static final String NS_TFC = "tfc";

    private static final String DEFAULT_ROCK_OVERWORLD = "granite";
    private static final String DEFAULT_ROCK_NETHER = "basalt";
    private static final String DEFAULT_ROCK_END = "granite";

    private static final String DEFAULT_SOIL = "mollisol";
    private static final String DEFAULT_WOOD = "oak";

    private static final Set<String> VANILLA_WOOD_TYPES =
            Set.of(
                    "oak",
                    "spruce",
                    "birch",
                    "jungle",
                    "acacia",
                    "dark_oak",
                    "mangrove",
                    "cherry",
                    "bamboo");

    private static final ThreadLocal<Long2ObjectOpenHashMap<String>> ROCK_CACHE =
            ThreadLocal.withInitial(Long2ObjectOpenHashMap::new);
    private static final ThreadLocal<Long2ObjectOpenHashMap<String>> SOIL_CACHE =
            ThreadLocal.withInitial(Long2ObjectOpenHashMap::new);
    private static final ThreadLocal<Long2ObjectOpenHashMap<String>> WOOD_CACHE =
            ThreadLocal.withInitial(Long2ObjectOpenHashMap::new);

    private enum ReplacementScope {
        FULL,
        UTILITY_ONLY
    }

    private TfcBlockReplacementProcessor() {}

    @Override
    protected StructureProcessorType<?> getType() {
        return ModStructureProcessors.TFC_BLOCK_REPLACEMENT.get();
    }

    @Override
    public @Nullable StructureTemplate.StructureBlockInfo process(
            LevelReader level,
            BlockPos offset,
            BlockPos pos,
            StructureTemplate.StructureBlockInfo rawBlockInfo,
            StructureTemplate.StructureBlockInfo processedBlockInfo,
            StructurePlaceSettings settings,
            @Nullable StructureTemplate template) {
        // In worldgen, the "level" is usually a WorldGenLevel/WorldGenRegion, not a ServerLevel.
        // We resolve the underlying ServerLevel for dimension-specific defaults.
        var serverLevel = resolveServerLevel(level);
        ReplacementScope scope = ReplacementScope.FULL;
        if (serverLevel != null && serverLevel.dimension() != Level.OVERWORLD) {
            scope = ReplacementScope.UTILITY_ONLY;
        }

        BlockState in = processedBlockInfo.state();
        Block inBlock = in.getBlock();

        // Skip air quickly.
        if (in.isAir()) {
            return processedBlockInfo;
        }

        ResourceLocation inId = BuiltInRegistries.BLOCK.getKey(inBlock);
        if (!NS_MINECRAFT.equals(inId.getNamespace())) {
            return processedBlockInfo;
        }

        String path = inId.getPath();
        boolean infested = false;
        if (path.startsWith("infested_")) {
            infested = true;
            path = path.substring("infested_".length());
        }

        // Cache context once per template placement origin (offset).
        long cacheKey = offset.asLong();
        String rock = DEFAULT_ROCK_OVERWORLD;
        String soil = DEFAULT_SOIL;
        if (scope == ReplacementScope.FULL) {
            String defaultRock = defaultRockFor(serverLevel);

            Long2ObjectOpenHashMap<String> rockCache = ROCK_CACHE.get();
            if (rockCache.size() > 2048) {
                rockCache.clear();
            }
            String cachedRock = rockCache.get(cacheKey);
            if (cachedRock == null) {
                cachedRock = findRockNameBelow(level, offset);
                if (cachedRock == null) {
                    cachedRock = defaultRock;
                }
                rockCache.put(cacheKey, cachedRock);
            }
            rock = cachedRock;

            Long2ObjectOpenHashMap<String> soilCache = SOIL_CACHE.get();
            if (soilCache.size() > 2048) {
                soilCache.clear();
            }
            String cachedSoil = soilCache.get(cacheKey);
            if (cachedSoil == null) {
                cachedSoil = findSoilNameBelow(level, offset);
                if (cachedSoil == null) {
                    cachedSoil = DEFAULT_SOIL;
                }
                soilCache.put(cacheKey, cachedSoil);
            }
            soil = cachedSoil;
        }

        Long2ObjectOpenHashMap<String> woodCache = WOOD_CACHE.get();
        if (woodCache.size() > 2048) {
            woodCache.clear();
        }
        String woodHint = woodCache.get(cacheKey);
        if (woodHint == null) {
            @Nullable String detected = detectVanillaWoodType(path);
            if (detected != null) {
                woodHint = detected;
                woodCache.put(cacheKey, woodHint);
            } else {
                woodHint = DEFAULT_WOOD;
            }
        }

        @Nullable ResourceLocation outId = mapVanillaToTfc(path, rock, soil, woodHint, infested, scope);
        if (outId == null) {
            return processedBlockInfo;
        }

        Block outBlock = BuiltInRegistries.BLOCK.getOptional(outId).orElse(null);
        if (outBlock == null || outBlock == Blocks.AIR) {
            return processedBlockInfo;
        }

        BlockState out = copyPropertiesByName(in, outBlock.defaultBlockState());
        if (LOGGED_FIRST_REPLACEMENT.compareAndSet(false, true)) {
            @Nullable ResourceLocation templateId = null;
            if (template instanceof StructureTemplateIdAccess access) {
                templateId = access.yungsbettertfc$getTemplateId();
            }
            LOGGER.info(
                    "Activated TFC block replacement processor. Example: {} -> {} (template {}, dim {}, rock {}, soil {}, wood {}).",
                    inId,
                    outId,
                    templateId,
                    serverLevel != null ? serverLevel.dimension().location() : null,
                    rock,
                    soil,
                    woodHint);
        }
        return new StructureTemplate.StructureBlockInfo(processedBlockInfo.pos(), out, processedBlockInfo.nbt());
    }

    private static @Nullable net.minecraft.server.level.ServerLevel resolveServerLevel(LevelReader level) {
        if (level instanceof net.minecraft.server.level.ServerLevel sl) {
            return sl;
        }
        if (level instanceof WorldGenLevel wgl) {
            return wgl.getLevel();
        }
        return null;
    }

    private static String defaultRockFor(@Nullable ServerLevel level) {
        if (level == null) {
            return DEFAULT_ROCK_OVERWORLD;
        }
        if (level.dimension() == Level.NETHER) {
            return DEFAULT_ROCK_NETHER;
        }
        if (level.dimension() == Level.END) {
            return DEFAULT_ROCK_END;
        }
        return DEFAULT_ROCK_OVERWORLD;
    }

    private static @Nullable ResourceLocation mapVanillaToTfc(
            String vanillaPath, String rock, String soil, String woodHint, boolean infested, ReplacementScope scope) {
        if (scope == ReplacementScope.UTILITY_ONLY) {
            return mapUtilityOnly(vanillaPath, woodHint);
        }

        // Stone families (rock-dependent).
        @Nullable ResourceLocation stone = mapStone(vanillaPath, rock, infested);
        if (stone != null) {
            return stone;
        }

        // Soils (soil-dependent).
        @Nullable ResourceLocation soilBlock = mapSoil(vanillaPath, soil);
        if (soilBlock != null) {
            return soilBlock;
        }

        // Wood families.
        @Nullable ResourceLocation wood = mapWood(vanillaPath, woodHint);
        if (wood != null) {
            return wood;
        }

        // Metals.
        @Nullable ResourceLocation metal = mapMetal(vanillaPath);
        if (metal != null) {
            return metal;
        }

        // Plants + decor.
        @Nullable ResourceLocation plantDecor = mapPlantsAndDecor(vanillaPath);
        if (plantDecor != null) {
            return plantDecor;
        }

        // Misc utilities.
        @Nullable ResourceLocation cauldron = mapCauldron(vanillaPath);
        if (cauldron != null) {
            return cauldron;
        }

        // Lights (TFC has its own torches / lamps).
        @Nullable ResourceLocation lights = mapLights(vanillaPath);
        if (lights != null) {
            return lights;
        }

        return null;
    }

    private static @Nullable ResourceLocation mapUtilityOnly(String vanillaPath, String woodHint) {
        // Wood utility blocks.
        @Nullable ResourceLocation wood = mapWoodUtilityOnly(vanillaPath, woodHint);
        if (wood != null) {
            return wood;
        }

        // Metal utilities (bars/chain/etc).
        switch (vanillaPath) {
            case "iron_bars":
            case "chain":
            case "iron_trapdoor":
            case "anvil":
            case "chipped_anvil":
            case "damaged_anvil":
                return mapMetal(vanillaPath);
            default:
                break;
        }

        // Small decor we can safely convert.
        @Nullable ResourceLocation plantDecor = mapPlantsAndDecor(vanillaPath);
        if (plantDecor != null) {
            return plantDecor;
        }

        @Nullable ResourceLocation cauldron = mapCauldron(vanillaPath);
        if (cauldron != null) {
            return cauldron;
        }

        @Nullable ResourceLocation lights = mapLights(vanillaPath);
        if (lights != null) {
            return lights;
        }

        return null;
    }

    private static @Nullable ResourceLocation mapWoodUtilityOnly(String vanillaPath, String woodHint) {
        switch (vanillaPath) {
            case "chest":
                return tfcWood("wood/chest/", woodHint);
            case "trapped_chest":
                return tfcWood("wood/trapped_chest/", woodHint);
            case "barrel":
                return tfcWood("wood/barrel/", woodHint);
            case "lectern":
                return tfcWood("wood/lectern/", woodHint);
            case "crafting_table":
                return tfcWood("wood/workbench/", woodHint);
            case "bookshelf":
                return tfcWood("wood/bookshelf/", woodHint);
            default:
                return null;
        }
    }

    private static @Nullable ResourceLocation mapCauldron(String vanillaPath) {
        // TFC doesn't have a direct cauldron equivalent; large vessels are the closest decorative container.
        switch (vanillaPath) {
            case "cauldron":
            case "water_cauldron":
            case "lava_cauldron":
            case "powder_snow_cauldron":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "ceramic/large_vessel");
            default:
                return null;
        }
    }

    private static @Nullable ResourceLocation mapLights(String vanillaPath) {
        switch (vanillaPath) {
            case "lantern":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/lamp/wrought_iron");
            case "torch":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "torch");
            case "wall_torch":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "wall_torch");
            default:
                return null;
        }
    }

    private static @Nullable ResourceLocation mapStone(String vanillaPath, String rock, boolean infested) {
        // Normalize infested blocks to their non-infested counterparts.
        String p = vanillaPath;
        if (infested) {
            // Already stripped "infested_" in caller; keep p.
        }

        // Stone bricks
        switch (p) {
            case "stone_bricks":
                return tfcRock("rock/bricks/", rock);
            case "mossy_stone_bricks":
                return tfcRock("rock/mossy_bricks/", rock);
            case "cracked_stone_bricks":
                return tfcRock("rock/cracked_bricks/", rock);
            case "chiseled_stone_bricks":
                return tfcRock("rock/chiseled/", rock);
            case "stone_brick_stairs":
                return tfcRock("rock/bricks/", rock, "_stairs");
            case "stone_brick_slab":
                return tfcRock("rock/bricks/", rock, "_slab");
            case "stone_brick_wall":
                return tfcRock("rock/bricks/", rock, "_wall");
            case "mossy_stone_brick_stairs":
                return tfcRock("rock/mossy_bricks/", rock, "_stairs");
            case "mossy_stone_brick_slab":
                return tfcRock("rock/mossy_bricks/", rock, "_slab");
            case "mossy_stone_brick_wall":
                return tfcRock("rock/mossy_bricks/", rock, "_wall");
            default:
                break;
        }

        // Cobblestone
        switch (p) {
            case "cobblestone":
                return tfcRock("rock/cobble/", rock);
            case "mossy_cobblestone":
                return tfcRock("rock/mossy_cobble/", rock);
            case "cobblestone_stairs":
                return tfcRock("rock/cobble/", rock, "_stairs");
            case "cobblestone_slab":
                return tfcRock("rock/cobble/", rock, "_slab");
            case "cobblestone_wall":
                return tfcRock("rock/cobble/", rock, "_wall");
            case "mossy_cobblestone_stairs":
                return tfcRock("rock/mossy_cobble/", rock, "_stairs");
            case "mossy_cobblestone_slab":
                return tfcRock("rock/mossy_cobble/", rock, "_slab");
            case "mossy_cobblestone_wall":
                return tfcRock("rock/mossy_cobble/", rock, "_wall");
            default:
                break;
        }

        // Generic stone
        switch (p) {
            case "stone":
                return tfcRock("rock/raw/", rock);
            case "stone_stairs":
                return tfcRock("rock/raw/", rock, "_stairs");
            case "stone_slab":
                return tfcRock("rock/raw/", rock, "_slab");
            case "smooth_stone":
                return tfcRock("rock/smooth/", rock);
            case "smooth_stone_slab":
                return tfcRock("rock/smooth/", rock, "_slab");
            default:
                break;
        }

        // Gravel
        if ("gravel".equals(p)) {
            return tfcRock("rock/gravel/", rock);
        }

        // Andesite family (YUNG structures use these frequently for variation).
        switch (p) {
            case "andesite":
                return tfcRock("rock/raw/", rock);
            case "andesite_stairs":
                return tfcRock("rock/raw/", rock, "_stairs");
            case "andesite_slab":
                return tfcRock("rock/raw/", rock, "_slab");
            case "andesite_wall":
                return tfcRock("rock/raw/", rock, "_wall");
            case "polished_andesite":
                return tfcRock("rock/smooth/", rock);
            case "polished_andesite_stairs":
                return tfcRock("rock/smooth/", rock, "_stairs");
            case "polished_andesite_slab":
                return tfcRock("rock/smooth/", rock, "_slab");
            default:
                break;
        }

        // Redstone / interaction blocks with rock variants.
        switch (p) {
            case "stone_button":
                return tfcRock("rock/button/", rock);
            case "stone_pressure_plate":
                return tfcRock("rock/pressure_plate/", rock);
            default:
                break;
        }

        return null;
    }

    private static @Nullable ResourceLocation mapSoil(String vanillaPath, String soil) {
        switch (vanillaPath) {
            case "dirt":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "dirt/" + soil);
            case "coarse_dirt":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "coarse_dirt/" + soil);
            case "grass_block":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "grass/" + soil);
            case "grass_path":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "grass_path/" + soil);
            case "rooted_dirt":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "rooted_dirt/" + soil);
            case "farmland":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "farmland/" + soil);
            default:
                return null;
        }
    }

    private static @Nullable ResourceLocation mapWood(String vanillaPath, String woodHint) {
        // Functional / decor blocks with TFC variants (no vanilla wood encoded).
        switch (vanillaPath) {
            case "chest":
                return tfcWood("wood/chest/", woodHint);
            case "trapped_chest":
                return tfcWood("wood/trapped_chest/", woodHint);
            case "barrel":
                return tfcWood("wood/barrel/", woodHint);
            case "lectern":
                return tfcWood("wood/lectern/", woodHint);
            case "crafting_table":
                return tfcWood("wood/workbench/", woodHint);
            case "bookshelf":
                return tfcWood("wood/bookshelf/", woodHint);
            default:
                break;
        }

        // Planks family: <wood>_planks, <wood>_stairs, <wood>_slab
        if (vanillaPath.endsWith("_planks")) {
            String wood = vanillaPath.substring(0, vanillaPath.length() - "_planks".length());
            if (!isVanillaWood(wood)) return null;
            return tfcWoodPlanks(wood);
        }
        if (vanillaPath.endsWith("_stairs")) {
            String wood = vanillaPath.substring(0, vanillaPath.length() - "_stairs".length());
            if (!isVanillaWood(wood)) return null;
            return tfcWoodPlanks(wood, "_stairs");
        }
        if (vanillaPath.endsWith("_slab")) {
            String wood = vanillaPath.substring(0, vanillaPath.length() - "_slab".length());
            if (!isVanillaWood(wood)) return null;
            return tfcWoodPlanks(wood, "_slab");
        }

        // Logs/wood
        if (vanillaPath.startsWith("stripped_") && vanillaPath.endsWith("_log")) {
            String wood = vanillaPath.substring("stripped_".length(), vanillaPath.length() - "_log".length());
            if (!isVanillaWood(wood)) return null;
            return tfcWood("wood/stripped_log/", wood);
        }
        if (vanillaPath.startsWith("stripped_") && vanillaPath.endsWith("_wood")) {
            String wood = vanillaPath.substring("stripped_".length(), vanillaPath.length() - "_wood".length());
            if (!isVanillaWood(wood)) return null;
            return tfcWood("wood/stripped_wood/", wood);
        }
        if (vanillaPath.endsWith("_log")) {
            String wood = vanillaPath.substring(0, vanillaPath.length() - "_log".length());
            if (!isVanillaWood(wood)) return null;
            return tfcWood("wood/log/", wood);
        }
        if (vanillaPath.endsWith("_wood")) {
            String wood = vanillaPath.substring(0, vanillaPath.length() - "_wood".length());
            if (!isVanillaWood(wood)) return null;
            return tfcWood("wood/wood/", wood);
        }

        // Wood utilities
        if (vanillaPath.endsWith("_fence_gate")) {
            String wood = vanillaPath.substring(0, vanillaPath.length() - "_fence_gate".length());
            if (!isVanillaWood(wood)) return null;
            return tfcWood("wood/fence_gate/", wood);
        }
        if (vanillaPath.endsWith("_fence")) {
            String wood = vanillaPath.substring(0, vanillaPath.length() - "_fence".length());
            if (!isVanillaWood(wood)) return null;
            return tfcWood("wood/fence/", wood);
        }
        if (vanillaPath.endsWith("_door")) {
            String wood = vanillaPath.substring(0, vanillaPath.length() - "_door".length());
            if (!isVanillaWood(wood)) return null;
            return tfcWood("wood/door/", wood);
        }
        if (vanillaPath.endsWith("_trapdoor")) {
            String wood = vanillaPath.substring(0, vanillaPath.length() - "_trapdoor".length());
            if (!isVanillaWood(wood)) return null;
            return tfcWood("wood/trapdoor/", wood);
        }

        if (vanillaPath.endsWith("_pressure_plate")) {
            String wood = vanillaPath.substring(0, vanillaPath.length() - "_pressure_plate".length());
            if (!isVanillaWood(wood)) return null;
            return tfcWood("wood/pressure_plate/", wood);
        }

        if (vanillaPath.endsWith("_button")) {
            String wood = vanillaPath.substring(0, vanillaPath.length() - "_button".length());
            if (!isVanillaWood(wood)) return null;
            return tfcWood("wood/button/", wood);
        }

        if (vanillaPath.endsWith("_wall_sign")) {
            String wood = vanillaPath.substring(0, vanillaPath.length() - "_wall_sign".length());
            if (!isVanillaWood(wood)) return null;
            return tfcWood("wood/wall_sign/", wood);
        }

        if (vanillaPath.endsWith("_sign")) {
            String wood = vanillaPath.substring(0, vanillaPath.length() - "_sign".length());
            if (!isVanillaWood(wood)) return null;
            return tfcWood("wood/sign/", wood);
        }

        return null;
    }

    private static boolean isVanillaWood(String wood) {
        return VANILLA_WOOD_TYPES.contains(wood);
    }

    private static @Nullable ResourceLocation mapMetal(String vanillaPath) {
        switch (vanillaPath) {
            case "iron_bars":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/bars/wrought_iron");
            case "chain":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/chain/wrought_iron");
            case "iron_block":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/block/wrought_iron");
            case "iron_trapdoor":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/trapdoor/wrought_iron");
            case "gold_block":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/block/gold");
            case "copper_block":
            case "cut_copper":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/block/copper");
            case "cut_copper_slab":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/block/copper_slab");
            case "cut_copper_stairs":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/block/copper_stairs");
            case "exposed_copper":
            case "exposed_cut_copper":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/exposed_block/copper");
            case "exposed_cut_copper_slab":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/exposed_block/copper_slab");
            case "exposed_cut_copper_stairs":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/exposed_block/copper_stairs");
            case "weathered_copper":
            case "weathered_cut_copper":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/weathered_block/copper");
            case "weathered_cut_copper_slab":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/weathered_block/copper_slab");
            case "weathered_cut_copper_stairs":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/weathered_block/copper_stairs");
            case "oxidized_copper":
            case "oxidized_cut_copper":
            case "waxed_oxidized_cut_copper":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/oxidized_block/copper");
            case "oxidized_cut_copper_slab":
            case "waxed_oxidized_cut_copper_slab":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/oxidized_block/copper_slab");
            case "oxidized_cut_copper_stairs":
            case "waxed_oxidized_cut_copper_stairs":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/oxidized_block/copper_stairs");
            case "anvil":
            case "chipped_anvil":
            case "damaged_anvil":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/anvil/wrought_iron");
            default:
                return null;
        }
    }

    private static @Nullable ResourceLocation mapPlantsAndDecor(String vanillaPath) {
        // Kelp (TFC has multiple types; leafy kelp is the closest analogue to vanilla).
        if ("kelp".equals(vanillaPath)) {
            return ResourceLocation.fromNamespaceAndPath(NS_TFC, "plant/leafy_kelp");
        }
        if ("kelp_plant".equals(vanillaPath)) {
            return ResourceLocation.fromNamespaceAndPath(NS_TFC, "plant/leafy_kelp_plant");
        }

        if ("sea_pickle".equals(vanillaPath)) {
            return ResourceLocation.fromNamespaceAndPath(NS_TFC, "sea_pickle");
        }

        // Flower pots.
        if (vanillaPath.startsWith("potted_")) {
            String plant = vanillaPath.substring("potted_".length());
            ResourceLocation candidate = ResourceLocation.fromNamespaceAndPath(NS_TFC, "plant/potted/" + plant);
            if (BuiltInRegistries.BLOCK.containsKey(candidate)) {
                return candidate;
            }
        }

        // Candles.
        if ("candle".equals(vanillaPath)) {
            return ResourceLocation.fromNamespaceAndPath(NS_TFC, "candle");
        }
        if (vanillaPath.endsWith("_candle")) {
            String color = vanillaPath.substring(0, vanillaPath.length() - "_candle".length());
            ResourceLocation candidate = ResourceLocation.fromNamespaceAndPath(NS_TFC, "candle/" + color);
            if (BuiltInRegistries.BLOCK.containsKey(candidate)) {
                return candidate;
            }
        }
        if ("candle_cake".equals(vanillaPath)) {
            return ResourceLocation.fromNamespaceAndPath(NS_TFC, "candle_cake");
        }
        if (vanillaPath.endsWith("_candle_cake")) {
            String color = vanillaPath.substring(0, vanillaPath.length() - "_candle_cake".length());
            ResourceLocation candidate = ResourceLocation.fromNamespaceAndPath(NS_TFC, "candle_cake/" + color);
            if (BuiltInRegistries.BLOCK.containsKey(candidate)) {
                return candidate;
            }
        }

        return null;
    }

    private static @Nullable String detectVanillaWoodType(String path) {
        // Strip common prefixes first.
        String p = path;
        if (p.startsWith("stripped_")) {
            p = p.substring("stripped_".length());
        }
        // Extract "<wood>_*".
        int idx = p.indexOf('_');
        if (idx <= 0) {
            return null;
        }
        String wood = p.substring(0, idx);
        if (!isVanillaWood(wood)) {
            return null;
        }
        return wood;
    }

    private static ResourceLocation tfcRock(String prefix, String rock) {
        return ResourceLocation.fromNamespaceAndPath(NS_TFC, prefix + rock);
    }

    private static ResourceLocation tfcRock(String prefix, String rock, String suffix) {
        return ResourceLocation.fromNamespaceAndPath(NS_TFC, prefix + rock + suffix);
    }

    private static ResourceLocation tfcWoodPlanks(String wood) {
        return tfcWoodPlanks(wood, "");
    }

    private static ResourceLocation tfcWoodPlanks(String wood, String suffix) {
        String w = normalizeWood(wood);
        ResourceLocation candidate = ResourceLocation.fromNamespaceAndPath(NS_TFC, "wood/planks/" + w + suffix);
        if (BuiltInRegistries.BLOCK.containsKey(candidate)) {
            return candidate;
        }
        return ResourceLocation.fromNamespaceAndPath(NS_TFC, "wood/planks/" + DEFAULT_WOOD + suffix);
    }

    private static ResourceLocation tfcWood(String prefix, String wood) {
        String w = normalizeWood(wood);
        ResourceLocation candidate = ResourceLocation.fromNamespaceAndPath(NS_TFC, prefix + w);
        if (BuiltInRegistries.BLOCK.containsKey(candidate)) {
            return candidate;
        }
        return ResourceLocation.fromNamespaceAndPath(NS_TFC, prefix + DEFAULT_WOOD);
    }

    private static String normalizeWood(String wood) {
        // TFC doesn't have all vanilla woods. These fallbacks keep the structure valid.
        if ("dark_oak".equals(wood)) return "oak";
        if ("jungle".equals(wood)) return "acacia";
        if ("cherry".equals(wood)) return "oak";
        if ("bamboo".equals(wood)) return "oak";
        if ("crimson".equals(wood)) return "oak";
        if ("warped".equals(wood)) return "oak";
        return wood;
    }

    private static @Nullable String findRockNameBelow(LevelReader level, BlockPos start) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(start.getX(), start.getY(), start.getZ());
        int minY = level.getMinBuildHeight();

        for (int i = 0; i < 64 && cursor.getY() >= minY; i++) {
            BlockState state = level.getBlockState(cursor);
            @Nullable String rock = rockNameFromTfcBlock(state);
            if (rock != null) {
                return rock;
            }
            cursor.move(0, -1, 0);
        }
        return null;
    }

    private static @Nullable String findSoilNameBelow(LevelReader level, BlockPos start) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(start.getX(), start.getY(), start.getZ());
        int minY = level.getMinBuildHeight();

        for (int i = 0; i < 64 && cursor.getY() >= minY; i++) {
            BlockState state = level.getBlockState(cursor);
            @Nullable String soil = soilNameFromTfcBlock(state);
            if (soil != null) {
                return soil;
            }
            cursor.move(0, -1, 0);
        }
        return null;
    }

    private static @Nullable String rockNameFromTfcBlock(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (!NS_TFC.equals(id.getNamespace())) {
            return null;
        }
        String path = id.getPath();
        if (!path.startsWith("rock/")) {
            return null;
        }
        int lastSlash = path.lastIndexOf('/');
        String tail = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        tail = stripSuffix(tail, "_stairs");
        tail = stripSuffix(tail, "_slab");
        tail = stripSuffix(tail, "_wall");
        return tail.isEmpty() ? null : tail;
    }

    private static @Nullable String soilNameFromTfcBlock(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (!NS_TFC.equals(id.getNamespace())) {
            return null;
        }
        String path = id.getPath();
        // Soil-like blocks have the soil type as the last path segment.
        if (!(path.startsWith("dirt/")
                || path.startsWith("coarse_dirt/")
                || path.startsWith("grass/")
                || path.startsWith("grass_path/")
                || path.startsWith("rooted_dirt/")
                || path.startsWith("farmland/")
                || path.startsWith("clay_grass/"))) {
            return null;
        }
        int lastSlash = path.lastIndexOf('/');
        String tail = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
        return tail.isEmpty() ? null : tail;
    }

    private static String stripSuffix(String s, String suffix) {
        return s.endsWith(suffix) ? s.substring(0, s.length() - suffix.length()) : s;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState copyPropertiesByName(BlockState from, BlockState to) {
        StateDefinition<Block, BlockState> def = to.getBlock().getStateDefinition();
        for (Property<?> fromProp : from.getProperties()) {
            Property<?> toProp = def.getProperty(fromProp.getName());
            if (toProp == null) {
                continue;
            }

            Comparable value = from.getValue((Property) fromProp);
            if (!((Property) toProp).getPossibleValues().contains(value)) {
                continue;
            }

            try {
                to = to.setValue((Property) toProp, value);
            } catch (Exception ignored) {
                // Defensive: if a property value can't be applied, just skip it.
            }
        }
        return to;
    }
}
