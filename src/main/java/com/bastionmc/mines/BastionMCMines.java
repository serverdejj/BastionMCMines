package com.bastionmc.mines;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.boss.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public final class BastionMCMines extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    final Map<String,Mine> mines=new LinkedHashMap<>();
    final Map<UUID,Selection> selecting=new HashMap<>();
    final Map<UUID,Edit> editing=new ConcurrentHashMap<>();
    final Map<UUID,BossBar> bars=new HashMap<>();
    final Set<String> resetting=new HashSet<>();
    final Set<String> broadcastAfterReset=new HashSet<>();
    File minesFolder;
    int glowCursor=0;
    String PREFIX;
    static final String WAND="§6§lBASTION §e§lMINAS §7| §fSelector";
    static final List<String> COLOR_NAMES=List.of("WHITE","ORANGE","MAGENTA","LIGHT_BLUE","YELLOW","LIME","PINK","GRAY","LIGHT_GRAY","CYAN","PURPLE","BLUE","BROWN","GREEN","RED","BLACK");
    static final List<Material> COLOR_WOOL=List.of(Material.WHITE_WOOL,Material.ORANGE_WOOL,Material.MAGENTA_WOOL,Material.LIGHT_BLUE_WOOL,Material.YELLOW_WOOL,Material.LIME_WOOL,Material.PINK_WOOL,Material.GRAY_WOOL,Material.LIGHT_GRAY_WOOL,Material.CYAN_WOOL,Material.PURPLE_WOOL,Material.BLUE_WOOL,Material.BROWN_WOOL,Material.GREEN_WOOL,Material.RED_WOOL,Material.BLACK_WOOL);
    static final List<String> COLOR_HEX=List.of("#FFFFFF","#FFAA00","#FF55FF","#55AAFF","#FFFF55","#55FF55","#FF77AA","#555555","#AAAAAA","#00AAAA","#AA00AA","#5555FF","#AA5500","#00AA00","#FF5555","#171717");

    @Override public void onEnable(){
        saveDefaultConfig(); PREFIX=getConfig().getString("prefix","BASTION MC");
        minesFolder=new File(getDataFolder(),"mines"); minesFolder.mkdirs();
        loadMines();
        for(String s:List.of("mina","minas","minareload")) {PluginCommand cmd=getCommand(s);if(cmd!=null){cmd.setExecutor(this);cmd.setTabCompleter(this);}}
        Bukkit.getPluginManager().registerEvents(this,this);
        if(Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI"))new MinePlaceholders().register();
        Bukkit.getScheduler().runTaskTimer(this,this::tick,20L,20L);
        Bukkit.getScheduler().runTaskTimer(this,this::glowTick,10L,10L);
        getLogger().info("BastionMCMines cargado: "+mines.size()+" minas.");
    }
    @Override public void onDisable(){for(BossBar b:bars.values())b.removeAll();for(Mine m:mines.values())save(m);}
    String color(String s){if(s==null)return "";s=s.replace("{prefix}",PREFIX);java.util.regex.Matcher matcher=java.util.regex.Pattern.compile("&#([0-9a-fA-F]{6})").matcher(s);StringBuilder sb=new StringBuilder();while(matcher.find()){String hex=matcher.group(1);StringBuilder rep=new StringBuilder("§x");for(char c:hex.toCharArray())rep.append('§').append(c);matcher.appendReplacement(sb,java.util.regex.Matcher.quoteReplacement(rep.toString()));}matcher.appendTail(sb);return ChatColor.translateAlternateColorCodes('&',sb.toString());}
    String msg(String key){return color(getConfig().getString("messages."+key,key));}
    String fmt(String key,Mine m){return msg(key).replace("{mine}",m.name);}
    void tell(Player p,String s){p.sendMessage(color(s));}
    void syntax(Player p){tell(p,msg("invalid-command"));}
    boolean admin(CommandSender sender){if(!(sender instanceof Player p)){sender.sendMessage("Solo jugadores OP en el servidor.");return false;}if(!p.isOp()){tell(p,msg("no-permission"));return false;}return true;}

    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] a){
        if(!admin(sender))return true;Player p=(Player)sender;
        if(label.equalsIgnoreCase("minareload")||(a.length>0&&a[0].equalsIgnoreCase("reload"))){reloadConfig();PREFIX=getConfig().getString("prefix","BASTION MC");tell(p,"{prefix} &aConfiguración recargada.");return true;}
        if(label.equalsIgnoreCase("minas")){listMenu(p,0);return true;}
        if(a.length==0||a[0].equalsIgnoreCase("help")){tell(p,"&6/mina crear <nombre> <public|private> &7| /mina finish | /mina profundidad <mina> <n> | /mina setspawn <mina> | /mina reset <mina> | /mina eliminar <mina> | /mina info <mina> | /mina listar | /minas menu");return true;}
        String op=a[0].toLowerCase(Locale.ROOT);
        if(op.equals("menu")){listMenu(p,0);return true;}
        if(op.equals("crear")){if(a.length!=3||(!a[2].equalsIgnoreCase("public")&&!a[2].equalsIgnoreCase("private"))){syntax(p);return true;}String key=key(a[1]);if(!key.matches("[a-z0-9_-]{1,32}")||mines.containsKey(key)){tell(p,"{prefix} &cNombre inválido o ya existente.");return true;}Selection s=new Selection(key,a[2].equalsIgnoreCase("private"),p.getWorld().getName());selecting.put(p.getUniqueId(),s);ItemStack wand=new ItemStack(Material.GOLDEN_AXE);ItemMeta meta=wand.getItemMeta();meta.setDisplayName(WAND);meta.setLore(List.of("§7Clic izquierdo: primer punto","§7Clic derecho: siguiente punto","§e/mina finish"));wand.setItemMeta(meta);p.getInventory().addItem(wand);tell(p,msg("selection-start"));return true;}
        if(op.equals("finish")){Selection s=selecting.get(p.getUniqueId());if(s==null||s.points.size()<3){tell(p,"{prefix} &cMarca al menos 3 puntos con el hacha dorada.");return true;}if(!Objects.equals(s.world,p.getWorld().getName())){tell(p,"{prefix} &cDebes estar en el mundo de la selección.");return true;}if(selfCross(s.points)){tell(p,"{prefix} &cEl perímetro se cruza consigo mismo; corrige la selección.");return true;}int depth=getConfig().getInt("selection.default-depth",20);Mine m=new Mine(s.name,s.world,s.priv,s.y,depth);m.points.addAll(s.points);long volume=(long)m.areaEstimate()*depth;if(volume>getConfig().getLong("selection.max-volume",350000)){tell(p,"{prefix} &cSelección demasiado grande: "+volume+" bloques estimados.");return true;}for(Mine other:mines.values()){if(other.world.equals(m.world)&&overlap(m,other)){tell(p,"{prefix} &cSe superpone con otra mina: "+other.name);return true;}}
            mines.put(m.id,m);save(m);selecting.remove(p.getUniqueId());beginReset(m,false);tell(p,fmt("created",m));tell(p,fmt("missing-spawn",m));return true;}
        if(op.equals("listar")){tell(p,"{prefix} &eMinas: &f"+String.join(", ",mines.keySet()));return true;}
        if(a.length<2){syntax(p);return true;}Mine m=mines.get(key(a[1]));if(m==null){tell(p,"{prefix} &cNo existe esa mina.");return true;}
        switch(op){
            case "info" -> tell(p,"{prefix} &e"+m.name+" &7| "+(m.priv?"private":"public")+" | "+m.depth+" bloques | "+m.interval+"s | "+m.points.size()+" puntos");
            case "setspawn" -> {if(!p.getWorld().getName().equals(m.world)){tell(p,"{prefix} &cMundo incorrecto.");break;}if(m.contains(p.getLocation())){tell(p,"{prefix} &cEstablece el spawn FUERA de la mina.");break;}m.spawn=p.getLocation().clone();save(m);tell(p,"{prefix} &aSpawn guardado fuera de la mina.");}
            case "reset" -> {beginReset(m,true);tell(p,"{prefix} &aRegeneración iniciada.");}
            case "profundidad" -> {if(a.length<3){syntax(p);break;}try{int n=Integer.parseInt(a[2]);if(n<1||n>128)throw new NumberFormatException();m.depth=n;save(m);tell(p,"{prefix} &aProfundidad actualizada a "+n+". Se aplicará al siguiente reinicio.");}catch(NumberFormatException ex){tell(p,"{prefix} &cProfundidad válida: 1–128.");}}
            case "eliminar" -> confirmDelete(p,m);
            case "editar" -> manageMenu(p,m);
            default -> syntax(p);
        }return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] a){if(!(sender instanceof Player p)||!p.isOp())return List.of();if(alias.equalsIgnoreCase("minas"))return List.of("menu");if(alias.equalsIgnoreCase("minareload"))return List.of();if(a.length==1)return filter(List.of("crear","finish","menu","reset","eliminar","info","listar","editar","profundidad","setspawn","reload"),a[0]);if(a.length==2&&List.of("reset","eliminar","info","editar","profundidad","setspawn").contains(a[0].toLowerCase()))return filter(new ArrayList<>(mines.keySet()),a[1]);if(a.length==3&&a[0].equalsIgnoreCase("crear"))return filter(List.of("public","private"),a[2]);return List.of();}
    List<String> filter(List<String> list,String prefix){return list.stream().filter(s->s.toLowerCase().startsWith(prefix.toLowerCase())).toList();}
    String key(String s){return s.toLowerCase(Locale.ROOT);}

    static class Point{final int x,z;Point(int x,int z){this.x=x;this.z=z;}}
    static class Selection{String name,world;boolean priv;int y;List<Point> points=new ArrayList<>();Selection(String name,boolean priv,String world){this.name=name;this.priv=priv;this.world=world;}}
    static class Reward{ItemStack item;double chance;Reward(ItemStack i,double c){item=i.clone();item.setAmount(1);chance=c;}}
    static class Mine{
        String id,name,world;boolean priv;int top,depth;long interval=60,remaining=60;int remainingBlocks=0,totalBlocks=0;boolean boss=true,announce=false;Location spawn;String state="Disponible";
        LinkedHashMap<Material,Double> blocks=new LinkedHashMap<>();Map<Material,List<Reward>> rewards=new HashMap<>();Set<Material> replace=new HashSet<>();Map<Material,String> glow=new HashMap<>();List<Point> points=new ArrayList<>();
        Mine(String id,String world,boolean priv,int top,int depth){this.id=id;this.name=id;this.world=world;this.priv=priv;this.top=top;this.depth=depth;blocks.put(Material.STONE,100.0);}
        boolean insideXZ(int x,int z){boolean inside=false;int n=points.size();for(int i=0,j=n-1;i<n;j=i++){Point a=points.get(i),b=points.get(j);if(((a.z>z)!=(b.z>z))&&(x<(double)(b.x-a.x)*(z-a.z)/(b.z-a.z)+a.x))inside=!inside;}return inside;}
        boolean contains(Location l){return l.getWorld()!=null&&world.equals(l.getWorld().getName())&&l.getBlockY()<=top&&l.getBlockY()>top-depth&&insideXZ(l.getBlockX(),l.getBlockZ());}
        int[] bounds(){int minX=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,minZ=Integer.MAX_VALUE,maxZ=Integer.MIN_VALUE;for(Point p:points){minX=Math.min(minX,p.x);maxX=Math.max(maxX,p.x);minZ=Math.min(minZ,p.z);maxZ=Math.max(maxZ,p.z);}return new int[]{minX,maxX,minZ,maxZ};}
        int areaEstimate(){int[] b=bounds();return Math.max(1,(b[1]-b[0]+1)*(b[3]-b[2]+1));}
        int players(){World w=Bukkit.getWorld(world);if(w==null)return 0;int n=0;for(Player p:w.getPlayers())if(contains(p.getLocation()))n++;return n;}
    }
    static boolean selfCross(List<Point> p){int n=p.size();for(int i=0;i<n;i++){Point a=p.get(i),b=p.get((i+1)%n);for(int j=i+1;j<n;j++){if(Math.abs(i-j)==1||(i==0&&j==n-1))continue;Point c=p.get(j),d=p.get((j+1)%n);if(intersects(a,b,c,d))return true;}}return false;}
    static long orient(Point a,Point b,Point c){return (long)(b.x-a.x)*(c.z-a.z)-(long)(b.z-a.z)*(c.x-a.x);}
    static boolean intersects(Point a,Point b,Point c,Point d){return (orient(a,b,c)*orient(a,b,d)<0)&&(orient(c,d,a)*orient(c,d,b)<0);}
    static boolean overlap(Mine a,Mine b){if(a.top<=b.top-b.depth||b.top<=a.top-a.depth)return false;for(Point p:a.points)if(b.insideXZ(p.x,p.z))return true;for(Point p:b.points)if(a.insideXZ(p.x,p.z))return true;int[] x=a.bounds(),y=b.bounds();return x[0]<=y[1]&&x[1]>=y[0]&&x[2]<=y[3]&&x[3]>=y[2]&& a.insideXZ((y[0]+y[1])/2,(y[2]+y[3])/2);}
    Mine at(Location l){for(Mine m:mines.values())if(m.contains(l))return m;return null;}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)public void breakBlock(BlockBreakEvent e){Mine m=at(e.getBlock().getLocation());if(m==null)return;if(resetting.contains(m.id)||!allowed(e.getPlayer(),m)){e.setCancelled(true);return;}e.setCancelled(false);e.setDropItems(false);e.setExpToDrop(0);Material mat=e.getBlock().getType();m.remainingBlocks=Math.max(0,m.remainingBlocks-1);if(!m.replace.contains(mat)){
            Collection<ItemStack> drops=e.getBlock().getDrops(e.getPlayer().getInventory().getItemInMainHand(),e.getPlayer());for(ItemStack item:drops)e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation().add(.5,.5,.5),item);
        }
        List<Reward> options=m.rewards.getOrDefault(mat,List.of());for(Reward r:options){if(ThreadLocalRandom.current().nextDouble(100.0)<r.chance){ItemStack drop=r.item.clone();e.getBlock().getWorld().dropItemNaturally(e.getBlock().getLocation().add(.5,.5,.5),drop);if(r.chance<=getConfig().getDouble("defaults.rare-announcement-threshold-percent",0.01)){String item=drop.hasItemMeta()&&drop.getItemMeta().hasDisplayName()?drop.getItemMeta().getDisplayName():drop.getType().toString();Bukkit.broadcastMessage(msg("reward-rare").replace("{player}",e.getPlayer().getName()).replace("{item}",item).replace("{mine}",m.name).replace("{chance}",String.valueOf(r.chance)));}}}
    }
    boolean allowed(Player p,Mine m){return p.isOp()||!m.priv||p.hasPermission("bastionminas.acceso."+m.id);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)public void place(BlockPlaceEvent e){if(at(e.getBlockPlaced().getLocation())!=null)e.setCancelled(true);}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=false)public void interact(PlayerInteractEvent e){Player p=e.getPlayer();Selection s=selecting.get(p.getUniqueId());if(s==null||!e.hasItem()||!isWand(e.getItem()))return;if(e.getClickedBlock()==null)return;boolean left=e.getAction()==Action.LEFT_CLICK_BLOCK;boolean right=e.getAction()==Action.RIGHT_CLICK_BLOCK;if(!left&&!right)return;e.setCancelled(true);if(!s.world.equals(p.getWorld().getName())){tell(p,"{prefix} &cSelecciona en un solo mundo.");return;}Block b=e.getClickedBlock();if(left){s.points.clear();s.y=b.getY();s.points.add(new Point(b.getX(),b.getZ()));}else{if(s.points.isEmpty()){tell(p,"{prefix} &cPrimero marca el punto inicial con clic izquierdo.");return;}if(s.points.size()>=getConfig().getInt("selection.max-points",256)){tell(p,"{prefix} &cAlcanzaste el límite de puntos.");return;}s.points.add(new Point(b.getX(),b.getZ()));}tell(p,msg("point-added").replace("{count}",String.valueOf(s.points.size())));}
    boolean isWand(ItemStack i){return i!=null&&i.getType()==Material.GOLDEN_AXE&&i.hasItemMeta()&&WAND.equals(i.getItemMeta().getDisplayName());}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void enter(PlayerMoveEvent e){if(e.getTo()==null||e.getFrom().getBlockX()==e.getTo().getBlockX()&&e.getFrom().getBlockY()==e.getTo().getBlockY()&&e.getFrom().getBlockZ()==e.getTo().getBlockZ())return;Mine to=at(e.getTo()),from=at(e.getFrom());if(to!=null&&to!=from&&!allowed(e.getPlayer(),to)){e.setTo(e.getFrom());tell(e.getPlayer(),fmt("access-denied",to));}}
    @EventHandler(priority=EventPriority.HIGHEST,ignoreCancelled=true)public void teleport(PlayerTeleportEvent e){Mine m=at(e.getTo());if(m!=null&&!allowed(e.getPlayer(),m)){e.setCancelled(true);tell(e.getPlayer(),fmt("access-denied",m));}}
    @EventHandler public void explode(EntityExplodeEvent e){e.blockList().removeIf(b->at(b.getLocation())!=null);}
    @EventHandler public void explodeBlock(BlockExplodeEvent e){e.blockList().removeIf(b->at(b.getLocation())!=null);}
    @EventHandler public void quit(PlayerQuitEvent e){selecting.remove(e.getPlayer().getUniqueId());editing.remove(e.getPlayer().getUniqueId());BossBar b=bars.remove(e.getPlayer().getUniqueId());if(b!=null)b.removeAll();}
    @EventHandler public void onWandDamage(org.bukkit.event.player.PlayerItemDamageEvent e){if(isWand(e.getItem()))e.setCancelled(true);}

    void tick(){for(Mine m:new ArrayList<>(mines.values())){if(!resetting.contains(m.id)){m.remaining--;if(m.remaining<=0)beginReset(m,true);}}for(Player p:Bukkit.getOnlinePlayers()){Mine m=at(p.getLocation());BossBar b=bars.get(p.getUniqueId());boolean show=m!=null&&m.boss&&m.remaining<=getConfig().getInt("defaults.bossbar-threshold-seconds",300)&&m.remaining>0&&!resetting.contains(m.id);if(!show){if(b!=null){b.removeAll();bars.remove(p.getUniqueId());}continue;}if(b==null){b=Bukkit.createBossBar("",BarColor.YELLOW,BarStyle.SOLID);b.addPlayer(p);bars.put(p.getUniqueId(),b);}b.setTitle(fmt("bossbar",m).replace("{time}",time(m.remaining)));b.setColor(m.remaining<=10?BarColor.RED:BarColor.YELLOW);b.setProgress(Math.max(.001,Math.min(1.0,(double)m.remaining/Math.min(m.interval,getConfig().getInt("defaults.bossbar-threshold-seconds",300)))));}}
    String time(long sec){return String.format(Locale.ROOT,"%02d:%02d",Math.max(0,sec)/60,Math.max(0,sec)%60);}
    Material nextBlock(Mine m){double total=m.blocks.values().stream().mapToDouble(Double::doubleValue).sum();if(total<=0)return Material.STONE;double n=ThreadLocalRandom.current().nextDouble(total);for(var entry:m.blocks.entrySet()){n-=entry.getValue();if(n<0)return entry.getKey();}return Material.STONE;}
    void beginReset(Mine m,boolean broadcast){if(resetting.contains(m.id))return;World w=Bukkit.getWorld(m.world);if(w==null){getLogger().warning("Mundo no disponible para "+m.id);return;}resetting.add(m.id);m.state="Reiniciando";
        Location dest=m.spawn!=null&&m.spawn.getWorld()!=null&&!m.contains(m.spawn)?m.spawn:w.getSpawnLocation();if(m.contains(dest)){getLogger().warning("Spawn inseguro en "+m.name+". Reconfigura /mina setspawn.");dest=w.getHighestBlockAt(m.bounds()[0]-4,m.bounds()[2]-4).getLocation().add(.5,1,.5);}
        for(Player p:w.getPlayers())if(m.contains(p.getLocation()))p.teleport(dest);
        int[] bounds=m.bounds();int totalDepth=m.depth;m.remainingBlocks=0;m.totalBlocks=0;
        new BukkitRunnable(){int x=bounds[0],z=bounds[2],depth=0;boolean finished=false;
            @Override public void run(){int changed=0,cap=getConfig().getInt("defaults.reset-blocks-per-tick",2500);while(changed<cap&&!finished){if(m.insideXZ(x,z)&&w.getMinHeight()<=m.top-depth&&m.top-depth<w.getMaxHeight()){w.getBlockAt(x,m.top-depth,z).setType(nextBlock(m),false);changed++;m.totalBlocks++;m.remainingBlocks++;}
               depth++;if(depth>=totalDepth){depth=0;z++;if(z>bounds[3]){z=bounds[2];x++;if(x>bounds[1])finished=true;}}}
               if(finished){cancel();resetting.remove(m.id);m.state="Disponible";m.remaining=m.interval;save(m);if(broadcast&&m.announce)Bukkit.broadcastMessage(fmt("reset-complete",m));}
            }
        }.runTaskTimer(this,1L,1L);
    }
    // Particulas de color sobre minerales expuestos, nunca un Glowing que atraviese paredes.
    // Se admite un jugador sobre el suelo (Y=top+1); Mine.contains solo cubre los bloques de piedra.
    void glowTick(){
        if(mines.isEmpty() || Bukkit.getOnlinePlayers().isEmpty()) return;
        int maxParticles=Math.max(0,getConfig().getInt("defaults.max-glow-particles-per-tick",150));
        int radius=Math.max(2,Math.min(16,getConfig().getInt("defaults.glow-view-distance",20)));
        int rendered=0;
        for(Player viewer:Bukkit.getOnlinePlayers()){
            if(rendered>=maxParticles) break;
            Location eye=viewer.getEyeLocation();
            Location feet=viewer.getLocation();
            World world=viewer.getWorld();
            Mine mine=null;
            for(Mine candidate:mines.values()){
                if(candidate.world.equals(world.getName()) && candidate.insideXZ(feet.getBlockX(),feet.getBlockZ())
                   && feet.getBlockY()<=candidate.top+3 && feet.getBlockY()>candidate.top-candidate.depth-2
                   && !candidate.glow.isEmpty()) {mine=candidate;break;}
            }
            if(mine==null) continue;
            int cx=feet.getBlockX(),cy=feet.getBlockY(),cz=feet.getBlockZ();
            int highest=Math.min(mine.top,cy+2);
            int lowest=Math.max(mine.top-mine.depth+1,cy-5);
            // Recorremos puntos cercanos primero, en lugar de probar 180 posiciones al azar
            // en toda la mina; de este modo se ven las vetas presentes en la superficie.
            for(int ring=0;ring<=radius && rendered<maxParticles;ring++){
                for(int dx=-ring;dx<=ring && rendered<maxParticles;dx++){
                    for(int dz=-ring;dz<=ring && rendered<maxParticles;dz++){
                        if(Math.max(Math.abs(dx),Math.abs(dz))!=ring) continue;
                        int x=cx+dx,z=cz+dz;
                        if(!mine.insideXZ(x,z)) continue;
                        for(int y=highest;y>=lowest && rendered<maxParticles;y--){
                            Block block=world.getBlockAt(x,y,z);
                            String hex=mine.glow.get(block.getType());
                            if(hex==null) continue;
                            Color particleColor;
                            try{java.awt.Color parsed=java.awt.Color.decode(hex);particleColor=Color.fromRGB(parsed.getRed(),parsed.getGreen(),parsed.getBlue());}
                            catch(IllegalArgumentException ex){continue;}
                            for(BlockFace face:List.of(BlockFace.UP,BlockFace.NORTH,BlockFace.SOUTH,BlockFace.EAST,BlockFace.WEST,BlockFace.DOWN)){
                                if(block.getRelative(face).getType().isOccluding())continue;
                                double fx=x+.5+face.getModX()*.515, fy=y+.5+face.getModY()*.515, fz=z+.5+face.getModZ()*.515;
                                Location center=new Location(world,fx,fy,fz);
                                Vector dir=center.toVector().subtract(eye.toVector());
                                double len=dir.length();
                                if(len<.01||len>radius+2)continue;
                                var trace=world.rayTraceBlocks(eye,dir,len,FluidCollisionMode.NEVER,true);
                                if(trace!=null&&trace.getHitBlock()!=null&&!trace.getHitBlock().equals(block))continue;
                                // Cuatro esquinas de la cara visible hacen el mineral mas reconocible.
                                for(int corner=0;corner<4 && rendered<maxParticles;corner++){
                                    double offsetA=(corner%2==0?-.32:.32),offsetB=(corner/2==0?-.32:.32);
                                    double px=fx,py=fy,pz=fz;
                                    if(face==BlockFace.UP||face==BlockFace.DOWN){px+=offsetA;pz+=offsetB;}
                                    else if(face==BlockFace.NORTH||face==BlockFace.SOUTH){px+=offsetA;py+=offsetB;}
                                    else{pz+=offsetA;py+=offsetB;}
                                    viewer.spawnParticle(Particle.DUST,px,py,pz,1,0,0,0,0,new Particle.DustOptions(particleColor,1.5f));
                                    rendered++;
                                }
                                break;
                            }
                        }
                    }
                }
            }
        }
    }
    // Menús GUI. Los ítems colocados desde el inventario se COPIAN, no se consumen.
    static class Screen implements InventoryHolder{final String type,mine,block;final int page;final Inventory inv;Screen(String type,String mine,String block,int page,String title){this.type=type;this.mine=mine;this.block=block;this.page=page;this.inv=Bukkit.createInventory(this,54,title);}@Override public Inventory getInventory(){return inv;}}
    ItemStack item(Material mat,String title,String... lore){ItemStack stack=new ItemStack(mat);ItemMeta meta=stack.getItemMeta();meta.setDisplayName(color(title));meta.setLore(Arrays.stream(lore).map(this::color).toList());stack.setItemMeta(meta);return stack;}
    Screen screen(String type,Mine m,String block,int page,String title){return new Screen(type,m==null?"":m.id,block,page,color(title));}
    void put(Screen s,int slot,Material material,String title,String... lore){s.inv.setItem(slot,item(material,title,lore));}
    void open(Player p,Screen s){p.openInventory(s.inv);}
    String title(String key,Mine m,String block){return getConfig().getString("menus."+key,key).replace("{mine}",m==null?"":m.name).replace("{block}",block==null?"":block);}
    void nav(Screen s){put(s,45,Material.ARROW,"&eAnterior");put(s,49,Material.BARRIER,"&cVolver");put(s,53,Material.ARROW,"&eSiguiente");}
    void listMenu(Player p,int page){Screen s=screen("list",null,"",page,getConfig().getString("menus.list-title","Minas"));List<Mine> data=new ArrayList<>(mines.values());for(int i=0;i<45;i++){int idx=page*45+i;if(idx>=data.size())break;Mine m=data.get(idx);put(s,i,m.priv?Material.IRON_PICKAXE:Material.DIAMOND_PICKAXE,"&e"+m.name,"&7Tipo: &f"+(m.priv?"Privada":"Pública"),"&7Reinicio: &f"+time(m.interval),"&aClic para administrar");}nav(s);open(p,s);}
    void manageMenu(Player p,Mine m){Screen s=screen("manage",m,"",0,title("manage-title",m,null));Material[] materials={Material.STONE,Material.CHEST,Material.CLOCK,Material.IRON_DOOR,Material.GLOWSTONE,Material.BELL,Material.COMPASS,Material.COMPARATOR,Material.TNT};String[] names={"Bloques","Recompensas","Reinicios","Acceso","Brillo","Anuncios","Spawn","Opciones","Eliminar"};int[] slots={10,12,14,16,28,30,32,34,40};for(int i=0;i<9;i++)put(s,slots[i],materials[i],"&6"+names[i],"&7Clic para configurar");put(s,49,Material.BARRIER,"&cVolver a las minas");open(p,s);}
    void blocksMenu(Player p,Mine m,int page){Screen s=screen("blocks",m,"",page,title("blocks-title",m,null));List<Material> mats=new ArrayList<>(m.blocks.keySet());for(int i=0;i<45;i++){int idx=page*45+i;if(idx>=mats.size())break;Material mat=mats.get(idx);put(s,i,mat,"&e"+mat,"&7Aparición: &f"+m.blocks.get(mat)+"%","&aClic izquierdo: cambiar porcentaje","&cClic derecho: quitar bloque");}nav(s);put(s,47,Material.HOPPER,"&aAgregar bloque","&7Haz clic en un bloque de tu inventario", "&7(en la parte inferior)");open(p,s);}
    void rewardsBlocksMenu(Player p,Mine m,int page){Screen s=screen("reward-blocks",m,"",page,title("rewards-title",m,null));List<Material> mats=new ArrayList<>(m.blocks.keySet());for(int i=0;i<45;i++){int idx=page*45+i;if(idx>=mats.size())break;Material mat=mats.get(idx);put(s,i,mat,"&e"+mat,"&7Recompensas: &f"+m.rewards.getOrDefault(mat,List.of()).size(),"&7Drops originales: &f"+(m.replace.contains(mat)?"No":"Sí"),"&aClic para editar");}nav(s);open(p,s);}
    void rewardsMenu(Player p,Mine m,Material mat,int page){Screen s=screen("rewards",m,mat.name(),page,"&8Recompensas: "+mat.name());List<Reward> rs=m.rewards.getOrDefault(mat,List.of());for(int i=0;i<45;i++){int idx=page*45+i;if(idx>=rs.size())break;Reward r=rs.get(idx);ItemStack display=r.item.clone();ItemMeta meta=display.getItemMeta();List<String> lore=new ArrayList<>(meta.hasLore()?meta.getLore():List.of());lore.add(color("&7Probabilidad: &e"+r.chance+"%"));lore.add(color("&aIzquierdo: cambiar probabilidad"));lore.add(color("&cDerecho: quitar recompensa"));meta.setLore(lore);display.setItemMeta(meta);s.inv.setItem(i,display);}nav(s);put(s,46,Material.HOPPER,"&aAgregar recompensa","&7Clic en un ítem de tu inventario inferior");put(s,47,m.replace.contains(mat)?Material.REDSTONE_BLOCK:Material.EMERALD_BLOCK,"&eDrops originales: "+(m.replace.contains(mat)?"&cDESACTIVADOS":"&aACTIVADOS"),"&7Clic para alternar");open(p,s);}
    void glowMenu(Player p,Mine m,int page){Screen s=screen("glow",m,"",page,title("glow-title",m,null));List<Material> mats=new ArrayList<>(m.blocks.keySet());for(int i=0;i<45;i++){int idx=page*45+i;if(idx>=mats.size())break;Material mat=mats.get(idx);String hex=m.glow.get(mat);put(s,i,mat,"&e"+mat,"&7Brillo: "+(hex==null?"&cDesactivado":"&aActivado"),"&7Color: &f"+(hex==null?"ninguno":hex),"&aClic izquierdo: agregar brillo / elegir color","&cClic derecho: desactivar brillo");}nav(s);open(p,s);}
    void colorsMenu(Player p,Mine m,Material mat){Screen s=screen("colors",m,mat.name(),0,title("colors-title",m,mat.name()));for(int i=0;i<COLOR_NAMES.size();i++)put(s,10+i+(i/8)*1,COLOR_WOOL.get(i),"&f"+COLOR_NAMES.get(i),"&7Color HEX: &f"+COLOR_HEX.get(i),"&aClic para activar el brillo");put(s,49,Material.BARRIER,"&cVolver");open(p,s);}
    void resetMenu(Player p,Mine m){Screen s=screen("reset",m,"",0,"&8Reinicios: "+m.name);put(s,20,Material.CLOCK,"&6Tiempo: &f"+time(m.interval),"&7Clic para escribir minutos en chat");put(s,22,Material.NETHER_STAR,"&6BossBar: "+(m.boss?"&aActivada":"&cDesactivada"),"&7Clic para cambiar");put(s,24,Material.REDSTONE,"&cReiniciar ahora","&7Expulsa primero a jugadores al spawn");put(s,49,Material.BARRIER,"&cVolver");open(p,s);}
    void accessMenu(Player p,Mine m){Screen s=screen("access",m,"",0,"&8Acceso: "+m.name);put(s,22,m.priv?Material.IRON_DOOR:Material.OAK_DOOR,"&eTipo: &f"+(m.priv?"PRIVATE":"PUBLIC"),"&7Clic para alternar","&7Permiso: bastionminas.acceso."+m.id);put(s,49,Material.BARRIER,"&cVolver");open(p,s);}
    void announceMenu(Player p,Mine m){Screen s=screen("announce",m,"",0,"&8Anuncios: "+m.name);put(s,22,Material.BELL,"&eAnuncio de reinicio: "+(m.announce?"&aACTIVADO":"&cDESACTIVADO"),"&7No hay avisos previos por chat","&7Clic para alternar");put(s,49,Material.BARRIER,"&cVolver");open(p,s);}
    void spawnMenu(Player p,Mine m){Screen s=screen("spawn",m,"",0,"&8Spawn: "+m.name);put(s,22,Material.COMPASS,"&eEstablecer spawn","&7Usa la posición donde estás actualmente","&7Debe estar fuera de la región","&7Estado: "+(m.spawn==null?"&cNo configurado":"&aConfigurado"));put(s,49,Material.BARRIER,"&cVolver");open(p,s);}
    void optionsMenu(Player p,Mine m){Screen s=screen("options",m,"",0,"&8Opciones: "+m.name);put(s,22,Material.DEEPSLATE,"&eProfundidad: &f"+m.depth+" bloques","&7Clic para escribir profundidad en chat");put(s,49,Material.BARRIER,"&cVolver");open(p,s);}
    void confirmDelete(Player p,Mine m){Screen s=screen("confirm",m,"",0,title("confirm-title",m,null));put(s,20,Material.LIME_CONCRETE,"&aCONFIRMAR ELIMINACIÓN","&cNo modifica bloques del terreno");put(s,24,Material.RED_CONCRETE,"&cCANCELAR");open(p,s);}
    void edit(Player p,Mine m,String category,Material material,int index){editing.put(p.getUniqueId(),new Edit(m.id,category,material,index));p.closeInventory();tell(p,msg("chat-prompt"));}
    record Edit(String mine,String type,Material mat,int index){}
    @EventHandler public void chat(AsyncPlayerChatEvent e){Edit ed=editing.remove(e.getPlayer().getUniqueId());if(ed==null)return;e.setCancelled(true);String raw=e.getMessage().trim();Bukkit.getScheduler().runTask(this,()->applyEdit(e.getPlayer(),ed,raw));}
    void applyEdit(Player p,Edit ed,String input){Mine m=mines.get(ed.mine());if(m==null)return;if(input.equalsIgnoreCase("cancelar")){manageMenu(p,m);return;}double n;try{n=Double.parseDouble(input.replace(',','.'));if(!Double.isFinite(n))throw new NumberFormatException();}catch(Exception ex){tell(p,msg("invalid-value"));editing.put(p.getUniqueId(),ed);return;}switch(ed.type()){
        case "blocks"->{if(n<0||n>100){tell(p,msg("invalid-value"));editing.put(p.getUniqueId(),ed);return;}m.blocks.put(ed.mat(),n);save(m);blocksMenu(p,m,0);}
        case "reward"->{if(n<.001||n>100){tell(p,msg("invalid-value"));editing.put(p.getUniqueId(),ed);return;}List<Reward> rs=m.rewards.get(ed.mat());if(rs!=null&&ed.index()<rs.size()){rs.get(ed.index()).chance=n;save(m);}rewardsMenu(p,m,ed.mat(),0);}
        case "reset"->{if(n<1||n>10080||Math.floor(n)!=n){tell(p,msg("invalid-value"));editing.put(p.getUniqueId(),ed);return;}m.interval=(long)n*60;m.remaining=m.interval;save(m);resetMenu(p,m);}
        case "depth"->{if(n<1||n>128||Math.floor(n)!=n){tell(p,msg("invalid-value"));editing.put(p.getUniqueId(),ed);return;}m.depth=(int)n;save(m);optionsMenu(p,m);}
    }tell(p,msg("saved"));}

    @EventHandler public void click(InventoryClickEvent e){if(!(e.getView().getTopInventory().getHolder() instanceof Screen s))return;e.setCancelled(true);if(!(e.getWhoClicked() instanceof Player p)||!p.isOp())return;Mine m=mines.get(s.mine);int raw=e.getRawSlot();boolean right=e.isRightClick();if(raw>=e.getView().getTopInventory().getSize()){
            ItemStack stack=e.getCurrentItem();if(stack==null||stack.getType().isAir()||m==null)return;
            if(s.type.equals("blocks")){Material mat=stack.getType();if(!mat.isBlock()){tell(p,"{prefix} &cSolo bloques.");return;}if(!m.blocks.containsKey(mat))m.blocks.put(mat,0.0);save(m);blocksMenu(p,m,s.page);tell(p,"{prefix} &aBloque agregado. Configura su porcentaje.");}
            if(s.type.equals("rewards")){Material mat=Material.matchMaterial(s.block);if(mat==null)return;m.rewards.computeIfAbsent(mat,k->new ArrayList<>()).add(new Reward(stack,1.0));save(m);rewardsMenu(p,m,mat,s.page);tell(p,"{prefix} &aRecompensa copiada. Ahora configura su porcentaje.");}return;}
        if(raw<0||raw>=54)return;
        if(s.type.equals("list")){if(raw==45&&s.page>0){listMenu(p,s.page-1);return;}if(raw==53){listMenu(p,s.page+1);return;}List<Mine> ms=new ArrayList<>(mines.values());int idx=s.page*45+raw;if(raw<45&&idx<ms.size())manageMenu(p,ms.get(idx));return;}
        if(m==null)return;
        if(raw==49&&!s.type.equals("manage")){if(s.type.equals("colors")){glowMenu(p,m,0);}else if(s.type.equals("rewards")){rewardsBlocksMenu(p,m,0);}else manageMenu(p,m);return;}
        if(s.type.equals("manage")){switch(raw){case 10->blocksMenu(p,m,0);case 12->rewardsBlocksMenu(p,m,0);case 14->resetMenu(p,m);case 16->accessMenu(p,m);case 28->glowMenu(p,m,0);case 30->announceMenu(p,m);case 32->spawnMenu(p,m);case 34->optionsMenu(p,m);case 40->confirmDelete(p,m);case 49->listMenu(p,0);}return;}
        if(s.type.equals("confirm")){if(raw==20){mines.remove(m.id);File file=new File(minesFolder,m.id+".yml");if(!file.delete())getLogger().warning("No se pudo borrar "+file);tell(p,fmt("deleted",m));listMenu(p,0);}else if(raw==24)manageMenu(p,m);return;}
        if(s.type.equals("reset")){if(raw==20)edit(p,m,"reset",null,0);else if(raw==22){m.boss=!m.boss;save(m);resetMenu(p,m);}else if(raw==24){beginReset(m,true);resetMenu(p,m);}return;}
        if(s.type.equals("access")&&raw==22){m.priv=!m.priv;save(m);accessMenu(p,m);return;}
        if(s.type.equals("announce")&&raw==22){m.announce=!m.announce;save(m);announceMenu(p,m);return;}
        if(s.type.equals("spawn")&&raw==22){if(!p.getWorld().getName().equals(m.world)||m.contains(p.getLocation())){tell(p,"{prefix} &cDebes estar fuera de la mina, en el mismo mundo.");return;}m.spawn=p.getLocation().clone();save(m);spawnMenu(p,m);return;}
        if(s.type.equals("options")&&raw==22){edit(p,m,"depth",null,0);return;}
        if(raw==45&&s.page>0){reopenList(p,s,m,s.page-1);return;}if(raw==53){reopenList(p,s,m,s.page+1);return;}
        if(s.type.equals("colors")){Material mat=Material.matchMaterial(s.block);if(mat==null)return;for(int i=0;i<COLOR_NAMES.size();i++){if(raw==10+i+(i/8)){m.glow.put(mat,COLOR_HEX.get(i));save(m);glowMenu(p,m,0);return;}}return;}
        if(raw>=45)return;
        if(s.type.equals("blocks")){List<Material> mats=new ArrayList<>(m.blocks.keySet());int idx=s.page*45+raw;if(idx>=mats.size())return;Material mat=mats.get(idx);if(right){if(m.blocks.size()<=1){tell(p,"{prefix} &cLa mina necesita al menos un bloque.");return;}m.blocks.remove(mat);m.rewards.remove(mat);m.replace.remove(mat);m.glow.remove(mat);save(m);blocksMenu(p,m,s.page);}else edit(p,m,"blocks",mat,0);}
        if(s.type.equals("reward-blocks")){List<Material> mats=new ArrayList<>(m.blocks.keySet());int idx=s.page*45+raw;if(idx<mats.size())rewardsMenu(p,m,mats.get(idx),0);}
        if(s.type.equals("glow")){List<Material> mats=new ArrayList<>(m.blocks.keySet());int idx=s.page*45+raw;if(idx>=mats.size())return;Material mat=mats.get(idx);if(right){m.glow.remove(mat);save(m);glowMenu(p,m,s.page);}else colorsMenu(p,m,mat);}
        if(s.type.equals("rewards")){Material mat=Material.matchMaterial(s.block);if(mat==null)return;if(raw==47){if(!m.replace.add(mat))m.replace.remove(mat);save(m);rewardsMenu(p,m,mat,s.page);return;}List<Reward> rs=m.rewards.getOrDefault(mat,List.of());int idx=s.page*45+raw;if(idx>=rs.size())return;if(right){rs.remove(idx);save(m);rewardsMenu(p,m,mat,s.page);}else edit(p,m,"reward",mat,idx);}
    }
    void reopenList(Player p,Screen s,Mine m,int page){switch(s.type){case "blocks"->blocksMenu(p,m,page);case "reward-blocks"->rewardsBlocksMenu(p,m,page);case "glow"->glowMenu(p,m,page);case "rewards"->rewardsMenu(p,m,Material.matchMaterial(s.block),page);default->manageMenu(p,m);}}
    @EventHandler public void drag(InventoryDragEvent e){if(e.getView().getTopInventory().getHolder() instanceof Screen)e.setCancelled(true);}

    void save(Mine m){YamlConfiguration yaml=new YamlConfiguration();yaml.set("name",m.name);yaml.set("world",m.world);yaml.set("private",m.priv);yaml.set("top",m.top);yaml.set("depth",m.depth);yaml.set("interval",m.interval);yaml.set("remaining",m.remaining);yaml.set("remainingBlocks",m.remainingBlocks);yaml.set("totalBlocks",m.totalBlocks);yaml.set("boss",m.boss);yaml.set("announce",m.announce);yaml.set("spawn",m.spawn);List<String> points=new ArrayList<>();for(Point a:m.points)points.add(a.x+","+a.z);yaml.set("points",points);for(var entry:m.blocks.entrySet())yaml.set("blocks."+entry.getKey().name(),entry.getValue());for(var entry:m.rewards.entrySet()){List<Map<String,Object>> rs=new ArrayList<>();for(Reward r:entry.getValue()){Map<String,Object> data=new HashMap<>();data.put("item",r.item);data.put("chance",r.chance);rs.add(data);}yaml.set("rewards."+entry.getKey().name(),rs);}yaml.set("replace",m.replace.stream().map(Enum::name).toList());for(var entry:m.glow.entrySet())yaml.set("glow."+entry.getKey().name(),entry.getValue());try{yaml.save(new File(minesFolder,m.id+".yml"));}catch(IOException ex){getLogger().severe("No se pudo guardar mina "+m.id+": "+ex.getMessage());}}
    void loadMines(){File[] files=minesFolder.listFiles((dir,name)->name.endsWith(".yml"));if(files==null)return;for(File f:files)try{YamlConfiguration yaml=YamlConfiguration.loadConfiguration(f);String id=f.getName().substring(0,f.getName().length()-4);Mine m=new Mine(id,yaml.getString("world","world"),yaml.getBoolean("private"),yaml.getInt("top"),yaml.getInt("depth",20));m.name=yaml.getString("name",id);m.interval=Math.max(60,yaml.getLong("interval",60));m.remaining=m.interval;m.remainingBlocks=yaml.getInt("remainingBlocks",0);m.totalBlocks=yaml.getInt("totalBlocks",0);m.boss=yaml.getBoolean("boss",true);m.announce=yaml.getBoolean("announce",false);m.spawn=yaml.getLocation("spawn");for(String s:yaml.getStringList("points")){String[] pair=s.split(",");m.points.add(new Point(Integer.parseInt(pair[0]),Integer.parseInt(pair[1])));}m.blocks.clear();ConfigurationSection sec=yaml.getConfigurationSection("blocks");if(sec!=null)for(String k:sec.getKeys(false)){Material mat=Material.matchMaterial(k);if(mat!=null)m.blocks.put(mat,sec.getDouble(k));}if(m.blocks.isEmpty())m.blocks.put(Material.STONE,100.0);ConfigurationSection rw=yaml.getConfigurationSection("rewards");if(rw!=null)for(String k:rw.getKeys(false)){Material mat=Material.matchMaterial(k);if(mat==null)continue;List<Reward> rewards=new ArrayList<>();for(Map<?,?> data:rw.getMapList(k)){Object stack=data.get("item"), chance=data.get("chance");if(stack instanceof ItemStack item)rewards.add(new Reward(item,chance instanceof Number num?num.doubleValue():1.0));}m.rewards.put(mat,rewards);}for(String s:yaml.getStringList("replace")){Material mat=Material.matchMaterial(s);if(mat!=null)m.replace.add(mat);}ConfigurationSection glow=yaml.getConfigurationSection("glow");if(glow!=null)for(String k:glow.getKeys(false)){Material mat=Material.matchMaterial(k);if(mat!=null)m.glow.put(mat,glow.getString(k,"#FFFFFF"));}if(m.points.size()>=3)mines.put(id,m);}catch(Exception ex){getLogger().severe("Error leyendo "+f.getName()+": "+ex.getMessage());}}
    class MinePlaceholders extends PlaceholderExpansion{
        @Override public String getIdentifier(){return "BastionMinas";}@Override public String getAuthor(){return "BASTION MC";}@Override public String getVersion(){return "1.0.0";}@Override public boolean persist(){return true;}@Override public boolean canRegister(){return true;}
        @Override public String onPlaceholderRequest(Player player,String params){for(String prefix:List.of("tiempo_reinicio_","nombre_","jugadores_","bloques_restantes_","porcentaje_","estado_")){if(!params.toLowerCase(Locale.ROOT).startsWith(prefix))continue;Mine m=mines.get(params.substring(prefix.length()).toLowerCase(Locale.ROOT));if(m==null)return "--";switch(prefix){case "tiempo_reinicio_":return time(m.remaining);case "nombre_":return m.name;case "jugadores_":return String.valueOf(m.players());case "estado_":return m.state;case "bloques_restantes_":return String.valueOf(m.remainingBlocks);case "porcentaje_":return m.totalBlocks<=0?"0%":String.format(Locale.ROOT,"%.1f%%",100.0*m.remainingBlocks/m.totalBlocks);}}return null;}
    }
}
