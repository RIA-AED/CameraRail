package red.ria.camerarail;

import red.ria.camerarail.api.CameraService;
import red.ria.camerarail.command.CameraCommand;
import red.ria.camerarail.config.LoadReport;
import red.ria.camerarail.config.RailConfig;
import red.ria.camerarail.config.TrackRepository;
import red.ria.camerarail.editor.EditorManager;
import red.ria.camerarail.editor.EditorUI;
import red.ria.camerarail.listener.CameraListener;
import red.ria.camerarail.message.Messages;
import red.ria.camerarail.runtime.CameraEngine;
import red.ria.camerarail.runtime.CameraOverlay;
import red.ria.camerarail.runtime.packet.PacketCameraOverlay;
import red.ria.camerarail.storage.RecoveryStore;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.logging.Level;

/**
 * CameraRail - lightweight spectator camera tracks for Paper and Folia.
 */
public final class CameraRailPlugin extends JavaPlugin {
    private volatile RailConfig config;
    private volatile Messages messages;
    private TrackRepository tracks;
    private RecoveryStore recovery;
    private CameraOverlay cameraOverlay;
    private CameraEngine engine;
    private EditorManager editors;
    private EditorUI editorUi;
    private CameraCommand command;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        saveResourceIfMissing("tracks.yml");
        saveResourceIfMissing("messages.yml");

        int schema = getConfig().getInt("schema-version", 1);
        if (schema != 1) {
            getLogger().warning("Unsupported config.yml schema-version " + schema + "; expected 1.");
        }

        config = RailConfig.load(getConfig(), getLogger());
        messages = Messages.load(this);

        File tracksFile = new File(getDataFolder(), "tracks.yml");
        tracks = new TrackRepository(tracksFile, config, getLogger());
        tracks.loadInitial();
        recovery = new RecoveryStore(getDataFolder(), config, getLogger());
        cameraOverlay = createCameraOverlay();

        engine = new CameraEngine(this, tracks, recovery, () -> config, () -> messages,
                this::reloadEverything, cameraOverlay);
        editors = new EditorManager(this, tracks, engine, () -> config, () -> messages);
        editorUi = new EditorUI(this, editors, tracks, () -> config, () -> messages);
        command = new CameraCommand(engine, tracks, editors, editorUi, () -> config, () -> messages,
                this::reloadEverything);

        getServer().getServicesManager().register(CameraService.class, engine, this, ServicePriority.Normal);
        getServer().getPluginManager().registerEvents(new CameraListener(engine), this);
        getServer().getPluginManager().registerEvents(editors, this);
        getServer().getPluginManager().registerEvents(editorUi, this);

        PluginCommand pluginCommand = getCommand("camerarail");
        if (pluginCommand == null) {
            getLogger().severe("Command camerarail is missing from plugin.yml; commands are unavailable.");
        } else {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        }
        getLogger().info("CameraRail enabled: " + tracks.ids().size() + " track(s), Paper/Folia ready.");
    }

    @Override
    public void onDisable() {
        if (engine != null) {
            engine.shutdown();
        }
        if (cameraOverlay != null) {
            cameraOverlay.close();
        }
        try {
            getServer().getServicesManager().unregister(CameraService.class, engine);
        } catch (RuntimeException exception) {
            getLogger().log(Level.FINE, "CameraService was already unregistered", exception);
        }
        if (tracks != null) {
            tracks.close();
        }
        if (recovery != null) {
            recovery.close();
        }
        getLogger().info("CameraRail disabled.");
    }

    /**
     * Reloads config, messages and tracks. Returns the number of loaded tracks, or -1 when
     * tracks.yml could not be loaded at all.
     */
    public int reloadEverything() {
        try {
            reloadConfig();
            RailConfig newConfig = RailConfig.load(getConfig(), getLogger());
            config = newConfig;
            messages = Messages.load(this);
            tracks.updateConfig(newConfig);
            recovery.updateConfig(newConfig);
            LoadReport report = tracks.reload();
            if (report.fatal() || !report.errors().isEmpty()) {
                return -1;
            }
            return report.tracks().size();
        } catch (RuntimeException exception) {
            getLogger().log(Level.SEVERE, "Could not reload CameraRail", exception);
            return -1;
        }
    }

    /**
     * The fake-player camera is an optional overlay. PacketEvents is a soft dependency:
     * without it the plugin still runs, but falls back to the armor-stand camera and loses
     * the FOV/blindness behaviour that needs a player camera entity.
     */
    private CameraOverlay createCameraOverlay() {
        if (getServer().getPluginManager().getPlugin("packetevents") == null) {
            getLogger().warning("PacketEvents not installed; fake-player camera disabled."
                    + " FOV and blindness/slowness/speed camera effects are unavailable;"
                    + " using the armor-stand camera. Install PacketEvents 2.14.0+ to enable them.");
            return null;
        }
        try {
            CameraOverlay overlay = new PacketCameraOverlay(this);
            getLogger().info("PacketEvents fake-player camera enabled.");
            return overlay;
        } catch (RuntimeException | LinkageError exception) {
            getLogger().log(Level.WARNING, "PacketEvents is installed but the fake-player camera "
                    + "could not start; using the armor-stand camera", exception);
            return null;
        }
    }

    private void saveResourceIfMissing(String name) {
        File file = new File(getDataFolder(), name);
        if (!file.isFile()) {
            try {
                saveResource(name, false);
            } catch (RuntimeException exception) {
                getLogger().log(Level.WARNING, "Could not write default " + name, exception);
            }
        }
    }
}
