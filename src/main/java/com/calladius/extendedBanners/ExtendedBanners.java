package com.calladius.extendedBanners;

import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.DyeColor;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BannerMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import net.kyori.adventure.text.event.ClickEvent;

import java.util.*;

/**
 * ExtendedBanners - Paper плагин для создания баннеров без ограничения в 6 паттернов
 */
public class ExtendedBanners extends JavaPlugin implements Listener, CommandExecutor {

    private static final Component MAIN_TITLE = Component.text("Дизайнер Баннеров ", NamedTextColor.GOLD)
            .append(Component.text("(Безлимитный)", NamedTextColor.GRAY));
    private static final Component PATTERN_TITLE = Component.text("Выберите паттерн", NamedTextColor.YELLOW);
    private static final Component COLOR_TITLE = Component.text("Выберите цвет краски", NamedTextColor.AQUA);
    private static final Component BASE_SELECT_TITLE = Component.text("Выберите базовый баннер", NamedTextColor.YELLOW);

    private NamespacedKey PDC_PATTERN_KEY;

    private final Map<UUID, List<PatternData>> pendingPatterns = new HashMap<>();
    private final Map<UUID, Inventory> openMainInventories = new HashMap<>();
    private final Map<UUID, NamespacedKey> currentPatternSelection = new HashMap<>();
    private final Map<UUID, Material> pendingBase = new HashMap<>();
    private Registry<PatternType> patternRegistry;

    @Override
    public void onEnable() {
        this.patternRegistry = RegistryAccess.registryAccess().getRegistry(RegistryKey.BANNER_PATTERN);
        this.PDC_PATTERN_KEY = new NamespacedKey(this, "extbanner_pattern");

        Bukkit.getPluginManager().registerEvents(this, this);
        PluginCommand cmd = this.getCommand("extbanner");
        if (cmd != null) cmd.setExecutor(this);

        getLogger().info("ExtendedBanners enabled!");
        getLogger().info("Note: Players need 'Infinite Banner Patterns' mod to see >6 patterns");
        getLogger().info("Download: https://modrinth.com/mod/infinite-banner-patterns");
    }

    @Override
    public void onDisable() {
        getLogger().info("ExtendedBanners disabled");
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(Component.text("Только игроки могут использовать эту команду.", NamedTextColor.RED));
            return true;
        }

        openMainGUI(p);
        return true;
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player p)) return;

        if (event.getInventory().getType() == InventoryType.LOOM) {
            event.setCancelled(true);
            Bukkit.getScheduler().runTask(this, () -> openMainGUI(p));
        }
    }

    private void openMainGUI(Player p) {
        Inventory inv = Bukkit.createInventory(null, 27, MAIN_TITLE);

        Material baseMat = pendingBase.getOrDefault(p.getUniqueId(), Material.BLACK_BANNER);
        List<PatternData> currentPatterns = pendingPatterns.getOrDefault(p.getUniqueId(), new ArrayList<>());

        // Слот 11: Базовый баннер
        inv.setItem(11, makeItem(baseMat,
                Component.text("Базовый баннер", NamedTextColor.AQUA),
                List.of(
                        Component.text("Текущий: ", NamedTextColor.GRAY)
                                .append(Component.text(formatName(baseMat.name()), NamedTextColor.WHITE)),
                        Component.empty(),
                        Component.text("Клик — выбрать базовый цвет", NamedTextColor.YELLOW),
                        Component.text("Нужен баннер выбранного цвета в инвентаре", NamedTextColor.GRAY)
                )));

        // Слот 13: Предпросмотр
        updatePreviewItem(inv, currentPatterns, baseMat);

        // Слот 15: Добавить паттерн
        inv.setItem(15, makeItem(Material.PAPER,
                Component.text("+ Добавить паттерн", NamedTextColor.GREEN),
                List.of(
                        Component.text("Открыть выбор паттерна", NamedTextColor.GRAY),
                        Component.text("После выбора паттерна — выберите цвет краски", NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("Текущих паттернов: ", NamedTextColor.YELLOW)
                                .append(Component.text(currentPatterns.size(), NamedTextColor.WHITE))
                )));

        // Слот 20: Удалить последний
        if (!currentPatterns.isEmpty()) {
            inv.setItem(20, makeItem(Material.ORANGE_CONCRETE,
                    Component.text("↶ Удалить последний", NamedTextColor.GOLD),
                    List.of(Component.text("Убрать последний добавленный паттерн", NamedTextColor.GRAY))));
        }

        // Слот 21: Очистить все
        if (!currentPatterns.isEmpty()) {
            inv.setItem(21, makeItem(Material.BARRIER,
                    Component.text("✖ Очистить все", NamedTextColor.RED),
                    List.of(Component.text("Удалить все паттерны", NamedTextColor.GRAY))));
        }

        // Слот 22: Создать баннер
        inv.setItem(22, makeItem(Material.GREEN_CONCRETE,
                Component.text("✓ Создать баннер", NamedTextColor.DARK_GREEN),
                List.of(
                        Component.text("Списать необходимые предметы", NamedTextColor.GRAY),
                        Component.text("и выдать готовый баннер", NamedTextColor.GRAY)
                )));

        // Слот 24: Информация
        inv.setItem(24, makeItem(Material.BOOK,
                Component.text("ℹ Информация", NamedTextColor.BLUE),
                List.of(
                        Component.text("Extended Banners v1.0", NamedTextColor.GRAY),
                        Component.empty(),
                        Component.text("Как пользоваться:", NamedTextColor.YELLOW),
                        Component.text("• /extbanner — открыть дизайнер", NamedTextColor.GRAY),
                        Component.text("• Клик по «Базовый баннер» — выбрать цвет (баннер нужен в инвентаре)", NamedTextColor.GRAY),
                        Component.text("• Добавляйте паттерны → затем выбирайте цвет краски", NamedTextColor.GRAY),
                        Component.text("• Некоторые паттерны требуют специального", NamedTextColor.GRAY),
                        Component.text("  предмета узора флага в инвентаре (напр. CREEPER)", NamedTextColor.GRAY),
                        Component.text("• Для создания баннера расходуются базовый баннер и красители", NamedTextColor.GRAY),
                        Component.empty(),
                        // Кликабельный кредит/ссылка
                        Component.text("Плагин создан ", NamedTextColor.GRAY)
                                .append(Component.text("Calladius", NamedTextColor.GOLD)
                                        .decorate(TextDecoration.BOLD)
                                        .clickEvent(ClickEvent.openUrl("https://github.com/Calladius")))
                )));

        pendingPatterns.putIfAbsent(p.getUniqueId(), new ArrayList<>());
        openMainInventories.put(p.getUniqueId(), inv);
        p.openInventory(inv);
    }

    private ItemStack makeItem(Material mat, Component name, List<Component> lore) {
        ItemStack i = new ItemStack(mat);
        ItemMeta m = i.getItemMeta();
        if (m != null) {
            m.displayName(name);
            m.lore(lore);
            i.setItemMeta(m);
        }
        return i;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        Component title = e.getView().title();

        // === ГЛАВНЫЙ GUI ===
        if (title.equals(MAIN_TITLE)) {
            e.setCancelled(true);
            int slot = e.getRawSlot();

            if (slot == 11) {
                openBaseSelection(p);
            } else if (slot == 15) {
                openPatternSelection(p);
            } else if (slot == 20) {
                List<PatternData> list = pendingPatterns.get(p.getUniqueId());
                if (list != null && !list.isEmpty()) {
                    PatternData removed = list.remove(list.size() - 1);
                    p.sendMessage(Component.text("✓ Удален паттерн: ", NamedTextColor.YELLOW)
                            .append(Component.text(removed.patternKey().toString() + " (" + removed.color().name() + ")", NamedTextColor.WHITE)));
                    p.closeInventory();
                    Bukkit.getScheduler().runTask(this, () -> openMainGUI(p));
                }
            } else if (slot == 21) {
                List<PatternData> list = pendingPatterns.get(p.getUniqueId());
                if (list != null && !list.isEmpty()) {
                    int count = list.size();
                    list.clear();
                    p.sendMessage(Component.text("✓ Очищено ", NamedTextColor.GREEN)
                            .append(Component.text(count + " ", NamedTextColor.WHITE))
                            .append(Component.text("паттернов!", NamedTextColor.GREEN)));
                    p.closeInventory();
                    Bukkit.getScheduler().runTask(this, () -> openMainGUI(p));
                }
            } else if (slot == 22) {
                createFinalBanner(p);
            }
            return;
        }

        // === ВЫБОР БАЗОВОГО БАННЕРА ===
        if (title.equals(BASE_SELECT_TITLE)) {
            e.setCancelled(true);
            ItemStack clicked = e.getCurrentItem();
            if (clicked == null || clicked.getType() == Material.AIR) return;

            Material chosen = clicked.getType();
            if (!chosen.name().endsWith("_BANNER")) return;

            if (!hasItemInInventory(p, chosen, 1) && p.getGameMode() != GameMode.CREATIVE) {
                p.sendMessage(Component.text("✗ У вас нет баннера ", NamedTextColor.RED)
                        .append(Component.text(formatName(chosen.name()), NamedTextColor.WHITE))
                        .append(Component.text(" в инвентаре!", NamedTextColor.RED)));
                return;
            }

            pendingBase.put(p.getUniqueId(), chosen);
            p.sendMessage(Component.text("✓ Выбран базовый баннер: ", NamedTextColor.GREEN)
                    .append(Component.text(formatName(chosen.name()), NamedTextColor.WHITE)));

            p.closeInventory();
            Bukkit.getScheduler().runTask(this, () -> openMainGUI(p));
            return;
        }

        // === ВЫБОР ПАТТЕРНА ===
        if (title.equals(PATTERN_TITLE)) {
            e.setCancelled(true);
            if (e.getRawSlot() < 0) return;

            ItemStack clicked = e.getCurrentItem();
            if (clicked == null || clicked.getType() == Material.AIR) return;

            ItemMeta meta = clicked.getItemMeta();
            if (meta == null) return;

            String keyStr = meta.getPersistentDataContainer().get(PDC_PATTERN_KEY, PersistentDataType.STRING);
            if (keyStr == null) return;

            NamespacedKey patternKey = NamespacedKey.fromString(keyStr);
            if (patternKey == null) {
                p.sendMessage(Component.text("✗ Ошибка: неверный ID паттерна", NamedTextColor.RED));
                return;
            }

            if (patternRegistry == null || patternRegistry.get(patternKey) == null) {
                p.sendMessage(Component.text("✗ Ошибка: паттерн не найден в реестре: " + keyStr, NamedTextColor.RED));
                return;
            }

            currentPatternSelection.put(p.getUniqueId(), patternKey);
            openColorSelection(p, patternKey);
            return;
        }

        // === ВЫБОР ЦВЕТА КРАСКИ ===
        if (title.equals(COLOR_TITLE)) {
            e.setCancelled(true);
            ItemStack clicked = e.getCurrentItem();
            if (clicked == null || clicked.getType() == Material.AIR) return;

            Material mat = clicked.getType();
            DyeColor dye = materialToDye(mat);
            if (dye == null) return;

            NamespacedKey patternKey = currentPatternSelection.get(p.getUniqueId());
            if (patternKey == null) {
                p.sendMessage(Component.text("✗ Ошибка: паттерн не найден (повторите выбор)", NamedTextColor.RED));
                p.closeInventory();
                return;
            }

            Material requiredPatternItem = getBannerPatternMaterialFor(patternKey);
            if (requiredPatternItem != null && p.getGameMode() != GameMode.CREATIVE && !hasItemInInventory(p, requiredPatternItem, 1)) {
                p.sendMessage(Component.text("✗ Для этого паттерна требуется предмет: ", NamedTextColor.RED)
                        .append(Component.text(formatName(requiredPatternItem.name()), NamedTextColor.WHITE)));
                p.closeInventory();
                Bukkit.getScheduler().runTask(this, () -> openPatternSelection(p)); // вернём игрока назад
                return;
            }

            if (!hasItemInInventory(p, mat, 1) && p.getGameMode() != GameMode.CREATIVE) {
                p.sendMessage(Component.text("✗ Нет краски ", NamedTextColor.RED)
                        .append(Component.text(formatName(dye.name()), NamedTextColor.WHITE))
                        .append(Component.text(" в инвентаре!", NamedTextColor.RED)));
                p.closeInventory();
                Bukkit.getScheduler().runTask(this, () -> openPatternSelection(p));
                return;
            }

            List<PatternData> list = pendingPatterns.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>());
            list.add(new PatternData(patternKey, dye));
            currentPatternSelection.remove(p.getUniqueId());

            p.sendMessage(Component.text("✓ Паттерн #", NamedTextColor.GREEN)
                    .append(Component.text(list.size() + " ", NamedTextColor.WHITE))
                    .append(Component.text("добавлен: ", NamedTextColor.GREEN))
                    .append(Component.text(formatName(dye.name()), NamedTextColor.GRAY)));

            p.closeInventory();
            Bukkit.getScheduler().runTask(this, () -> openMainGUI(p));
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        if (!(e.getPlayer() instanceof Player p)) return;
        if (MAIN_TITLE.equals(e.getView().title())) {
            openMainInventories.remove(p.getUniqueId());
        }
    }

    private void openBaseSelection(Player p) {
        Inventory inv = Bukkit.createInventory(null, 18, BASE_SELECT_TITLE);

        Material[] banners = {
                Material.WHITE_BANNER, Material.ORANGE_BANNER, Material.MAGENTA_BANNER,
                Material.LIGHT_BLUE_BANNER, Material.YELLOW_BANNER, Material.LIME_BANNER,
                Material.PINK_BANNER, Material.GRAY_BANNER, Material.LIGHT_GRAY_BANNER,
                Material.CYAN_BANNER, Material.PURPLE_BANNER, Material.BLUE_BANNER,
                Material.BROWN_BANNER, Material.GREEN_BANNER, Material.RED_BANNER,
                Material.BLACK_BANNER
        };

        for (int i = 0; i < banners.length; i++) {
            Material m = banners[i];
            inv.setItem(i, makeItem(m,
                    Component.text(formatName(m.name()), NamedTextColor.AQUA),
                    List.of(
                            Component.text("Клик — выбрать как базовый баннер", NamedTextColor.YELLOW),
                            Component.text("Требуется: ", NamedTextColor.GRAY)
                                    .append(Component.text("1x " + formatName(m.name()), NamedTextColor.WHITE))
                    )));
        }

        p.openInventory(inv);
    }

    private void openPatternSelection(Player p) {
        List<PatternType> types = patternRegistry.stream().toList();
        int size = Math.min(54, ((types.size() + 8) / 9) * 9);
        if (size == 0) size = 9;

        Inventory inv = Bukkit.createInventory(null, size, PATTERN_TITLE);

        Material baseMat = pendingBase.getOrDefault(p.getUniqueId(), Material.BLACK_BANNER);
        List<PatternData> current = pendingPatterns.getOrDefault(p.getUniqueId(), new ArrayList<>());

        for (int i = 0; i < Math.min(types.size(), size); i++) {
            PatternType pt = types.get(i);
            NamespacedKey key = patternRegistry.getKey(pt);
            if (key == null) continue;

            ItemStack preview = new ItemStack(baseMat);
            BannerMeta bm = (BannerMeta) preview.getItemMeta();
            if (bm != null) {
                List<Pattern> patterns = new ArrayList<>();

                for (PatternData pd : current) {
                    PatternType realPt = patternRegistry.get(pd.patternKey());
                    if (realPt != null) {
                        patterns.add(new Pattern(pd.color(), realPt));
                    }
                }

                patterns.add(new Pattern(DyeColor.WHITE, pt));

                bm.setPatterns(patterns);
                bm.displayName(Component.text(formatName(key.value()), NamedTextColor.YELLOW));
                bm.lore(List.of(
                        Component.text("ID: ", NamedTextColor.GRAY)
                                .append(Component.text(key.toString(), NamedTextColor.DARK_GRAY)),
                        Component.empty(),
                        Component.text("Клик — выбрать этот паттерн", NamedTextColor.GREEN)
                ));

                bm.getPersistentDataContainer().set(PDC_PATTERN_KEY, PersistentDataType.STRING, key.toString());
                preview.setItemMeta(bm);
            }

            inv.setItem(i, preview);
        }

        p.openInventory(inv);
    }

    private void openColorSelection(Player p, NamespacedKey patternKey) {
        Inventory inv = Bukkit.createInventory(null, 18, COLOR_TITLE);

        Material[] dyes = {
                Material.WHITE_DYE, Material.ORANGE_DYE, Material.MAGENTA_DYE,
                Material.LIGHT_BLUE_DYE, Material.YELLOW_DYE, Material.LIME_DYE,
                Material.PINK_DYE, Material.GRAY_DYE, Material.LIGHT_GRAY_DYE,
                Material.CYAN_DYE, Material.PURPLE_DYE, Material.BLUE_DYE,
                Material.BROWN_DYE, Material.GREEN_DYE, Material.RED_DYE,
                Material.BLACK_DYE
        };

        for (int i = 0; i < dyes.length; i++) {
            Material m = dyes[i];
            DyeColor dye = materialToDye(m);
            String colorName = dye != null ? formatName(dye.name()) : formatName(m.name());

            inv.setItem(i, makeItem(m,
                    Component.text(colorName, NamedTextColor.AQUA),
                    List.of(
                            Component.text("Клик — выбрать цвет паттерна", NamedTextColor.YELLOW),
                            Component.text("Требуется: ", NamedTextColor.GRAY)
                                    .append(Component.text("1x " + colorName, NamedTextColor.WHITE))
                    )));
        }
        Objects.requireNonNull(patternKey);
        p.openInventory(inv);
    }

    private boolean hasItemInInventory(Player p, Material mat, int count) {
        if (p.getGameMode() == GameMode.CREATIVE) return true;

        int found = 0;
        for (ItemStack is : p.getInventory().getContents()) {
            if (is != null && is.getType() == mat) {
                found += is.getAmount();
                if (found >= count) return true;
            }
        }
        return false;
    }

    private boolean removeItemsFromInventory(Player p, Material mat, int count) {
        if (p.getGameMode() == GameMode.CREATIVE) return true;

        int need = count;
        ItemStack[] contents = p.getInventory().getContents();

        for (int i = 0; i < contents.length && need > 0; i++) {
            ItemStack is = contents[i];
            if (is != null && is.getType() == mat) {
                if (is.getAmount() > need) {
                    is.setAmount(is.getAmount() - need);
                    need = 0;
                } else {
                    need -= is.getAmount();
                    p.getInventory().setItem(i, null);
                }
            }
        }

        return need == 0;
    }

    private void createFinalBanner(Player p) {
        UUID id = p.getUniqueId();
        Material baseMat = pendingBase.getOrDefault(id, Material.BLACK_BANNER);
        List<PatternData> patterns = pendingPatterns.getOrDefault(id, new ArrayList<>());

        Map<Material, Integer> needed = new HashMap<>();
        needed.put(baseMat, 1);

        for (PatternData pd : patterns) {
            Material dyeMat = dyeToMaterial(pd.color());
            needed.put(dyeMat, needed.getOrDefault(dyeMat, 0) + 1);
        }

        List<Component> missing = new ArrayList<>();
        for (Map.Entry<Material, Integer> e : needed.entrySet()) {
            if (!hasItemInInventory(p, e.getKey(), e.getValue())) {
                missing.add(Component.text("• ", NamedTextColor.RED)
                        .append(Component.text(e.getValue() + "x ", NamedTextColor.WHITE))
                        .append(Component.text(formatName(e.getKey().name()), NamedTextColor.YELLOW)));
            }
        }

        if (!missing.isEmpty() && p.getGameMode() != GameMode.CREATIVE) {
            p.sendMessage(Component.text("✗ Не хватает предметов:", NamedTextColor.RED));
            for (Component c : missing) {
                p.sendMessage(c);
            }
            return;
        }

        for (Map.Entry<Material, Integer> e : needed.entrySet()) {
            if (!removeItemsFromInventory(p, e.getKey(), e.getValue())) {
                p.sendMessage(Component.text("✗ Ошибка при снятии: " + formatName(e.getKey().name()), NamedTextColor.RED));
                return;
            }
        }

        ItemStack result = new ItemStack(baseMat);
        BannerMeta bm = (BannerMeta) result.getItemMeta();

        if (bm != null) {
            List<Pattern> bmPatterns = new ArrayList<>();
            for (PatternData pd : patterns) {
                PatternType pt = patternRegistry.get(pd.patternKey());
                if (pt != null) {
                    bmPatterns.add(new Pattern(pd.color(), pt));
                }
            }

            bm.setPatterns(bmPatterns);
            bm.displayName(Component.text("Кастомный баннер", NamedTextColor.GOLD));

            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Создан в Extended Banners", NamedTextColor.GRAY));
            lore.add(Component.text("Паттернов: ", NamedTextColor.GRAY)
                    .append(Component.text(bmPatterns.size(), NamedTextColor.YELLOW)));

            if (bmPatterns.size() > 6) {
                lore.add(Component.empty());
                lore.add(Component.text("⚠ Для полного отображения", NamedTextColor.YELLOW));
                lore.add(Component.text("нужен клиентский мод", NamedTextColor.YELLOW));
            }

            bm.lore(lore);
            result.setItemMeta(bm);
        }

        pendingPatterns.put(id, new ArrayList<>());
        pendingBase.remove(id);

        p.getInventory().addItem(result);
        p.sendMessage(Component.text("✓ Баннер успешно создан!", NamedTextColor.GREEN));
        p.closeInventory();
    }

    private Material dyeToMaterial(DyeColor color) {
        try {
            return Material.valueOf(color.name() + "_DYE");
        } catch (IllegalArgumentException ex) {
            return Material.WHITE_DYE;
        }
    }

    private DyeColor materialToDye(Material mat) {
        if (mat == null) return null;
        String name = mat.name();
        if (!name.endsWith("_DYE")) return null;

        String base = name.substring(0, name.length() - 4);
        try {
            return DyeColor.valueOf(base);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void updatePreviewItem(Inventory main, List<PatternData> list, Material baseMat) {
        ItemStack preview = new ItemStack(baseMat);
        BannerMeta meta = (BannerMeta) preview.getItemMeta();

        if (meta != null) {
            List<Pattern> patterns = new ArrayList<>();
            for (PatternData pd : list) {
                PatternType pt = patternRegistry.get(pd.patternKey());
                if (pt != null) {
                    patterns.add(new Pattern(pd.color(), pt));
                }
            }

            meta.setPatterns(patterns);
            meta.displayName(Component.text("Предпросмотр", NamedTextColor.WHITE)
                    .decorate(TextDecoration.BOLD));

            List<Component> lore = new ArrayList<>();
            lore.add(Component.text("Паттернов: ", NamedTextColor.GRAY)
                    .append(Component.text(list.size(), NamedTextColor.YELLOW)));
            lore.add(Component.empty());

            if (list.isEmpty()) {
                lore.add(Component.text("Добавьте паттерны →", NamedTextColor.GRAY)
                        .decorate(TextDecoration.ITALIC));
            } else if (list.size() > 6) {
                lore.add(Component.text("⚠ Требуется мод для", NamedTextColor.YELLOW));
                lore.add(Component.text("полного отображения", NamedTextColor.YELLOW));
            } else {
                lore.add(Component.text("✓ Виден всем игрокам", NamedTextColor.GREEN));
            }

            meta.lore(lore);
            preview.setItemMeta(meta);
        }

        main.setItem(13, preview);
    }

    private Material getBannerPatternMaterialFor(NamespacedKey patternKey) {
        if (patternKey == null) return null;
        String key = patternKey.getKey();
        if (key.isEmpty()) return null;

        String candidate = key.toUpperCase(Locale.ROOT) + "_BANNER_PATTERN";
        try {
            return Material.valueOf(candidate);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String formatName(String s) {
        return Arrays.stream(s.toLowerCase().split("_"))
                .map(word -> word.substring(0, 1).toUpperCase() + word.substring(1))
                .reduce((a, b) -> a + " " + b)
                .orElse(s);
    }

    private record PatternData(NamespacedKey patternKey, DyeColor color) {}
}