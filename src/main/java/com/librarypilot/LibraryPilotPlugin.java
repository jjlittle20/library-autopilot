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
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
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
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.Skill;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.overlay.OverlayManager;

@PluginDescriptor(name = "Library Autopilot", description = "Draws a line to the next bookshelf or customer in the Arceuus Library, using stairs and the railing shortcuts", tags = {
		"arceuus", "kourend", "library", "books", "path", "magic", "runecraft" })
@Slf4j
public class LibraryPilotPlugin extends Plugin {
	private static final int REGION = 6459;
	private static final Pattern BOOK_EXTRACTOR = Pattern.compile("'<col=0000ff>(.*)</col>'");
	private static final Pattern TAG_MATCHER = Pattern.compile("(<[^>]*>)");
	/**
	 * How long after clicking a staircase or railing a floor change still counts as
	 * having used it.
	 */
	private static final int TRANSPORT_LEARN_TICKS = 15;
	/**
	 * How many of the nearest spare-book shelves to check the full detour cost for,
	 * each tick.
	 */
	private static final int MAX_DETOUR_CANDIDATES = 8;
	/**
	 * How many times the normal max detour the book that was just handed in may
	 * cost to pick up again.
	 */
	private static final int REGRAB_DETOUR_MULTIPLIER = 3;
	private static final String BOOKSHELF = "Bookshelf";
	private static final int INVENTORY_SIZE = 28;
	/**
	 * Efficient mode only takes a spare book when at least this many inventory
	 * slots are free.
	 * This leaves one slot available for the actual requested book.
	 */
	private static final int MIN_FREE_SLOTS_FOR_SPARE = 2;

	enum Task {
		GET_TASK("Get task"),
		FIND_BOOK("Find book"),
		SEARCH_SHELVES("Search shelves"),
		RETURN_BOOK("Return book"),
		CLAIM_XP("Claim XP");

		@Getter
		private final String label;

		Task(String label) {
			this.label = label;
		}
	}

	@Inject
	private Client client;

	@Inject
	private LibraryPilotConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ClientThread clientThread;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private LibraryPilotOverlay overlay;

	@Inject
	private LibraryPilotInfoOverlay infoOverlay;

	@Inject
	private LibraryPilotItemOverlay itemOverlay;

	private final Library library = new Library();

	private WorldPoint lastBookcaseClick;
	private WorldPoint lastBookcaseAnimatedOn;
	private EnumSet<Book> playerBooks = EnumSet.noneOf(Book.class);
	/**
	 * The customer who was last handed a book. They won't give out the next
	 * request.
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
	 * What the path leads to: a bookshelf, or a customer's home spot when the
	 * customer isn't loaded.
	 */
	@Getter(AccessLevel.PACKAGE)
	private volatile WorldPoint targetTile;

	/**
	 * The customer the path leads to, when they are loaded.
	 */
	@Getter(AccessLevel.PACKAGE)
	private volatile NPC targetNpc;

	/**
	 * Whether the inventory is full and holds Books of Arcane Knowledge that should
	 * be read to make space.
	 */
	@Getter(AccessLevel.PACKAGE)
	private volatile boolean claimXp;

	/**
	 * Number of currently free inventory slots. Used by efficient mode so spare
	 * books cannot
	 * consume the final slot needed for the requested book.
	 */
	private volatile int inventoryFreeSlots = INVENTORY_SIZE;

	/**
	 * The bookshelf the path leads to, when it is loaded.
	 */
	@Getter(AccessLevel.PACKAGE)
	private volatile TileObject targetObject;

	/**
	 * What the player should be doing, for the info box. Null outside the library.
	 */
	@Getter(AccessLevel.PACKAGE)
	private volatile Task task;

	@Getter(AccessLevel.PACKAGE)
	private volatile Book taskBook;

	@Getter(AccessLevel.PACKAGE)
	private volatile Customer taskCustomer;

	/**
	 * The book last handed in, until it is picked up again or the shelves
	 * reshuffle. Efficient mode goes further out of
	 * its way to get another copy.
	 */
	@Getter(AccessLevel.PACKAGE)
	private volatile Book regrabBook;

	/**
	 * A spare book the path is picking up on the way, in efficient mode.
	 */
	@Getter(AccessLevel.PACKAGE)
	private volatile Book detourBook;

	@Provides
	LibraryPilotConfig provideConfig(ConfigManager configManager) {
		return configManager.getConfig(LibraryPilotConfig.class);
	}

	@Override
	protected void startUp() {
		loadLearnedTransports();
		overlayManager.add(overlay);
		overlayManager.add(infoOverlay);
		overlayManager.add(itemOverlay);
		sceneDirty = true;
		clientThread.invoke(() -> {
			if (client.getGameState() == GameState.LOGGED_IN) {
				updatePlayerBooks();
			}
		});
	}

	@Override
	protected void shutDown() {
		overlayManager.remove(overlay);
		overlayManager.remove(infoOverlay);
		overlayManager.remove(itemOverlay);
		claimXp = false;
		task = null;
		library.reset();
		regrabBook = null;
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
	public void onConfigChanged(ConfigChanged event) {
		if (LibraryPilotConfig.GROUP.equals(event.getGroup()) && "useShortcuts".equals(event.getKey())) {
			sceneDirty = true;
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event) {
		if (event.getGameState() == GameState.LOADING || event.getGameState() == GameState.LOGGED_IN) {
			sceneDirty = true;
		} else if (event.getGameState() == GameState.LOGIN_SCREEN || event.getGameState() == GameState.HOPPING) {
			// Book locations and requests are per world
			library.reset();
			regrabBook = null;
			library.setCustomer(-1, null);
			lastServedCustomer = -1;
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged event) {
		// Railing shortcuts depend on the Agility level
		if (event.getSkill() == Skill.AGILITY) {
			sceneDirty = true;
		}
	}

	@Subscribe
	public void onMenuOptionClicked(MenuOptionClicked event) {
		MenuAction action = event.getMenuAction();
		if (action == MenuAction.GAME_OBJECT_FIRST_OPTION && event.getMenuTarget().contains("Bookshelf")) {
			lastBookcaseClick = WorldPoint.fromScene(client.getTopLevelWorldView(), event.getParam0(),
					event.getParam1(), client.getTopLevelWorldView().getPlane());
		}

		String option = event.getMenuOption();
		boolean isObjectOption = action == MenuAction.GAME_OBJECT_FIRST_OPTION
				|| action == MenuAction.GAME_OBJECT_SECOND_OPTION
				|| action == MenuAction.GAME_OBJECT_THIRD_OPTION || action == MenuAction.GAME_OBJECT_FOURTH_OPTION
				|| action == MenuAction.GAME_OBJECT_FIFTH_OPTION;
		if (isObjectOption && (TransportScanner.CLIMB_UP.equals(option) || TransportScanner.CLIMB_DOWN.equals(option)
				|| TransportScanner.CLIMB.equals(option) || TransportScanner.JUMP_DOWN.equals(option))) {
			pendingTransportObject = objectLocation(event.getParam0(), event.getParam1(), event.getId());
			pendingTransportTick = client.getTickCount();
		}
	}

	@Subscribe
	public void onAnimationChanged(AnimationChanged event) {
		if (event.getActor() == client.getLocalPlayer()
				&& event.getActor().getAnimation() == AnimationID.HUMAN_PICKUPTABLE) {
			lastBookcaseAnimatedOn = lastBookcaseClick;
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event) {
		if (event.getType() != ChatMessageType.GAMEMESSAGE) {
			return;
		}

		if (lastBookcaseAnimatedOn != null && event.getMessage().equals("You don't find anything useful here.")) {
			library.mark(lastBookcaseAnimatedOn, null);
			lastBookcaseAnimatedOn = null;
		}

		// has a color tag at start
		if (event.getMessage().endsWith(
				"You hear the shifting of books due to a mysterious force...or are you just hearing things?")) {
			library.reset();
			regrabBook = null;
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event) {
		if (event.getContainerId() == InventoryID.INV) {
			updatePlayerBooks();
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick) {
		Player player = client.getLocalPlayer();
		if (player == null) {
			return;
		}

		WorldPoint location = player.getWorldLocation();
		learnTransport(location);

		if (location.getRegionID() != REGION) {
			clearPath();
			task = null;
			return;
		}

		trackBookFound();
		trackCustomerDialog();

		if (sceneDirty || grid == null) {
			grid = CollisionGrid.fromWorldView(client.getTopLevelWorldView());
			transports = TransportScanner.scan(client, grid, learnedTransports, config.useShortcuts());
			sceneDirty = false;
			log.debug("Found {} library transports", transports.size());
		}

		if (claimXp) {
			// A full inventory can't take any more books. Do this before calculating a
			// route so an
			// old/main bookshelf path cannot remain visible while the player is supposed to
			// make space.
			clearPath();
			setTask(Task.CLAIM_XP, null, null);
			return;
		}

		updatePath(location);
	}

	private void updatePath(WorldPoint location) {
		PathFinder.Search search = PathFinder.search(grid, location, transports);
		List<WorldPoint> mainTargets;
		WorldPoint mainTile = null;
		NPC mainNpc = null;
		List<WorldPoint> shelves = null;

		Book wanted = library.getCustomerBook();
		log.debug(
				"[PATH] wanted={} haveWanted={} regrab={} detourBook={}",
				wanted,
				wanted != null && playerBooks.contains(wanted),
				regrabBook,
				detourBook);
		if (wanted != null && playerBooks.contains(wanted)) {
			// Holding the requested book, take it to whoever asked for it
			Customer customer = Customer.byId(library.getCustomerId());
			setTask(Task.RETURN_BOOK, wanted, customer);
			if (customer == null) {
				clearPath();
				return;
			}
			mainNpc = findNpc(customer.getNpcId());
			mainTile = mainNpc != null ? mainNpc.getWorldLocation() : customer.getHome();
			mainTargets = standTiles(mainTile);
		} else if (wanted != null) {
			setTask(isLocationKnown(wanted) ? Task.FIND_BOOK : Task.SEARCH_SHELVES, wanted, null);
			shelves = shelvesFor(wanted);
			mainTargets = new ArrayList<>();
			for (WorldPoint shelf : shelves) {
				mainTargets.addAll(standTiles(shelf));
			}
		} else {
			// No request yet, go to the closest customer that will give one
			Customer best = null;
			int bestDist = PathFinder.UNREACHABLE;
			for (Customer customer : taskGivers()) {
				NPC npc = findNpc(customer.getNpcId());
				WorldPoint tile = npc != null ? npc.getWorldLocation() : customer.getHome();
				int d = search.distance(standTiles(tile));
				if (best == null || d < bestDist) {
					best = customer;
					bestDist = d;
					mainNpc = npc;
					mainTile = tile;
				}
			}
			setTask(Task.GET_TASK, null, best);
			mainTargets = mainTile == null ? Collections.emptyList() : standTiles(mainTile);
		}

		int mainDist = search.distance(mainTargets);
		if (config.efficientMode()
				&& inventoryFreeSlots >= MIN_FREE_SLOTS_FOR_SPARE
				&& mainDist != PathFinder.UNREACHABLE
				&& detourForSpareBook(search, mainTargets, mainDist, wanted)) {
			return;
		}

		detourBook = null;
		List<PathFinder.Step> steps = search.pathTo(mainTargets);
		if (shelves != null && !steps.isEmpty()) {
			mainTile = closestShelf(shelves, steps.get(steps.size() - 1).getPoint());
		}
		publish(steps, mainTile, mainNpc);
	}

	/**
	 * Efficient mode: heads for a shelf with a book the player doesn't have yet, if
	 * picking it up only adds a few tiles
	 * to the trip. The book that was just handed in is allowed a bigger detour,
	 * since the same book is often requested
	 * again before the shelves reshuffle.
	 *
	 * @return true if a detour was chosen and published
	 */
	private boolean detourForSpareBook(PathFinder.Search search, List<WorldPoint> mainTargets, int mainDist,
			Book wanted) {
		int maxDetour = config.maxDetour() * PathFinder.TILE;
		int maxRegrabDetour = maxDetour * REGRAB_DETOUR_MULTIPLIER;

		// Cheap filter first: a detour costs at least the extra distance to reach the
		// shelf
		List<Bookcase> candidates = new ArrayList<>();
		Map<Bookcase, Integer> toShelf = new HashMap<>();
		Bookcase regrabShelf = null;
		for (Bookcase bookcase : library.getBookcases()) {
			Book book = knownBook(bookcase);
			if (book == null || book == wanted || book == Book.VARLAMORE_ENVOY || playerBooks.contains(book)) {
				continue;
			}
			int allowed = book == regrabBook ? maxRegrabDetour : maxDetour;
			int d = search.distance(standTiles(bookcase.getLocation()));
			if (d != PathFinder.UNREACHABLE && d - mainDist <= allowed) {
				candidates.add(bookcase);
				toShelf.put(bookcase, d);
				if (book == regrabBook) {
					regrabShelf = bookcase;
				}
			}
		}
		candidates.sort((a, b) -> Integer.compare(toShelf.get(a), toShelf.get(b)));
		List<Bookcase> checked = new ArrayList<>(
				candidates.subList(0, Math.min(candidates.size(), MAX_DETOUR_CANDIDATES)));
		if (regrabShelf != null && !checked.contains(regrabShelf)) {
			checked.add(regrabShelf);
		}

		Bookcase best = null;
		int bestDetour = Integer.MAX_VALUE;
		for (Bookcase bookcase : checked) {
			WorldPoint standing = search.closestPoint(standTiles(bookcase.getLocation()));
			int onward = PathFinder.search(grid, standing, mainTargets, transports).distance(mainTargets);
			if (onward == PathFinder.UNREACHABLE) {
				continue;
			}
			int detour = toShelf.get(bookcase) + onward - mainDist;
			int allowed = knownBook(bookcase) == regrabBook ? maxRegrabDetour : maxDetour;
			if (detour <= allowed && detour < bestDetour) {
				best = bookcase;
				bestDetour = detour;
			}
		}

		if (best == null) {
			return false;
		}
		detourBook = knownBook(best);

		log.debug(
				"[EFFICIENT] Choosing detour book={} shelf={} detour={} allowedNormal={} regrab={} wanted={} inventory={}",
				detourBook,
				best.getLocation(),
				bestDetour,
				maxDetour,
				regrabBook,
				wanted,
				playerBooks);

		publish(search.pathTo(standTiles(best.getLocation())), best.getLocation(), null);
		return true;
	}

	/**
	 * The book on a shelf, if it is known for certain.
	 */
	private Book knownBook(Bookcase bookcase) {
		if (bookcase.isBookSet()) {
			return bookcase.getBook();
		}
		if (library.getState() == SolvedState.COMPLETE && bookcase.getPossibleBooks().size() == 1) {
			return bookcase.getPossibleBooks().iterator().next();
		}
		return null;
	}

	private boolean isLocationKnown(Book book) {
		for (Bookcase bookcase : library.getBookcases()) {
			if (knownBook(bookcase) == book) {
				return true;
			}
		}
		return false;
	}

	private void setTask(Task task, Book book, Customer customer) {
		this.task = task;
		this.taskBook = book;
		this.taskCustomer = customer;
	}

	/**
	 * Customers to get the next request from. By default requests alternate between
	 * Villia and Professor Gracklebone,
	 * since whoever was just served won't give the next one and Sam is out of the
	 * way.
	 */
	private List<Customer> taskGivers() {
		List<Customer> givers = new ArrayList<>();
		givers.add(Customer.VILLIA);
		givers.add(Customer.PROFESSOR_GRACKLEBONE);
		if (config.includeSam()) {
			givers.add(Customer.SAM);
		}
		givers.removeIf(c -> c.getNpcId() == lastServedCustomer);
		return givers;
	}

	private NPC findNpc(int npcId) {
		for (NPC npc : client.getTopLevelWorldView().npcs()) {
			if (npc.getId() == npcId) {
				return npc;
			}
		}
		return null;
	}

	private void publish(List<PathFinder.Step> steps, WorldPoint tile, NPC npc) {
		path = steps;
		targetTile = steps.isEmpty() ? null : tile;
		targetNpc = steps.isEmpty() ? null : npc;
		targetObject = steps.isEmpty() || npc != null || tile == null ? null : bookshelfAt(tile);
	}

	/**
	 * The bookshelf object on a tile, so the overlay can outline the shelf itself
	 * rather than its tile.
	 */
	private GameObject bookshelfAt(WorldPoint wp) {
		WorldView wv = client.getTopLevelWorldView();
		LocalPoint lp = LocalPoint.fromWorld(wv, wp);
		if (lp == null) {
			return null;
		}
		Tile tile = wv.getScene().getTiles()[wp.getPlane()][lp.getSceneX()][lp.getSceneY()];
		if (tile == null) {
			return null;
		}
		for (GameObject go : tile.getGameObjects()) {
			if (go != null) {
				ObjectComposition comp = TransportScanner.composition(client, go.getId());
				if (comp != null && BOOKSHELF.equals(comp.getName())) {
					return go;
				}
			}
		}
		return null;
	}

	private void clearPath() {
		path = Collections.emptyList();
		targetTile = null;
		targetNpc = null;
		targetObject = null;
		detourBook = null;
	}

	/**
	 * Shelves that have, or may have, the book.
	 */
	private List<WorldPoint> shelvesFor(Book book) {
		List<WorldPoint> known = new ArrayList<>();
		List<WorldPoint> possible = new ArrayList<>();
		List<WorldPoint> unsearched = new ArrayList<>();
		for (Bookcase bookcase : library.getBookcases()) {
			if (bookcase.isBookSet()) {
				if (bookcase.getBook() == book) {
					known.add(bookcase.getLocation());
				}
			} else if (bookcase.getPossibleBooks().contains(book)) {
				possible.add(bookcase.getLocation());
			} else if (library.getState() == SolvedState.NO_DATA) {
				unsearched.add(bookcase.getLocation());
			}
		}

		if (!known.isEmpty()) {
			return known;
		}
		if (library.getState() == SolvedState.COMPLETE) {
			return possible;
		}
		if (!config.searchUnknown()) {
			return Collections.emptyList();
		}
		return possible.isEmpty() ? unsearched : possible;
	}

	private static WorldPoint closestShelf(List<WorldPoint> shelves, WorldPoint standingOn) {
		WorldPoint best = null;
		for (WorldPoint shelf : shelves) {
			if (shelf.getPlane() == standingOn.getPlane()
					&& (best == null || shelf.distanceTo2D(standingOn) < best.distanceTo2D(standingOn))) {
				best = shelf;
			}
		}
		return best;
	}

	/**
	 * Tiles a player can stand on to interact with something occupying the given
	 * tile.
	 */
	private List<WorldPoint> standTiles(WorldPoint target) {
		List<WorldPoint> tiles = new ArrayList<>();
		if (grid.walkable(target)) {
			tiles.add(target);
		}
		for (int[] d : new int[][] { { 0, 1 }, { 0, -1 }, { 1, 0 }, { -1, 0 } }) {
			WorldPoint n = target.dx(d[0]).dy(d[1]);
			if (grid.walkable(n) && !grid.wallBetween(n, target)) {
				tiles.add(n);
			}
		}
		return tiles;
	}

	private void trackBookFound() {
		if (lastBookcaseAnimatedOn == null) {
			return;
		}
		Widget find = client.getWidget(InterfaceID.Objectbox.ITEM);
		if (find != null) {
			Book book = Book.byId(find.getItemId());
			if (book != null) {
				library.mark(lastBookcaseAnimatedOn, book);
				lastBookcaseAnimatedOn = null;
				if (book == regrabBook) {
					log.debug(
							"[EFFICIENT] Regrab book {} found. Clearing regrab state.",
							regrabBook);

					regrabBook = null;
				}
			}
		}
	}

	private void trackCustomerDialog() {
		Widget npcHead = client.getWidget(InterfaceID.ChatLeft.HEAD);
		if (npcHead == null || !isLibraryCustomer(npcHead.getModelId())) {
			return;
		}
		Widget textWidget = client.getWidget(InterfaceID.ChatLeft.TEXT);
		if (textWidget == null) {
			return;
		}

		String text = textWidget.getText();
		Matcher m = BOOK_EXTRACTOR.matcher(text);
		if (m.find()) {
			String bookName = TAG_MATCHER.matcher(m.group(1).replace("<br>", " ")).replaceAll("");
			Book book = Book.byName(bookName);
			if (book == null) {
				log.warn("Book '{}' is not recognised", bookName);
				return;
			}
			library.setCustomer(npcHead.getModelId(), book);
		} else if (text.contains("You can have this other book") || text.contains("please accept a token of my thanks.")
				|| text.contains("Thanks, I'll get on with reading it.")) {
			if (library.getCustomerBook() != null) {
				lastServedCustomer = npcHead.getModelId();
				regrabBook = library.getCustomerBook();

				log.debug(
						"[EFFICIENT] Customer completed. Setting regrabBook={}",
						regrabBook);
			}
			library.setCustomer(-1, null);
		}
	}

	private void updatePlayerBooks() {
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		EnumSet<Book> books = EnumSet.noneOf(Book.class);
		int used = 0;
		boolean hasXpBook = false;
		if (inventory != null) {
			for (Item item : inventory.getItems()) {
				if (item.getId() <= 0) {
					continue;
				}
				used++;
				hasXpBook |= item.getId() == ItemID.ARCEUUS_LIBRARY_REWARD;
				Book book = Book.byId(item.getId());
				if (book != null) {
					books.add(book);
				}
			}
		}
		playerBooks = books;
		inventoryFreeSlots = Math.max(0, INVENTORY_SIZE - used);
		claimXp = inventoryFreeSlots == 0 && hasXpBook;
		log.debug(
				"[INVENTORY] used={}/{} books={} hasXpBook={} claimXp={} regrab={}",
				used,
				INVENTORY_SIZE,
				playerBooks,
				hasXpBook,
				claimXp,
				regrabBook);
		// Inventory state is more reliable than the bookshelf popup for confirming a
		// successful
		// re-grab. If the book is now present, stop giving it the enlarged detour
		// allowance.
		if (regrabBook != null && books.contains(regrabBook)) {
			regrabBook = null;
		}
	}

	/**
	 * Records where a staircase or railing actually put the player, so later paths
	 * use the real landing tile.
	 */
	private void learnTransport(WorldPoint location) {
		int plane = location.getPlane();
		if (pendingTransportObject != null && lastPlane != -1 && plane != lastPlane) {
			if (client.getTickCount() - pendingTransportTick <= TRANSPORT_LEARN_TICKS
					&& pendingTransportObject.getPlane() == lastPlane
					&& CollisionGrid.inRegion(location)) {
				String key = Transport.key(pendingTransportObject, plane);
				if (!location.equals(learnedTransports.get(key))) {
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
	private WorldPoint objectLocation(int sceneX, int sceneY, int id) {
		WorldView wv = client.getTopLevelWorldView();
		int plane = wv.getPlane();
		Tile[][][] tiles = wv.getScene().getTiles();
		if (sceneX < 0 || sceneY < 0 || sceneX >= tiles[plane].length || sceneY >= tiles[plane][sceneX].length) {
			return null;
		}
		Tile tile = tiles[plane][sceneX][sceneY];
		if (tile != null) {
			for (GameObject go : tile.getGameObjects()) {
				if (go != null && go.getId() == id) {
					return WorldPoint.fromScene(wv, go.getSceneMinLocation().getX(), go.getSceneMinLocation().getY(),
							plane);
				}
			}
			TileObject wall = tile.getWallObject();
			if (wall != null && wall.getId() == id) {
				return wall.getWorldLocation();
			}
		}
		return WorldPoint.fromScene(wv, sceneX, sceneY, plane);
	}

	private void loadLearnedTransports() {
		learnedTransports.clear();
		String saved = configManager.getConfiguration(LibraryPilotConfig.GROUP, LibraryPilotConfig.LEARNED_TRANSPORTS);
		if (saved == null || saved.isEmpty()) {
			return;
		}
		for (String entry : saved.split(";")) {
			String[] kv = entry.split("=");
			if (kv.length != 2) {
				continue;
			}
			String[] c = kv[1].split(",");
			try {
				learnedTransports.put(kv[0],
						new WorldPoint(Integer.parseInt(c[0]), Integer.parseInt(c[1]), Integer.parseInt(c[2])));
			} catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
				log.debug("Ignoring bad learned transport {}", entry);
			}
		}
	}

	private void saveLearnedTransports() {
		StringBuilder sb = new StringBuilder();
		learnedTransports.forEach((key, wp) -> {
			if (sb.length() > 0) {
				sb.append(';');
			}
			sb.append(key).append('=').append(wp.getX()).append(',').append(wp.getY()).append(',')
					.append(wp.getPlane());
		});
		configManager.setConfiguration(LibraryPilotConfig.GROUP, LibraryPilotConfig.LEARNED_TRANSPORTS, sb.toString());
	}

	static boolean isLibraryCustomer(int npcId) {
		return Customer.byId(npcId) != null;
	}
}
