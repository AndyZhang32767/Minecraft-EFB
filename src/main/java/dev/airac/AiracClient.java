package dev.airac;

import dev.airac.data.*;
import dev.airac.compat.FlightAssistantIntegration;
import dev.airac.nav.RouteTracker;
import dev.airac.ui.*;
import dev.airac.map.XaeroMapRenderer;
import dev.airac.sync.ClientChartSync;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.Locale;

public final class AiracClient implements ClientModInitializer {
    public static AiracClient INSTANCE;
    public static final Logger LOGGER = LoggerFactory.getLogger("airac");
    public ChartData chart = new ChartData();
    public NdSettings settings = new NdSettings();
    public final XaeroMapRenderer terrain = new XaeroMapRenderer();
    public String worldLabel = "";
    public String dimension = "";
    public String error = "";
    public boolean writable = true;
    public long chartEpoch;
    public long planRevision;
    public final ClientChartSync sync = new ClientChartSync(this);
    private boolean settingsWritable = true;
    private final ChartStore store = new ChartStore(FabricLoader.getInstance().getConfigDir().resolve("airac"));
    private final RouteTracker tracker = new RouteTracker();
    private String identity, connectionIdentity, playerId;
    public KeyBinding openKey;
    private KeyBinding modeKey, displayKey, rangeUp, rangeDown;
    private boolean rangeUpHeld, rangeDownHeld;

    @Override public void onInitializeClient() {
        INSTANCE = this;
        sync.register();
        FlightAssistantIntegration.init();
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->disconnect());
        try { settings = store.loadSettings(); }
        catch (IOException e) { settingsWritable = false; report("Settings could not be read; original preserved", e); }
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("airac", "navigation"));
        openKey = bind("chart", GLFW.GLFW_KEY_N, category);
        modeKey = bind("mode", GLFW.GLFW_KEY_V, category);
        displayKey = bind("display", GLFW.GLFW_KEY_B, category);
        rangeUp = bind("nd_range_up", GLFW.GLFW_KEY_PAGE_UP, category);
        rangeDown = bind("nd_range_down", GLFW.GLFW_KEY_PAGE_DOWN, category);
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> flush());
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.of("airac", "nd"),
                (context, counter) -> NdRenderer.render(context, this));
    }
    private KeyBinding bind(String name, int key, KeyBinding.Category category) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding("key.airac." + name, InputUtil.Type.KEYSYM, key, category));
    }
    private void tick(MinecraftClient client) {
        syncWorld(client);
        sync.tick(client);
        while (openKey.wasPressed()) if (client.player != null && client.currentScreen == null) client.setScreen(new ChartScreen());
        while (modeKey.wasPressed()) if (client.currentScreen == null) { settings.toggleMode(); saveSettings(); }
        while (displayKey.wasPressed()) if (client.currentScreen == null) { settings.cycleVisibility(); saveSettings(); announce("ui.airac.visibility", Ui.tr("ui.airac." + settings.visibility.name().toLowerCase(Locale.ROOT))); }
        rangeUpHeld = stepRange(client, rangeUp, rangeUpHeld, 1);
        rangeDownHeld = stepRange(client, rangeDown, rangeDownHeld, -1);
        if (client.world == null || client.player == null || identity == null) return;
        boolean retuned=false;
        for(int i=0;i<chart.radios.size();i++){
            var radio=chart.radios.get(i);
            boolean ilsOnPrimary=i==0 && FlightAssistantIntegration.active() && radio.mode==dev.airac.data.ChartData.Kind.ILS;
            if(radio.auto && !ilsOnPrimary && dev.airac.nav.RadioNavigation.tuneNearestVor(radio,chart,client.player.getX(),client.player.getZ())) retuned=true;
        }
        if(retuned) save();
        FlightAssistantIntegration.tick();
        // Planning is safe while standing in a menu; sequencing resumes after closing it.
        if (client.currentScreen == null && writable && !FlightAssistantIntegration.active() && tracker.tick(chart, client.player.getX(), client.player.getZ(),
                dev.airac.nav.NavMath.captureRadius(client.player.getY(), settings.captureRadius))) save();
    }
    /** Rising edge of the held key. GLFW repeat arrives about a second late and is ignored. */
    private boolean stepRange(MinecraftClient client, KeyBinding key, boolean held, int step) {
        while (key.wasPressed()) {}
        boolean down = key.isPressed();
        if (down && !held && client.currentScreen == null && client.player != null) {
            settings.manualRange(step, client.player.getY());
            saveSettings();
        }
        return down;
    }
    public boolean flying(MinecraftClient client) {
        return client.player != null && client.player.isGliding();
    }
    public void syncWorld(MinecraftClient client) {
        if (client.world == null || client.player == null) {
            if (identity != null) {
                disconnect();
            }
            return;
        }
        String nextIdentity;
        String label;
        if (client.getServer() != null) {
            nextIdentity = "singleplayer:" + client.getServer().getSavePath(WorldSavePath.ROOT).toAbsolutePath().normalize();
            label = client.getServer().getSaveProperties().getLevelName();
        } else if (client.getCurrentServerEntry() != null) {
            nextIdentity = "server:" + client.getCurrentServerEntry().address.trim().toLowerCase(Locale.ROOT);
            label = client.getCurrentServerEntry().name;
        } else {
            // LAN/direct connections still have a remote address, never a shared fallback bucket.
            var connection = client.getNetworkHandler();
            if (connection == null) return;
            nextIdentity = "connection:" + connection.getConnection().getAddress();
            label = nextIdentity;
        }
        String nextPlayer = client.player.getUuid().toString();
        String nextDimension = client.world.getRegistryKey().getValue().toString();
        if (nextIdentity.equals(connectionIdentity) && nextDimension.equals(dimension) && nextPlayer.equals(playerId)) return;
        FlightAssistantIntegration.stop();
        flush();
        terrain.reset();
        connectionIdentity = nextIdentity; playerId = nextPlayer;
        identity = ChartStore.personalIdentity(nextIdentity, playerId); dimension = nextDimension; sync.reset(); chartEpoch++; worldLabel = label;
        writable = true; error = ""; tracker.reset();
        try { chart = store.loadPersonal(nextIdentity, dimension, label, playerId); }
        catch (IOException e) { chart = new ChartData(); writable = false; report("Chart could not be read; original preserved", e); }
        if (client.currentScreen instanceof ChartScreen || client.currentScreen instanceof EditorScreen || client.currentScreen instanceof ChoiceScreen) client.setScreen(null);
    }
    private void disconnect() {
        FlightAssistantIntegration.stop();
        flush(); identity = connectionIdentity = playerId = null; dimension = ""; chart = new ChartData();
        tracker.reset(); terrain.reset(); sync.reset(); chartEpoch++;
    }
    /** Personal flight plans depend only on local storage, never OP or shared-chart permissions. */
    public boolean canEditPlans() { return writable; }
    public boolean canEditShared() { return writable && sync.canEdit(); }
    public void changed() { FlightAssistantIntegration.stop(); chart.refreshApproachFrequencies(); planRevision++; tracker.reset(); sync.publish(); save(); }
    public void planChanged() { FlightAssistantIntegration.stop(); planRevision++; tracker.reset(); save(); }
    public void useServerChart(String serverId) {
        save();
        String serverIdentity = connectionIdentity + "|efb:" + serverId;
        identity = ChartStore.personalIdentity(serverIdentity, playerId);
        writable = true;
        try { chart = store.loadPersonal(serverIdentity, dimension, worldLabel, playerId); }
        catch (IOException e) { chart = new ChartData(); writable = false; report("Personal flight plans could not be read; original preserved",e); }
    }
    public void syncError(String status) {
        error = status.isEmpty() ? "" : Ui.tr("ui.airac.sync_" + status);
    }
    public void sharedChartUpdated() {
        FlightAssistantIntegration.stop();tracker.reset(); chartEpoch++;
        var client=MinecraftClient.getInstance();
        if(client.currentScreen instanceof ChartScreen screen)screen.sharedChartUpdated();
    }
    public void save() {
        if (identity == null || !writable) return;
        try { store.save(identity, dimension, chart); if(!sync.shared)error = ""; }
        catch (IOException | RuntimeException e) { report("Unable to save chart", e); }
    }
    public void saveSettings() {
        if (!settingsWritable) return;
        try { store.saveSettings(settings); }
        catch (IOException e) { report("Unable to save settings", e); }
    }
    private void flush() { save(); }
    private void report(String message, Exception e) {
        error = message;
        LOGGER.error(message, e);
        var player = MinecraftClient.getInstance().player;
        if (player != null) player.sendMessage(Text.literal("[EFB] " + message), false);
    }
    private void announce(String key, Object... args) {
        var player = MinecraftClient.getInstance().player;
        if (player != null) player.sendMessage(Text.translatable(key, args), true);
    }
}
