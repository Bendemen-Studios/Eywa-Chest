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
import org.bukkit.plugin.java.JavaPlugin;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class EywaChestPlugin extends JavaPlugin implements Listener, CommandExecutor {
    private final Map<UUID,Integer> limits=new ConcurrentHashMap<>();
    private final Map<String,ChestData> chests=new ConcurrentHashMap<>();
    private final Map<String,Set<UUID>> trusted=new ConcurrentHashMap<>();
    private NamespacedKey itemKey;
    private record ChestData(UUID owner,String currency,Set<String> locations){}
    private record ChestRef(String id,Set<Block> blocks,Inventory inventory){}
    @Override public void onEnable(){saveDefaultConfig();itemKey=new NamespacedKey(this,"eywachest_item");Bukkit.getPluginManager().registerEvents(this,this);Objects.requireNonNull(getCommand("eywachest")).setExecutor(this);loadData();long ticks=Math.max(1,getConfig().getLong("chests.autosell-interval-seconds",10))*20L;Bukkit.getScheduler().runTaskTimer(this,this::sellAll,ticks,ticks);}
    @Override public void onDisable(){saveData();}
    private boolean admin(CommandSender s){return s instanceof ConsoleCommandSender||s.isOp()||s.hasPermission("eywachest.chestadmin");}
    private boolean canGive(CommandSender s){return s instanceof ConsoleCommandSender||s.isOp()||s.hasPermission("eywachest.give");}
    private String color(String s){return ChatColor.translateAlternateColorCodes('&',s);}
    private void msg(CommandSender s,String t){s.sendMessage(color(getConfig().getString("messages.prefix","&8[&bEywaChest&8] "))+color(t));}
    private int parse(String s,int f){try{return Integer.parseInt(s);}catch(Exception e){return f;}}
    private boolean usage(CommandSender s,String t){msg(s,"&c"+t);return true;}
    private ItemStack createItem(){ItemStack i=new ItemStack(Material.CHEST);ItemMeta m=i.getItemMeta();m.setDisplayName(color(getConfig().getString("chests.item-name","&b&lEywaChest")));m.setLore(List.of(color("&7Automatic selling chest"),color("&8EywaMC")));m.getPersistentDataContainer().set(itemKey,PersistentDataType.BYTE,(byte)1);i.setItemMeta(m);return i;}
    @Override public boolean onCommand(CommandSender s,Command c,String label,String[] a){if(a.length==0){msg(s,"&f/eywachest give <player> [amount] | limit <player> <amount> | upgrade <player> <extra> | info | trust <player> | untrust <player> | currency <id> | reload");return true;}
      switch(a[0].toLowerCase(Locale.ROOT)){
      case "give"->{if(!canGive(s)){msg(s,"&cMissing permission: eywachest.give");return true;}if(a.length<2)return usage(s,"/eywachest give <player> [amount]");Player p=Bukkit.getPlayerExact(a[1]);if(p==null)return usage(s,"Player must be online.");int n=Math.min(64,a.length>2?parse(a[2],1):1);if(n<1)return usage(s,"Invalid amount.");ItemStack i=createItem();i.setAmount(n);p.getInventory().addItem(i).values().forEach(it->p.getWorld().dropItemNaturally(p.getLocation(),it));msg(s,"&aGave "+n+" EywaChest(s) to "+p.getName()+".");}
      case "limit"->{if(!admin(s)){msg(s,"&cMissing permission: eywachest.chestadmin");return true;}if(a.length!=3)return usage(s,"/eywachest limit <player> <amount>");UUID id=Bukkit.getOfflinePlayer(a[1]).getUniqueId();int n=parse(a[2],-1);if(n<0||n>getConfig().getInt("chests.max-limit",100))return usage(s,"Invalid limit.");limits.put(id,n);saveData();msg(s,"&aSet "+a[1]+"'s chest limit to "+n+".");}
      case "upgrade"->{if(!admin(s)){msg(s,"&cMissing permission: eywachest.chestadmin");return true;}if(a.length!=3)return usage(s,"/eywachest upgrade <player> <extra>");UUID id=Bukkit.getOfflinePlayer(a[1]).getUniqueId();int n=parse(a[2],-1);if(n<1)return usage(s,"Extra limit must be at least 1.");int next=Math.min(getConfig().getInt("chests.max-limit",100),limits.getOrDefault(id,getConfig().getInt("chests.default-limit",2))+n);limits.put(id,next);saveData();msg(s,"&aIncreased "+a[1]+"'s limit to "+next+".");}
      case "info"->{if(!(s instanceof Player p)){msg(s,"&cPlayers only.");return true;}msg(s,"&7EywaChests: &b"+countOwned(p.getUniqueId())+"&7/&b"+limits.getOrDefault(p.getUniqueId(),getConfig().getInt("chests.default-limit",2)));}
      case "trust","untrust"->{if(!(s instanceof Player p)){msg(s,"&cPlayers only.");return true;}if(a.length!=2)return usage(s,"/eywachest "+a[0]+" <player>");Block b=p.getTargetBlockExact(6);ChestRef r=b==null?null:findChest(b);if(r==null||!isOwner(r,p.getUniqueId()))return usage(s,"Look at your own EywaChest within 6 blocks.");UUID other=Bukkit.getOfflinePlayer(a[1]).getUniqueId();if(other.equals(p.getUniqueId()))return usage(s,"You cannot trust yourself.");Set<UUID> set=trusted.computeIfAbsent(r.id(),k->ConcurrentHashMap.newKeySet());if(a[0].equalsIgnoreCase("trust"))set.add(other);else set.remove(other);saveData();msg(s,"&a"+a[1]+(a[0].equalsIgnoreCase("trust")?" can now access this chest.":" was removed from this chest."));}
      case "currency"->{if(!(s instanceof Player p))return usage(s,"Players only.");if(a.length!=2)return usage(s,"/eywachest currency <currency-id> (look at chest)");Block b=p.getTargetBlockExact(6);ChestRef r=b==null?null:findChest(b);if(r==null||!isOwner(r,p.getUniqueId()))return usage(s,"Look at your own EywaChest within 6 blocks.");List<String> allowed=getConfig().getStringList("economy.allowed-currencies");if(!allowed.isEmpty()&&!allowed.contains(a[1]))return usage(s,"Currency is not allowed in config.yml.");ChestData d=getData(r);chests.put(r.id(),new ChestData(d.owner(),a[1],d.locations()));saveData();msg(s,"&aThis EywaChest now uses currency: "+a[1]);}
      case "reload"->{if(!admin(s))return usage(s,"No permission.");reloadConfig();msg(s,"&aConfig reloaded.");}
      default->msg(s,"&cUnknown subcommand.");}return true;}
    private int countOwned(UUID id){return(int)chests.values().stream().filter(d->d.owner().equals(id)).count();}
    private boolean isOwner(ChestRef r,UUID id){ChestData d=getData(r);return d!=null&&d.owner().equals(id);}
    private boolean allowed(ChestData d,String key,UUID p){return d.owner().equals(p)||trusted.getOrDefault(key,Set.of()).contains(p);}
    private ChestRef findChest(Block b){if(!(b.getState() instanceof Chest chest))return null;Set<Block> blocks=new HashSet<>();blocks.add(b);Inventory inv=chest.getBlockInventory();if(chest.getInventory().getHolder() instanceof DoubleChest dc){if(dc.getLeftSide() instanceof Chest l)blocks.add(l.getBlock());if(dc.getRightSide() instanceof Chest r)blocks.add(r.getBlock());inv=dc.getInventory();}String id=blocks.stream().map(this::key).sorted().reduce((x,y)->x+"|"+y).orElse("");return new ChestRef(id,blocks,inv);}
    private String key(Block b){return b.getWorld().getUID()+":"+b.getX()+":"+b.getY()+":"+b.getZ();}
    private void register(Block b,Player p){ChestRef r=findChest(b);if(r==null)return;for(Block part:r.blocks()){ChestRef prev=findChest(part);if(prev!=null&&chests.containsKey(prev.id())&&!prev.id().equals(r.id())){msg(p,"&cCannot merge an unrelated chest into an EywaChest.");return;}}chests.put(r.id(),new ChestData(p.getUniqueId(),getConfig().getString("economy.default-currency","coins"),r.blocks().stream().map(this::key).collect(java.util.stream.Collectors.toSet())));saveData();msg(p,"&aEywaChest placed. Look at it and use /eywachest trust <player>.");}
    private ChestData getData(ChestRef r){ChestData d=chests.get(r.id());if(d!=null)return d;Set<String> keys=r.blocks().stream().map(this::key).collect(java.util.stream.Collectors.toSet());return chests.values().stream().filter(x->x.locations().equals(keys)).findFirst().orElse(null);}
    @EventHandler public void onPlace(BlockPlaceEvent e){ItemStack i=e.getItemInHand();if(!i.hasItemMeta()||i.getItemMeta().getPersistentDataContainer().get(itemKey,PersistentDataType.BYTE)==null)return;Player p=e.getPlayer();if(!p.hasPermission("eywachest.use")){e.setCancelled(true);msg(p,"&cMissing permission: eywachest.use");return;}if(countOwned(p.getUniqueId())>=limits.getOrDefault(p.getUniqueId(),getConfig().getInt("chests.default-limit",2))){e.setCancelled(true);msg(p,"&cYou reached your EywaChest limit.");return;}Bukkit.getScheduler().runTask(this,()->register(e.getBlockPlaced(),p));}
    @EventHandler public void onInteract(PlayerInteractEvent e){if(e.getClickedBlock()==null)return;ChestRef r=findChest(e.getClickedBlock());if(r==null)return;ChestData d=getData(r);if(d!=null&&!allowed(d,r.id(),e.getPlayer().getUniqueId())){e.setCancelled(true);msg(e.getPlayer(),"&cYou do not have access to this EywaChest.");}}
    @EventHandler public void onOpen(InventoryOpenEvent e){if(!(e.getPlayer() instanceof Player p)||!(e.getInventory().getHolder() instanceof Chest c))return;ChestRef r=findChest(c.getBlock());if(r==null)return;ChestData d=getData(r);if(d!=null&&!allowed(d,r.id(),p.getUniqueId()))e.setCancelled(true);}
    @EventHandler public void onBreak(BlockBreakEvent e){ChestRef r=findChest(e.getBlock());if(r==null)return;ChestData d=getData(r);if(d==null)return;if(!d.owner().equals(e.getPlayer().getUniqueId())&&!admin(e.getPlayer())){e.setCancelled(true);msg(e.getPlayer(),"&cOnly the owner can break this EywaChest.");return;}chests.values().removeIf(v->v.locations().equals(r.blocks().stream().map(this::key).collect(java.util.stream.Collectors.toSet())));saveData();}
    @EventHandler public void onExplode(EntityExplodeEvent e){if(getConfig().getBoolean("chests.protect-from-explosions",true))e.blockList().removeIf(b->{ChestRef r=findChest(b);return r!=null&&getData(r)!=null;});}
    @EventHandler public void onMove(InventoryMoveItemEvent e){if(!getConfig().getBoolean("chests.prevent-hoppers",true))return;InventoryHolder h=e.getDestination().getHolder();if(h instanceof Chest c){ChestRef r=findChest(c.getBlock());if(r!=null&&getData(r)!=null)e.setCancelled(true);}}
    private void sellAll(){/* Selling requires the exact installed ExcellentShop v5 transaction API; do not remove items until its transaction result can be verified. */}
    private void loadData(){java.io.File f=new java.io.File(getDataFolder(),"data.yml");if(!f.exists())return;var y=org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);var cs=y.getConfigurationSection("chests");if(cs!=null)for(String k:cs.getKeys(false)){String p="chests."+k;chests.put(k,new ChestData(UUID.fromString(y.getString(p+".owner")),y.getString(p+".currency","coins"),new HashSet<>(y.getStringList(p+".locations"))));}var ls=y.getConfigurationSection("limits");if(ls!=null)for(String k:ls.getKeys(false))limits.put(UUID.fromString(k),ls.getInt(k));var ts=y.getConfigurationSection("trusted");if(ts!=null)for(String k:ts.getKeys(false)){Set<UUID> ids=new HashSet<>();for(String v:ts.getStringList(k))ids.add(UUID.fromString(v));trusted.put(k,ids);}}
    private void saveData(){var y=new org.bukkit.configuration.file.YamlConfiguration();for(var e:chests.entrySet()){String p="chests."+e.getKey();y.set(p+".owner",e.getValue().owner().toString());y.set(p+".currency",e.getValue().currency());y.set(p+".locations",new ArrayList<>(e.getValue().locations()));}limits.forEach((id,n)->y.set("limits."+id,n));trusted.forEach((id,ids)->y.set("trusted."+id,ids.stream().map(UUID::toString).toList()));try{getDataFolder().mkdirs();y.save(new java.io.File(getDataFolder(),"data.yml"));}catch(java.io.IOException ex){getLogger().severe("Failed saving data: "+ex.getMessage());}}
}
