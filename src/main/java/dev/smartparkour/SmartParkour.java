package dev.smartparkour;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

/** Experimental client-only controller for one-block, gray-wool dynamic parkour. */
public final class SmartParkour implements ClientModInitializer {
    private enum Phase { WAIT, BRAKE, CENTER, DELAY, AIM, APPROACH, AIRBORNE, FALLBACK, FALLBACK_DONE }
    // Every observed gray-wool appearance has its own identity. A vanished block
    // can reappear at the same coordinates and is then a fresh candidate.
    private final java.util.Map<BlockPos, Long> livePlatforms = new java.util.HashMap<>();
    private final java.util.Set<Long> visited = new java.util.HashSet<>();
    private long nextPlatformId = 1;
    private boolean sawAirborne = false;
    // Two indistinguishable platforms appearing simultaneously have no knowable order.

    private boolean enabled, previousToggle, previousMenu;
    private boolean fullStop = true, centerBeforeJump = true, fallbackEnabled = false;
    private int landingDelayMs = 350, delayTicks = 0;
    private double centerTolerance = 0.15;
    private float rotationSpeed = 20f, angleTolerance = 3f;
    private Phase phase = Phase.WAIT;
    private BlockPos target, takeoff;
    private int groundedTicks, waitingTicks, airborneTicks, brakeTicks;
    private double homeX = 0, homeY = 64, homeZ = 0;
    private boolean homeConfigured = false, fallbackUsed = false;

    @Override public void onInitializeClient() {
        loadConfig();
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void tick(MinecraftClient c) {
        if (c.player == null || c.world == null) { enabled = false; return; }
        boolean menuPressed = GLFW.glfwGetKey(c.getWindow().getHandle(), GLFW.GLFW_KEY_P) == GLFW.GLFW_PRESS;
        if (menuPressed && !previousMenu && c.currentScreen == null) { release(c); c.setScreen(new SettingsScreen()); }
        previousMenu = menuPressed;
        if (c.currentScreen != null) { release(c); return; }
        boolean pressed = GLFW.glfwGetKey(c.getWindow().getHandle(), GLFW.GLFW_KEY_O) == GLFW.GLFW_PRESS;
        if (pressed && !previousToggle) {
            enabled = !enabled;
            reset(c);
            fallbackUsed = false;
            visited.clear();
            livePlatforms.clear();
            nextPlatformId = 1;
            if (enabled) {
                updatePlatforms(c);
                markVisited(c.player.getBlockPos().down());
            }
            c.player.sendMessage(net.minecraft.text.Text.translatable(enabled ? "message.smartparkour.on" : "message.smartparkour.off"), true);
        }
        previousToggle = pressed;
        if (!enabled) return;
        updatePlatforms(c);
        boolean grounded = c.player.isOnGround();
        groundedTicks = grounded ? groundedTicks + 1 : 0;
        if (!grounded) sawAirborne = true;
        if (groundedTicks > 2 && sawAirborne && phase == Phase.AIRBORNE) {
            // Only accept a landing on the intended gray-wool block.
            BlockPos below = c.player.getBlockPos().down();
            if (below.equals(target)) {
                takeoff = target;
                markVisited(target);
                target = null;
                phase = fullStop ? Phase.BRAKE : (centerBeforeJump ? Phase.CENTER : Phase.DELAY);
                brakeTicks = 0;
                delayTicks = 0;
                waitingTicks = 0;
                fallbackUsed = false;
            } else { reset(c); return; }
        }
        if (phase == Phase.BRAKE) {
            release(c);
            if (!grounded) return;
            // Do not alter velocity directly: use ordinary opposite-direction input.
            Vec3d v = c.player.getVelocity();
            double speed = Math.hypot(v.x, v.z);
            if (speed > .025) {
                double yaw = Math.toRadians(c.player.getYaw());
                double forward = -Math.sin(yaw) * v.x + Math.cos(yaw) * v.z;
                double side = Math.cos(yaw) * v.x + Math.sin(yaw) * v.z;
                c.options.backKey.setPressed(forward > .025);
                c.options.forwardKey.setPressed(forward < -.025);
                c.options.leftKey.setPressed(side > .025);
                c.options.rightKey.setPressed(side < -.025);
                brakeTicks = 0;
                return;
            }
            if (++brakeTicks < 4) return;
            release(c);
            phase = centerBeforeJump ? Phase.CENTER : Phase.DELAY;
            delayTicks = 0;
        }
        if (phase == Phase.CENTER) {
            release(c);
            if (!grounded) return;
            BlockPos platform = c.player.getBlockPos().down();
            if (!c.world.getBlockState(platform).isOf(Blocks.GRAY_WOOL)) { phase = Phase.DELAY; delayTicks = 0; return; }
            Vec3d p = c.player.getEntityPos();
            double dx = platform.getX() + .5 - p.x, dz = platform.getZ() + .5 - p.z;
            if (Math.hypot(dx, dz) <= centerTolerance && Math.hypot(c.player.getVelocity().x, c.player.getVelocity().z) < .045) {
                phase = Phase.DELAY; delayTicks = 0; return;
            }
            // Low-speed positioning: never drive toward the edge when near the center.
            if (Math.hypot(dx, dz) > centerTolerance && Math.hypot(c.player.getVelocity().x, c.player.getVelocity().z) < .07) {
                aim(c, dx, dz);
                if (Math.abs(MathHelper.wrapDegrees((float)Math.toDegrees(Math.atan2(-dx, dz)) - c.player.getYaw())) < 8)
                    c.options.forwardKey.setPressed(true);
            }
            return;
        }
        if (phase == Phase.DELAY) {
            release(c);
            if (!grounded) return;
            if (++delayTicks <= (int)Math.ceil(landingDelayMs / 50.0)) return;
            phase = Phase.WAIT;
            waitingTicks = 0;
            return;
        }
        if (phase == Phase.FALLBACK_DONE) { release(c); phase = Phase.WAIT; return; }
        if (phase == Phase.FALLBACK) {
            release(c);
            if (!grounded) return;
            Vec3d pos = c.player.getEntityPos();
            double dx = homeX - pos.x, dz = homeZ - pos.z;
            if (Math.hypot(dx, dz) < .65 && Math.abs(homeY - pos.y) < 1.6) {
                // Re-arm parkour at the fallback destination, not the same fallback trip.
                visited.clear();
                markVisited(c.player.getBlockPos().down());
                fallbackUsed = true;
                phase = Phase.FALLBACK_DONE;
                return;
            }
            // Safe ground-only movement: no crossing gaps or obstacle pathfinding.
            double len = Math.hypot(dx, dz);
            if (len < .01) return;
            aim(c, dx, dz);
            float yawDiff = MathHelper.wrapDegrees((float)Math.toDegrees(Math.atan2(-dx, dz)) - c.player.getYaw());
            if (Math.abs(yawDiff) > 7) return;
            double stepX = dx / len * .65, stepZ = dz / len * .65;
            BlockPos feet = c.player.getBlockPos();
            BlockPos ahead = BlockPos.ofFloored(pos.x + stepX, pos.y, pos.z + stepZ);
            if (!c.world.getBlockState(ahead.down()).isSolidBlock(c.world, ahead.down()) ||
                !c.world.getBlockState(ahead).getCollisionShape(c.world, ahead).isEmpty() ||
                !c.world.getBlockState(ahead.up()).getCollisionShape(c.world, ahead.up()).isEmpty()) return;
            if (Math.abs(c.player.getVelocity().x) + Math.abs(c.player.getVelocity().z) > .18) return;
            c.options.forwardKey.setPressed(true);
            return;
        }
        if (phase == Phase.WAIT) {
            release(c);
            if (!grounded || groundedTicks < 3) return;
            if (++waitingTicks > 200) waitingTicks = 200;
            BlockPos next = findTarget(c);
            if (next == null) {
                // Never leave for fallback while another gray-wool platform exists.
                if (fallbackEnabled && homeConfigured && !fallbackUsed && waitingTicks >= 10 && !hasOtherWool(c)) phase = Phase.FALLBACK;
                return;
            }
            target = next;
            takeoff = c.player.getBlockPos().down();
            phase = Phase.AIM;
            release(c);
            return;
        }
        if (phase == Phase.AIM) {
            release(c);
            if (!grounded || groundedTicks < 3) return;
            Vec3d position = c.player.getEntityPos();
            double dx = target.getX() + .5 - position.x, dz = target.getZ() + .5 - position.z;
            aim(c, dx, dz);
            float yawGoal = (float)Math.toDegrees(Math.atan2(-dx, dz));
            if (Math.abs(MathHelper.wrapDegrees(yawGoal - c.player.getYaw())) <= angleTolerance) {
                phase = Phase.APPROACH;
                release(c);
            }
            return;
        }
        if (target == null || !validTarget(c, target)) { reset(c); return; }
        if (phase == Phase.APPROACH) {
            if (!grounded) { phase = Phase.AIRBORNE; airborneTicks = 0; return; }
            Vec3d position = c.player.getEntityPos();
            Vec3d destination = Vec3d.ofCenter(target).add(0, 0.5, 0);
            double dx = destination.x - position.x, dz = destination.z - position.z;
            double distance = Math.hypot(dx, dz);
            double vx = c.player.getVelocity().x, vz = c.player.getVelocity().z;
            double speedToward = distance > 0.01 ? (vx * dx + vz * dz) / distance : 0;
            // Re-aim only if alignment is lost; never move while turning significantly.
            float desiredYaw = (float)Math.toDegrees(Math.atan2(-dx, dz));
            if (Math.abs(MathHelper.wrapDegrees(desiredYaw - c.player.getYaw())) > angleTolerance) {
                release(c);
                phase = Phase.AIM;
                return;
            }
            c.options.forwardKey.setPressed(true);
            c.options.sprintKey.setPressed(true);
            c.options.jumpKey.setPressed(false);
            c.options.leftKey.setPressed(false);
            c.options.rightKey.setPressed(false);
            c.options.backKey.setPressed(false);
            double edge = distanceToEdge(c, takeoff);
            if (edge < 0.24 || (distance < 2.4 && speedToward > 0.12)) {
                c.options.jumpKey.setPressed(true);
                phase = Phase.AIRBORNE;
                sawAirborne = false;
                airborneTicks = 0;
            }
        } else if (phase == Phase.AIRBORNE) {
            airborneTicks++;
            if (airborneTicks > 35) { reset(c); return; }
            Vec3d p = c.player.getEntityPos();
            Vec3d v = c.player.getVelocity();
            double dx = target.getX() + .5 - p.x, dz = target.getZ() + .5 - p.z;
            aim(c, dx, dz);
            c.options.jumpKey.setPressed(false);
            c.options.sprintKey.setPressed(true);
            // Brake near the landing center to reduce overshoot; steering is intentionally conservative.
            double along = Math.hypot(dx, dz);
            double projected = along > .01 ? (v.x * dx + v.z * dz) / along : 0;
            c.options.forwardKey.setPressed(along > .32 && projected < .22);
            c.options.backKey.setPressed(along < .25 && projected < -.08);
            c.options.leftKey.setPressed(false);
            c.options.rightKey.setPressed(false);
        }
    }

    private BlockPos findTarget(MinecraftClient c) {
        BlockPos origin = c.player.getBlockPos().down();
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) for (int y = -1; y <= 1; y++) {
            BlockPos b = origin.add(x, y, z);
            Long id = livePlatforms.get(b);
            if (b.equals(origin) || b.equals(takeoff) || id == null || visited.contains(id) || !validTarget(c, b)) continue;
            double horizontal = Math.hypot(b.getX() - origin.getX(), b.getZ() - origin.getZ());
            // Deliberately conservative reachability limits, not a guarantee of landing.
            if (horizontal < 1.1 || horizontal > (y > 0 ? 3.0 : 4.0)) continue;
            if (y > 1 || y < -1) continue;
            double score = horizontal + Math.abs(y) * 1.2;
            if (score < bestScore) { bestScore = score; best = b.toImmutable(); }
        }
        return best;
    }


    /** Scan around the player, retaining identities for blocks still present.
     *  Only positions within the scanned loaded region can be removed; a block
     *  that leaves scan range keeps its identity until revisited and observed gone.
     */
    private void updatePlatforms(MinecraftClient c) {
        BlockPos center = c.player.getBlockPos();
        for (int x = -7; x <= 7; x++) for (int z = -7; z <= 7; z++) for (int y = -3; y <= 3; y++) {
            BlockPos pos = center.add(x, y, z);
            if (!c.world.isChunkLoaded(pos)) continue;
            if (c.world.getBlockState(pos).isOf(Blocks.GRAY_WOOL)) {
                if (!livePlatforms.containsKey(pos)) livePlatforms.put(pos.toImmutable(), nextPlatformId++);
            } else {
                livePlatforms.remove(pos);
            }
        }
    }

    private void markVisited(BlockPos pos) {
        Long id = livePlatforms.get(pos);
        if (id != null) visited.add(id);
    }

    private boolean hasOtherWool(MinecraftClient c) {
        BlockPos standing = c.player.getBlockPos().down();
        for (BlockPos pos : livePlatforms.keySet()) {
            if (!pos.equals(standing) && validTarget(c, pos)) return true;
        }
        return false;
    }

    private boolean validTarget(MinecraftClient c, BlockPos b) {
        if (!c.world.isChunkLoaded(b)) return false;
        BlockState floor = c.world.getBlockState(b);
        return floor.isOf(Blocks.GRAY_WOOL)
            && c.world.getBlockState(b.up()).getCollisionShape(c.world, b.up()).isEmpty()
            && c.world.getBlockState(b.up(2)).getCollisionShape(c.world, b.up(2)).isEmpty();
    }

    private void aim(MinecraftClient c, double dx, double dz) {
        float yaw = (float)(Math.toDegrees(Math.atan2(-dx, dz)));
        float current = c.player.getYaw();
        c.player.setYaw(current + MathHelper.clamp(MathHelper.wrapDegrees(yaw - current), -rotationSpeed, rotationSpeed));
    }

    private static double distanceToEdge(MinecraftClient c, BlockPos b) {
        if (b == null) return 0;
        Vec3d p = c.player.getEntityPos();
        double x = Math.min(p.x - b.getX(), b.getX() + 1 - p.x);
        double z = Math.min(p.z - b.getZ(), b.getZ() + 1 - p.z);
        return Math.min(x, z);
    }

    private void loadConfig() {
        // Set all three coordinates in config/smartparkour.properties to enable fallback.
        java.nio.file.Path file = net.fabricmc.loader.api.FabricLoader.getInstance()
            .getConfigDir().resolve("smartparkour.properties");
        try {
            if (!java.nio.file.Files.exists(file)) {
                java.nio.file.Files.writeString(file,
                    "# Set enabled=true and your destination coordinates to enable ground-only fallback\n" +
                    "enabled=false\nx=0\ny=64\nz=0\nfullStop=true\ncenterBeforeJump=true\nlandingDelayMs=350\ncenterTolerance=0.15\n");
            }
            java.util.Properties props = new java.util.Properties();
            try (java.io.Reader reader = java.nio.file.Files.newBufferedReader(file)) { props.load(reader); }
            homeConfigured = Boolean.parseBoolean(props.getProperty("enabled", "false"));
            fallbackEnabled = homeConfigured;
            fullStop = Boolean.parseBoolean(props.getProperty("fullStop", "true"));
            centerBeforeJump = Boolean.parseBoolean(props.getProperty("centerBeforeJump", "true"));
            landingDelayMs = Math.max(0, Math.min(1500, Integer.parseInt(props.getProperty("landingDelayMs", "350"))));
            centerTolerance = Math.max(.05, Math.min(.35, Double.parseDouble(props.getProperty("centerTolerance", "0.15"))));
            rotationSpeed = (float)Math.max(5, Math.min(90, Double.parseDouble(props.getProperty("rotationSpeed", "20"))));
            angleTolerance = (float)Math.max(1, Math.min(15, Double.parseDouble(props.getProperty("angleTolerance", "3"))));
            homeX = Double.parseDouble(props.getProperty("x", "0"));
            homeY = Double.parseDouble(props.getProperty("y", "64"));
            homeZ = Double.parseDouble(props.getProperty("z", "0"));
        } catch (Exception e) {
            homeConfigured = false;
            System.err.println("SmartParkour: invalid config: " + e.getMessage());
        }
    }

    private void saveConfig() {
        java.util.Properties props = new java.util.Properties();
        props.setProperty("enabled", Boolean.toString(fallbackEnabled));
        props.setProperty("x", Double.toString(homeX));
        props.setProperty("y", Double.toString(homeY));
        props.setProperty("z", Double.toString(homeZ));
        props.setProperty("fullStop", Boolean.toString(fullStop));
        props.setProperty("centerBeforeJump", Boolean.toString(centerBeforeJump));
        props.setProperty("landingDelayMs", Integer.toString(landingDelayMs));
        props.setProperty("centerTolerance", Double.toString(centerTolerance));
        props.setProperty("rotationSpeed", Float.toString(rotationSpeed));
        props.setProperty("angleTolerance", Float.toString(angleTolerance));
        try (java.io.Writer w = java.nio.file.Files.newBufferedWriter(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("smartparkour.properties"))) {
            props.store(w, "SmartParkour 0.7.0 settings");
        } catch (Exception e) { System.err.println("SmartParkour save failed: " + e); }
    }

    private final class SettingsScreen extends Screen {
        private TextFieldWidget xField, yField, zField;
        private String error = "";
        SettingsScreen() { super(Text.translatable("screen.smartparkour.title")); }
        private Text tr(String key, Object... args) { return Text.translatable("option.smartparkour." + key, args); }
        private Text toggle(String key, boolean value) { return tr(key).copy().append(": ").append(Text.translatable(value ? "option.smartparkour.on" : "option.smartparkour.off")); }
        @Override protected void init() {
            int cx = width / 2, y = Math.max(30, height / 2 - 125);
            addDrawableChild(ButtonWidget.builder(toggle("fallback", fallbackEnabled), b -> {
                fallbackEnabled = !fallbackEnabled; b.setMessage(toggle("fallback", fallbackEnabled));
            }).dimensions(cx - 155, y, 150, 20).build());
            addDrawableChild(ButtonWidget.builder(toggle("full_stop", fullStop), b -> {
                fullStop = !fullStop; b.setMessage(toggle("full_stop", fullStop));
            }).dimensions(cx + 5, y, 150, 20).build());
            y += 24;
            addDrawableChild(ButtonWidget.builder(toggle("center", centerBeforeJump), b -> {
                centerBeforeJump = !centerBeforeJump; b.setMessage(toggle("center", centerBeforeJump));
            }).dimensions(cx - 155, y, 310, 20).build());
            y += 24;
            addDrawableChild(ButtonWidget.builder(tr("delay", landingDelayMs), b -> {
                landingDelayMs = Math.max(0, landingDelayMs - 50); b.setMessage(tr("delay", landingDelayMs));
            }).dimensions(cx - 155, y, 150, 20).build());
            addDrawableChild(ButtonWidget.builder(tr("delay_plus"), b -> {
                landingDelayMs = Math.min(1500, landingDelayMs + 50);
            }).dimensions(cx + 5, y, 150, 20).build());
            y += 24;
            addDrawableChild(ButtonWidget.builder(tr("tolerance", String.format(java.util.Locale.ROOT, "%.2f", centerTolerance)), b -> {
                centerTolerance = centerTolerance >= .35 ? .05 : Math.round((centerTolerance + .05) * 100.0) / 100.0;
                b.setMessage(tr("tolerance", String.format(java.util.Locale.ROOT, "%.2f", centerTolerance)));
            }).dimensions(cx - 155, y, 310, 20).build());
            y += 24;
            addDrawableChild(ButtonWidget.builder(tr("rotation_speed", (int)rotationSpeed), b -> {
                rotationSpeed = rotationSpeed >= 90 ? 5 : rotationSpeed + 5;
                b.setMessage(tr("rotation_speed", (int)rotationSpeed));
            }).dimensions(cx - 155, y, 150, 20).build());
            addDrawableChild(ButtonWidget.builder(tr("angle", (int)angleTolerance), b -> {
                angleTolerance = angleTolerance >= 15 ? 1 : angleTolerance + 1;
                b.setMessage(tr("angle", (int)angleTolerance));
            }).dimensions(cx + 5, y, 150, 20).build());
            y += 27;
            xField = new TextFieldWidget(textRenderer, cx - 155, y, 98, 20, Text.literal("X")); xField.setText(Double.toString(homeX)); addDrawableChild(xField);
            yField = new TextFieldWidget(textRenderer, cx - 49, y, 98, 20, Text.literal("Y")); yField.setText(Double.toString(homeY)); addDrawableChild(yField);
            zField = new TextFieldWidget(textRenderer, cx + 57, y, 98, 20, Text.literal("Z")); zField.setText(Double.toString(homeZ)); addDrawableChild(zField);
            y += 24;
            addDrawableChild(ButtonWidget.builder(tr("use_position"), b -> {
                if (client != null && client.player != null) {
                    Vec3d p = client.player.getEntityPos();
                    xField.setText(Double.toString(Math.floor(p.x) + .5));
                    yField.setText(Double.toString(Math.floor(p.y)));
                    zField.setText(Double.toString(Math.floor(p.z) + .5));
                }
            }).dimensions(cx - 155, y, 310, 20).build());
            y += 24;
            addDrawableChild(ButtonWidget.builder(tr("save"), b -> {
                try {
                    homeX = Double.parseDouble(xField.getText()); homeY = Double.parseDouble(yField.getText()); homeZ = Double.parseDouble(zField.getText());
                    if (!Double.isFinite(homeX) || !Double.isFinite(homeY) || !Double.isFinite(homeZ)) throw new NumberFormatException();
                    homeConfigured = fallbackEnabled;
                    saveConfig();
                    if (client != null) client.setScreen(null);
                } catch (NumberFormatException e) { error = "error.smartparkour.coordinates"; }
            }).dimensions(cx - 155, y, 310, 20).build());
        }
        @Override public void render(net.minecraft.client.gui.DrawContext context, int mouseX, int mouseY, float delta) {
            super.render(context, mouseX, mouseY, delta);
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("SmartParkour v0.7.0 | P / O"), width / 2, Math.max(15, height / 2 - 143), 0xFFFFFF);
            if (!error.isEmpty()) context.drawCenteredTextWithShadow(textRenderer, Text.translatable(error), width / 2, height / 2 + 118, 0xFF5555);
        }
    }

    private void reset(MinecraftClient c) {
        target = null;
        takeoff = null;
        groundedTicks = 0;
        waitingTicks = 0;
        airborneTicks = 0;
        phase = Phase.WAIT;
        sawAirborne = false;
        release(c);
    }
    private static void release(MinecraftClient c) {
        c.options.forwardKey.setPressed(false);
        c.options.backKey.setPressed(false);
        c.options.leftKey.setPressed(false);
        c.options.rightKey.setPressed(false);
        c.options.jumpKey.setPressed(false);
        c.options.sprintKey.setPressed(false);
    }
}
