package dev.smartparkour.mixin;

import dev.smartparkour.SmartParkour;
import net.minecraft.client.gui.screens.PauseScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Keep singleplayer ticking when the option is enabled. */
@Mixin(PauseScreen.class)
public abstract class PauseScreenMixin {
    @Inject(method = "isPauseScreen", at = @At("HEAD"), cancellable = true)
    private void smartparkour$unpause(CallbackInfoReturnable<Boolean> cir) {
        if (SmartParkour.shouldContinueInMenus()) cir.setReturnValue(false);
    }
}
