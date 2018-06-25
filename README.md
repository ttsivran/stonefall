# Stonefall

# README
Project Idea: stonefall.io

Our project idea is a multiplayer, interactive '.io' game to be hosted on
a public web server. The game will be based on each player having a base they
protect, while simultaneously collecting resources, upgrading their base and
attacking other players' bases.

## stonefall.io

How to build and play: 
- Clone this repository. You will not have a local directory called stonefall. 
- Make sure you have maven installed on your computer and then go to the stonefall directory in your terminal and write `mvn package && ./run --gui`. 
- Then, open a browser and go to: `localhost:4567`
- If you'd like, read the tutorial to get familiar with the game.
- When running locally if you want to include multiple players in the game, open up multiple tabs and log them on.

- If you'd like to play in a local network with other people, connect to the same network with them and then go into: src/main/resources/static/js/Websockets.js and comment out the `const ip = localhost:4567` and replace it with `const ip = <enter-ip-address>`. Then all players on the same network can go to `<enter-ip-address>:4567` and play together.

Map is 2d array of blocks which can contain structures(base, wall, turret?)/pawns(gatherers, attackers)/
resources on them. Each player starts with a block somewhere on the map that is their base, and some gatherer
pawn. They can then look around the map using the gatherer for resources, which are used in order to buy
attackers and wall structures (potentially turret structures too). Attackers can roam on map and have a way of knowing what block of 2d array theyre in. In roaming the map, they find other player's bases and can attack structures in order to take them down. (They do some damage per second, structures and soldiers have health). Attacker health is depleted either over time or by turrets. Wall structures can be placed around bases in order to not allow attackers to go straight for the base. Some sort of reward is offered to players for beating other player's bases.

A main difficulty in implementing the game will be equality of play for players. Some ideas we have in order to achieve this are:
- exponential cost of objects
- degradation of structures over time (in order to not allow huge bases)

### Updates:
computer players

### THE TEAM:

David Oyeka:
- Strengths:
  - Implementing pseudocode/wikipedia pathing
  - Ready to pull multiple/consecutive all-nighters
  - Equally versed in functional and object-oriented programming
  - Experience with mobile application development
- Weaknesses:
  - Has never designed a complex game from scratch
  - Thinking about code design deeply before I write it

Mac McCann:
- Strengths:
  - Ready to write and maintain lots of code
  - Implementing pseudocode/wikipedia pathing
- Weaknesses:
  - Patience
  - Code design

Teo Tsivranidis:
- Strengths:
  - Backend Java code
  - Gui design
  - Time management
- Weaknesses:
  - Difficulty of deciding that some code design is optimal.

  Fabrice Guyot-Sionnest:
- Strength:
  - extracting maximum help out of tas
  - networking/sockets
- Weakness: 
  - algorithms
