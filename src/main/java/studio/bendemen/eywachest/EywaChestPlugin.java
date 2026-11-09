package studio.bendemen.eywachest;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.Chest;
import org.bukkit.block.DoubleChest;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class EywaChestPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private static final String UPGRADE_TITLE = ChatColor.DARK_AQUA + "EywaChest Upgrades";
    private static final int[] TIER_SECONDS = {300, 180, 60, 30, 10};
    private static final int[] TIER_COSTS = {0, 30, 75, 125, 200};
    private final Map<UUID,Integer> limits=new ConcurrentHashMap<>();
    private final Map<String,ChestData> chests=new ConcurrentHashMap<>();
    private final Map<String,Set<UUID>> trusted=new ConcurrentHashMap<>();
    private final Map<UUID,String> upgradeMenus=new ConcurrentHashMap<>();
    private final Map<String,Long> nextSellAt=new ConcurrentHashMap<>();
    private final Set<String> selling=ConcurrentHashMap.newKeySet();
    private NamespacedKey itemKey;
    private record ChestData(UUID owner,String currency,Set<String> locations,int tier){}
    private record ChestRef(String id,Set<Block> blocks,Inventory inventory){}
    @Override public void onEnable(){
        saveDefaultConfig(); itemKey=new NamespacedKey(this,"eywachest_item");
        Bukkit.getPluginManager().registerEvents(this,this);
        Objects.requireNonNull(getCommand("eywachest")).setExecutor(this);
        loadData();
        Bukkit.getScheduler().runTaskTimer(this,this::sellAll,20L,20L);
    }
    @Override public void onDisable(){saveData();}
    private boolean admin(CommandSender s){return s instanceof ConsoleCommandSender||s.isOp()||s.hasPermission("eywachest.chestadmin");}
    private boolean canGive(CommandSender s){return s instanceof ConsoleCommandSender||s.isOp()||s.hasPermission("eywachest.give");}
    private String color(String s){return ChatColor.translateAlternateColorCodes('&',s);}
    private void msg(CommandSender s,String t){s.sendMessage(color(getConfig().getString("messages.prefix","&8[&bEywaChest&8] "))+color(t));}
    private int parse(String s,int f){try{return Integer.parseInt(s);}catch(Exception e){return f;}}
    private boolean usage(CommandSender s,String t){msg(s,"&c"+t);return true;}
    private ItemStack createItem(){ItemStack i=new ItemStack(Material.CHEST);ItemMeta m=i.getItemMeta();m.setDisplayName(color(getConfig().getString("chests.item-name","&b&lEywaChest")));m.setLore(List.of(color("&7Automatic selling chest"),color("&8EywaMC")));m.getPersistentDataContainer().set(itemKey,PersistentDataType.BYTE,(byte)1);i.setItemMeta(m);return i;}
    @Override public boolean onCommand(CommandSender s,Command c,String label,String[] a){
      if(a.length==0){msg(s,"&f/eywachest give <player> [amount] | upgrade (look at chest) | limit <player> <amount> | info | trust <player> | untrust <player> | currency <id> | reload");return true;}
      switch(a[0].toLowerCase(Locale.ROOT)){
      case "give"->{if(!canGive(s)){msg(s,"&cMissing permission: eywachest.give");return true;}if(a.length<2)return usage(s,"/eywachest give <player> [amount]");Player p=Bukkit.getPlayerExact(a[1]);if(p==null)return usage(s,"Player must be online.");int n=Math.min(64,a.length>2?parse(a[2],1):1);if(n<1)return usage(s,"Invalid amount.");ItemStack i=createItem();i.setAmount(n);p.getInventory().addItem(i).values().forEach(it->p.getWorld().dropItemNaturally(p.getLocation(),it));msg(s,"&aGave "+n+" EywaChest(s) to "+p.getName()+".");}
      case "limit"->{if(!admin(s)){msg(s,"&cMissing permission: eywachest.chestadmin");return true;}if(a.length!=3)return usage(s,"/eywachest limit <player> <amount>");UUID id=Bukkit.getOfflinePlayer(a[1]).getUniqueId();int n=parse(a[2],-1);if(n<0||n>getConfig().getInt("chests.max-limit",100))return usage(s,"Invalid limit.");limits.put(id,n);saveData();msg(s,"&aSet "+a[1]+"'s chest limit to "+n+".");}
      case "upgrade"->{if(!(s instanceof Player p))return usage(s,"Players only.");Block b=p.getTargetBlockExact(6);ChestRef r=b==null?null:findChest(b);if(r==null||!isOwner(r,p.getUniqueId()))return usage(s,"Look at your own EywaChest within 6 blocks.");openUpgradeMenu(p,r);}
      case "info"->{if(!(s instanceof Player p)){msg(s,"&cPlayers only.");return true;}msg(s,"&7EywaChests: &b"+countOwned(p.getUniqueId())+"&7/&b"+limits.getOrDefault(p.getUniqueId(),getConfig().getInt("chests.default-limit",2)));}
      case "trust","untrust"->{if(!(s instanceof Player p)){msg(s,"&cPlayers only.");return true;}if(a.length!=2)return usage(s,"/eywachest "+a[0]+" <player>");Block b=p.getTargetBlockExact(6);ChestRef r=b==null?null:findChest(b);if(r==null||!isOwner(r,p.getUniqueId()))return usage(s,"Look at your own EywaChest within 6 blocks.");UUID other=Bukkit.getOfflinePlayer(a[1]).getUniqueId();if(other.equals(p.getUniqueId()))return usage(s,"You cannot trust yourself.");Set<UUID> set=trusted.computeIfAbsent(r.id(),k->ConcurrentHashMap.newKeySet());if(a[0].equalsIgnoreCase("trust"))set.add(other);else set.remove(other);saveData();msg(s,"&a"+a[1]+(a[0].equalsIgnoreCase("trust")?" can now access this chest.":" was removed from this chest."));}
      case "currency"->{if(!(s instanceof Player p))return usage(s,"Players only.");if(a.length!=2)return usage(s,"/eywachest currency <currency-id> (look at chest)");Block b=p.getTargetBlockExact(6);ChestRef r=b==null?null:findChest(b);if(r==null||!isOwner(r,p.getUniqueId()))return usage(s,"Look at your own EywaChest within 6 blocks.");List<String> allowed=getConfig().getStringList("economy.allowed-currencies");if(!allowed.isEmpty()&&!allowed.contains(a[1]))return usage(s,"Currency is not allowed in config.yml.");ChestData d=getData(r);chests.put(r.id(),new ChestData(d.owner(),a[1],d.locations(),d.tier()));saveData();msg(s,"&aThis EywaChest now uses currency: "+a[1]);}
      case "reload"->{if(!admin(s))return usage(s,"No permission.");reloadConfig();msg(s,"&aConfig reloaded.");}
      default->msg(s,"&cUnknown subcommand.");}return true;
    }
    private int countOwned(UUID id){return(int)chests.values().stream().filter(d->d.owner().equals(id)).count();}
    private boolean isOwner(ChestRef r,UUID id){ChestData d=getData(r);return d!=null&&d.owner().equals(id);}
    private boolean allowed(ChestData d,String key,UUID p){return d.owner().equals(p)||trusted.getOrDefault(key,Set.of()).contains(p);}
    private ChestRef findChest(Block b){if(!(b.getState() instanceof Chest chest))return null;Set<Block> blocks=new HashSet<>();blocks.add(b);Inventory inv=chest.getBlockInventory();if(chest.getInventory().getHolder() instanceof DoubleChest dc){if(dc.getLeftSide() instanceof Chest l)blocks.add(l.getBlock());if(dc.getRightSide() instanceof Chest r)blocks.add(r.getBlock());inv=dc.getInventory();}String id=blocks.stream().map(this::key).sorted().reduce((x,y)->x+"|"+y).orElse("");return new ChestRef(id,blocks,inv);}
    private String key(Block b){return b.getWorld().getUID()+":"+b.getX()+":"+b.getY()+":"+b.getZ();}
    private void register(Block b,Player p){ChestRef r=findChest(b);if(r==null)return;ChestData existing=getData(r);Set<String> locations=r.blocks().stream().map(this::key).collect(java.util.stream.Collectors.toSet());if(existing!=null){chests.put(r.id(),new ChestData(existing.owner(),existing.currency(),locations,existing.tier()));saveData();if(existing.owner().equals(p.getUniqueId()))msg(p,"&aEywaChest bijgewerkt. Dubbele chests tellen als 2.");else msg(p,"&eDeze chest is gekoppeld aan de bestaande EywaChest-eigenaar.");return;}chests.put(r.id(),new ChestData(p.getUniqueId(),getConfig().getString("economy.default-currency","coins"),locations,1));saveData();msg(p,"&aEywaChest placed. Look at it and use /eywachest trust <player>.");}
    private ChestData getData(ChestRef r){ChestData d=chests.get(r.id());if(d!=null)return d;Set<String> keys=r.blocks().stream().map(this::key).collect(java.util.stream.Collectors.toSet());for(var entry:new ArrayList<>(chests.entrySet())){ChestData old=entry.getValue();if(!Collections.disjoint(old.locations(),keys)){ChestData migrated=new ChestData(old.owner(),old.currency(),keys,old.tier());chests.remove(entry.getKey());chests.put(r.id(),migrated);nextSellAt.remove(entry.getKey());return migrated;}}return null;}
    private ItemStack menuItem(Material material,String name,List<String> lore){ItemStack item=new ItemStack(material);ItemMeta meta=item.getItemMeta();meta.setDisplayName(color(name));meta.setLore(lore.stream().map(this::color).toList());item.setItemMeta(meta);return item;}
    private void openUpgradeMenu(Player p,ChestRef r){
        ChestData d=getData(r);if(d==null)return;Inventory inv=Bukkit.createInventory(null,27,UPGRADE_TITLE);int tier=Math.max(1,Math.min(5,d.tier()));
        inv.setItem(11,menuItem(Material.BOOK,"&bHuidige tier: &f"+tier,List.of("&7Verkoopt elke &f"+formatTime(TIER_SECONDS[tier-1]),"&7Chestgrootte: &f"+r.blocks().size()+"x")));
        if(tier<5){int next=tier+1;long price=(long)TIER_COSTS[next-1]*r.blocks().size();inv.setItem(15,menuItem(Material.EMERALD,"&aUpgrade naar tier "+next,List.of("&7Prijs: &e"+price+" Moonshards","&7Verkoopt elke &f"+formatTime(TIER_SECONDS[next-1]),"&eKlik om te upgraden")));}
        else inv.setItem(15,menuItem(Material.NETHER_STAR,"&6Maximale tier bereikt",List.of("&7Je EywaChest heeft de hoogste tier.")));
        inv.setItem(13,menuItem(Material.CLOCK,"&bEywaChest tiers",List.of("&7Tier 1: 5 minuten","&7Tier 2: 3 minuten","&7Tier 3: 1 minuut","&7Tier 4: 30 seconden","&7Tier 5: 10 seconden","&8Dubbele chest = dubbele upgradeprijs")));
        upgradeMenus.put(p.getUniqueId(),r.id());p.openInventory(inv);
    }
    private String formatTime(int seconds){return seconds>=60?(seconds/60)+" minuut"+(seconds/60==1?"":"en"):seconds+" seconden";}
    @EventHandler public void onUpgradeMenuClick(InventoryClickEvent e){
        if(!(e.getWhoClicked() instanceof Player p)||!upgradeMenus.containsKey(p.getUniqueId()))return;if(!UPGRADE_TITLE.equals(e.getView().getTitle()))return;e.setCancelled(true);if(e.getRawSlot()!=15)return;
        String id=upgradeMenus.get(p.getUniqueId());ChestData d=chests.get(id);if(d==null){p.closeInventory();upgradeMenus.remove(p.getUniqueId());msg(p,"&cDeze EywaChest bestaat niet meer.");return;}if(d.tier()>=5){msg(p,"&eDeze EywaChest is al tier 5.");return;}
        int next=d.tier()+1;long price=(long)TIER_COSTS[next-1]*d.locations().size();if(!withdrawMoonshards(p,price)){msg(p,"&cJe hebt niet genoeg Moonshards of ExcellentEconomy is niet beschikbaar.");return;}
        chests.put(id,new ChestData(d.owner(),d.currency(),d.locations(),next));nextSellAt.put(id,System.currentTimeMillis()+TIER_SECONDS[next-1]*1000L);saveData();msg(p,"&aEywaChest geüpgraded naar tier "+next+" voor "+price+" Moonshards!");String firstLocation=d.locations().iterator().next();p.closeInventory();Bukkit.getScheduler().runTask(this,()->{Block b=resolveBlock(firstLocation);ChestRef updated=b==null?null:findChest(b);if(updated!=null)openUpgradeMenu(p,updated);});
    }
    @EventHandler public void onUpgradeMenuClose(InventoryCloseEvent e){if(e.getView().getTitle().equals(UPGRADE_TITLE))upgradeMenus.remove(e.getPlayer().getUniqueId());}
    private Block resolveBlock(String key){String[] v=key.split(":");if(v.length!=4)return null;try{World w=Bukkit.getWorld(UUID.fromString(v[0]));return w==null?null:w.getBlockAt(Integer.parseInt(v[1]),Integer.parseInt(v[2]),Integer.parseInt(v[3]));}catch(Exception ex){return null;}}
    private boolean withdrawMoonshards(Player player,double amount){
        try{Plugin economy=Bukkit.getPluginManager().getPlugin("ExcellentEconomy");if(economy==null||!economy.isEnabled())return false;Object api=economy.getClass().getMethod("getAPI").invoke(economy);String currencyId=getConfig().getString("economy.moonshards-currency","moonshards");Object currency=api.getClass().getMethod("getCurrency",String.class).invoke(api,currencyId);if(currency==null)return false;
            for(Method method:api.getClass().getMethods())if(method.getName().equals("withdraw")&&method.getParameterCount()==3&&method.getParameterTypes()[0].isAssignableFrom(Player.class)&&method.getParameterTypes()[1].isInstance(currency)&&method.getParameterTypes()[2]==double.class)return Boolean.TRUE.equals(method.invoke(api,player,currency,amount));
        }catch(Exception ex){getLogger().warning("Could not withdraw Moonshards: "+ex.getMessage());}return false;
    }
    @EventHandler public void onPlace(BlockPlaceEvent e){ItemStack i=e.getItemInHand();if(!i.hasItemMeta()||i.getItemMeta().getPersistentDataContainer().get(itemKey,PersistentDataType.BYTE)==null)return;Player p=e.getPlayer();if(!p.hasPermission("eywachest.use")){e.setCancelled(true);msg(p,"&cMissing permission: eywachest.use");return;}if(countOwned(p.getUniqueId())>=limits.getOrDefault(p.getUniqueId(),getConfig().getInt("chests.default-limit",2))){e.setCancelled(true);msg(p,"&cYou reached your EywaChest limit.");return;}Bukkit.getScheduler().runTask(this,()->register(e.getBlockPlaced(),p));}
    @EventHandler public void onInteract(PlayerInteractEvent e){if(e.getClickedBlock()==null)return;ChestRef r=findChest(e.getClickedBlock());if(r==null)return;ChestData d=getData(r);if(d!=null&&!allowed(d,r.id(),e.getPlayer().getUniqueId())){e.setCancelled(true);msg(e.getPlayer(),"&cYou do not have access to this EywaChest.");}}
    @EventHandler public void onOpen(InventoryOpenEvent e){if(!(e.getPlayer() instanceof Player p)||!(e.getInventory().getHolder() instanceof Chest c))return;ChestRef r=findChest(c.getBlock());if(r==null)return;ChestData d=getData(r);if(d!=null&&!allowed(d,r.id(),p.getUniqueId()))e.setCancelled(true);}
    @EventHandler public void onBreak(BlockBreakEvent e){ChestRef r=findChest(e.getBlock());if(r==null)return;ChestData d=getData(r);if(d==null)return;if(!d.owner().equals(e.getPlayer().getUniqueId())&&!admin(e.getPlayer())){e.setCancelled(true);msg(e.getPlayer(),"&cOnly the owner can break this EywaChest.");return;}chests.values().removeIf(v->v.locations().equals(r.blocks().stream().map(this::key).collect(java.util.stream.Collectors.toSet())));saveData();}
    @EventHandler public void onExplode(EntityExplodeEvent e){if(getConfig().getBoolean("chests.protect-from-explosions",true))e.blockList().removeIf(b->{ChestRef r=findChest(b);return r!=null&&getData(r)!=null;});}
    @EventHandler public void onMove(InventoryMoveItemEvent e){if(!getConfig().getBoolean("chests.prevent-hoppers",true))return;InventoryHolder h=e.getDestination().getHolder();if(h instanceof Chest c){ChestRef r=findChest(c.getBlock());if(r!=null&&getData(r)!=null)e.setCancelled(true);}}
    private void sellAll(){long now=System.currentTimeMillis();for(var entry:new ArrayList<>(chests.entrySet())){String id=entry.getKey();ChestData data=entry.getValue();int tier=Math.max(1,Math.min(5,data.tier()));long interval=TIER_SECONDS[tier-1]*1000L;long due=nextSellAt.computeIfAbsent(id,k->now+interval);if(now<due||selling.contains(id))continue;nextSellAt.put(id,now+interval);Player owner=Bukkit.getPlayer(data.owner());if(owner==null)continue;String location=data.locations().stream().findFirst().orElse(null);Block block=location==null?null:resolveBlock(location);ChestRef chest=block==null?null:findChest(block);if(chest==null||chest.inventory().isEmpty())continue;if(!selling.add(id))continue;try{Plugin shop=Bukkit.getPluginManager().getPlugin("ExcellentShop");if(shop==null||!shop.isEnabled()){selling.remove(id);continue;}Object module=shop.getClass().getMethod("getVirtualShop").invoke(shop);if(module==null){selling.remove(id);continue;}Class<?> callbackType=Class.forName("su.nightexpress.excellentshop.shop.transaction.TransactionCallback");Object callback=Proxy.newProxyInstance(callbackType.getClassLoader(),new Class<?>[]{callbackType},(proxy,method,args)->{if(method.getName().equals("accept")&&args!=null&&args.length==1){Object completed=args[0];boolean success=Boolean.TRUE.equals(completed.getClass().getMethod("successful").invoke(completed));if(success)getLogger().fine("EywaChest sale completed for "+owner.getName()+" ("+id+").");selling.remove(id);}return null;});Method sellMethod=module.getClass().getMethod("sellAll",Player.class,Inventory.class,boolean.class,callbackType);sellMethod.invoke(module,owner,chest.inventory(),true,callback);}catch(Exception ex){selling.remove(id);getLogger().warning("ExcellentShop autosell failed for chest "+id+": "+ex.getClass().getSimpleName()+": "+ex.getMessage());}}}
    private void loadData(){java.io.File f=new java.io.File(getDataFolder(),"data.yml");if(!f.exists())return;var y=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);var cs=y.getConfigurationSection("chests");if(cs!=null)for(String k:cs.getKeys(false)){String p="chests."+k;chests.put(k,new ChestData(UUID.fromString(y.getString(p+".owner")),y.getString(p+".currency","coins"),new HashSet<>(y.getStringList(p+".locations")),Math.max(1,Math.min(5,y.getInt(p+".tier",1)))));}var ls=y.getConfigurationSection("limits");if(ls!=null)for(String k:ls.getKeys(false))limits.put(UUID.fromString(k),ls.getInt(k));var ts=y.getConfigurationSection("trusted");if(ts!=null)for(String k:ts.getKeys(false)){Set<UUID> ids=new HashSet<>();for(String v:y.getStringList("trusted."+k))ids.add(UUID.fromString(v));trusted.put(k,ids);}}
    private void saveData(){var y=new org.bukkit.configuration.file.YamlConfiguration();for(var e:chests.entrySet()){String p="chests."+e.getKey();y.set(p+".owner",e.getValue().owner().toString());y.set(p+".currency",e.getValue().currency());y.set(p+".locations",new ArrayList<>(e.getValue().locations()));y.set(p+".tier",e.getValue().tier());}limits.forEach((id,n)->y.set("limits."+id,n));trusted.forEach((id,ids)->y.set("trusted."+id,ids.stream().map(UUID::toString).toList()));try{getDataFolder().mkdirs();y.save(new java.io.File(getDataFolder(),"data.yml"));}catch(java.io.IOException ex){getLogger().severe("Failed saving data: "+ex.getMessage());}}
}
