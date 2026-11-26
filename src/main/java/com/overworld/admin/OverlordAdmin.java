package com.overlord.admin;

import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.*;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.*;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class OverlordAdmin extends JavaPlugin implements Listener, TabExecutor {
    private final Set<UUID> frozen = ConcurrentHashMap.newKeySet();
    private final Set<UUID> god = ConcurrentHashMap.newKeySet();
    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();
    private final Set<UUID> xray = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> balances = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastScan = new ConcurrentHashMap<>();

    @Override public void onEnable() {
        saveDefaultConfig();
        loadBalances();
        Objects.requireNonNull(getCommand("overlord")).setExecutor(this);
        Objects.requireNonNull(getCommand("overlord")).setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("OverlordAdmin enabled. Allowlist entries: " + getConfig().getStringList("allowed-users").size());
    }

    @Override public void onDisable() { saveBalances(); }

    private boolean allowed(CommandSender sender) {
        if (!(sender instanceof Player p)) return sender.hasPermission("overlord.console");
        List<String> names = getConfig().getStringList("allowed-users");
        return names.stream().anyMatch(n -> n.equalsIgnoreCase(p.getName()))
                || getConfig().getStringList("allowed-uuids").contains(p.getUniqueId().toString());
    }
    private boolean gate(CommandSender s) {
        if (allowed(s)) return true;
        s.sendMessage(ChatColor.RED + "You are not on the Overlord allowlist.");
        return false;
    }
    private Player target(String name) {
        Player p = Bukkit.getPlayerExact(name);
        return p;
    }
    private void msg(CommandSender s, String text) { s.sendMessage(ChatColor.LIGHT_PURPLE + "[Overlord] " + ChatColor.GRAY + text); }

    @Override public boolean onCommand(CommandSender s, Command c, String label, String[] a) {
        if (!gate(s)) return true;
        if (a.length == 0 || a[0].equalsIgnoreCase("help")) {
            msg(s, "Commands: /ov help, rich [player], money <set|add> <player> <amount>, kit [player], god [player], fly [player], vanish, tp <player|x y z>, tphere <player>, freeze <player>, unfreeze <player>, invsee <player>, heal [player], kill <player>, launch <player>, lightning <player>, boom <player>, speed [player] [level], strength [player] [level], jump [player] [level], feed [player], give <player> <item> [amount], repair, enchant <enchantment> [level], radar [radius], xray [radius], find <block> [radius], scanstop, reload");
            return true;
        }
        try {
            switch (a[0].toLowerCase(Locale.ROOT)) {
                case "rich" -> {
                    Player p = a.length > 1 ? requireTarget(s,a[1]) : asPlayer(s);
                    if (p != null) { setMoney(p, 9_000_000_000_000L); msg(s,p.getName()+" balance set to $9 trillion"+(vaultAvailable()?" in the server economy.":" in the Overlord wallet.")); }
                }
                case "money" -> {
                    if (a.length < 4) { msg(s,"Usage: /ov money <set|add> <player> <amount>"); break; }
                    Player p=requireTarget(s,a[2]); if(p==null) break;
                    long amount=Long.parseLong(a[3]); if(amount<0) throw new IllegalArgumentException("Amount must be positive.");
                    long old=getMoney(p);
                    long next=a[1].equalsIgnoreCase("set")?amount:Math.addExact(old,amount);
                    setMoney(p,next);
                    msg(s,p.getName()+" now has $"+getMoney(p)+(vaultAvailable()?" in the server economy.":" in the Overlord wallet."));
                }
                case "balance" -> {
                    Player p=a.length>1?requireTarget(s,a[1]):asPlayer(s);
                    if(p!=null) msg(s,p.getName()+" balance: $"+getMoney(p)+(vaultAvailable()?" (server economy)":" (Overlord wallet)"));
                }
                case "kit" -> {
                    Player p=a.length>1?requireTarget(s,a[1]):asPlayer(s); if(p!=null) giveKit(p);
                }
                case "god" -> {
                    Player p=a.length>1?requireTarget(s,a[1]):asPlayer(s); if(p!=null) {
                        toggle(god,p); if(god.contains(p.getUniqueId())) {p.setInvulnerable(true); p.setFireTicks(0); p.setFoodLevel(20); p.setSaturation(20);}
                        else p.setInvulnerable(false);
                        msg(s,"God mode "+(god.contains(p.getUniqueId())?"enabled":"disabled")+" for "+p.getName());
                    }
                }
                case "fly" -> {
                    Player p=a.length>1?requireTarget(s,a[1]):asPlayer(s); if(p!=null) {
                        p.setAllowFlight(!p.getAllowFlight()); if(!p.getAllowFlight()) p.setFlying(false);
                        msg(s,"Flight "+(p.getAllowFlight()?"enabled":"disabled")+" for "+p.getName());
                    }
                }
                case "vanish" -> {
                    Player p=asPlayer(s); if(p!=null) {
                        toggle(vanished,p);
                        for(Player online:Bukkit.getOnlinePlayers()) if(!online.equals(p)) {
                            if(vanished.contains(p.getUniqueId())) online.hidePlayer(this,p); else online.showPlayer(this,p);
                        }
                        msg(s,"Vanish "+(vanished.contains(p.getUniqueId())?"enabled":"disabled")+". This is not invisible to console, logs, or server plugins.");
                    }
                }
                case "tp" -> {
                    if(a.length==2) {Player t=requireTarget(s,a[1]); Player p=asPlayer(s); if(t!=null&&p!=null)p.teleport(t.getLocation());}
                    else if(a.length==4) {Player p=asPlayer(s); if(p!=null){double x=Double.parseDouble(a[1]),y=Double.parseDouble(a[2]),z=Double.parseDouble(a[3]);p.teleport(new Location(p.getWorld(),x,y,z));}}
                    else msg(s,"Usage: /ov tp <player> OR /ov tp <x> <y> <z>");
                }
                case "tphere" -> {
                    if(a.length<2){msg(s,"Usage: /ov tphere <player>");break;}
                    Player t=requireTarget(s,a[1]); Player p=asPlayer(s); if(t!=null&&p!=null)t.teleport(p.getLocation());
                }
                case "freeze", "unfreeze" -> {
                    if(a.length<2){msg(s,"Usage: /ov "+a[0]+" <player>");break;}
                    Player p=requireTarget(s,a[1]); if(p!=null) {
                        if(a[0].equalsIgnoreCase("freeze")) frozen.add(p.getUniqueId()); else frozen.remove(p.getUniqueId());
                        msg(s,p.getName()+(frozen.contains(p.getUniqueId())?" frozen.":" unfrozen."));
                    }
                }
                case "invsee" -> {
                    if(a.length<2){msg(s,"Usage: /ov invsee <player>");break;}
                    Player p=requireTarget(s,a[1]); if(p!=null && s instanceof Player viewer)viewer.openInventory(p.getInventory());
                }
                case "heal" -> {
                    Player p=a.length>1?requireTarget(s,a[1]):asPlayer(s); if(p!=null){
                        p.setHealth(Objects.requireNonNull(p.getAttribute(Attribute.MAX_HEALTH)).getValue());p.setFoodLevel(20);p.setSaturation(20);p.setFireTicks(0);msg(s,"Healed "+p.getName());
                    }
                }
                case "kill" -> {if(a.length<2){msg(s,"Usage: /ov kill <player>");break;}Player p=requireTarget(s,a[1]);if(p!=null){p.setHealth(0);msg(s,"Eliminated "+p.getName());}}
                case "launch" -> {if(a.length<2){msg(s,"Usage: /ov launch <player>");break;}Player p=requireTarget(s,a[1]);if(p!=null){p.setVelocity(new Vector(0,3.5,0));msg(s,"Launched "+p.getName());}}
                case "lightning", "boom", "speed", "strength", "jump", "feed" -> {
                    if(a.length<2){msg(s,"Usage: /ov "+a[0]+" <player> [level]");break;}
                    Player p=requireTarget(s,a[1]);if(p==null)break;
                    switch(a[0].toLowerCase(Locale.ROOT)){
                        case "lightning" -> {p.getWorld().strikeLightningEffect(p.getLocation());msg(s,"Lightning strike effect sent to "+p.getName());}
                        case "boom" -> {p.getWorld().createExplosion(p.getLocation(),4.0f,false,false);msg(s,"Non-block-damaging explosion triggered at "+p.getName());}
                        case "speed" -> applyEffect(p, PotionEffectType.SPEED, a.length>2?Integer.parseInt(a[2]):4);
                        case "strength" -> applyEffect(p, PotionEffectType.STRENGTH, a.length>2?Integer.parseInt(a[2]):4);
                        case "jump" -> applyEffect(p, PotionEffectType.JUMP_BOOST, a.length>2?Integer.parseInt(a[2]):4);
                        case "feed" -> {p.setFoodLevel(20);p.setSaturation(20);msg(s,"Fed "+p.getName());}
                    }
                }
                case "give" -> {
                    if(a.length<3){msg(s,"Usage: /ov give <player> <item> [amount]");break;}
                    Player p=requireTarget(s,a[1]);if(p==null)break;
                    Material material=Material.matchMaterial(a[2].toUpperCase(Locale.ROOT));
                    if(material==null||!material.isItem())throw new IllegalArgumentException("Unknown item material.");
                    int amount=a.length>3?Integer.parseInt(a[3]):64;
                    if(amount<1||amount>material.getMaxStackSize()*36)throw new IllegalArgumentException("Amount must be between 1 and "+(material.getMaxStackSize()*36)+".");
                    int remaining=amount;
                    while(remaining>0){int stack=Math.min(remaining,material.getMaxStackSize());p.getInventory().addItem(new ItemStack(material,stack));remaining-=stack;}
                    msg(s,"Gave "+amount+" "+material+" to "+p.getName());
                }
                case "repair" -> {
                    Player p=asPlayer(s);if(p==null)break;
                    ItemStack item=p.getInventory().getItemInMainHand();
                    if(item.getType().isAir()){msg(s,"Hold a damageable item in your main hand.");break;}
                    if(item.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable meta){meta.setDamage(0);item.setItemMeta(meta);msg(s,"Repaired your held item.");}
                    else msg(s,"That item cannot be repaired.");
                }
                case "enchant" -> {
                    Player p=asPlayer(s);if(p==null)break;
                    if(a.length<2){msg(s,"Usage: /ov enchant <enchantment> [level]");break;}
                    Enchantment enchant=Registry.ENCHANTMENT.get(NamespacedKey.minecraft(a[1].toLowerCase(Locale.ROOT)));
                    if(enchant==null)throw new IllegalArgumentException("Unknown enchantment key.");
                    int level=a.length>2?Integer.parseInt(a[2]):10;
                    if(level<1||level>255)throw new IllegalArgumentException("Level must be 1–255.");
                    ItemStack item=p.getInventory().getItemInMainHand();
                    if(item.getType().isAir())throw new IllegalArgumentException("Hold an item first.");
                    item.addUnsafeEnchantment(enchant,level);msg(s,"Applied "+enchant.getKey()+" "+level+".");
                }
                case "radar" -> {
                    Player p=asPlayer(s);if(p==null)break;
                    int radius=a.length>1?Integer.parseInt(a[1]):128;
                    if(radius<16||radius>512)throw new IllegalArgumentException("Radius must be 16–512 blocks.");
                    radar(p,radius);
                }
                case "xray" -> {
                    Player p=asPlayer(s); if(p==null)break;
                    if(xray.remove(p.getUniqueId())) {msg(s,"X-ray scanner disabled.");break;}
                    int radius=a.length>1?Integer.parseInt(a[1]):24;
                    if(radius<4||radius>48)throw new IllegalArgumentException("Radius must be 4–48 blocks.");
                    xray.add(p.getUniqueId()); msg(s,"Scanning nearby blocks within "+radius+" blocks. Highlights are particles, not a client-side X-ray texture.");
                    scanBlocks(p,radius);
                }
                case "find" -> {
                    if(a.length<2){msg(s,"Usage: /ov find <block_material> [radius]");break;}
                    Player p=asPlayer(s);if(p!=null){
                        Material mat=Material.matchMaterial(a[1].toUpperCase(Locale.ROOT));
                        if(mat==null||!mat.isBlock())throw new IllegalArgumentException("Unknown block material.");
                        int radius=a.length>2?Integer.parseInt(a[2]):64;
                        if(radius<1||radius>128)throw new IllegalArgumentException("Radius must be 1–128.");
                        findBlocks(p,mat,radius);
                    }
                }
                case "scanstop" -> {Player p=asPlayer(s);if(p!=null){xray.remove(p.getUniqueId());msg(s,"Scanner stopped.");}}
                case "reload" -> {reloadConfig();msg(s,"Configuration reloaded.");}
                default -> msg(s,"Unknown command. Use /ov help.");
            }
        } catch (NumberFormatException e) { msg(s,"That number is invalid."); }
          catch (IllegalArgumentException | ArithmeticException e) { msg(s,e.getMessage()==null?"Invalid argument.":e.getMessage()); }
        return true;
    }

    private Player asPlayer(CommandSender s){if(s instanceof Player p)return p;msg(s,"This command requires an in-game player.");return null;}
    private Player requireTarget(CommandSender s,String name){Player p=target(name);if(p==null)msg(s,"That player must be online.");return p;}
    private void toggle(Set<UUID> set,Player p){UUID id=p.getUniqueId();if(!set.remove(id))set.add(id);}
    private void giveKit(Player p) {
        p.getInventory().addItem(enchanted(new ItemStack(Material.NETHERITE_SWORD),"§dOVERLORD BLADE",Enchantment.SHARPNESS,10,Enchantment.FIRE_ASPECT,3,Enchantment.LOOTING,5));
        p.getInventory().addItem(enchanted(new ItemStack(Material.NETHERITE_CHESTPLATE),"§dOVERLORD ARMOR",Enchantment.PROTECTION,10,Enchantment.THORNS,5));
        p.getInventory().addItem(new ItemStack(Material.GOLDEN_APPLE,64));
        p.getInventory().addItem(new ItemStack(Material.ENDER_PEARL,64));
        p.getInventory().addItem(new ItemStack(Material.TOTEM_OF_UNDYING,16));
        msg(Bukkit.getConsoleSender(),"Issued Overlord kit to "+p.getName());
        p.sendMessage(ChatColor.LIGHT_PURPLE+"[Overlord] Your custom kit has arrived.");
    }
    private ItemStack enchanted(ItemStack item,String name,Object... pairs) {
        ItemMeta meta=item.getItemMeta();meta.setDisplayName(name);
        for(int i=0;i<pairs.length;i+=2)meta.addEnchant((Enchantment)pairs[i],(Integer)pairs[i+1],true);
        item.setItemMeta(meta);return item;
    }

    private void applyEffect(Player p,PotionEffectType type,int amplifier){
        if(amplifier<0||amplifier>10)throw new IllegalArgumentException("Effect level must be 0–10.");
        p.addPotionEffect(new PotionEffect(type,20*60*60,amplifier,true,false,true));
        msg(Bukkit.getConsoleSender(),"Applied "+type.getKey()+" level "+(amplifier+1)+" to "+p.getName());
    }

    // Optional Vault bridge: if Vault and a Vault-compatible economy are installed,
    // /ov money and /ov rich operate on the server's real economy. Otherwise use local balances.
    private Object vaultEconomy(){
        try{
            Class<?> economyClass=Class.forName("net.milkbowl.vault.economy.Economy");
            RegisteredServiceProvider<?> registration=Bukkit.getServicesManager().getRegistration((Class)economyClass);
            return registration==null?null:registration.getProvider();
        }catch(Throwable ignored){return null;}
    }
    // Use the public Vault interface for reflection; the provider's own class may be non-public and reject invoke().
    private Class<?> economyClass() throws ClassNotFoundException { return Class.forName("net.milkbowl.vault.economy.Economy"); }
    private boolean vaultAvailable(){return vaultEconomy()!=null;}
    private long getMoney(Player p){
        Object economy=vaultEconomy();
        if(economy!=null)try{
            Object response=economyClass().getMethod("getBalance",OfflinePlayer.class).invoke(economy,(OfflinePlayer)p);
            return Math.max(0L,Math.round(((Number)response).doubleValue()));
        }catch(Throwable ex){getLogger().warning("Vault balance lookup failed: "+ex.getMessage());}
        return balances.getOrDefault(p.getUniqueId(),0L);
    }
    private void setMoney(Player p,long amount){
        Object economy=vaultEconomy();
        if(economy!=null)try{
            long current=getMoney(p);long delta=amount-current;
            if(delta>0)economyClass().getMethod("depositPlayer",OfflinePlayer.class,double.class).invoke(economy,(OfflinePlayer)p,(double)delta);
            else if(delta<0)economyClass().getMethod("withdrawPlayer",OfflinePlayer.class,double.class).invoke(economy,(OfflinePlayer)p,(double)-delta);
            return;
        }catch(Throwable ex){getLogger().warning("Vault update failed; using local wallet: "+ex.getMessage());}
        balances.put(p.getUniqueId(),amount);saveBalances();
    }

    // Radar only inspects chunks that are already loaded; it deliberately does not force-load chunks.
    private void radar(Player p,int radius){
        World world=p.getWorld();Location here=p.getLocation();int minCx=(here.getBlockX()-radius)>>4,maxCx=(here.getBlockX()+radius)>>4;
        int minCz=(here.getBlockZ()-radius)>>4,maxCz=(here.getBlockZ()+radius)>>4;
        int found=0,checked=0;
        msg(p,"Radar scanning already-loaded chunks within "+radius+" blocks...");
        for(int cx=minCx;cx<=maxCx;cx++)for(int cz=minCz;cz<=maxCz;cz++){
            if(!world.isChunkLoaded(cx,cz))continue;
            checked++;
            for(org.bukkit.block.BlockState state:world.getChunkAt(cx,cz).getTileEntities()){
                if(!(state instanceof org.bukkit.block.Container))continue;
                Location loc=state.getLocation();
                if(loc.distanceSquared(here)>((double)radius*radius))continue;
                Material type=state.getType();
                p.sendMessage(ChatColor.GOLD+"[RADAR] "+type+" at "+loc.getBlockX()+" "+loc.getBlockY()+" "+loc.getBlockZ());
                p.spawnParticle(Particle.END_ROD,loc.clone().add(.5,.5,.5),8,.25,.25,.25,.01);
                if(++found>=100){msg(p,"Radar capped at 100 results. Loaded chunks checked: "+checked);return;}
            }
        }
        msg(p,"Radar found "+found+" containers in "+checked+" already-loaded chunks. Unloaded chunks are not scanned.");
    }

        private void findBlocks(Player p,Material material,int radius) {
        long now=System.currentTimeMillis(), last=lastScan.getOrDefault(p.getUniqueId(),0L);
        if(now-last<5000){msg(p,"Wait a few seconds before scanning again.");return;}
        lastScan.put(p.getUniqueId(),now);
        Location origin=p.getLocation(); World w=p.getWorld(); int found=0;
        int minX=origin.getBlockX()-radius,maxX=origin.getBlockX()+radius,minZ=origin.getBlockZ()-radius,maxZ=origin.getBlockZ()+radius;
        for(int cx=minX>>4;cx<=maxX>>4;cx++)for(int cz=minZ>>4;cz<=maxZ>>4;cz++){
            if(!w.isChunkLoaded(cx,cz))continue;
            Chunk ch=w.getChunkAt(cx,cz);
            for(int x=Math.max(minX,cx<<4);x<=Math.min(maxX,(cx<<4)+15);x++)
                for(int z=Math.max(minZ,cz<<4);z<=Math.min(maxZ,(cz<<4)+15);z++)
                    for(int y=w.getMinHeight();y<w.getMaxHeight();y++)
                        if(ch.getBlock(x&15,y,z&15).getType()==material){
                            Location hit=new Location(w,x+0.5,y+0.5,z+0.5);
                            p.sendMessage(ChatColor.GOLD+"Found "+material+" at "+x+" "+y+" "+z);
                            p.spawnParticle(Particle.END_ROD,hit,8,0.25,0.25,0.25,0.01);
                            if(++found>=40){msg(p,"Stopped after 40 matches.");return;}
                        }
        }
        msg(p,"Found "+found+" matching blocks in already-loaded chunks. Unloaded chunks were not forced open.");
    }
    private void scanBlocks(Player p,int radius) {
        Location o=p.getLocation();World w=p.getWorld();int n=0;
        scan: for(int x=o.getBlockX()-radius;x<=o.getBlockX()+radius;x++)
            for(int y=Math.max(w.getMinHeight(),o.getBlockY()-radius);y<=Math.min(w.getMaxHeight()-1,o.getBlockY()+radius);y++)
                for(int z=o.getBlockZ()-radius;z<=o.getBlockZ()+radius;z++){
                    if(!w.isChunkLoaded(x>>4,z>>4))continue;
                    Material m=w.getBlockAt(x,y,z).getType();
                    if(m==Material.DIAMOND_ORE||m==Material.DEEPSLATE_DIAMOND_ORE||m==Material.ANCIENT_DEBRIS||m==Material.CHEST||m==Material.BARREL||m==Material.ENDER_CHEST){
                        p.spawnParticle(Particle.END_ROD,x+0.5,y+0.5,z+0.5,3,0.15,0.15,0.15,0.0);
                        if(++n>=500)break scan;
                    }
                }
        msg(p,"Scanner marked "+n+" valuable/storage blocks. Toggle /ov xray to stop.");
    }

    @EventHandler public void onDamage(EntityDamageEvent e){if(e.getEntity() instanceof Player p&&god.contains(p.getUniqueId()))e.setCancelled(true);}
    @EventHandler public void onFood(FoodLevelChangeEvent e){if(e.getEntity() instanceof Player p&&god.contains(p.getUniqueId()))e.setCancelled(true);}
    @EventHandler public void onMove(PlayerMoveEvent e){
        if(!frozen.contains(e.getPlayer().getUniqueId())||e.getTo()==null)return;
        if(e.getFrom().getX()!=e.getTo().getX()||e.getFrom().getZ()!=e.getTo().getZ())
            e.setTo(new Location(e.getFrom().getWorld(),e.getFrom().getX(),e.getTo().getY(),e.getFrom().getZ(),e.getTo().getYaw(),e.getTo().getPitch()));
    }
    @EventHandler public void onJoin(org.bukkit.event.player.PlayerJoinEvent e){
        for(UUID id:vanished){Player v=Bukkit.getPlayer(id);if(v!=null&&!e.getPlayer().equals(v))e.getPlayer().hidePlayer(this,v);}
    }
    private void loadBalances(){
        if(!getDataFolder().exists())getDataFolder().mkdirs();
        var section=getConfig().getConfigurationSection("wallets");
        if(section!=null)for(String key:section.getKeys(false))try{balances.put(UUID.fromString(key),section.getLong(key));}catch(IllegalArgumentException ignored){}
    }
    private void saveBalances(){
        for(var entry:balances.entrySet())getConfig().set("wallets."+entry.getKey(),entry.getValue());
        saveConfig();
    }
    @Override public List<String> onTabComplete(CommandSender s,Command c,String a,String[] args){
        if(!allowed(s))return Collections.emptyList();
        if(args.length==1)return Arrays.asList("help","rich","money","balance","kit","god","fly","vanish","tp","tphere","freeze","unfreeze","invsee","heal","kill", "launch","lightning","boom","speed","strength","jump","feed","give","repair","enchant","radar","xray","find","scanstop","reload");
        return Collections.emptyList();
    }
}
