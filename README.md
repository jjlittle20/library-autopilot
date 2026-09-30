# Library Autopilot

Draws a line to where you need to go next in the Arceuus (Kourend) Library:

- **Before a request:** to Villia or Professor Gracklebone, alternating between them, since the customer you just served can't give the next request. Sam is only used if he gave you the current request, unless you turn on *Get requests from Sam*.
- Customers that are too far away to be loaded (for example when you're upstairs) are pathed to at their usual spot.
- **Book requested and you don't have it:** to the bookshelf that holds it. If the location isn't known yet, to the nearest shelf that could hold it.
- **Holding the requested book:** back to the customer who asked for it.

Paths are worked out from the game's own collision data and cover all three floors. They take the stairs, or the railing shortcuts over the central hole when those are faster. The line is drawn on your current floor and ends on the next thing to click: a staircase, railing, bookshelf or customer.

An info box shows the current step (*Get task*, *Find book*, *Search shelves* or *Return book*), with the book and customer. When your inventory is full, it switches to *Claim XP* and your Books of Arcane Knowledge are outlined so you can read them to make space.

**Efficient mode** (off by default): picks up books you don't have yet when they're close to the route, so later requests are quicker. A book is only picked up if it adds at most *Max detour* tiles (default 6) to the trip. After a hand-in, it also goes back for another copy of that book (allowed three times the detour), since the same book is often requested again before the shelves reshuffle. With it off, the line goes straight to the requested book and back.

This plugin works alongside the built-in **Kourend Library** plugin, which you can keep for its book panel. Turn off that plugin's *Show target book hint arrow* option if you don't want its arrow as well.

## Staircase landings

The server decides where a staircase or railing puts you, so that can't be read from the game data. Until you've used a transport once, its landing tile is estimated from the floor it leads to. After each use, the plugin records where you actually landed and uses that from then on.
