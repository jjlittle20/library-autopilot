# Library Autopilot

Draws a line to where you need to go next in the Arceuus (Kourend) Library:

- **Before a request:** to the nearest customer who can give you one. The customer you just served can't give the next request, so they're skipped.
- **Book requested and you don't have it:** to the bookshelf that holds it. If the location isn't known yet, to the nearest shelf that could hold it.
- **Holding the requested book:** back to the customer who asked for it.

Paths are worked out from the game's own collision data and cover all three floors. They take the stairs, or the railing shortcuts over the central hole (52 Agility on the middle floor, 62 on the top floor) when those are faster. The line is drawn on your current floor and ends on the next thing to click: a staircase, railing, bookshelf or customer.

This plugin works alongside the built-in **Kourend Library** plugin, which you can keep for its book panel. Turn off that plugin's *Show target book hint arrow* option if you don't want its arrow as well.

## Staircase landings

The server decides where a staircase or railing puts you, so that can't be read from the game data. Until you've used a transport once, its landing tile is estimated from the floor it leads to. After each use, the plugin records where you actually landed (saved in the `librarypilot.learnedTransports` config) and uses that from then on.


```
./gradlew run
```
