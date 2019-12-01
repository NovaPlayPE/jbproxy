package net.novaplay.jbproxy.server;

import java.io.File;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import io.netty.channel.Channel;
import lombok.Getter;
import lombok.Setter;
import net.novaplay.jbproxy.client.ProxyClient;
import net.novaplay.jbproxy.command.CommandMap;
import net.novaplay.jbproxy.command.CommandReader;
import net.novaplay.jbproxy.command.CommandSender;
import net.novaplay.jbproxy.command.ConsoleCommandSender;
import net.novaplay.jbproxy.command.defaults.ClientsCommand;
import net.novaplay.jbproxy.command.defaults.HelpCommand;
import net.novaplay.jbproxy.command.defaults.PluginsCommand;
import net.novaplay.jbproxy.command.defaults.StopCommand;
import net.novaplay.jbproxy.config.Config;
import net.novaplay.jbproxy.config.ConfigSection;
import net.novaplay.jbproxy.event.HandlerList;
import net.novaplay.jbproxy.player.Player;
import net.novaplay.jbproxy.plugin.Plugin;
import net.novaplay.jbproxy.plugin.PluginManager;
import net.novaplay.jbproxy.plugin.SimplePluginManager;
import net.novaplay.jbproxy.plugin.java.JavaPluginLoader;
import net.novaplay.networking.IPlayerPacket;
import net.novaplay.networking.server.ProxyConnectPacket;
import net.novaplay.jbproxy.scheduler.ServerScheduler;
import net.novaplay.jbproxy.session.SessionManager;
import net.novaplay.jbproxy.utils.Color;
import net.novaplay.jbproxy.utils.Logger;
import net.novaplay.library.netty.packet.Packet;

public class Server {

	private static Server instance = null;
	public Logger logger = null;
    @Getter
	public String filePath;
    @Getter
    public String dataPath;
    @Getter
    public String pluginPath;
    @Getter
    public ServerScheduler scheduler = null;
    private SessionManager sessionManager = null;
    private PluginManager pluginManager = null;
    private String password = null;
    
    private boolean isRunning = true;
    private boolean isStopped = false;
    private Config properties;
    private Config clientConfig;
    private int tickCounter = 0;
    private long nextTick = 0;
    private ConsoleCommandSender commandSender = new ConsoleCommandSender();
    private CommandMap commandMap = null;
    private ExecutorService pool = Executors.newCachedThreadPool();
    private ExecutorService synchronizedPool = Executors.newSingleThreadExecutor();
    
	private Map<String,Player> players = new HashMap<String,Player>();
	private Map<String, ProxyClient> clients = new HashMap<String,ProxyClient>();
    
	public String getApiVersion() {
		return "1.0.0";
	}
	
	public String getVersion() {
		return "1.0";
	}
	
    public static Server getInstance() {
    	return instance;
    }
    
	public Server(String filePath, String dataPath, String pluginPath) {
		instance = this;
		this.logger = new Logger(dataPath + "server.log");
		this.filePath = filePath;
        if (!new File(pluginPath).exists()) {
            new File(pluginPath).mkdirs();
        }
        this.dataPath = new File(dataPath).getAbsolutePath() + "/";
        this.pluginPath = new File(pluginPath).getAbsolutePath() + "/";
        
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {}));
        Thread.currentThread().setName("JBProxy");
        
        this.logger.info(Color.GREEN + "Loading " + Color.WHITE + "server.properties");
        this.properties = new Config(this.dataPath + "server.properties", Config.PROPERTIES, new ConfigSection() {
            {
                put("proxy-ip", "0.0.0.0");
                put("proxy-port", 9855);
                put("password", "ExamplePassword123");
                put("async-workers", "auto");
            }
        });
        File file = new File(this.dataPath + "clients.yml");
		if (!file.exists()) {
			this.clientConfig = new Config(this.dataPath + "clients.yml", Config.YAML);
			clientConfig.set("servers.Server1.address", "0.0.0.0");
			clientConfig.set("servers.Server1.port", 19132);
			clientConfig.set("servers.Server1.type", "bedrock");
			clientConfig.set("servers.Server2.address", "0.0.0.0");
			clientConfig.set("servers.Server2.port", 25565);
			clientConfig.set("servers.Server2.type", "java");
			clientConfig.save();
		} else {
			this.clientConfig = new Config(this.dataPath + "clients.yml", Config.YAML);
		}
        
        this.commandSender = new ConsoleCommandSender();
        this.commandMap = new CommandMap(this);
        this.pool.execute(() -> {
        	Thread.currentThread().setName("JBProxy-Commands");
        	this.getCommandMap().dispatch(this.commandSender, command -> getLogger().error("Command " + command + " not found"));
        });
        Object poolSize = this.getConfig("async-workers", "auto");
        if (!(poolSize instanceof Integer)) {
            try {
                poolSize = Integer.valueOf((String) poolSize);
            } catch (Exception e) {
                poolSize = Math.max(Runtime.getRuntime().availableProcessors() + 1, 4);
            }
        }
        ServerScheduler.WORKERS = (int) poolSize;
        scheduler = new ServerScheduler();
        
        this.logger.info(Color.GREEN + "Loading all plugins");
        pluginManager = new SimplePluginManager(this);
        pluginManager.registerInterface(JavaPluginLoader.class);
        pluginManager.loadPlugins(this.pluginPath);
        enablePlugins();
        
        this.logger.info(Color.GREEN + "Creating netty server");
        sessionManager = new SessionManager(this, getPort());
        sessionManager.start();
        
        this.properties.save(true);
        
        Runtime.getRuntime().addShutdownHook(new Thread(() ->  {
        	this.isStopped = true;
			
			getLogger().info("Disabling all plugins");
			getPluginManager().disablePlugins();
			
			getLogger().info("Disabling event handlers");
			HandlerList.unregisterAll();
			
			getLogger().info("Stopping all tasks");
			this.scheduler.cancelAllTasks();
			this.scheduler.mainThreadHeartbeat(Integer.MAX_VALUE);
        }));
        registerCommands();
        start();
	}
	
	private void registerCommands() {
		this.getCommandMap().registerCommand(new HelpCommand("help"));
		this.getCommandMap().registerCommand(new StopCommand("stop"));
		this.getCommandMap().registerCommand(new PluginsCommand("plugins"));
		this.getCommandMap().registerCommand(new ClientsCommand("clients"));
	}
	
	public void start() {
		getLogger().info(Color.GREEN + "Server has been started!, Type help for help (?)");
		this.nextTick = System.currentTimeMillis();
		while(this.isRunning) {
			try {
				++this.tickCounter;
				this.scheduler.mainThreadHeartbeat(this.tickCounter);
			} catch(RuntimeException e) {
				getLogger().logException(e);
			}
		}
	}
	
	public void shutdown() {
		this.synchronizedPool.shutdown();
		this.pool.shutdown();
		this.isRunning = false;
		System.exit(0);
	}
	
	public Logger getLogger() {
		return Logger.getLogger();
	}
	
	public PluginManager getPluginManager() {
		return this.pluginManager;
	}
	
	public void enablePlugins() {
		for(Plugin plugin : getPluginManager().getPlugins().values()) {
			if(!plugin.isEnabled()) {
				getPluginManager().enablePlugin(plugin);
			}
		}
	}
	
	public CommandMap getCommandMap() {
		return this.commandMap;
	}
	
	public SessionManager getSessionManager() {
		return sessionManager;
	}
	
	public String getPassword() {
		return this.getPropertyString("proxy-password","ExamplePassword123");
	}
	
	public int getPort() {
		return this.getPropertyInt("proxy-port",9855);
	}
	
	public void handleProxyPackets(Packet packet, Channel channel) {
		if(packet instanceof ProxyConnectPacket) {
			if(getSessionManager().getVerifiedChannels().contains(channel)) {
				return;
			}
			ProxyConnectPacket pk = (ProxyConnectPacket)packet;
			if(pk.address.equalsIgnoreCase("0.0.0.0")) {
				pk.address = "localhost";
			}
			ProxyClient client = getClientByName(pk.serverId);
			if(client == null) {
				this.logger.info(Color.RED + "Unknown client");
				pk.success = false;
				getSessionManager().sendPacket(pk,channel);
				return;
			}
			InetAddress add1;
			InetAddress add2;
			try {
				add1 = InetAddress.getByName(pk.address);
				add2 = InetAddress.getByName(client.getAddress());
				if(add1 != add2) {
					this.logger.info(pk.address + ":" + client.getAddress());
					this.logger.info(Color.RED + "Unallowed adress");
					pk.success = false;
					getSessionManager().sendPacket(pk,channel);
					return;
				}
			} catch(UnknownHostException e) {
				return;
			}
			if(client.getPort() != pk.port) {
				this.logger.info(pk.port + ":" + client.getPort());
				this.logger.info(Color.RED + "Right address and id, but wrong port");
				pk.success = false;
				getSessionManager().sendPacket(pk,channel);
				return;
			}
			if(!this.getPassword().equals(pk.password)) {
				this.logger.info(Color.RED + "Wrong password");
				pk.success = false;
				getSessionManager().sendPacket(packet, channel);
				return;
			}
			if(!client.isOnline()) {
				client.setOnline(true);
			}
			getSessionManager().getVerifiedChannels().add(channel);
			client.setServerChannel(channel);
			this.logger.info(Color.GREEN + "Client " + pk.serverId + " [" + pk.address + ":" + pk.port + "] connected");
			//ProxyClient client = 
			getSessionManager().sendPacket(pk,channel);
			
		} if(getSessionManager().getVerifiedChannels().contains(channel)) {
			return;
		} 
		
		if(packet instanceof IPlayerPacket) {
			players.forEach((id,pla) -> {pla.handleDataPacket(packet);});
		}
	}
	
	public void refreshClients() {
		this.clients = new HashMap<>();
		this.players = new HashMap<>();
		ConfigSection section = this.clientConfig.getSection("servers");
		for(Map.Entry map : section.getAll().entrySet()) {
			String serverId = (String) map.getKey();
			ConfigSection info = (ConfigSection) map.getValue();
			String address = info.getString("address");
			int port = info.getInt("port");
			String type = info.getString("type");
			registerNewClient(serverId,port,address,type);
		}
	}
	
	public ProxyClient registerNewClient(String serverId, int port, String address) {
		return registerNewClient(serverId, port, address, "java");
	}
	
	public ProxyClient registerNewClient(String serverId, int port, String address, String type) {
		if(type.equals("java") || type.equals("bedrock")) {
			ProxyClient client = new ProxyClient(serverId, address, port,type);
			clients.put(serverId,client);
			getLogger().log("Registered new Client " + serverId);
			return client;
		}
		return null;
	}
	
	public Map<String,Player> getOnlinePlayers(){
		return players;
	}
	
	public Player getPlayerByName(String name) {
		name = name.toLowerCase();
		int delta = Integer.MAX_VALUE;
		for(ProxyClient client : getOnlineClients().values()) {
			for(Player pla : client.getOnlinePlayers().values()) {
				if(pla.getName().toLowerCase().startsWith(name)) {
					int curDelta = pla.getName().length() - name.length();
					if(curDelta < delta) {
						delta = curDelta;
						return pla;
					}
				}
			}
		}
		return null;
	}
	
	public boolean isClientOnline(String name) {
		ProxyClient client = getClientByName(name);
		return client.isOnline();
	}
	
	public Map<String,ProxyClient> getOnlineClients(){
		Map<String,ProxyClient> clients = new HashMap<String,ProxyClient>();
		for(Map.Entry<String,ProxyClient> connected : getClients().entrySet()) {
			if(connected.getValue().isOnline()) {
				clients.put(connected.getKey(),connected.getValue());
			}
		}
		return clients;
	}
	
	public Map<String,ProxyClient> getClients(){
		return clients;
	}
	
	public ProxyClient getOnlineClientByName(String name) {
		if(getOnlineClients().containsKey(name)) {
			return getOnlineClients().get(name);
		}
		return null;
	}
	
	public ProxyClient getClientByName(String name) {
		if(clients.containsKey(name)) {
			return clients.get(name);
		}
		return null;
	}
	
	public Object getConfig(String variable) {
        return this.getConfig(variable, null);
    }

    public Object getConfig(String variable, Object defaultValue) {
        Object value = this.properties.get(variable);
        return value == null ? defaultValue : value;
    }
    
    public Object getProperty(String variable) {
        return this.getProperty(variable, null);
    }

    public Object getProperty(String variable, Object defaultValue) {
        return this.properties.exists(variable) ? this.properties.get(variable) : defaultValue;
    }

    public void setPropertyString(String variable, String value) {
        this.properties.set(variable, value);
        this.properties.save();
    }

    public String getPropertyString(String variable) {
        return this.getPropertyString(variable, null);
    }

    public String getPropertyString(String variable, String defaultValue) {
        return this.properties.exists(variable) ? (String) this.properties.get(variable) : defaultValue;
    }

    public int getPropertyInt(String variable) {
        return this.getPropertyInt(variable, null);
    }

    public int getPropertyInt(String variable, Integer defaultValue) {
        return this.properties.exists(variable) ? (!this.properties.get(variable).equals("") ? Integer.parseInt(String.valueOf(this.properties.get(variable))) : defaultValue) : defaultValue;
    }

    public void setPropertyInt(String variable, int value) {
        this.properties.set(variable, value);
        this.properties.save();
    }

    public boolean getPropertyBoolean(String variable) {
        return this.getPropertyBoolean(variable, null);
    }

    public boolean getPropertyBoolean(String variable, Object defaultValue) {
        Object value = this.properties.exists(variable) ? this.properties.get(variable) : defaultValue;
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        switch (String.valueOf(value)) {
            case "on":
            case "true":
            case "1":
            case "yes":
                return true;
        }
        return false;
    }

    public void setPropertyBoolean(String variable, boolean value) {
        this.properties.set(variable, value ? "1" : "0");
        this.properties.save();
    }
	
}
