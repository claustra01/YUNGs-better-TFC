package net.claustra01.yungsbettertfc.world.processor;

import com.mojang.serialization.MapCodec;
import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.Set;
import javax.annotation.Nullable;
import net.claustra01.yungsbettertfc.ModStructureProcessors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
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
 * <p>This is intentionally conservative: it avoids replacing functional blocks (containers/spawners/etc.)
 * and focuses on building materials (stone, wood, some metal) where TFC differs significantly.</p>
 */
public final class TfcBlockReplacementProcessor extends StructureProcessor {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final AtomicBoolean LOGGED_FIRST_REPLACEMENT = new AtomicBoolean(false);

    public static final TfcBlockReplacementProcessor INSTANCE = new TfcBlockReplacementProcessor();
    public static final MapCodec<TfcBlockReplacementProcessor> CODEC = MapCodec.unit(INSTANCE);

    private static final String NS_MINECRAFT = "minecraft";
    private static final String NS_TFC = "tfc";

    private static final String DEFAULT_ROCK = "granite";
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
                    "bamboo",
                    "crimson",
                    "warped");

    private static final ThreadLocal<Long2ObjectOpenHashMap<String>> ROCK_CACHE =
            ThreadLocal.withInitial(Long2ObjectOpenHashMap::new);

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
        // We resolve the underlying ServerLevel to perform a dimension guard.
        var serverLevel = resolveServerLevel(level);
        // Only apply in the overworld (TFC overworld replacement).
        if (serverLevel != null && serverLevel.dimension() != Level.OVERWORLD) {
            return processedBlockInfo;
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

        // Determine rock name once per template placement origin (offset).
        Long2ObjectOpenHashMap<String> cache = ROCK_CACHE.get();
        if (cache.size() > 2048) {
            cache.clear();
        }
        String rock = cache.get(offset.asLong());
        if (rock == null) {
            rock = findRockNameBelow(level, offset);
            if (rock == null) {
                rock = DEFAULT_ROCK;
            }
            cache.put(offset.asLong(), rock);
        }

        @Nullable ResourceLocation outId = mapVanillaToTfc(path, rock, infested);
        if (outId == null) {
            return processedBlockInfo;
        }

        Block outBlock = BuiltInRegistries.BLOCK.getOptional(outId).orElse(null);
        if (outBlock == null || outBlock == Blocks.AIR) {
            return processedBlockInfo;
        }

        BlockState out = copyPropertiesByName(in, outBlock.defaultBlockState());
        if (LOGGED_FIRST_REPLACEMENT.compareAndSet(false, true)) {
            LOGGER.info(
                    "Activated TFC block replacement processor. Example: {} -> {} (at {}, offset {}).",
                    inId,
                    outId,
                    processedBlockInfo.pos(),
                    offset);
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

    private static @Nullable ResourceLocation mapVanillaToTfc(String vanillaPath, String rock, boolean infested) {
        // Stone families (rock-dependent).
        @Nullable ResourceLocation stone = mapStone(vanillaPath, rock, infested);
        if (stone != null) {
            return stone;
        }

        // Wood families.
        @Nullable ResourceLocation wood = mapWood(vanillaPath);
        if (wood != null) {
            return wood;
        }

        // Metals.
        @Nullable ResourceLocation metal = mapMetal(vanillaPath);
        if (metal != null) {
            return metal;
        }

        // Lights (TFC has its own torches).
        if ("lantern".equals(vanillaPath)) {
            return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/lamp/wrought_iron");
        }
        if ("soul_lantern".equals(vanillaPath)) {
            return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/lamp/wrought_iron");
        }
        if ("torch".equals(vanillaPath)) {
            return ResourceLocation.fromNamespaceAndPath(NS_TFC, "torch");
        }
        if ("wall_torch".equals(vanillaPath)) {
            return ResourceLocation.fromNamespaceAndPath(NS_TFC, "wall_torch");
        }

        return null;
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

        // Other stone-like building blocks used as accents in YUNG templates.
        switch (p) {
            case "polished_blackstone":
                return tfcRock("rock/smooth/", rock);
            case "polished_blackstone_stairs":
                return tfcRock("rock/smooth/", rock, "_stairs");
            case "polished_blackstone_slab":
                return tfcRock("rock/smooth/", rock, "_slab");
            case "chiseled_polished_blackstone":
                return tfcRock("rock/chiseled/", rock);
            case "nether_bricks":
                return tfcRock("rock/bricks/", rock);
            case "nether_brick_stairs":
                return tfcRock("rock/bricks/", rock, "_stairs");
            case "nether_brick_slab":
                return tfcRock("rock/bricks/", rock, "_slab");
            case "nether_brick_wall":
                return tfcRock("rock/bricks/", rock, "_wall");
            case "red_nether_bricks":
                return tfcRock("rock/bricks/", rock);
            case "red_nether_brick_slab":
                return tfcRock("rock/bricks/", rock, "_slab");
            case "chiseled_nether_bricks":
                return tfcRock("rock/chiseled/", rock);
            case "sandstone":
                return tfcRock("rock/raw/", rock);
            case "red_sandstone_slab":
                return tfcRock("rock/raw/", rock, "_slab");
            case "bricks":
                return tfcRock("rock/bricks/", rock);
            case "brick_slab":
                return tfcRock("rock/bricks/", rock, "_slab");
            case "brick_stairs":
                return tfcRock("rock/bricks/", rock, "_stairs");
            case "end_stone_brick_slab":
                return tfcRock("rock/bricks/", rock, "_slab");
            default:
                break;
        }

        return null;
    }

    private static @Nullable ResourceLocation mapWood(String vanillaPath) {
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

        // Vanilla bookshelf doesn't encode wood type; use oak by default.
        if ("bookshelf".equals(vanillaPath)) {
            return tfcWood("wood/bookshelf/", DEFAULT_WOOD);
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
            case "anvil":
            case "chipped_anvil":
            case "damaged_anvil":
                return ResourceLocation.fromNamespaceAndPath(NS_TFC, "metal/anvil/wrought_iron");
            default:
                return null;
        }
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
