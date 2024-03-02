package me.athlaeos.valhallammo.crafting.dynamicitemmodifiers.implementations.crafting_conditionals;

import me.athlaeos.valhallammo.crafting.dynamicitemmodifiers.DynamicItemModifier;
import me.athlaeos.valhallammo.crafting.dynamicitemmodifiers.ModifierCategoryRegistry;
import me.athlaeos.valhallammo.dom.Pair;
import me.athlaeos.valhallammo.item.ItemBuilder;
import org.bukkit.command.CommandSender;
import me.athlaeos.valhallammo.item.SmithingItemPropertyManager;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.*;
import java.util.stream.Collectors;

public class SmithingTagsAdd extends DynamicItemModifier {
    private final Collection<Integer> tags = new HashSet<>();
    private int tag = 0;

    public SmithingTagsAdd(String name) {
        super(name);
    }

    @Override
    public void processItem(Player crafter, ItemBuilder outputItem, boolean use, boolean validate, int timesExecuted) {
        SmithingItemPropertyManager.addTag(outputItem.getMeta(), tags.toArray(new Integer[0]));
    }

    @Override
    public void onButtonPress(InventoryClickEvent e, int button) {
        if (button == 12)
            tag = Math.max(0, tag + ((e.isLeftClick() ? 1 : -1) * (e.isShiftClick() ? 10 : 1)));
        else if (button == 17){
            if (e.isShiftClick()) tags.clear();
            else tags.add(tag);
        }
    }

    @Override
    public Map<Integer, ItemStack> getButtons() {
        List<String> tagLore = tags.isEmpty() ? List.of("&cNone") : tags.stream().map(t ->
                "&e" + Objects.requireNonNullElse(
                        SmithingItemPropertyManager.getTagLore(t),
                        t + " &7(invisible)"))
                .collect(Collectors.toList());
        return new Pair<>(12,
                new ItemBuilder(Material.WRITABLE_BOOK)
                        .name("&fTag Selection?")
                        .lore("&fTag &e" + tag + "&7 (" + Objects.requireNonNullElse(
                                SmithingItemPropertyManager.getTagLore(tag),
                                "invisible") + "&7)",
                                "&fClick the button below to add",
                                "&fthis tag to the list.",
                                "&6Click to add/subtract 1",
                                "&6Shift-Click to add/subtract 10",
                                "&fCurrent tags:")
                        .appendLore(tagLore)
                        .get()).map(Set.of(
                new Pair<>(17,
                        new ItemBuilder(Material.STRUCTURE_VOID)
                                .name("&fConfirm Tag")
                                .lore("&fCurrently selected: &e" + tag,
                                        "&6Click to add selected tag to",
                                        "&6the list.",
                                        "&6Shift-Click to clear list",
                                        "&fCurrent tags:")
                                .appendLore(tagLore)
                                .get())
        ));
    }

    @Override
    public ItemStack getModifierIcon() {
        return new ItemBuilder(Material.PAPER).get();
    }

    @Override
    public String getDisplayName() {
        return "&7Item Tags (ADD)";
    }

    @Override
    public String getDescription() {
        return "&fAdds a number of item tags to the item. Item tags are useful for recipe condition checking";
    }

    @Override
    public String getActiveDescription() {
        return "&fAdds the following tags to the item: /n&e" + (tags.isEmpty() ? List.of("&cNone") : tags.stream().map(t ->
                "&e" + Objects.requireNonNullElse(
                        SmithingItemPropertyManager.getTagLore(t),
                        String.valueOf(t)))
                .collect(Collectors.joining(", ")));
    }

    @Override
    public Collection<String> getCategories() {
        return Set.of(ModifierCategoryRegistry.CRAFTING_CONDITIONALS.id());
    }

    public Collection<Integer> getTags() {
        return tags;
    }

    @Override
    public DynamicItemModifier copy() {
        SmithingTagsAdd m = new SmithingTagsAdd(getName());
        m.getTags().addAll(this.tags);
        m.setPriority(this.getPriority());
        return m;
    }

    @Override
    public String parseCommand(CommandSender executor, String[] args) {
        if (args.length != 1) return "One argument is expected: a string joining all numeric tags together (example: 1,2,3,4)";
        for (String t : args[0].split(",")){
            try {
                int tag = Integer.parseInt(t);
                tags.add(tag);
            } catch (NumberFormatException ignored){
                return "One argument is expected: a string joining all numeric tags together (example: 1,2,3,4). One of them was not a number";
            }
        }
        return null;
    }

    @Override
    public List<String> commandSuggestions(CommandSender executor, int currentArg) {
        if (currentArg == 0) return List.of("<tags_separated_by_commas>");
        return null;
    }

    @Override
    public int commandArgsRequired() {
        return 1;
    }
}