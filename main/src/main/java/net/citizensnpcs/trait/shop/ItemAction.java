package net.citizensnpcs.trait.shop;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

import com.google.common.base.Joiner;
import com.google.common.collect.Lists;

import net.citizensnpcs.api.gui.InputMenus;
import net.citizensnpcs.api.gui.InventoryMenuPage;
import net.citizensnpcs.api.gui.InventoryMenuSlot;
import net.citizensnpcs.api.gui.Menu;
import net.citizensnpcs.api.gui.MenuContext;
import net.citizensnpcs.api.persistence.Persist;
import net.citizensnpcs.api.util.Messaging;
import net.citizensnpcs.api.util.SpigotUtil;
import net.citizensnpcs.util.InventoryMultiplexer;
import net.citizensnpcs.util.NMS;
import net.citizensnpcs.util.Util;
import net.kyori.adventure.platform.bukkit.BukkitComponentSerializer;
import net.kyori.adventure.text.Component;

public class ItemAction extends NPCShopAction {
    @Persist
    public boolean compareSimilarity = true;
    @Persist
    public List<ItemStack> items = Lists.newArrayList();
    @Persist
    public List<String> metaFilter = Lists.newArrayList();
    @Persist
    public boolean requireUndamaged = true;

    public ItemAction() {
    }

    public ItemAction(ItemStack... items) {
        this(Arrays.asList(items));
    }

    public ItemAction(List<ItemStack> items) {
        this.items = items;
    }

    @Override
    public String describe() {
        if (items.size() == 1)
            return stringify(items.get(0));
        String description = items.size() + " 物品";
        for (int i = 0; i < items.size(); i++) {
            ItemStack item = items.get(i);
            description += "\n" + stringify(item);
            if (i == 3) {
                description += "...";
                break;
            }
        }
        return description;
    }

    @Override
    public int getMaxRepeats(Entity entity, InventoryMultiplexer im) {
        if (!(entity instanceof InventoryHolder))
            return 0;

        ItemStack[] inventory = im.getInventory();
        List<Integer> req = items.stream().map(ItemStack::getAmount).collect(Collectors.toList());
        List<Integer> has = items.stream().map(i -> 0).collect(Collectors.toList());
        for (int i = 0; i < inventory.length; i++) {
            ItemStack toMatch = inventory[i];
            if (toMatch == null || toMatch.getType() == Material.AIR || tooDamaged(toMatch))
                continue;

            toMatch = toMatch.clone();
            for (int j = 0; j < items.size(); j++) {
                if (!matches(items.get(j), toMatch))
                    continue;

                int remaining = req.get(j);
                int taken = toMatch.getAmount() > remaining ? remaining : toMatch.getAmount();
                has.set(j, has.get(j) + taken);
                if (toMatch.getAmount() - taken <= 0)
                    break;

                toMatch.setAmount(toMatch.getAmount() - taken);
            }
        }
        return IntStream.range(0, req.size()).map(i -> req.get(i) == 0 ? 0 : has.get(i) / req.get(i)).reduce(Math::min)
                .orElse(0);
    }

    private void giveItems(ItemStack[] inventory, int repeats) {
        for (int i = 0; i < repeats; i++) {
            List<ItemStack> toAdd = items.stream().map(ItemStack::clone).collect(Collectors.toList());
            for (int j = 0; j < inventory.length; j++) {
                if (toAdd.isEmpty())
                    return;
                if (inventory[j] == null)
                    continue;
                ItemStack last = toAdd.get(toAdd.size() - 1);
                if (!inventory[j].isSimilar(last) || inventory[j].getAmount() >= inventory[j].getMaxStackSize())
                    continue;
                int diff = inventory[j].getMaxStackSize() - inventory[j].getAmount();
                if (diff >= last.getAmount()) {
                    inventory[j].setAmount(inventory[j].getAmount() + last.getAmount());
                    toAdd.remove(toAdd.size() - 1);
                } else {
                    inventory[j].setAmount(inventory[j].getAmount() + diff);
                    last.setAmount(last.getAmount() - diff);
                }
            }
            for (int j = 0; j < inventory.length; j++) {
                if (toAdd.isEmpty())
                    break;
                if (inventory[j] == null || inventory[j].getType() == Material.AIR) {
                    inventory[j] = toAdd.remove(toAdd.size() - 1);
                }
            }
        }
    }

    @Override
    public Transaction grant(Entity entity, InventoryMultiplexer im, int repeats) {
        if (!(entity instanceof InventoryHolder))
            return Transaction.fail();
        return Transaction.create(() -> {
            ItemStack[] inventory = im.getInventory();
            int free = 0;
            for (ItemStack stack : inventory) {
                if (stack == null || stack.getType() == Material.AIR) {
                    free++;
                    continue;
                }
            }
            return free >= items.size() * repeats;
        }, () -> im.transact(inventory -> giveItems(inventory, repeats)),
                () -> im.transact(inventory -> takeItems(inventory, repeats, true)));
    }

    private boolean matches(ItemStack a, ItemStack b) {
        if (metaFilter.size() > 0 || compareSimilarity) {
            // work around a Vanilla/Spigot bug: display name can be a Component with single string element or a
            // Component with sibling text. even if the content is the same, isSimilar will treat these as separate.
            // to fix this, go through a normalisation step. XXX: assumes that b is the Minecraft supplied item stack
            // and a has already been normalised.
            b.setItemMeta(b.getItemMeta());
        }
        if (Messaging.isDebugging()) {
            Messaging.debug("Shop filter: comparing " + a + " to " + b + " (" + metaFilter + ") " + a.isSimilar(b));
        }
        if (a.getType() != b.getType() || metaFilter.size() > 0 && !metaMatches(a, b, metaFilter))
            return false;

        if (compareSimilarity && !a.isSimilar(b))
            return false;

        return true;
    }

    @SuppressWarnings("unchecked")
    private boolean metaMatches(ItemStack needle, ItemStack haystack, List<String> meta) {
        Map<String, Object> source = NMS.getComponentMap(needle);
        Map<String, Object> compare = NMS.getComponentMap(haystack);
        for (String nbt : meta) {
            String[] parts = nbt.split("\\.");
            Object acc = source;
            Object cmp = compare;
            for (int i = 0; i < parts.length; i++) {
                if (acc == null || cmp == null)
                    return false;
                if (i < parts.length - 1 && !(acc instanceof Map))
                    return false;
                if (i < parts.length - 1 && !(cmp instanceof Map))
                    return false;
                Map<String, Object> nextAcc = (Map<String, Object>) acc;
                Map<String, Object> nextCmp = (Map<String, Object>) cmp;
                if (!nextAcc.containsKey(parts[i])) {
                    Messaging.warn("商店筛选器可能存在错误：源物品不包含请求的元信息 "
                            + metaFilter + " 实际元信息为： " + source);
                    return false;
                }
                if (!nextCmp.containsKey(parts[i]))
                    return false;
                acc = nextAcc.get(parts[i]);
                cmp = nextCmp.get(parts[i]);
                if (i == parts.length - 1 && !acc.equals(cmp))
                    return false;
            }
        }
        return true;
    }

    private void sanityCheck() {
        if (metaFilter.size() == 0)
            return;
        for (ItemStack item : items) {
            metaMatches(item, item, metaFilter);
        }
    }

    private String stringify(ItemStack item) {
        if (SUPPORT_TRANSLATABLE) {
            return BukkitComponentSerializer.legacy().serialize(Component.text(item.getAmount() + " ")
                    .append(Component.translatable().key(item.getTranslationKey())));
        }
        return item.getAmount() + " " + Util.prettyEnum(item.getType());
    }

    @Override
    public Transaction take(Entity entity, InventoryMultiplexer im, int repeats) {
        if (!(entity instanceof InventoryHolder))
            return Transaction.fail();

        return Transaction.create(() -> takeItems(im.getInventory(), repeats, false),
                () -> im.transact(inventory -> takeItems(inventory, repeats, true)),
                () -> im.transact(inventory -> giveItems(inventory, repeats)));
    }

    private boolean takeItems(ItemStack[] contents, int repeats, boolean modify) {
        List<Integer> req = items.stream().map(i -> i.getAmount() * repeats).collect(Collectors.toList());
        for (int i = 0; i < contents.length; i++) {
            ItemStack toMatch = contents[i];
            if (toMatch == null || toMatch.getType() == Material.AIR || tooDamaged(toMatch))
                continue;

            toMatch = toMatch.clone();
            for (int j = 0; j < items.size(); j++) {
                if (toMatch == null)
                    break;

                ItemStack item = items.get(j);
                if (req.get(j) <= 0 || !matches(item, toMatch))
                    continue;

                int remaining = req.get(j);
                int taken = toMatch.getAmount() > remaining ? remaining : toMatch.getAmount();

                if (toMatch.getAmount() == taken) {
                    toMatch = null;
                } else {
                    toMatch.setAmount(toMatch.getAmount() - taken);
                }
                if (modify) {
                    if (toMatch == null) {
                        contents[i] = null;
                    } else {
                        contents[i] = toMatch.clone();
                    }
                }
                req.set(j, remaining - taken);
            }
        }
        return req.stream().collect(Collectors.summingInt(n -> n)) <= 0;
    }

    private boolean tooDamaged(ItemStack toMatch) {
        if (!requireUndamaged)
            return false;

        if (SpigotUtil.isUsing1_13API())
            return toMatch.getItemMeta() instanceof Damageable && ((Damageable) toMatch.getItemMeta()).getDamage() != 0;

        return toMatch.getType().getMaxDurability() != 0 && toMatch.getDurability() != 0;
    }

    @Menu(title = "物品编辑器", dimensions = { 4, 9 })
    public static class ItemActionEditor extends InventoryMenuPage {
        private ItemAction base;
        private Consumer<NPCShopAction> callback;
        private MenuContext ctx;

        public ItemActionEditor() {
        }

        public ItemActionEditor(ItemAction base, Consumer<NPCShopAction> callback) {
            this.base = base;
            this.callback = callback;
        }

        @Override
        public void initialise(MenuContext ctx) {
            this.ctx = ctx;
            for (int i = 0; i < 3 * 9; i++) {
                InventoryMenuSlot slot = ctx.getSlot(i);
                slot.clear();
                if (i < base.items.size()) {
                    slot.setItemStack(base.items.get(i).clone());
                }
                slot.setClickHandler(event -> {
                    event.setCurrentItem(event.getCursorNonNull());
                    event.setCancelled(true);
                });
            }
            ctx.getSlot(3 * 9 + 1).setItemStack(new ItemStack(Material.ANVIL), "必须完好无损",
                    base.requireUndamaged ? ChatColor.GREEN + "开启" : ChatColor.RED + "关闭");
            ctx.getSlot(3 * 9 + 1)
                    .addClickHandler(InputMenus.toggler(res -> base.requireUndamaged = res, base.requireUndamaged));
            ctx.getSlot(3 * 9 + 2).setItemStack(
                    new ItemStack(Util.getFallbackMaterial("COMPARATOR", "REDSTONE_COMPARATOR")),
                    "比较物品相似性", base.compareSimilarity ? ChatColor.GREEN + "开启" : ChatColor.RED + "关闭");
            ctx.getSlot(3 * 9 + 2)
                    .addClickHandler(InputMenus.toggler(res -> base.compareSimilarity = res, base.compareSimilarity));
            ctx.getSlot(3 * 9 + 3).setItemStack(new ItemStack(Material.BOOK), "组件比较过滤器",
                    Joiner.on("\n").join(base.metaFilter));
            ctx.getSlot(3 * 9 + 3)
                    .addClickHandler(event -> ctx.getMenu()
                            .transition(InputMenus.stringSetter(() -> Joiner.on(',').join(base.metaFilter),
                                    res -> base.metaFilter = res == null ? null : Arrays.asList(res.split(",")))));
        }

        @Override
        public void onClose(HumanEntity player) {
            List<ItemStack> items = Lists.newArrayList();
            for (int i = 0; i < 3 * 9; i++) {
                if (ctx.getSlot(i).getCurrentItem() != null) {
                    items.add(ctx.getSlot(i).getCurrentItem().clone());
                }
            }
            base.items = items;
            base.sanityCheck();
            callback.accept(items.isEmpty() ? null : base);
        }
    }

    public static class ItemActionGUI implements GUI {
        @Override
        public InventoryMenuPage createEditor(NPCShopAction previous, Consumer<NPCShopAction> callback) {
            return new ItemActionEditor(previous == null ? new ItemAction() : (ItemAction) previous, callback);
        }

        @Override
        public ItemStack createMenuItem(NPCShopAction previous) {
            String description = null;
            if (previous != null) {
                ItemAction old = (ItemAction) previous;
                description = old.describe();
            }
            return Util.createItem(Material.CHEST, "Item", description);
        }

        @Override
        public boolean manages(NPCShopAction action) {
            return action instanceof ItemAction;
        }
    }

    private static boolean SUPPORT_TRANSLATABLE = true;

    static {
        try {
            Class.forName("org.bukkit.Translatable");
        } catch (ClassNotFoundException e) {
            SUPPORT_TRANSLATABLE = false;
        }
    }
}