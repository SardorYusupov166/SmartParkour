package dev.smartparkour.mixin;

import dev.smartparkour.SmartParkour;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Only suppress the vanilla pause screen pause when explicitly enabled. */
@Mixin(Screen.class)
public abstract class PauseScreenMixin {
    @Inject(method = "isPauseScreen", at = @At("HEAD"), cancellable = true)
    private void smartparkour$unpause(CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this instanceof PauseScreen && SmartParkour.shouldContinueInMenus()) {
            cir.setReturnValue(false);
        }
    }
}
