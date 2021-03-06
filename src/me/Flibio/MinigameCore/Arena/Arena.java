package me.Flibio.MinigameCore.Arena;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.spongepowered.api.Game;
import org.spongepowered.api.data.key.Keys;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.entity.living.player.gamemode.GameModes;
import org.spongepowered.api.event.Listener;
import org.spongepowered.api.event.network.ClientConnectionEvent;
import org.spongepowered.api.service.scheduler.Task;
import org.spongepowered.api.world.Location;
import org.spongepowered.api.world.World;

import com.google.common.base.Optional;

public class Arena {
	
	public enum ArenaState {
		LOBBY_WAITING,
		LOBBY_COUNTDOWN,
		GAME_COUNTDOWN,
		GAME_PLAYING,
		GAME_OVER
	}
	
	private Location<World> lobbySpawnLocation;
	private Location<World> failedJoinLocation;
	private ConcurrentHashMap<String, Location<World>> spawnLocations = new ConcurrentHashMap<String, Location<World>>();
	private CopyOnWriteArrayList<Player> onlinePlayers = new CopyOnWriteArrayList<Player>();
	private Runnable runnable;
	private Runnable onGameStart;
	private ArenaState arenaState;
	private int currentLobbyCountdown;
	private Task lobbyCountdownTask;
	
	private ArenaOptions arenaOptions;
	
	private Game game;
	private Object plugin;
	
	//TODO - Scoreboard implementation throughout arena
	public Arena(String arenaName, Game game, Runnable runnable, Object plugin, boolean dedicatedServer, Location<World> lobbySpawnLocation) {
		this.arenaOptions = new ArenaOptions(arenaName);
		this.game = game;
		this.runnable = runnable;
		this.lobbySpawnLocation = lobbySpawnLocation;
		arenaOptions.setDedicatedServer(dedicatedServer);
		
		game.getEventManager().registerListeners(plugin, this);
	}
	
	public void addOnlinePlayer(Player player) {
		//Check if the game is in the correct state
		if(arenaState.equals(ArenaState.LOBBY_WAITING)||arenaState.equals(ArenaState.LOBBY_COUNTDOWN)) {
			if(onlinePlayers.size()>=arenaOptions.getMaxPlayers()) {
				//Lobby is full
				if(arenaOptions.isDedicatedServer()) {
					//Kick the player
					player.kick(arenaOptions.LOBBY_FULL);
				} else {
					//Try to teleport the player to the failed join location
					if(failedJoinLocation!=null) {
						player.sendMessage(arenaOptions.LOBBY_FULL);
						player.setLocation(failedJoinLocation);
					} else {
						player.kick(arenaOptions.LOBBY_FULL);
					}
				}
			} else {
				//Player can join
				onlinePlayers.add(player);
				for(Player onlinePlayer : game.getServer().getOnlinePlayers()) {
					//TODO - replace %name% with the player name
					onlinePlayer.sendMessage(arenaOptions.PLAYER_JOINED);
				}
				player.setLocation(lobbySpawnLocation);
				if(arenaState.equals(ArenaState.LOBBY_WAITING)&&onlinePlayers.size()>=arenaOptions.getMinPlayers()) {
					startCountdown();
				}
			}
		} else {
			if(arenaOptions.isDedicatedServer()) {
				//Kick the player
				player.kick(arenaOptions.GAME_IN_PROGRESS);
			} else {
				//Try to teleport the player to the failed join location
				if(failedJoinLocation!=null) {
					player.sendMessage(arenaOptions.GAME_IN_PROGRESS);
					player.setLocation(failedJoinLocation);
				} else {
					player.kick(arenaOptions.GAME_IN_PROGRESS);
				}
			}
		}
	}
	
	public void removeOnlinePlayer(Player player) {
		//TODO disconnect message
		if(arenaState.equals(ArenaState.LOBBY_COUNTDOWN)&&onlinePlayers.size()<arenaOptions.getMinPlayers()) {
			cancelCountdown();
		}
		onlinePlayers.remove(player);
	}
	
	public CopyOnWriteArrayList<Player> getOnlinePlayers() {
		return onlinePlayers;
	}
	
	
	/**
	 * Initializes the arena. This starts the game clock with the runnable provided in the Arena constructor. The runnable runs on a seperate thread every 1 tick.
	 */
	public void initializeArena() {
		//TODO - Make a boolean and do checks
		game.getScheduler().createTaskBuilder().execute(new Runnable() {
			public void run() {
				runnable.run();
			}
		}).async().interval(1).submit(plugin);
	}
	
	//Lobby Control Methods
	
	public void startCountdown() {
		currentLobbyCountdown = arenaOptions.getLobbyCountdownTime();
		arenaState = ArenaState.LOBBY_COUNTDOWN;
		for(Player onlinePlayer : game.getServer().getOnlinePlayers()) {
			//TODO - replace %time% with the seconds to go
			onlinePlayer.sendMessage(arenaOptions.LOBBY_COUNTDOWN_STARTED);
		}
		//Register task the run the countdown every 1 second
		lobbyCountdownTask = game.getScheduler().createTaskBuilder().execute(new Runnable() {
			public void run() {
				if(currentLobbyCountdown==0) {
					startGame();
					lobbyCountdownTask.cancel();
					return;
				}
				if(arenaOptions.getLobbyCountdownTime()/2==currentLobbyCountdown||currentLobbyCountdown<=10) {
					//Send a message
					for(Player onlinePlayer : game.getServer().getOnlinePlayers()) {
						//TODO - replace %time% with the seconds to go
						onlinePlayer.sendMessage(arenaOptions.LOBBY_COUNTDOWN_PROGRESS);
					}
				}
				currentLobbyCountdown--;
			}
		}).async().interval(1,TimeUnit.SECONDS).submit(plugin);
	}
	
	public void cancelCountdown() {
		currentLobbyCountdown = arenaOptions.getLobbyCountdownTime();
		if(lobbyCountdownTask!=null) {
			lobbyCountdownTask.cancel();
		}
		arenaState = ArenaState.LOBBY_WAITING;
		for(Player onlinePlayer : game.getServer().getOnlinePlayers()) {
			onlinePlayer.sendMessage(arenaOptions.LOBBY_COUNTDOWN_CANCELLED);
		}
	}
	
	//Game Control Methods
	
	public void startGame() {
		currentLobbyCountdown = arenaOptions.getLobbyCountdownTime();
		arenaState = ArenaState.GAME_COUNTDOWN;
		//Send a message
		for(Player onlinePlayer : game.getServer().getOnlinePlayers()) {
			onlinePlayer.sendMessage(arenaOptions.GAME_STARTING);
		}
		if(onGameStart!=null) {
			onGameStart.run();
		}
	}
	
	public void endGame() {
		arenaState = ArenaState.GAME_OVER;
		for(Player onlinePlayer : game.getServer().getOnlinePlayers()) {
			onlinePlayer.sendMessage(arenaOptions.GAME_OVER);
			//End game spectator only works with end game delay on
			if(arenaOptions.isEndGameDelay()&&arenaOptions.isEndGameSpectator()) {
				//TODO - Save the gamemode
				onlinePlayer.offer(Keys.GAME_MODE,GameModes.SPECTATOR);
			}
		}
		if(arenaOptions.isEndGameDelay()) {
			game.getScheduler().createTaskBuilder().execute(new Runnable() {
				public void run() {
					for(Player onlinePlayer : game.getServer().getOnlinePlayers()) {
						onlinePlayer.sendMessage(arenaOptions.GAME_OVER);
						if(arenaOptions.isEndGameSpectator()) {
							//TODO load the gamemode
							onlinePlayer.offer(Keys.GAME_MODE,GameModes.SURVIVAL);
						}
						if(arenaOptions.isDedicatedServer()) {
							onlinePlayer.kick(arenaOptions.GAME_OVER);
						} else {
							onlinePlayer.setLocation(lobbySpawnLocation);
						}
					}
				}
			}).async().delay(5,TimeUnit.SECONDS).submit(plugin);
		} else {
			//No delay
			for(Player onlinePlayer : game.getServer().getOnlinePlayers()) {
				onlinePlayer.sendMessage(arenaOptions.GAME_OVER);
				if(arenaOptions.isEndGameSpectator()) {
					//TODO load the gamemode
					onlinePlayer.offer(Keys.GAME_MODE,GameModes.SURVIVAL);
				}
				if(arenaOptions.isDedicatedServer()) {
					onlinePlayer.kick(arenaOptions.GAME_OVER);
				} else {
					onlinePlayer.setLocation(lobbySpawnLocation);
				}
			}
		}
	}
	
	//Spawn Locations
	
	/**
	 * Gets all of the possible spawn locations
	 * @return
	 * 	All of the possible spawn locations
	 */
	public ConcurrentHashMap<String, Location<World>> getSpawnLocations() {
		return spawnLocations;
	}
	
	/**
	 * Adds a spawn location to the list of possible spawn locations
	 * @param name
	 * 	The name of the spawn location
	 * @param location
	 * 	The location to add
	 * @return
	 * 	Boolean based on if the method was successful or not
	 */
	public boolean addSpawnLocation(String name, Location<World> location) {
		if(spawnLocations.containsKey(name)) {
			return false;
		}
		spawnLocations.put(name, location);
		return true;
	}
	
	/**
	 * Removes a spawn location from the list of possible spawn locations
	 * @param name
	 * 	The name of the spawn location
	 * @return
	 * 	Boolean based on if the method was successful or not
	 */
	public boolean removeSpawnLocation(String name) {
		if(!spawnLocations.containsKey(name)) {
			return false;
		}
		spawnLocations.remove(name);
		return true;
	}
	
	/**
	 * Gets a spawn location by name
	 * @param name
	 * 	The name of the spawn location to get
	 * @return
	 * 	Optional of the spawn location
	 */
	public Optional<Location<World>> getSpawnLocation(String name) {
		if(!spawnLocations.containsKey(name)) {
			return Optional.absent();
		}
		return Optional.of(spawnLocations.get(name));
	}
	
	/**
	 * Selects a random spawn location from the list of available spawn locations
	 * @return
	 * 	Optional of the spawn location
	 */
	public Optional<Location<World>> randomSpawnLocation() {
		if(spawnLocations.isEmpty()) {
			return Optional.absent();
		}
		//TODO
		return Optional.absent();
	}
	
	/**
	 * Disperses the players among all the spawn locations
	 */
	public void dispersePlayers() {
		//TODO
	}
	
	//Other Arena Properties
	
	/**
	 * Sets the location a player will teleport to if they failed to join the arena(Non-Dedicated Arenas Only)
	 * @param location
	 * 	The location to set the failed join location to
	 */
	public void setFailedJoinLocation(Location<World> location) {
		this.failedJoinLocation = location;
	}
	
	/**
	 * Gets the set of arena options
	 * @return
	 * 	The ArenaOptions
	 */
	public ArenaOptions getOptions() {
		return this.arenaOptions;
	}

	//Listeners
	
	@Listener
	public void onPlayerDisconnect(ClientConnectionEvent.Disconnect event) {
		if(arenaOptions.isDedicatedServer()) {
			Player player = event.getTargetEntity();
			removeOnlinePlayer(player);
		}
	}
	
	@Listener
	public void onPlayerJoin(ClientConnectionEvent.Join event) {
		if(arenaOptions.isDedicatedServer()) {
			Player player = event.getTargetEntity();
			addOnlinePlayer(player);
		}
	}
}
