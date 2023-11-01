package me.athlaeos.valhallammo.listeners;

import me.athlaeos.valhallammo.ValhallaMMO;
import me.athlaeos.valhallammo.animations.Animation;
import me.athlaeos.valhallammo.animations.AnimationRegistry;
import me.athlaeos.valhallammo.dom.*;
import me.athlaeos.valhallammo.event.EntityCriticallyHitEvent;
import me.athlaeos.valhallammo.item.EquipmentClass;
import me.athlaeos.valhallammo.item.ItemAttributesRegistry;
import me.athlaeos.valhallammo.item.ItemBuilder;
import me.athlaeos.valhallammo.item.item_attributes.AttributeWrapper;
import me.athlaeos.valhallammo.localization.TranslationManager;
import me.athlaeos.valhallammo.playerstats.AccumulativeStatManager;
import me.athlaeos.valhallammo.playerstats.EntityCache;
import me.athlaeos.valhallammo.playerstats.EntityProperties;
import me.athlaeos.valhallammo.potioneffects.CustomPotionEffect;
import me.athlaeos.valhallammo.potioneffects.PotionEffectRegistry;
import me.athlaeos.valhallammo.potioneffects.PotionEffectWrapper;
import me.athlaeos.valhallammo.potioneffects.implementations.Stun;
import me.athlaeos.valhallammo.utility.Bleeder;
import me.athlaeos.valhallammo.utility.Parryer;
import me.athlaeos.valhallammo.utility.*;
import me.athlaeos.valhallammo.utility.Timer;
import org.bukkit.EntityEffect;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.util.Vector;

import java.util.*;

public class EntityAttackListener implements Listener {

    private static final double facingAngleCos = MathUtils.cos(ValhallaMMO.getPluginConfig().getDouble("facing_angle", 70));
    private final boolean requireFacingForDodge = ValhallaMMO.getPluginConfig().getBoolean("prevent_dodge_not_facing_attacker", true);
    private final Particle dodgeParticle = Catch.catchOrElse(() -> Particle.valueOf(ValhallaMMO.getPluginConfig().getString("dodge_effect")), Particle.SWEEP_ATTACK, "Invalid dodge particle effect given, used default");
    private final String dodgeMessage = TranslationManager.getTranslation(ValhallaMMO.getPluginConfig().getString("dodge_message", ""));
    private final EntityDamageEvent.DamageCause reflectDamageType = Catch.catchOrElse(() -> EntityDamageEvent.DamageCause.valueOf(ValhallaMMO.getPluginConfig().getString("reflect_damage_type")), EntityDamageEvent.DamageCause.THORNS, "Invalid reflect damage type given, used default");
    private final Sound critSound = Catch.catchOrElse(() -> Sound.valueOf(ValhallaMMO.getPluginConfig().getString("crit_sound_effect")), Sound.ENCHANT_THORNS_HIT, "Invalid crit sound effect given, used default");
    private Animation critAnimation = ValhallaMMO.getPluginConfig().getBoolean("crit_particle_effect", true) ? AnimationRegistry.getAnimation(AnimationRegistry.ENTITY_FLASH.id()) : null;

    private final double tridentThrownDamage = ValhallaMMO.getPluginConfig().getDouble("trident_damage_ranged");
    private final double tridentThrownLoyalDamage = ValhallaMMO.getPluginConfig().getDouble("trident_damage_ranged_loyalty");

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onAttack(EntityDamageByEntityEvent e){
        if (ValhallaMMO.isWorldBlacklisted(e.getEntity().getWorld().getName()) || e.isCancelled() || !(e.getEntity() instanceof LivingEntity v)) return;
        Entity trueDamager = EntityUtils.getTrueDamager(e);
        boolean sweep = e.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK;

        AttributeInstance dL = trueDamager instanceof LivingEntity a ? a.getAttribute(Attribute.GENERIC_LUCK) : null;
        double damagerLuck = dL != null ? dL.getValue() : 0;
        AttributeInstance vL = v.getAttribute(Attribute.GENERIC_LUCK);
        double victimLuck = vL != null ? vL.getValue() : 0;

        boolean facing = EntityUtils.isEntityFacing(v, e.getDamager().getLocation(), facingAngleCos);

        // dodging mechanic
        // for this and following mechanics where the victim is hit by a sweep attack the mechanic does not activate, because large clusters of victims will cause a lag spike
        // due to the amount of stats being fetched.
        // the dodge is also considered first, as dodging an attack voids all following effects
        if (!sweep && (facing || !requireFacingForDodge)){
            if (Utils.proc(AccumulativeStatManager.getCachedRelationalStats("DODGE_CHANCE", v, e.getDamager(), 10000, true), victimLuck - damagerLuck, false)){
                if (dodgeParticle != null) e.getEntity().getWorld().spawnParticle(dodgeParticle, e.getEntity().getLocation().add(0, 1, 0), 10, 0.2, 0.5, 0.2);
                if (e.getEntity() instanceof Player p) Utils.sendActionBar(p, dodgeMessage);
                e.setCancelled(true);
                return;
            }
        }

        // trident damage overhaul
        if (e.getDamager() instanceof Trident t){
            ItemMeta tridentMeta = ItemUtils.getItemMeta(t.getItem());
            AttributeWrapper damageWrapper = ItemAttributesRegistry.getAttribute(tridentMeta, "GENERIC_ATTACK_DAMAGE", false);
            if (damageWrapper != null){
                if (t.getItem().containsEnchantment(Enchantment.LOYALTY)) e.setDamage(damageWrapper.getValue() * tridentThrownLoyalDamage);
                else e.setDamage(damageWrapper.getValue() * tridentThrownDamage);
            }
        }
        double parryDamageMultiplier = 1;

        CombatType combatType = e.getDamager() instanceof Projectile ? CombatType.RANGED : CombatType.MELEE_UNARMED;
        if (trueDamager instanceof LivingEntity a){
            if(a.getEquipment() != null && !ItemUtils.isEmpty(a.getEquipment().getItemInMainHand())) combatType = CombatType.MELEE_ARMED;

            // parry mechanic
            if (e.getDamager() instanceof LivingEntity d){
                if (facing) parryDamageMultiplier = Parryer.handleParry(e);
                Timer.setCooldown(d.getUniqueId(), 0, "parry_effective"); // the attacker should have their parry interrupted if they attack while active
            }
        }

        // damage buffs
        // as mentioned previously mechanics where the victim's stats have to be fetched on an attack, these mechanics do not activate on sweeping hits.
        // unlike them, attacker mechanics don't need to worry about that since their stats are cached and fetched without going through all their stat sources.
        double damageMultiplier = 1 + AccumulativeStatManager.getCachedAttackerRelationalStats("DAMAGE_DEALT", v, e.getDamager(), 10000, true);
        if (e.getDamager() instanceof Projectile) {
            // ranged damage buffs
            damageMultiplier += AccumulativeStatManager.getCachedAttackerRelationalStats("RANGED_DAMAGE_DEALT", v, e.getDamager(), 10000, true);
        } else {
            // melee damage buffs
            damageMultiplier += AccumulativeStatManager.getCachedAttackerRelationalStats(combatType == CombatType.MELEE_UNARMED ? "UNARMED_DAMAGE_DEALT" : "MELEE_DAMAGE_DEALT", v, e.getDamager(), 10000, true);
        }
        EntityProperties victimProperties = EntityCache.getAndCacheProperties(v);
        damageMultiplier += (victimProperties.getLightArmorCount() * AccumulativeStatManager.getCachedAttackerRelationalStats("LIGHT_ARMOR_DAMAGE_BONUS", v, e.getDamager(), 10000, true));
        damageMultiplier += (victimProperties.getHeavyArmorCount() * AccumulativeStatManager.getCachedAttackerRelationalStats("HEAVY_ARMOR_DAMAGE_BONUS", v, e.getDamager(), 10000, true));
        e.setDamage(Math.max(0, e.getDamage() * damageMultiplier));

        // custom crit mechanics
        // the crit mechanic fetches the victim's crit chance and damage resistance stats and so sweeping hits should not be able to crit
        if (!sweep){
            double critChanceResistance = AccumulativeStatManager.getCachedRelationalStats("CRIT_CHANCE_RESISTANCE", v, e.getDamager(), 10000, true);
            double critChance = AccumulativeStatManager.getCachedAttackerRelationalStats("CRIT_CHANCE", v, e.getDamager(), 10000, true) * (1 - critChanceResistance);
            if (critNextAttack.contains(trueDamager.getUniqueId()) || Utils.proc(critChance, damagerLuck - victimLuck, false)) {
                critNextAttack.remove(trueDamager.getUniqueId());
                double critDamageResistance = AccumulativeStatManager.getCachedRelationalStats("CRIT_DAMAGE_RESISTANCE", v, e.getDamager(), 10000, true);
                double critDamage = 1 + (AccumulativeStatManager.getCachedAttackerRelationalStats("CRIT_DAMAGE", v, e.getDamager(), 10000, true) * (1 - critDamageResistance));
                EntityCriticallyHitEvent event = new EntityCriticallyHitEvent(v, e.getDamager(), combatType, e.getDamage(), critDamage);
                ValhallaMMO.getInstance().getServer().getPluginManager().callEvent(event);
                if (!event.isCancelled()){
                    e.setDamage(event.getDamageBeforeCrit() * event.getCritMultiplier());
                    if (critAnimation != null) critAnimation.animate(v, v.getEyeLocation().add(0, -v.getHeight()/2, 0), e.getDamager() instanceof LivingEntity l ? l.getEyeLocation().getDirection() : e.getDamager().getVelocity(), 0);
                    if (critSound != null) v.getWorld().playSound(v.getEyeLocation(), critSound, 1F, 1F);
                }
            }
        }

        // custom stun mechanics
        // stuns fetch the victim's stun resistance and so sweeping hits should not be able to stun
        if (!sweep){
            double stunChance = AccumulativeStatManager.getCachedAttackerRelationalStats("STUN_CHANCE", v, e.getDamager(), 10000, true);
            if (Utils.proc(stunChance, damagerLuck - victimLuck, false)) Stun.attemptStun(v, trueDamager instanceof LivingEntity l ? l : null);
        }

        // damage reflecting mechanic
        if (!sweep && reflectDamageType != null && e.getCause() != reflectDamageType && trueDamager instanceof LivingEntity a){
            if (Utils.proc(AccumulativeStatManager.getCachedRelationalStats("REFLECT_CHANCE", v, e.getDamager(), 10000, true), victimLuck - damagerLuck, false)){
                double reflectFraction = AccumulativeStatManager.getCachedRelationalStats("REFLECT_FRACTION", v, e.getDamager(), 10000, true);
                double reflectDamage = e.getDamage() * reflectFraction;
                a.playEffect(EntityEffect.THORNS_HURT);
                EntityUtils.damage(a, v, reflectDamage, reflectDamageType.toString());
            }
        }

        // custom knockback mechanics
        if (!sweep){
            double knockbackBonus = AccumulativeStatManager.getCachedAttackerRelationalStats("KNOCKBACK_BONUS", v, e.getDamager(), 10000, true);
            AttributeInstance knockbackInstance = v.getAttribute(Attribute.GENERIC_KNOCKBACK_RESISTANCE);
            double knockbackResistance = (knockbackInstance == null ? 0 : knockbackInstance.getValue()) - Math.min(0, knockbackBonus);
            if (knockbackBonus > 0) knockbackBonus *= (1 - knockbackResistance);

            if (knockbackResistance > 0) EntityUtils.addUniqueAttribute(v, "valhalla_negative_knockback_taken", Attribute.GENERIC_KNOCKBACK_RESISTANCE, knockbackResistance, AttributeModifier.Operation.ADD_NUMBER);
            if (knockbackBonus != 0){
                double finalKnockbackBonus = knockbackBonus;
                ValhallaMMO.getInstance().getServer().getScheduler().runTaskLater(ValhallaMMO.getInstance(), () -> {
                    // finishing off custom knockback mechanics (removing attribute if placed, or changing velocity to comply with increased knockback)
                    if (knockbackResistance > 0) EntityUtils.removeUniqueAttribute(v, "valhalla_negative_knockback_taken", Attribute.GENERIC_KNOCKBACK_RESISTANCE);
                    else if (finalKnockbackBonus > 0){
                        Vector lookingDirection = e.getDamager() instanceof LivingEntity a ? a.getEyeLocation().getDirection().normalize() : e.getDamager().getVelocity().normalize();

                        lookingDirection.setX(lookingDirection.getX() * finalKnockbackBonus);
                        lookingDirection.setY(0);
                        lookingDirection.setZ(lookingDirection.getZ() * finalKnockbackBonus);
                        v.setVelocity(v.getVelocity().add(lookingDirection));
                    }
                }, 1L);
            }
        }

        // custom dismount mechanics
        if (v.getVehicle() != null){
            double dismountChance = AccumulativeStatManager.getCachedAttackerRelationalStats("DISMOUNT_CHANCE", v, e.getDamager(), 10000, true);
            if (Utils.proc(dismountChance, damagerLuck - victimLuck, false)) v.getVehicle().eject();
        }

        // custom bleed mechanics
        double bleedChance = AccumulativeStatManager.getCachedAttackerRelationalStats("BLEED_CHANCE", v, e.getDamager(), 10000, true);
        if (!sweep && (bleedNextAttack.contains(trueDamager.getUniqueId()) || Utils.proc(bleedChance, damagerLuck - victimLuck, false))){
            bleedNextAttack.remove(trueDamager.getUniqueId());
            Bleeder.inflictBleed(v, e.getDamager(), combatType);
        }

        // custom power attack mechanics
        double powerAttackMultiplier = 1.5;
        // sweep attacks should not trigger custom power attack damage multipliers
        if (!sweep && e.getDamager() instanceof LivingEntity a && a.getFallDistance() > 0){
            String damageSource = EntityDamagedListener.getLastDamageCause(v);
            powerAttackMultiplier += AccumulativeStatManager.getCachedAttackerRelationalStats("POWER_ATTACK_DAMAGE_MULTIPLIER", v, a, 10000, true);

            double baseDamage = e.getDamage() / 1.5; // remove vanilla crit damage
            e.setDamage(baseDamage * powerAttackMultiplier); // set custom power attack damage

            double radius = AccumulativeStatManager.getCachedAttackerRelationalStats("POWER_ATTACK_RADIUS", v, a, 10000, true);
            double fraction = AccumulativeStatManager.getCachedAttackerRelationalStats("POWER_ATTACK_DAMAGE_FRACTION", v, a, 10000, true);
            double damage = e.getDamage() * fraction;
            if (damage > 0 && radius > 0){
                for (Entity entity : e.getEntity().getWorld().getNearbyEntities(e.getEntity().getLocation(), radius, radius, radius, (en) -> en instanceof LivingEntity)){
                    if (EntityClassification.matchesClassification(entity.getType(), EntityClassification.UNALIVE) || entity.equals(a)) continue;
                    EntityUtils.damage((LivingEntity) entity, a, damage, damageSource);
                }
            }
        }

        // custom damage types mechanics
        EntityDamageEvent.DamageCause originalCause = e.getCause();
        double cooldownDamageMultiplier = EntityUtils.cooldownDamageMultiplier(e.getDamager() instanceof Player p ? p.getAttackCooldown() : 1);
        for (String damageType : customDamageBonusses.keySet()){
            double baseDamage = AccumulativeStatManager.getCachedAttackerRelationalStats(customDamageBonusses.get(damageType), v, e.getDamager(), 10000, true);
            double elementalDamage = damageMultiplier * baseDamage * cooldownDamageMultiplier * parryDamageMultiplier;
            if (!sweep && isPowerAttackable(damageType) && e.getDamager().getFallDistance() > 0) elementalDamage *= powerAttackMultiplier; // power attacks deal 50% more damage
            if (elementalDamage > 0) {
                EntityUtils.damage(v, e.getDamager(), elementalDamage, damageType);
                v.setNoDamageTicks(0); // the entity should not receive immunity frames for these types of damage, as this will reduce or even nullify the entity attack damage taken afterwards
            }
        }
        EntityDamagedListener.setCustomDamageCause(v.getUniqueId(), originalCause.toString());

        // in-combat mechanics
        if ((trueDamager instanceof Player p && v instanceof Monster)) combatAction(p);
        else if (v instanceof Player p) combatAction(p);
    }

    private static final Collection<UUID> critNextAttack = new HashSet<>();
    public static void critNextAttack(LivingEntity entity){
        critNextAttack.add(entity.getUniqueId());
    }
    private static final Collection<UUID> bleedNextAttack = new HashSet<>();
    public static void bleedNextAttack(LivingEntity entity){
        bleedNextAttack.add(entity.getUniqueId());
    }

    private static final Map<String, String> customDamageBonusses = new HashMap<>();
    private static final Map<String, String> customDamageMultipliers = new HashMap<>();
    private static final Map<String, Boolean> powerAttackableSources = new HashMap<>(); // defines if damage should be multiplied by 1.5x if the attacker is falling (vanilla "crit", though in the plugin defined as power attacks)
    static {
        addCustomDamageSource("FIRE_DAMAGE_BONUS", "FIRE_DAMAGE_DEALT", "FIRE");
        addCustomDamageSource("EXPLOSION_DAMAGE_BONUS", "EXPLOSION_DAMAGE_DEALT", "ENTITY_EXPLOSION");
        addCustomDamageSource("POISON_DAMAGE_BONUS", "POISON_DAMAGE_DEALT", "POISON");
        addCustomDamageSource("MAGIC_DAMAGE_BONUS", "MAGIC_DAMAGE_DEALT", "MAGIC");
        addCustomDamageSource("LIGHTNING_DAMAGE_BONUS", "LIGHTNING_DAMAGE_DEALT", "LIGHTNING");
        addCustomDamageSource("FREEZING_DAMAGE_BONUS", "FREEZING_DAMAGE_DEALT", "FREEZE");
        addCustomDamageSource("RADIANT_DAMAGE_BONUS", "RADIANT_DAMAGE_DEALT", "RADIANT");
        addCustomDamageSource("NECROTIC_DAMAGE_BONUS", "NECROTIC_DAMAGE_DEALT", "NECROTIC");
        addCustomDamageSource("BLUDGEONING_DAMAGE_BONUS", "BLUDGEONING_DAMAGE_DEALT", "BLUDGEONING");

        setPowerAttackable("BLUDGEONING", true);
    }

    public static Map<String, String> getCustomDamageMultipliers() {
        return customDamageMultipliers;
    }

    public static Map<String, String> getCustomDamageBonusses() {
        return customDamageBonusses;
    }

    public static Map<String, Boolean> getPowerAttackableSources() {
        return powerAttackableSources;
    }

    /**
     * Registers a custom damage type to be inflicted on an attack. The additiveStatSource determines the stat source to be gotten from
     * the {@link AccumulativeStatManager} to gather the base damage of this type dealt to the victim, where the multiplicativeStatSource
     * determines the stat this base number should be increased/decreased by (-0.3 would be 30% reduced damage)
     * @param additiveStatSource the base damage stat source of this damage type
     * @param multiplicativeStatSource the multiplier stat source of this damage type
     * @param damageType the damage type this attack should deal if values > 0
     */
    public static void addCustomDamageSource(String additiveStatSource, String multiplicativeStatSource, String damageType){
        customDamageBonusses.put(damageType, additiveStatSource);
        customDamageMultipliers.put(damageType, multiplicativeStatSource);
    }

    public static void setPowerAttackable(String source, boolean powerHit){
        if (powerHit) powerAttackableSources.put(source, true);
        else powerAttackableSources.remove(source);
    }

    public static boolean isPowerAttackable(String source){
        return powerAttackableSources.getOrDefault(source, false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPotionAttack(EntityDamageByEntityEvent e){
        if (ValhallaMMO.isWorldBlacklisted(e.getEntity().getWorld().getName()) || e.isCancelled()) return;

        if (e.getDamager() instanceof LivingEntity a && e.getEntity() instanceof LivingEntity v && a.getEquipment() != null){
            ItemStack hand = a.getEquipment().getItemInMainHand();
            if (ItemUtils.isEmpty(hand)) return;
            ItemBuilder weapon = new ItemBuilder(hand);

            if (hand.getType().isEdible() || weapon.getMeta() instanceof PotionMeta || !EquipmentClass.isHandHeld(weapon.getMeta())) return;

            // apply potion effects
            for (PotionEffectWrapper wrapper : PotionEffectRegistry.getStoredEffects(weapon.getMeta(), false).values()){
                if (PotionEffectRegistry.spendCharge(weapon.getMeta(), wrapper.getEffect())){
                    if (wrapper.isVanilla()) v.addPotionEffect(new PotionEffect(wrapper.getVanillaEffect(), (int) wrapper.getDuration(), (int) wrapper.getAmplifier(), false));
                    else PotionEffectRegistry.addEffect(v, a, new CustomPotionEffect(wrapper, (int) wrapper.getDuration(), wrapper.getAmplifier()), false, 1, EntityPotionEffectEvent.Cause.ARROW);
                }
            }
            hand.setItemMeta(weapon.getMeta());
            a.getEquipment().setItemInMainHand(weapon.get(), true);
        }
    }

    public static Map<UUID, CombatLog> playersInCombat = new HashMap<>();
    /**
     * This method should be called when a player experiences an event that would cause them to be in combat
     * If it's been more than 10 seconds since their last combat action their combat timer is reset
     * This method calls a PlayerEnterCombatEvent if the player was previously not in combat
     */
    public static void combatAction(Player who) {
        playersInCombat.putIfAbsent(who.getUniqueId(), new CombatLog(who));
        playersInCombat.get(who.getUniqueId()).combatAction();
    }

    /**
     * This method should be called regularly to check if a player was previously in combat, but not any more.
     * If a player's isInCombat tag is false, nothing happens
     * If a player's isInCombat tag is true, and it's been more than 10 seconds since the player's last combat action,
     * a PlayerLeaveCombatEvent is called and their combat timer is updated to how long they were in combat for.
     * If a player's isInCombat tag is true, but it's been less than 10 seconds since the player's last combat action,
     * nothing happens.
     *
     * @param who the player to update their status
     */
    public static void updateCombatStatus(Player who) {
        CombatLog log = playersInCombat.get(who.getUniqueId());
        if (log != null) log.checkPlayerLeftCombat();
    }

    /**
     * Returns the time the player with this log was in combat for, this number resets to 0 when the player re-enters
     * combat. To get the accurate time the player was in combat for, listen to a PlayerLeaveCombatEvent
     *
     * @return the time in milliseconds the player was in combat for
     */
    public static long timePlayerInCombat(Player who) {
        CombatLog log = playersInCombat.get(who.getUniqueId());
        if (log == null) return 0;
        return log.getTimeInCombat();
    }

    public static boolean isInCombat(Player who){
        CombatLog log = playersInCombat.get(who.getUniqueId());
        if (log == null) return false;
        log.checkPlayerLeftCombat();
        return log.isInCombat();
    }

    public void setCritAnimation(Animation critAnimation) {
        this.critAnimation = critAnimation;
    }

    public static double getFacingAngleCos() {
        return facingAngleCos;
    }
}