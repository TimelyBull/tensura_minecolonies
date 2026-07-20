package com.example.examplemod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = ExampleMod.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = ExampleMod.MODID, value = Dist.CLIENT)
public class ExampleModClient {
    public ExampleModClient(ModContainer container) {
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        // Do not forget to add translations for your config options to the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // Some client setup code
        ExampleMod.LOGGER.info("HELLO FROM CLIENT SETUP");
        ExampleMod.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());

        // Absolute Annihilator: a "charged" model override. The item model's
        // override selects the lit-up texture when this property returns 1.0,
        // which happens once the weapon's Tensura EP reaches the threshold.
        event.enqueueWork(() -> ItemProperties.register(
                ExampleMod.ABSOLUTE_ANNIHILATOR.get(),
                ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "charged"),
                (stack, level, entity, seed) -> {
                    Double ep = stack.get(
                            io.github.manasmods.tensura.registry.item.misc.TensuraDataComponents.EP.get());
                    return (ep != null && ep >= AbsoluteAnnihilatorItem.CHARGE_EP) ? 1.0f : 0.0f;
                }));

        // Masterwork line: each blade starts as sleek steel at 0 EP and gains its
        // shimmer in stages — this property selects the model per EP tier.
        event.enqueueWork(() -> {
            for (var weapon : ExampleMod.MASTERWORK_WEAPONS) {
                ItemProperties.register(weapon.get(),
                        ResourceLocation.fromNamespaceAndPath(ExampleMod.MODID, "ep_tier"),
                        (stack, level, entity, seed) -> MasterworkItem.shimmerTier(stack));
            }
        });
    }
}
