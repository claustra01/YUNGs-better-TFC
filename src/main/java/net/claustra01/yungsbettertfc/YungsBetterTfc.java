package net.claustra01.yungsbettertfc;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod(YungsBetterTfc.MODID)
public final class YungsBetterTfc {
    public static final String MODID = "yungsbettertfc";

    public YungsBetterTfc(IEventBus modEventBus) {
        ModStructureProcessors.register(modEventBus);
    }
}
