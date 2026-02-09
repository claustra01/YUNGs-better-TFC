package net.claustra01.yungsbettertfc;

import net.claustra01.yungsbettertfc.world.processor.TfcBlockReplacementProcessor;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModStructureProcessors {
    private ModStructureProcessors() {}

    private static final DeferredRegister<StructureProcessorType<?>> STRUCTURE_PROCESSORS =
            DeferredRegister.create(Registries.STRUCTURE_PROCESSOR, YungsBetterTfc.MODID);

    public static final DeferredHolder<StructureProcessorType<?>, StructureProcessorType<TfcBlockReplacementProcessor>>
            TFC_BLOCK_REPLACEMENT = STRUCTURE_PROCESSORS.register(
                    "tfc_block_replacement",
                    () -> (StructureProcessorType<TfcBlockReplacementProcessor>) () -> TfcBlockReplacementProcessor.CODEC);

    public static void register(IEventBus modEventBus) {
        STRUCTURE_PROCESSORS.register(modEventBus);
    }
}
