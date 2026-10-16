package dev.airac.compat.mixin;

import dev.airac.compat.FlightAssistantIntegration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import ru.octol1ttle.flightassistant.impl.computer.autoflight.AutoFlightComputer;

/** Runtime-only patch: official FA is untouched; ordinary FA plans keep their own guidance. */
@Pseudo
@Mixin(targets="ru.octol1ttle.flightassistant.impl.computer.autoflight.FlightPlanComputer",remap=false)
abstract class FaFlightPlanMixin {
    @Inject(method="getLateralMode",at=@At("HEAD"),cancellable=true)
    private void airac$lateral(CallbackInfoReturnable<AutoFlightComputer.LateralMode> callback) {
        Object mode=FlightAssistantIntegration.flightPlanMode(this,0);
        if(mode!=null)callback.setReturnValue((AutoFlightComputer.LateralMode)mode);
    }
    @Inject(method="getVerticalMode",at=@At("HEAD"),cancellable=true)
    private void airac$vertical(CallbackInfoReturnable<AutoFlightComputer.VerticalMode> callback) {
        Object mode=FlightAssistantIntegration.flightPlanMode(this,1);
        if(mode!=null)callback.setReturnValue((AutoFlightComputer.VerticalMode)mode);
    }
    @Inject(method="getThrustMode",at=@At("HEAD"),cancellable=true)
    private void airac$thrust(CallbackInfoReturnable<AutoFlightComputer.ThrustMode> callback) {
        Object mode=FlightAssistantIntegration.flightPlanMode(this,2);
        if(mode!=null)callback.setReturnValue((AutoFlightComputer.ThrustMode)mode);
    }
}
