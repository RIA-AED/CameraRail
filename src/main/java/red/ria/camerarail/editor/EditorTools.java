package red.ria.camerarail.editor;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import red.ria.camerarail.config.RailConfig;
import red.ria.camerarail.config.TrackRepository;
import red.ria.camerarail.message.Messages;
import red.ria.camerarail.model.CameraTrack;
import red.ria.camerarail.model.PathStep;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Item-based editing tools: a chest menu that hands out a capture tool and a preview tool,
 * plus the per-player selection state and the ActionBar readout those tools need.
 *
 * <p>The items carry no editing state in NBT beyond their kind: they address targets exactly
 * like the stateless commands do and hand every mutation to {@link EditService}. Selections
 * live in memory per player (and survive re-obtaining the item during the same session), so a
 * tool can never write to a stale hidden target after a reload; a deleted track or a step that
 * stopped being a path step is reported on the ActionBar instead of throwing.</p>
 *
 * <p>Everything player-related runs on the player's scheduler ({@code player.getScheduler()}),
 * which keeps the tools Folia-safe.</p>
 */
public final class EditorTools implements Listener, ToolSelectorUI.Callback {
    private static final String PERMISSION = "camerarail.edit";
    private static final String PDC_KEY = "edit_tool";
    private static final int MENU_SIZE = 27;
    private static final int SLOT_INFO = 4;
    private static final int SLOT_CAPTURE = 10;
    private static final int SLOT_PREVIEW = 12;
    private static final int SLOT_EDITOR = 14;
    private static final int SLOT_REMOVE = 16;
    private static final long ACTION_BAR_PERIOD_TICKS = 10L;
    private static final long ACTION_BAR_INITIAL_DELAY_TICKS = 10L;
    private static final long USE_DEBOUNCE_MILLIS = 150L;
    private static final long FLASH_MILLIS = 2000L;
    private static final int MAX_ACTION_BAR_LABEL = 30;

    /** The two item tools. The name is stored as a string in the item's persistent data container. */
    public enum ToolKind {
        CAPTURE(Material.BLAZE_ROD, "取景工具"),
        PREVIEW(Material.ENDER_EYE, "预览工具");

        private final Material material;
        private final String displayName;

        ToolKind(Material material, String displayName) {
            this.material = material;
            this.displayName = displayName;
        }

        public Material material() {
            return material;
        }

        public String displayName() {
            return displayName;
        }
    }

    /** What the capture tool writes the player's current view into. */
    public enum CaptureTarget {
        START,
        EXIT,
        PATH
    }

    private record CaptureSelection(CaptureTarget target, String trackId, int stepIndex) {
    }

    private record PreviewSelection(String trackId, int stepIndex) {
    }

    /** Per-player selections; a missing entry means "nothing chosen yet". */
    private static final class PlayerState {
        private CaptureSelection capture;
        private PreviewSelection preview;
    }

    /** Short-lived ActionBar text that wins over the persistent status until it expires. */
    private record Flash(Component text, long expiresAtMillis) {
    }

    /** Marker holder so the menu inventory can be told apart from every other inventory. */
    private static final class ToolMenu implements InventoryHolder {
        private Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final Plugin plugin;
    private final TrackRepository repository;
    private final EditService edits;
    private final Supplier<RailConfig> config;
    private final Supplier<Messages> messages;
    private final Consumer<Player> openEditorDialog;
    private final NamespacedKey toolKey;
    private final ToolSelectorUI selector;
    private final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledTask> actionBarTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Flash> flashes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastUseMillis = new ConcurrentHashMap<>();

    public EditorTools(Plugin plugin, TrackRepository repository, EditService edits,
                       Supplier<RailConfig> config, Supplier<Messages> messages,
                       java.util.function.Consumer<Player> openEditorDialog) {
        this.plugin = plugin;
        this.repository = repository;
        this.edits = edits;
        this.config = config;
        this.messages = messages;
        this.openEditorDialog = openEditorDialog;
        this.toolKey = new NamespacedKey(plugin, PDC_KEY);
        this.selector = new ToolSelectorUI(plugin, repository, edits, messages, this);
    }

    // ------------------------------------------------------------------ menu

    /** Opens the tool menu; the player gets the items here and never crafts commands. */
    public void openMenu(Player player) {
        if (!player.hasPermission(PERMISSION)) {
            messages.get().send(player, "command.no-permission");
            return;
        }
        onPlayer(player, () -> {
            player.closeDialog();
            ToolMenu holder = new ToolMenu();
            Inventory inventory = Bukkit.createInventory(holder, MENU_SIZE,
                    Component.text("镜头编辑工具"));
            holder.inventory = inventory;
            fillMenu(inventory);
            player.openInventory(inventory);
        });
    }

    private void fillMenu(Inventory inventory) {
        inventory.setItem(SLOT_INFO, infoItem());
        inventory.setItem(SLOT_CAPTURE, createTool(ToolKind.CAPTURE, "点击获取"));
        inventory.setItem(SLOT_PREVIEW, createTool(ToolKind.PREVIEW, "点击获取"));
        inventory.setItem(SLOT_EDITOR, editorItem());
        inventory.setItem(SLOT_REMOVE, removeItem());
    }

    private ItemStack infoItem() {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(itemName("编辑工具说明", NamedTextColor.WHITE));
        List<Component> lore = new ArrayList<>();
        lore.add(itemLore("取景工具：把当前视角写入轨道起点/退出点，或追加路径点.", NamedTextColor.GRAY));
        lore.add(itemLore("预览工具：选择轨道与步骤后右键开始预览.", NamedTextColor.GRAY));
        lore.add(itemLore("选择目标一律使用 Shift+右键.", NamedTextColor.GRAY));
        lore.add(itemLore("已载入轨道 " + repository.all().size() + " 条.", NamedTextColor.DARK_GRAY));
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack editorItem() {
        ItemStack item = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(itemName("图形编辑器", NamedTextColor.WHITE));
        meta.lore(List.of(
                itemLore("打开完整的 Dialog 图形编辑器.", NamedTextColor.GRAY),
                itemLore("编辑轨道、步骤、路径点与轨道设置.", NamedTextColor.GRAY)));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack removeItem() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(itemName("移除编辑工具", NamedTextColor.WHITE));
        meta.lore(List.of(
                itemLore("从背包移除所有取景/预览工具.", NamedTextColor.GRAY),
                itemLore("已选择的目标会保留到退出游戏.", NamedTextColor.DARK_GRAY)));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof ToolMenu)) {
            return;
        }
        // Every click inside this view is ours: nothing may be taken out or moved into it.
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Inventory clicked = event.getClickedInventory();
        if (clicked == null || clicked != event.getView().getTopInventory()) {
            return;
        }
        int slot = event.getRawSlot();
        onPlayer(player, () -> handleMenuClick(player, slot));
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ToolMenu) {
            event.setCancelled(true);
        }
    }

    private void handleMenuClick(Player player, int slot) {
        if (!player.isOnline()) {
            return;
        }
        switch (slot) {
            case SLOT_CAPTURE -> giveTool(player, ToolKind.CAPTURE);
            case SLOT_PREVIEW -> giveTool(player, ToolKind.PREVIEW);
            case SLOT_EDITOR -> {
                player.closeInventory();
                if (openEditorDialog != null) {
                    openEditorDialog.accept(player);
                }
            }
            case SLOT_REMOVE -> removeTools(player);
            default -> {
                // Decorative slots do nothing; the click is still cancelled above.
            }
        }
    }

    // ------------------------------------------------------------------ tool items

    private ItemStack createTool(ToolKind kind, String acquisitionHint) {
        ItemStack item = new ItemStack(kind.material());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(itemName(kind.displayName(), NamedTextColor.AQUA));
        List<Component> lore = new ArrayList<>();
        if (kind == ToolKind.CAPTURE) {
            lore.add(itemLore("右键：把当前视角写入所选目标", NamedTextColor.GRAY));
            lore.add(itemLore("Shift+右键：选择轨道与目标", NamedTextColor.GRAY));
            lore.add(itemLore("路径点上限 " + config.get().maxPathPoints() + " 个", NamedTextColor.DARK_GRAY));
        } else {
            lore.add(itemLore("右键：开始预览", NamedTextColor.GRAY));
            lore.add(itemLore("Shift+右键：选择轨道与步骤", NamedTextColor.GRAY));
            lore.add(itemLore("预览中按 Shift 退出预览", NamedTextColor.DARK_GRAY));
        }
        if (acquisitionHint != null) {
            lore.add(itemLore(acquisitionHint, NamedTextColor.DARK_GRAY));
        }
        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        meta.setEnchantmentGlintOverride(Boolean.TRUE);
        meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING, kind.name());
        item.setItemMeta(meta);
        return item;
    }

    private static Component itemName(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private static Component itemLore(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    private ToolKind toolKind(ItemStack item) {
        if (item == null) {
            return null;
        }
        Material type = item.getType();
        // Cheap type gate first: the PDC read below is only needed for the two tool materials,
        // and the ActionBar tick runs this check twice a second.
        if (type != ToolKind.CAPTURE.material() && type != ToolKind.PREVIEW.material()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        String raw = meta.getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        for (ToolKind kind : ToolKind.values()) {
            if (kind.name().equals(raw)) {
                return kind;
            }
        }
        return null;
    }

    private boolean containsTool(PlayerInventory inventory) {
        for (ItemStack item : inventory.getContents()) {
            if (toolKind(item) != null) {
                return true;
            }
        }
        return false;
    }

    private boolean hasTool(PlayerInventory inventory, ToolKind kind) {
        for (ItemStack item : inventory.getContents()) {
            if (toolKind(item) == kind) {
                return true;
            }
        }
        return false;
    }

    private void giveTool(Player player, ToolKind kind) {
        if (!player.hasPermission(PERMISSION)) {
            flash(player, red("没有 " + PERMISSION + " 权限."));
            return;
        }
        PlayerInventory inventory = player.getInventory();
        if (hasTool(inventory, kind)) {
            flash(player, gray("你已经拥有" + kind.displayName() + "."));
            return;
        }
        ItemStack tool = createTool(kind, null);
        if (inventory.getItemInMainHand().getType().isAir()) {
            inventory.setItemInMainHand(tool);
        } else {
            Map<Integer, ItemStack> leftover = inventory.addItem(tool);
            if (!leftover.isEmpty()) {
                flash(player, red("背包已满，无法获取" + kind.displayName() + "."));
                return;
            }
        }
        ensureActionBar(player);
        flash(player, gray("已获得" + kind.displayName() + " · Shift+右键选择目标/轨道"));
    }

    private void removeTools(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        int removed = 0;
        for (int slot = 0; slot < contents.length; slot++) {
            if (toolKind(contents[slot]) != null) {
                inventory.setItem(slot, null);
                removed++;
            }
        }
        if (removed == 0) {
            flash(player, gray("背包里没有编辑工具."));
            return;
        }
        cancelActionBar(player);
        flashes.remove(player.getUniqueId());
        player.sendActionBar(gray("已移除 " + removed + " 个编辑工具."));
    }

    // ------------------------------------------------------------------ tool use

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        EquipmentSlot hand = event.getHand();
        if (hand == null) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack used = hand == EquipmentSlot.HAND
                ? player.getInventory().getItemInMainHand()
                : player.getInventory().getItemInOffHand();
        if (toolKind(used) == null) {
            return;
        }
        // Always deny both the item use and the block interaction; the tool never falls through.
        event.setCancelled(true);
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
        // A right click is reported once per hand; keep only the first report of the pair.
        if (!claimUse(player)) {
            return;
        }
        boolean select = player.isSneaking();
        onPlayer(player, () -> handleToolUse(player, select));
    }

    private boolean claimUse(Player player) {
        long now = System.currentTimeMillis();
        Long previous = lastUseMillis.put(player.getUniqueId(), now);
        return previous == null || now - previous >= USE_DEBOUNCE_MILLIS;
    }

    private void handleToolUse(Player player, boolean select) {
        if (!player.isOnline()) {
            return;
        }
        if (!player.hasPermission(PERMISSION)) {
            flash(player, red("没有 " + PERMISSION + " 权限，无法使用编辑工具."));
            return;
        }
        PlayerInventory inventory = player.getInventory();
        ToolKind kind = toolKind(inventory.getItemInMainHand());
        if (kind == null) {
            kind = toolKind(inventory.getItemInOffHand());
        }
        if (kind == null) {
            return;
        }
        // Also covers players who already had the item before a plugin reload: the session task
        // is memory-only, so first use re-attaches it.
        ensureActionBar(player);
        if (select) {
            selector.open(player, kind);
            return;
        }
        if (kind == ToolKind.CAPTURE) {
            capture(player);
        } else {
            preview(player);
        }
    }

    private void capture(Player player) {
        PlayerState state = states.get(player.getUniqueId());
        CaptureSelection selection = state == null ? null : state.capture;
        if (selection == null) {
            flash(player, red("请先 Shift+右键选择取景目标."));
            return;
        }
        String problem = captureProblem(player, selection);
        if (problem != null) {
            flash(player, invalid(problem));
            return;
        }
        EditService.Result result;
        try {
            result = switch (selection.target()) {
                case START -> edits.captureStart(player, selection.trackId());
                case EXIT -> edits.captureExit(player, selection.trackId());
                case PATH -> edits.appendPathPoint(player,
                        EditTarget.step(selection.trackId(), selection.stepIndex()));
            };
        } catch (RuntimeException exception) {
            flash(player, red("取景失败：" + message(exception)));
            return;
        }
        if (result.failed()) {
            flash(player, red(result.error()));
            return;
        }
        if (!result.changed()) {
            flash(player, gray("未变化 · 当前视角与目标相同"));
            return;
        }
        switch (selection.target()) {
            case START -> flash(player, Component.text("已写入轨道起点", NamedTextColor.WHITE)
                    .append(gray(" · ")).append(white(selection.trackId())));
            case EXIT -> flash(player, Component.text("已写入退出点", NamedTextColor.WHITE)
                    .append(gray(" · ")).append(white(selection.trackId()))
                    .append(gray(" · 脚部坐标")));
            case PATH -> {
                int points = pathPointCount(result.track(), selection.stepIndex());
                Component text = Component.text("已追加路径点", NamedTextColor.WHITE)
                        .append(gray(" · "))
                        .append(white(selection.trackId() + ":" + (selection.stepIndex() + 1)))
                        .append(gray(" · 共 "))
                        .append(aqua(String.valueOf(points)))
                        .append(gray(" 点"));
                flash(player, text);
            }
        }
    }

    private void preview(Player player) {
        if (edits.isPreviewing(player)) {
            EditService.Result result;
            try {
                result = edits.stopPreview(player);
            } catch (RuntimeException exception) {
                flash(player, red("停止预览失败：" + message(exception)));
                return;
            }
            if (result.failed()) {
                flash(player, red(result.error()));
                return;
            }
            flash(player, gray("已停止预览"));
            return;
        }
        PlayerState state = states.get(player.getUniqueId());
        PreviewSelection selection = state == null ? null : state.preview;
        if (selection == null) {
            flash(player, red("请先 Shift+右键选择预览目标."));
            return;
        }
        String problem = previewProblem(player, selection);
        if (problem != null) {
            flash(player, invalid(problem));
            return;
        }
        EditTarget target = selection.stepIndex() >= 0
                ? EditTarget.step(selection.trackId(), selection.stepIndex())
                : EditTarget.track(selection.trackId());
        EditService.Result result;
        try {
            result = edits.preview(player, target);
        } catch (RuntimeException exception) {
            flash(player, red("预览失败：" + message(exception)));
            return;
        }
        if (result.failed()) {
            flash(player, red(result.error()));
            return;
        }
        flash(player, Component.text("预览已开始", NamedTextColor.WHITE)
                .append(gray(" · ")).append(white(previewLabel(player, selection)))
                .append(gray(" · 潜行退出")));
    }

    // ------------------------------------------------------------------ selectors

    @Override
    public void captureTargetChosen(Player player, String trackId, CaptureTarget target, int stepIndex) {
        CaptureSelection selection = new CaptureSelection(target, trackId, stepIndex);
        state(player).capture = selection;
        player.closeDialog();
        ensureActionBar(player);
        flash(player, Component.text("取景目标已设为", NamedTextColor.WHITE)
                .append(gray(" · ")).append(white(captureDisplay(selection)))
                .append(gray(" · " + captureHint(selection))));
        player.sendMessage(gray("取景目标已设为 " + captureDisplay(selection)
                + " · " + captureHint(selection)));
    }

    @Override
    public void previewTargetChosen(Player player, String trackId, int stepIndex) {
        PreviewSelection selection = new PreviewSelection(trackId, stepIndex);
        state(player).preview = selection;
        player.closeDialog();
        ensureActionBar(player);
        String label = previewLabel(player, selection);
        flash(player, Component.text("预览目标已设为", NamedTextColor.WHITE)
                .append(gray(" · ")).append(white(label))
                .append(gray(" · 右键开始预览")));
        player.sendMessage(gray("预览目标已设为 " + label + " · 右键开始预览"));
    }

    @Override
    public void toolChoiceCleared(Player player, ToolKind tool) {
        PlayerState state = states.get(player.getUniqueId());
        if (state != null) {
            if (tool == ToolKind.CAPTURE) {
                state.capture = null;
            } else {
                state.preview = null;
            }
        }
        player.closeDialog();
        ensureActionBar(player);
        String label = tool == ToolKind.CAPTURE ? "取景目标" : "预览目标";
        flash(player, gray("已清除" + label + "."));
        player.sendMessage(gray("已清除" + label + "."));
    }

    // ------------------------------------------------------------------ action bar

    private void ensureActionBar(Player player) {
        UUID id = player.getUniqueId();
        if (actionBarTasks.containsKey(id)) {
            return;
        }
        ScheduledTask task = player.getScheduler().runAtFixedRate(plugin,
                scheduled -> tickActionBar(player, id, scheduled),
                () -> actionBarTasks.remove(id),
                ACTION_BAR_INITIAL_DELAY_TICKS,
                ACTION_BAR_PERIOD_TICKS);
        if (task != null) {
            actionBarTasks.put(id, task);
        }
    }

    private void cancelActionBar(Player player) {
        ScheduledTask task = actionBarTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
    }

    private void tickActionBar(Player player, UUID id, ScheduledTask task) {
        if (!player.isOnline()) {
            task.cancel();
            actionBarTasks.remove(id);
            return;
        }
        try {
            // The task lives for the whole session: the tool may sit in the backpack or a chest
            // for a while and still has to light up the ActionBar when it comes back to a hand.
            PlayerInventory inventory = player.getInventory();
            if (toolKind(inventory.getItemInMainHand()) == null
                    && toolKind(inventory.getItemInOffHand()) == null
                    && !flashes.containsKey(id)) {
                return;
            }
            refresh(player);
        } catch (RuntimeException exception) {
            // A single bad tick must not kill the repeating task.
            plugin.getLogger().log(Level.FINE,
                    "Editing ActionBar tick failed for " + player.getName(), exception);
        }
    }

    private void refresh(Player player) {
        if (!player.isOnline()) {
            return;
        }
        try {
            UUID id = player.getUniqueId();
            Flash flash = flashes.get(id);
            if (flash != null) {
                if (System.currentTimeMillis() < flash.expiresAtMillis()) {
                    player.sendActionBar(flash.text());
                    return;
                }
                flashes.remove(id, flash);
            }
            Component status = statusText(player);
            if (status != null) {
                player.sendActionBar(status);
            }
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.FINE, "Could not refresh the editing ActionBar for "
                    + player.getName(), exception);
        }
    }

    private Component statusText(Player player) {
        ToolKind kind = toolKind(player.getInventory().getItemInMainHand());
        if (kind == null) {
            kind = toolKind(player.getInventory().getItemInOffHand());
        }
        if (kind == null) {
            return null;
        }
        PlayerState state = states.get(player.getUniqueId());
        if (kind == ToolKind.CAPTURE) {
            return captureStatus(player, state == null ? null : state.capture);
        }
        return previewStatus(player, state == null ? null : state.preview);
    }

    private Component captureStatus(Player player, CaptureSelection selection) {
        if (selection == null) {
            return Component.text("取景工具", NamedTextColor.AQUA)
                    .append(gray(" · Shift+右键选择目标/轨道"));
        }
        String problem = captureProblem(player, selection);
        if (problem != null) {
            return red(problem + " · Shift+右键重新选择");
        }
        return switch (selection.target()) {
            case START -> Component.text("取景", NamedTextColor.AQUA)
                    .append(gray(" · ")).append(white(selection.trackId()))
                    .append(gray(" · ")).append(aqua("起点"))
                    .append(gray(" · 右键写入轨道起点"));
            case EXIT -> Component.text("取景", NamedTextColor.AQUA)
                    .append(gray(" · ")).append(white(selection.trackId()))
                    .append(gray(" · ")).append(aqua("退出点"))
                    .append(gray(" · 右键写入脚部坐标"));
            case PATH -> {
                int points = pathPointCount(edits.view(selection.trackId()),
                        selection.stepIndex());
                yield Component.text("取景", NamedTextColor.AQUA)
                        .append(gray(" · "))
                        .append(white(selection.trackId() + ":" + (selection.stepIndex() + 1)))
                        .append(gray(" · ")).append(aqua("路径"))
                        .append(gray(" · ")).append(white(points + " 点"))
                        .append(gray(" · 右键追加路径点"));
            }
        };
    }

    private Component previewStatus(Player player, PreviewSelection selection) {
        if (edits.isPreviewing(player)) {
            Component text = Component.text("预览中", NamedTextColor.AQUA);
            if (selection != null) {
                text = text.append(gray(" · ")).append(white(previewLabel(player, selection)));
            }
            // The engine makes every preview escapable with Shift; while the session runs the
            // player is a spectator, so Shift is also the one input that always reaches us.
            return text.append(gray(" · 潜行退出预览"));
        }
        if (selection == null) {
            return Component.text("预览工具", NamedTextColor.AQUA)
                    .append(gray(" · Shift+右键选择目标/轨道"));
        }
        String problem = previewProblem(player, selection);
        if (problem != null) {
            return red(problem + " · Shift+右键重新选择");
        }
        return Component.text("预览目标", NamedTextColor.AQUA)
                .append(gray(" · ")).append(white(previewLabel(player, selection)))
                .append(gray(" · 右键开始预览"));
    }

    private void flash(Player player, Component text) {
        if (!player.isOnline()) {
            return;
        }
        flashes.put(player.getUniqueId(), new Flash(text, System.currentTimeMillis() + FLASH_MILLIS));
        player.sendActionBar(text);
    }

    // ------------------------------------------------------------------ validation and labels

    /** Why the capture selection cannot be used right now, or {@code null} when it is usable. */
    private String captureProblem(Player player, CaptureSelection selection) {
        CameraTrack track = edits.view(selection.trackId());
        if (track == null) {
            return "取景目标已失效：轨道 " + selection.trackId() + " 已不存在";
        }
        if (selection.target() != CaptureTarget.PATH) {
            return null;
        }
        int index = selection.stepIndex();
        if (index < 0) {
            return "取景目标已失效：路径步骤无效";
        }
        if (index >= track.steps().size()) {
            return "取景目标已失效：步骤 " + (index + 1) + " 已不存在";
        }
        if (!(track.steps().get(index) instanceof PathStep)) {
            return "取景目标已失效：步骤 " + (index + 1) + " 已不是路径步骤";
        }
        return null;
    }

    /** Why the preview selection cannot be used right now, or {@code null} when it is usable. */
    private String previewProblem(Player player, PreviewSelection selection) {
        CameraTrack track = edits.view(selection.trackId());
        if (track == null) {
            return "预览目标已失效：轨道 " + selection.trackId() + " 已不存在";
        }
        if (selection.stepIndex() >= track.steps().size()) {
            return "预览目标已失效：步骤 " + (selection.stepIndex() + 1) + " 已不存在";
        }
        return null;
    }

    private String previewLabel(Player player, PreviewSelection selection) {
        if (selection.stepIndex() < 0) {
            return selection.trackId() + " · 从轨道开头";
        }
        CameraTrack track = edits.view(selection.trackId());
        if (track == null || selection.stepIndex() >= track.steps().size()) {
            return selection.trackId() + ":" + (selection.stepIndex() + 1);
        }
        return selection.trackId() + ":" + (selection.stepIndex() + 1) + " · "
                + shorten(track.steps().get(selection.stepIndex()).describe(), MAX_ACTION_BAR_LABEL);
    }

    private static String captureDisplay(CaptureSelection selection) {
        return switch (selection.target()) {
            case START -> selection.trackId() + " · 起点";
            case EXIT -> selection.trackId() + " · 退出点";
            case PATH -> selection.trackId() + ":" + (selection.stepIndex() + 1);
        };
    }

    private static String captureHint(CaptureSelection selection) {
        return switch (selection.target()) {
            case START -> "右键写入轨道起点";
            case EXIT -> "右键写入退出点";
            case PATH -> "右键追加路径点";
        };
    }

    private static int pathPointCount(CameraTrack track, int stepIndex) {
        if (track == null || stepIndex < 0 || stepIndex >= track.steps().size()) {
            return 0;
        }
        return track.steps().get(stepIndex) instanceof PathStep path ? path.points().size() : 0;
    }

    private PlayerState state(Player player) {
        return states.computeIfAbsent(player.getUniqueId(), ignored -> new PlayerState());
    }

    // ------------------------------------------------------------------ player events

    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        if (toolKind(player.getInventory().getItem(event.getNewSlot())) == null) {
            return;
        }
        onPlayer(player, () -> {
            ensureActionBar(player);
            refresh(player);
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        onPlayer(player, () -> {
            if (!player.isOnline() || !containsTool(player.getInventory())) {
                return;
            }
            ensureActionBar(player);
            refresh(player);
        }, 1L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        ScheduledTask task = actionBarTasks.remove(id);
        if (task != null) {
            task.cancel();
        }
        flashes.remove(id);
        lastUseMillis.remove(id);
        states.remove(id);
    }

    // ------------------------------------------------------------------ scheduling and text

    private void onPlayer(Player player, Runnable action) {
        onPlayer(player, action, 0L);
    }

    private void onPlayer(Player player, Runnable action, long delayTicks) {
        player.getScheduler().execute(plugin, action, () -> {
        }, delayTicks);
    }

    private static String message(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String text = current.getMessage();
        return text == null || text.isBlank() ? current.getClass().getSimpleName() : text;
    }

    private static String shorten(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static Component invalid(String problem) {
        return red(problem + " · Shift+右键重新选择");
    }

    private static Component white(String text) {
        return Component.text(text, NamedTextColor.WHITE);
    }

    private static Component gray(String text) {
        return Component.text(text, NamedTextColor.GRAY);
    }

    private static Component aqua(String text) {
        return Component.text(text, NamedTextColor.AQUA);
    }

    private static Component red(String text) {
        return Component.text(text, NamedTextColor.RED);
    }
}
