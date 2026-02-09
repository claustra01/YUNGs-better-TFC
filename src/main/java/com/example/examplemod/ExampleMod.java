package com.example.examplemod;

import net.neoforged.fml.common.Mod;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(ExampleMod.MODID)
public final class ExampleMod {
    public static final String MODID = "examplemod";

    public ExampleMod() {
        // Intentionally empty: this mod is data-pack driven (tags only).
    }
}
