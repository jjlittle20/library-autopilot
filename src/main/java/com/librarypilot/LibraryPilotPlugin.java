package com.librarypilot;

import com.google.inject.Provides;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.MenuAction;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.StatChanged;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.NpcID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.Skill;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(
	name = "Library Autopilot",
	description = "Draws a line to the next bookshelf or customer in the Arceuus Library, using stairs and the railing shortcuts",
	tags = {"arceuus", "kourend", "library", "books", "path", "magic", "runecraft"}
)
@Slf4j
public class LibraryPilotPlugin extends Plugin
{
	private static final int REGION = 6459;
	private static final Pattern BOOK_EXTRACTOR = Pattern.compile("'<col=0000ff>(.*)</col>'");
	private static final Pattern TAG_MATCHER = Pattern.compile("(<[^>]*>)");
	/**
	 * How long after clicking a staircase or railing a floor change still counts as having used it.
	 */
	private static final int TRANSPORT_LEARN_TICKS = 15;

	@Inject
	private Client client;

	@Inject
	private LibraryPilotConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private LibraryPilotOverlay overlay;

	private final Library library = new Library();

	private WorldPoint lastBookcaseClick;
	private WorldPoint lastBookcaseAnimatedOn;
	private EnumSet<Book> playerBooks = EnumSet.noneOf(Book.class);
	/**
	 * The customer who was last handed a book. They won't give out the next request.
	 */
	private int lastServedCustomer = -1;

	private CollisionGrid grid;
	private List<Transport> transports = Collections.emptyList();
	private boolean sceneDirty = true;
	private final Map<String, WorldPoint> learnedTransports = new HashMap<>();

	private WorldPoint pendingTransportObject;
	private int pendingTransportTick;
	private int lastPlane = -1;

	@Getter(AccessLevel.PACKAGE)
	private volatile List<PathFinder.Step> path = Collections.emptyList();

	/**
	 * What the path leads to: a bookshelf location or a customer NPC.
	 */
	@Getter(AccessLevel.PACKAGE)
	private volatile WorldPoint targetBookcase;

	@Getter(AccessLevel.PACKAGE)
	private volatile NPC targetNpc;

	@Provides
	LibraryPilotConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(LibraryPilotConfig.class);
	}

	@Override
	protected void startUp()
	{
		loadLearnedTransports();
		overlayManager.add(overlay);
		sceneDirty = true;
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		library.reset();
		library.setCustomer(-1, null);
		lastBookcaseClick = null;
		lastBookcaseAnimatedOn = null;
		playerBooks = EnumSet.noneOf(Book.class);
		lastServedCustomer = -1;
		grid = null;
		transports = Collections.emptyList();
		pendingTransportObject = null;
		clearPath();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (LibraryPilotConfig.GROUP.equals(event.getGroup()) && "useShortcuts".equals(event.getKey()))
		{
			sceneDirty = true;
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOADING || event.getGameState() == GameState.LOGGED_IN)
		{
			sceneDirty = true;
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING)
		{
			// Book locations and requests are per world
			library.reset();
			library.setCustomer(-1, null);
			lastServedCustomer = -1;
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		// Railing shortcuts depend on the Agility level
		if (event.getSkill() == Skill.AGILITY)
		{
			sceneDirty = true;
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event)
	{
		MenuAction action = event.getMenuAction();
		if (action == MenuAction.GAME_OBJECT_FIRST_OPTION && event.getMenuTarget().contains("Bookshelf"))
		{
			lastBookcaseClick = WorldPoint.fromScene(client.getTopLevelWorldView(), event.getParam0(), event.getParam1(), client.getTopLevelWorldView().getPlane());
		}

		String option = event.getMenuOption();
		boolean isObjectOption = action == MenuAction.GAME_OBJECT_FIRST_OPTION || action == MenuAction.GAME_OBJECT_SECOND_OPTION
			|| action == MenuAction.GAME_OBJECT_THIRD_OPTION || action == MenuAction.GAME_OBJECT_FOURTH_OPTION
			|| action == MenuAction.GAME_OBJECT_FIFTH_OPTION;
		if (isObjectOption && (TransportScanner.CLIMB_UP.equals(option) || TransportScanner.CLIMB_DOWN.equals(option)
			|| TransportScanner.CLIMB.equals(option) || TransportScanner.JUMP_DOWN.equals(option)))
		{
			pendingTransportObject = objectLocation(event.getParam0(), event.getParam1(), event.getId());
			pendingTransportTick = client.getTickCount();
		}
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged event)
	{
		if (event.getActor() == client.getLocalPlayer() && event.getActor().getAnimation() == AnimationID.HUMAN_PICKUPTABLE)
		{
			lastBookcaseAnimatedOn = lastBookcaseClick;
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}

		if (lastBookcaseAnimatedOn != null && event.getMessage().equals("You don't find anything useful here."))
		{
			library.mark(lastBookcaseAnimatedOn, null);
			lastBookcaseAnimatedOn = null;
		}

		// has a color tag at start
		if (event.getMessage().endsWith("You hear the shifting of books due to a mysterious force...or are you just hearing things?"))
		{
			library.reset();
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		if (event.getContainerId() == InventoryID.INV)
		{
			updatePlayerBooks();
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		Player player = client.getLocalPlayer();
		if (player == null)
		{
			return;
		}

		WorldPoint location = player.getWorldLocation();
		learnTransport(location);

		if (location.getRegionID() != REGION)
		{
			clearPath();
			return;
		}

		trackBookFound();
		trackCustomerDialog();

		if (sceneDirty || grid == null)
		{
			grid = CollisionGrid.fromWorldView(client.getTopLevelWorldView());
			transports = TransportScanner.scan(client, grid, learnedTransports, config.useShortcuts());
			sceneDirty = false;
			log.debug("Found {} library transports", transports.size());
		}

		updatePath(location);
	}

	private void updatePath(WorldPoint location)
	{
		List<WorldPoint> targets = new ArrayList<>();
		WorldPoint bookcase = null;
		NPC npc = null;

		Book wanted = library.getCustomerBook();
		if (wanted != null && playerBooks.contains(wanted))
		{
			// Holding the requested book, take it to whoever asked for it
			npc = nearest(customers(library.getCustomerId(), true), location);
		}
		else if (wanted != null)
		{
			List<WorldPoint> shelves = shelvesFor(wanted);
			for (WorldPoint shelf : shelves)
			{
				targets.addAll(standTiles(shelf));
			}
			List<PathFinder.Step> steps = PathFinder.find(grid, location, targets, transports);
			if (!steps.isEmpty())
			{
				bookcase = closestShelf(shelves, steps.get(steps.size() - 1).getPoint());
			}
			publish(steps, bookcase, null);
			return;
		}
		else
		{
			// No request yet, go to the nearest customer that will give one
			npc = nearest(customers(lastServedCustomer, false), location);
		}

		if (npc == null)
		{
			clearPath();
			return;
		}
		targets.addAll(standTiles(npc.getWorldLocation()));
		publish(PathFinder.find(grid, location, targets, transports), null, npc);
	}

	private void publish(List<PathFinder.Step> steps, WorldPoint bookcase, NPC npc)
	{
		path = steps;
		targetBookcase = steps.isEmpty() ? null : bookcase;
		targetNpc = steps.isEmpty() ? null : npc;
	}

	private void clearPath()
	{
		path = Collections.emptyList();
		targetBookcase = null;
		targetNpc = null;
	}

	/**
	 * Shelves that have, or may have, the book.
	 */
	private List<WorldPoint> shelvesFor(Book book)
	{
		List<WorldPoint> known = new ArrayList<>();
		List<WorldPoint> possible = new ArrayList<>();
		List<WorldPoint> unsearched = new ArrayList<>();
		for (Bookcase bookcase : library.getBookcases())
		{
			if (bookcase.isBookSet())
			{
				if (bookcase.getBook() == book)
				{
					known.add(bookcase.getLocation());
				}
			}
			else if (bookcase.getPossibleBooks().contains(book))
			{
				possible.add(bookcase.getLocation());
			}
			else if (library.getState() == SolvedState.NO_DATA)
			{
				unsearched.add(bookcase.getLocation());
			}
		}

		if (!known.isEmpty())
		{
			return known;
		}
		if (library.getState() == SolvedState.COMPLETE)
		{
			return possible;
		}
		if (!config.searchUnknown())
		{
			return Collections.emptyList();
		}
		return possible.isEmpty() ? unsearched : possible;
	}

	private static WorldPoint closestShelf(List<WorldPoint> shelves, WorldPoint standingOn)
	{
		WorldPoint best = null;
		for (WorldPoint shelf : shelves)
		{
			if (shelf.getPlane() == standingOn.getPlane()
				&& (best == null || shelf.distanceTo2D(standingOn) < best.distanceTo2D(standingOn)))
			{
				best = shelf;
			}
		}
		return best;
	}

	/**
	 * Tiles a player can stand on to interact with something occupying the given tile.
	 */
	private List<WorldPoint> standTiles(WorldPoint target)
	{
		List<WorldPoint> tiles = new ArrayList<>();
		if (grid.walkable(target))
		{
			tiles.add(target);
		}
		for (int[] d : new int[][]{{0, 1}, {0, -1}, {1, 0}, {-1, 0}})
		{
			WorldPoint n = target.dx(d[0]).dy(d[1]);
			if (grid.walkable(n) && !grid.wallBetween(n, target))
			{
				tiles.add(n);
			}
		}
		return tiles;
	}

	private List<NPC> customers(int id, boolean only)
	{
		List<NPC> npcs = new ArrayList<>();
		for (NPC npc : client.getTopLevelWorldView().npcs())
		{
			if (isLibraryCustomer(npc.getId()) && (only ? npc.getId() == id : npc.getId() != id))
			{
				npcs.add(npc);
			}
		}
		return npcs;
	}

	private NPC nearest(List<NPC> npcs, WorldPoint from)
	{
		NPC best = null;
		int bestLength = Integer.MAX_VALUE;
		for (NPC npc : npcs)
		{
			List<PathFinder.Step> steps = PathFinder.find(grid, from, standTiles(npc.getWorldLocation()), transports);
			if (!steps.isEmpty() && steps.size() < bestLength)
			{
				best = npc;
				bestLength = steps.size();
			}
		}
		return best;
	}

	private void trackBookFound()
	{
		if (lastBookcaseAnimatedOn == null)
		{
			return;
		}
		Widget find = client.getWidget(InterfaceID.Objectbox.ITEM);
		if (find != null)
		{
			Book book = Book.byId(find.getItemId());
			if (book != null)
			{
				library.mark(lastBookcaseAnimatedOn, book);
				lastBookcaseAnimatedOn = null;
			}
		}
	}

	private void trackCustomerDialog()
	{
		Widget npcHead = client.getWidget(InterfaceID.ChatLeft.HEAD);
		if (npcHead == null || !isLibraryCustomer(npcHead.getModelId()))
		{
			return;
		}
		Widget textWidget = client.getWidget(InterfaceID.ChatLeft.TEXT);
		if (textWidget == null)
		{
			return;
		}

		String text = textWidget.getText();
		Matcher m = BOOK_EXTRACTOR.matcher(text);
		if (m.find())
		{
			String bookName = TAG_MATCHER.matcher(m.group(1).replace("<br>", " ")).replaceAll("");
			Book book = Book.byName(bookName);
			if (book == null)
			{
				log.warn("Book '{}' is not recognised", bookName);
				return;
			}
			library.setCustomer(npcHead.getModelId(), book);
		}
		else if (text.contains("You can have this other book") || text.contains("please accept a token of my thanks.") || text.contains("Thanks, I'll get on with reading it."))
		{
			if (library.getCustomerBook() != null)
			{
				lastServedCustomer = npcHead.getModelId();
			}
			library.setCustomer(-1, null);
		}
	}

	private void updatePlayerBooks()
	{
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		EnumSet<Book> books = EnumSet.noneOf(Book.class);
		if (inventory != null)
		{
			for (Item item : inventory.getItems())
			{
				Book book = Book.byId(item.getId());
				if (book != null)
				{
					books.add(book);
				}
			}
		}
		playerBooks = books;
	}

	/**
	 * Records where a staircase or railing actually put the player, so later paths use the real landing tile.
	 */
	private void learnTransport(WorldPoint location)
	{
		int plane = location.getPlane();
		if (pendingTransportObject != null && lastPlane != -1 && plane != lastPlane)
		{
			if (client.getTickCount() - pendingTransportTick <= TRANSPORT_LEARN_TICKS
				&& pendingTransportObject.getPlane() == lastPlane
				&& CollisionGrid.inRegion(location))
			{
				String key = Transport.key(pendingTransportObject, plane);
				if (!location.equals(learnedTransports.get(key)))
				{
					log.debug("Learned library transport {} -> {}", key, location);
					learnedTransports.put(key, location);
					saveLearnedTransports();
					sceneDirty = true;
				}
			}
			pendingTransportObject = null;
		}
		lastPlane = plane;
	}

	/**
	 * South-west tile of the object with the given id on the clicked scene tile.
	 */
	private WorldPoint objectLocation(int sceneX, int sceneY, int id)
	{
		WorldView wv = client.getTopLevelWorldView();
		int plane = wv.getPlane();
		Tile[][][] tiles = wv.getScene().getTiles();
		if (sceneX < 0 || sceneY < 0 || sceneX >= tiles[plane].length || sceneY >= tiles[plane][sceneX].length)
		{
			return null;
		}
		Tile tile = tiles[plane][sceneX][sceneY];
		if (tile != null)
		{
			for (GameObject go : tile.getGameObjects())
			{
				if (go != null && go.getId() == id)
				{
					return WorldPoint.fromScene(wv, go.getSceneMinLocation().getX(), go.getSceneMinLocation().getY(), plane);
				}
			}
			TileObject wall = tile.getWallObject();
			if (wall != null && wall.getId() == id)
			{
				return wall.getWorldLocation();
			}
		}
		return WorldPoint.fromScene(wv, sceneX, sceneY, plane);
	}

	private void loadLearnedTransports()
	{
		learnedTransports.clear();
		String saved = configManager.getConfiguration(LibraryPilotConfig.GROUP, LibraryPilotConfig.LEARNED_TRANSPORTS);
		if (saved == null || saved.isEmpty())
		{
			return;
		}
		for (String entry : saved.split(";"))
		{
			String[] kv = entry.split("=");
			if (kv.length != 2)
			{
				continue;
			}
			String[] c = kv[1].split(",");
			try
			{
				learnedTransports.put(kv[0], new WorldPoint(Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2])));
			}
			catch (NumberFormatException | ArrayIndexOutOfBoundsException e)
			{
				log.debug("Ignoring bad learned transport {}", entry);
			}
		}
	}

	private void saveLearnedTransports()
	{
		StringBuilder sb = new StringBuilder();
		learnedTransports.forEach((key, wp) ->
		{
			if (sb.length() > 0)
			{
				sb.append(';');
			}
			sb.append(key).append('=').append(wp.getX()).append(',').append(wp.getY()).append(',').append(wp.getPlane());
		});
		configManager.setConfiguration(LibraryPilotConfig.GROUP, LibraryPilotConfig.LEARNED_TRANSPORTS, sb.toString());
	}

	static boolean isLibraryCustomer(int npcId)
	{
		return npcId == NpcID.ARCEUUS_LIBRARY_CUSTOMER_2 || npcId == NpcID.ARCEUUS_LIBRARY_CUSTOMER_3 || npcId == NpcID.ARCEUUS_LIBRARY_CUSTOMER_4;
	}
}
