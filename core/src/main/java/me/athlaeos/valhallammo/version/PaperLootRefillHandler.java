package me.athlaeos.valhallammo.version;

import com.destroystokyo.paper.loottable.LootableInventory;
import me.athlaeos.valhallammo.ValhallaMMO;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

public class PaperLootRefillHandler {

    public static boolean canGenerateLoot(BlockState i, Player opener){
        if (!ValhallaMMO.isUsingPaperMC()) return true;
        if (i instanceof LootableInventory l){
            // TODO temp implementation, how the hell does papermc refilling work?
            return !l.hasPendingRefill() && !l.hasPlayerLooted(opener);
        }
        return true;
    }

    public static boolean canGenerateLoot(Entity i, Player opener){
        if (!ValhallaMMO.isUsingPaperMC()) return true;
        if (i instanceof LootableInventory l){
            // TODO temp.yml implementation, how the hell does papermc refilling work?
            return !l.hasPendingRefill() && !l.hasPlayerLooted(opener);
        }
        return true;
    }
}
