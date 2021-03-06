package edu.brown.cs.stonefall.network;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketException;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketClose;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketConnect;
import org.eclipse.jetty.websocket.api.annotations.OnWebSocketMessage;
import org.eclipse.jetty.websocket.api.annotations.WebSocket;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import edu.brown.cs.stonefall.custom_exceptions.UnreachableVertexException;
import edu.brown.cs.stonefall.game.Constants;
import edu.brown.cs.stonefall.game.Game;
import edu.brown.cs.stonefall.game.GameState;
import edu.brown.cs.stonefall.game.Player;
import edu.brown.cs.stonefall.gamebean.AttackerBean;
import edu.brown.cs.stonefall.gamebean.BaseBean;
import edu.brown.cs.stonefall.gamebean.MineBean;
import edu.brown.cs.stonefall.gamebean.ResourceBean;
import edu.brown.cs.stonefall.gamebean.ScaffoldBean;
import edu.brown.cs.stonefall.gamebean.TurretBean;
import edu.brown.cs.stonefall.gamebean.WallBean;
import edu.brown.cs.stonefall.map.Grid;

/**
 * Websockets class. Handles connections between frontend and backend.
 *
 * @author Fabrice
 */
@WebSocket
public class WebSockets {
  private static final Gson GSON = new Gson();
  private static final Map<Integer, Session> sessions = new ConcurrentHashMap<>();
  private static Game game;

  private static Integer nextId = 0;

  /**
   * Sets the websockets instance game to the passed in game instance.
   *
   * @param game
   *          websocket game reference
   */
  public static void setGame(Game game) {
    WebSockets.game = game;
  }

  public static boolean isFull() {
    return (sessions.size() >= Constants.MAX_PLAYERS);
  }

  /**
   * Handles a new connection to websockets.
   *
   * @param session
   *          takes in the session
   * @throws IOException
   *           in case of IOException
   */
  @OnWebSocketConnect
  public void connected(Session session) throws IOException {
    if (sessions.size() < Constants.MAX_PLAYERS) {
      // Add the session to the queue
      sessions.put(nextId, session);

      // Build the CONNECT message
      JsonObject Json = new JsonObject();
      JsonObject payload = new JsonObject();
      payload.addProperty("id", nextId);
      Json.addProperty("type", Constants.MESSAGE_TYPE.CONNECT.ordinal());
      Json.add("payload", payload);

      // Send the CONNECT message
      session.getRemote().sendStringByFuture(GSON.toJson(Json));

      // update id
      nextId++;
    }
  }

  /**
   * Handles a user leaving the game.
   *
   * @param session
   *          the session of the user
   * @param statusCode
   *          the statuscode of the user
   * @param reason
   *          and the string reason they departed
   */
  @OnWebSocketClose
  public void closed(Session session, int statusCode, String reason) {
    // get key given value
    Integer sessionId = getKeyFromValue(sessions, session);
    if (sessionId != null) {
      if (sessions.containsKey(sessionId)) {
        // Remove the session from the queue
        sessions.remove(sessionId);
      }
    }
    // Remove the player from the game
    String playerId = "/p/" + sessionId;
    game.removePlayer(game.getPlayers().get(playerId));
  }

  private Integer getKeyFromValue(Map<Integer, Session> sessions2,
      Session session) {
    for (Entry<Integer, Session> entry : sessions2.entrySet()) {
      if (session.equals(entry.getValue())) {
        return entry.getKey();
      }
    }
    return null;
  }

  /**
   * Receives messages. Types are set by the MESSAGE_TYPE enum.
   *
   * @param session
   *          session of the player
   * @param message
   *          the message from the frontend
   */
  @OnWebSocketMessage
  public void message(Session session, String message) {
    JsonObject received = GSON.fromJson(message, JsonObject.class);
    JsonObject payload = received.get("payload").getAsJsonObject();
    int curId = payload.get("id").getAsInt();
    // Get the player object
    String playerId = "/p/" + curId;
    Player thisPlayer = game.getPlayer(playerId);

    if (received.get("type").getAsInt() == Constants.MESSAGE_TYPE.SELL
        .ordinal()) {
      // object type to sell
      int objType = payload.get("objectType").getAsInt();

      // list of to sell ids
      JsonArray objEl = payload.get("toSellIds").getAsJsonArray();
      Map<String, String> hash = new ConcurrentHashMap<>();
      for (JsonElement sellId : objEl) {
        String toSellId = sellId.getAsString();
        hash.put(toSellId, "");
      }

      Set<String> myHash = hash.keySet();
      Set<String> myLinkedHash = new LinkedHashSet<>(myHash);

      for (String id : myLinkedHash) {
        thisPlayer.sell(objType, id);
      }
    }
    if (received.get("type").getAsInt() == Constants.MESSAGE_TYPE.ATTACK
        .ordinal()) {
      // communicate to backend that an attack has occurred

      // get coordinates of objective
      int x1 = payload.get("x1").getAsInt();
      int y1 = payload.get("y1").getAsInt();

      // set of attacker ids
      JsonArray attackerIds = payload.get("attackers").getAsJsonArray();
      Map<String, String> hash = new ConcurrentHashMap<>();

      for (JsonElement attackerId : attackerIds) {
        String stringId = attackerId.getAsString();
        hash.put(stringId, "");
      }

      Set<String> myHash = hash.keySet();
      Set<String> myLinkedHash = new LinkedHashSet<>(myHash);

      for (String attackerId : myLinkedHash) {
        try {
          game.startAction(thisPlayer, attackerId, x1, y1);
        } catch (UnreachableVertexException e) {
          // This attackerId failed to efficiently recalculate a path, so just
          // drop it.
        }
      }
    } else if (received.get("type").getAsInt() == Constants.MESSAGE_TYPE.CREATE
        .ordinal()) {
      // when user creates something
      int x1 = payload.get("x1").getAsInt();
      int y1 = payload.get("y1").getAsInt();

      if (!Grid.validateCoordinates(x1, y1)) {
        sendError(curId, 1, "Invalid creation");
        return;
      }

      int creationType = payload.get("objectType").getAsInt();
      if (thisPlayer.validateSpawn(x1, y1)) {
        if (creationType == Constants.OBJECT_TYPE.WALL.ordinal()) {
          thisPlayer.spawnScaffold(x1, y1,
              Constants.OBJECT_TYPE.WALL.ordinal());
        } else if (creationType == Constants.OBJECT_TYPE.TURRET.ordinal()) {
          thisPlayer.spawnScaffold(x1, y1,
              Constants.OBJECT_TYPE.TURRET.ordinal());
        } else if (creationType == Constants.OBJECT_TYPE.ATTACKER.ordinal()) {
          thisPlayer.spawnMeleeAttacker(x1, y1);
        } else if (creationType == Constants.OBJECT_TYPE.MINE.ordinal()) {
          thisPlayer.spawnScaffold(x1, y1,
              Constants.OBJECT_TYPE.MINE.ordinal());
        }
      }
    }

    if (received.get("type").getAsInt() == Constants.MESSAGE_TYPE.INITIALIZE
        .ordinal()) {
      // update set of coordinates by parsing coordinates message
      String name = payload.get("name").getAsString();

      // Add the player to the game if doesn't already exist
      if (thisPlayer == null) {
        thisPlayer = new Player(name, playerId);
        game.addPlayer(thisPlayer);
      }

      // call update session and tell the backend to update everything
      updateSession(curId);
    }
  }

  /**
   * Method called by the tick to update every session with a new gamestate.
   */
  public static void update() {
    for (Entry<Integer, Session> curEntry : sessions.entrySet()) {
      Integer curSeshId = curEntry.getKey();
      WebSockets.updateSession(curSeshId);
    }
  }

  /**
   * Updates one specific session
   *
   * @param curSeshId
   *          the session id
   */
  public static void updateSession(Integer curSeshId) {
    String playerId = "/p/" + curSeshId;
    if (!game.playerExists(playerId)) {
      return;
    }
    try {
      // Session curSesh = sessions.get(curSeshId);

      Player thisPlayer = game.getPlayer(playerId);
      GameState myState = game.getPayload(thisPlayer);

      // String Json = "";
      // Transform that ^ into the payload and send it
      String Json = fillUpdatePayloadAndSend(myState, thisPlayer, curSeshId);
      // String Json = fillUpdatePayloadManuallyAndSend(myState, thisPlayer,
      // curSeshId);
      // Transform that ^ into the payload and send it
      // BUT WITH JACKSON JSON <3
      // fillUpdatePayloadAndSendButBetter(myState, thisPlayer, curSeshId);

      Session curSesh = sessions.get(curSeshId);
      if ((curSesh != null) && curSesh.isOpen()) {
        curSesh.getRemote().sendStringByFuture(Json);
      }
    } catch (IllegalStateException e) {
      System.out
          .println("Timer error found and caught here in WebSockets.java!");
      e.printStackTrace();
    } catch (WebSocketException w) {
      System.out.println("Web Socket connection error");
      // } catch (JsonProcessingException e1) {
      // // TODO Auto-generated catch block
      // e1.printStackTrace();
    } catch (Exception f) {
      f.printStackTrace();
    }
  }

  // private static

  private static String fillUpdatePayloadAndSendButBetter(GameState myState,
      Player thisPlayer, Integer curSeshId) throws JsonProcessingException {
    JacksonJSON payload = new JacksonJSON();

    // me (contains all relevant information for me)
    My my = new My();

    // player stats
    Statistics statistics = new Statistics();
    statistics.setName(thisPlayer.getName());
    statistics.setResources(thisPlayer.getResourceCount());
    statistics.setScore(thisPlayer.getScore());
    statistics.setMineCost(
        thisPlayer.multiplyByScoreLogistically(Constants.MINE_COST));
    statistics.setWallCost(
        thisPlayer.multiplyByScoreLogistically(Constants.WALL_COST));
    statistics.setAttacker1Cost(
        thisPlayer.multiplyByScoreLogistically(Constants.ATTACKER_COST));
    statistics.setTurret1Cost(
        thisPlayer.multiplyByScoreLogistically(Constants.TURRET_COST));
    // leaderboard
    List<Leaderboard> leaderboard = new ArrayList<>();
    List<Player> leaders = myState.getLeaders();
    for (Player player : leaders) {
      Leaderboard tempPlayer = new Leaderboard();
      tempPlayer.setName(player.getName());
      tempPlayer.setScore(player.getScore());
      tempPlayer.setColor(player.getColorHex());
      leaderboard.add(tempPlayer);
    }
    statistics.setLeaderboard(leaderboard);
    my.setStatistics(statistics);

    // base
    JBase base = new JBase();
    BaseBean mybb = myState.getMyBase();
    base.setX(mybb.getX());
    base.setY(mybb.getY());
    base.setHealth(mybb.getHp());
    base.setMaxHealth(mybb.getMaxHp());
    base.setColor(mybb.getColorHex());
    base.setName(thisPlayer.getName());
    my.setBase(base);

    // walls
    List<Killable> walls = new ArrayList<>();
    List<WallBean> retWalls = myState.getMyWalls();
    for (WallBean w : retWalls) {
      Killable tempWall = new Killable();
      tempWall.setX(w.getX());
      tempWall.setY(w.getY());
      tempWall.setHealth(w.getHp());
      tempWall.setMaxHealth(w.getMaxHp());
      tempWall.setId(w.getId());
      tempWall.setColor(w.getColorHex());
      walls.add(tempWall);
    }
    my.setWalls(walls);

    // turrets
    List<JTurret> turrets = new ArrayList<>();
    List<TurretBean> retTurs = myState.getMyTurrets();
    for (TurretBean t : retTurs) {
      JTurret tempTur = new JTurret();
      tempTur.setX(t.getX());
      tempTur.setY(t.getY());
      tempTur.setHealth(t.getHp());
      tempTur.setMaxHealth(t.getMaxHp());
      tempTur.setId(t.getId());
      tempTur.setColor(t.getColorHex());
      if (t.getAttackStatus()) {
        Attacking tempAttacking = new Attacking();
        tempAttacking.setX(t.getTargetX());
        tempAttacking.setY(t.getTargetY());
        tempAttacking.setDirection(t.getTargetDirection().encode());
        tempAttacking.setRatio(t.getTargetRatio());
        tempTur.setAttacking(tempAttacking);
      }
      turrets.add(tempTur);
    }
    my.setTurrets(turrets);

    // attackers
    List<JAttacker> attackers = new ArrayList<>();
    List<AttackerBean> retAtts = myState.getMyAttackers();
    for (AttackerBean a : retAtts) {
      JAttacker tempAtt = new JAttacker();
      tempAtt.setTargetX(a.getTargetX());
      tempAtt.setTargetY(a.getTargetY());
      tempAtt.setX(a.getX());
      tempAtt.setY(a.getY());
      tempAtt.setHealth(a.getHp());
      tempAtt.setMaxHealth(a.getMaxHp());
      tempAtt.setId(a.getId());
      tempAtt.setColor(a.getColorHex());
      Movement movement = new Movement();
      movement.setDirection(a.getDirection());
      movement.setRatio(a.getRatio());
      movement.setMotion(a.inMotion());
      tempAtt.setMovement(movement);
      tempAtt.setAttacking(a.attackStatus());
      attackers.add(tempAtt);
    }
    my.setAttackers(attackers);

    // mines
    List<Killable> mines = new ArrayList<>();
    List<MineBean> retMines = myState.getMyMines();
    for (MineBean m : retMines) {
      Killable tempMine = new Killable();
      tempMine.setX(m.getX());
      tempMine.setY(m.getY());
      tempMine.setHealth(m.getHp());
      tempMine.setMaxHealth(m.getMaxHp());
      tempMine.setId(m.getId());
      tempMine.setColor(m.getColorHex());
      mines.add(tempMine);
    }
    my.setMines(mines);

    // scaffolds
    List<Killable> scaffoldings = new ArrayList<>();
    List<ScaffoldBean> retScaffolds = myState.getMyScaffolds();
    for (ScaffoldBean s : retScaffolds) {
      Killable tempScaf = new Killable();
      tempScaf.setX(s.getX());
      tempScaf.setY(s.getY());
      tempScaf.setHealth(s.getHp());
      tempScaf.setMaxHealth(s.getMaxHp());
      tempScaf.setId(s.getId());
      tempScaf.setColor(s.getColorHex());
      scaffoldings.add(tempScaf);
    }
    my.setScaffoldings(scaffoldings);

    // others (contains all
    Others others = new Others();

    // enemy bases
    List<JBase> enemyBases = new ArrayList<>();
    List<BaseBean> enemyBaseBeans = myState.getEnemyBases();
    for (BaseBean eb : enemyBaseBeans) {
      JBase tempEnemyBase = new JBase();
      tempEnemyBase.setX(eb.getX());
      tempEnemyBase.setY(eb.getY());
      tempEnemyBase.setHealth(eb.getHp());
      tempEnemyBase.setMaxHealth(eb.getMaxHp());
      tempEnemyBase.setColor(eb.getColorHex());
      tempEnemyBase.setName(eb.getName());
      enemyBases.add(tempEnemyBase);
    }
    others.setBases(enemyBases);

    // enemy walls
    List<Killable> enemyWalls = new ArrayList<>();
    List<WallBean> enemyWallBeans = myState.getEnemyWalls();
    for (WallBean ew : enemyWallBeans) {
      Killable tempEnemyWall = new Killable();
      tempEnemyWall.setX(ew.getX());
      tempEnemyWall.setY(ew.getY());
      tempEnemyWall.setHealth(ew.getHp());
      tempEnemyWall.setMaxHealth(ew.getMaxHp());
      tempEnemyWall.setId(ew.getId());
      tempEnemyWall.setColor(ew.getColorHex());
      enemyWalls.add(tempEnemyWall);
    }
    others.setWalls(enemyWalls);

    // enemy turrets
    List<JTurret> enemyTurrets = new ArrayList<>();
    List<TurretBean> enemyTurretBeans = myState.getEnemyTurrets();
    for (TurretBean et : enemyTurretBeans) {
      JTurret tempEnemyTurret = new JTurret();
      tempEnemyTurret.setX(et.getX());
      tempEnemyTurret.setY(et.getY());
      tempEnemyTurret.setHealth(et.getHp());
      tempEnemyTurret.setMaxHealth(et.getMaxHp());
      tempEnemyTurret.setId(et.getId());
      tempEnemyTurret.setColor(et.getColorHex());
      if (et.getAttackStatus()) {
        Attacking tempAttacking = new Attacking();
        tempAttacking.setX(et.getTargetX());
        tempAttacking.setY(et.getTargetY());
        tempAttacking.setDirection(et.getTargetDirection().encode());
        tempAttacking.setRatio(et.getTargetRatio());
        tempEnemyTurret.setAttacking(tempAttacking);
      }
      enemyTurrets.add(tempEnemyTurret);
    }
    others.setTurrets(enemyTurrets);

    // enemy attackers
    List<JAttacker> enemyAttackers = new ArrayList<>();
    List<AttackerBean> enemyAttackerBeans = myState.getEnemyAttackers();
    for (AttackerBean ea : enemyAttackerBeans) {
      JAttacker tempEnemyAttacker = new JAttacker();
      tempEnemyAttacker.setTargetX(ea.getTargetX());
      tempEnemyAttacker.setTargetY(ea.getTargetY());
      tempEnemyAttacker.setX(ea.getX());
      tempEnemyAttacker.setY(ea.getY());
      tempEnemyAttacker.setHealth(ea.getHp());
      tempEnemyAttacker.setMaxHealth(ea.getMaxHp());
      tempEnemyAttacker.setId(ea.getId());
      tempEnemyAttacker.setColor(ea.getColorHex());
      Movement movement = new Movement();
      movement.setDirection(ea.getDirection());
      movement.setRatio(ea.getRatio());
      tempEnemyAttacker.setMovement(movement);
      enemyAttackers.add(tempEnemyAttacker);
      tempEnemyAttacker.setAttacking(ea.attackStatus());
    }
    others.setAttackers(enemyAttackers);

    // enemy mines
    List<Killable> enemyMines = new ArrayList<>();
    List<MineBean> enemyMineBeans = myState.getEnemyMines();
    for (MineBean em : enemyMineBeans) {
      Killable tempEnemyMine = new Killable();
      tempEnemyMine.setX(em.getX());
      tempEnemyMine.setY(em.getY());
      tempEnemyMine.setHealth(em.getHp());
      tempEnemyMine.setMaxHealth(em.getMaxHp());
      tempEnemyMine.setId(em.getId());
      tempEnemyMine.setColor(em.getColorHex());
      enemyMines.add(tempEnemyMine);
    }
    others.setMines(enemyMines);

    // enemy scaffolds
    List<Killable> enemyScaffoldings = new ArrayList<>();
    List<ScaffoldBean> enemyScafs = myState.getEnemyScaffolds();
    for (ScaffoldBean s : enemyScafs) {
      Killable tempEnemyScaf = new Killable();
      tempEnemyScaf.setX(s.getX());
      tempEnemyScaf.setY(s.getY());
      tempEnemyScaf.setHealth(s.getHp());
      tempEnemyScaf.setMaxHealth(s.getMaxHp());
      tempEnemyScaf.setId(s.getId());
      tempEnemyScaf.setColor(s.getColorHex());
      enemyScaffoldings.add(tempEnemyScaf);
    }
    others.setScaffoldings(enemyScaffoldings);

    List<Resources> resources = new ArrayList<>();
    List<ResourceBean> resourceBeans = myState.getResources();
    for (ResourceBean rb : resourceBeans) {
      Resources tempResource = new Resources();
      tempResource.setX(rb.getX());
      tempResource.setY(rb.getY());
      tempResource.setHealth(rb.getHp());
      tempResource.setMaxHealth(rb.getMaxHp());
      resources.add(tempResource);
    }

    payload.setResources(resources);
    payload.setOthers(others);
    payload.setMy(my);
    payload.setId(curSeshId);

    // Put payload into return Json
    Message Json = new Message();
    Json.setPayload(payload);
    Json.setType(Constants.MESSAGE_TYPE.UPDATE.ordinal());

    ObjectMapper mapper = new ObjectMapper();

    return mapper.writeValueAsString(Json);
  }

  private static String fillUpdatePayloadManuallyAndSend(GameState myState,
      Player thisPlayer, Integer curSeshId) {
    StringBuilder payload = new StringBuilder();

    // my
    StringBuilder my = new StringBuilder();
    my.append("{");

    // player stats
    StringBuilder statistics = new StringBuilder();
    statistics.append("{");

    statistics.append("\"name\": \"");
    statistics.append(thisPlayer.getName() + "\",");
    statistics.append("\"resources\":");
    statistics.append(thisPlayer.getResourceCount() + ",");
    statistics.append("\"score\":");
    statistics.append(thisPlayer.getScore() + ",");
    statistics.append("\"mineCost\":");
    statistics.append(
        thisPlayer.multiplyByScoreLogistically(Constants.MINE_COST) + ",");
    statistics.append("\"wallCost\":");
    statistics.append(
        thisPlayer.multiplyByScoreLogistically(Constants.WALL_COST) + ",");
    statistics.append("\"attacker1Cost\":");
    statistics.append(
        thisPlayer.multiplyByScoreLogistically(Constants.ATTACKER_COST) + ",");
    statistics.append("\"turretCost\":");
    statistics.append(
        thisPlayer.multiplyByScoreLogistically(Constants.TURRET_COST) + ",");

    StringBuilder leaderboard = new StringBuilder();
    leaderboard.append("[");
    List<Player> leaders = myState.getLeaders();
    for (Player player : leaders) {
      StringBuilder tempPlayer = new StringBuilder();
      tempPlayer.append("{");
      tempPlayer.append("\"name\": \"");
      tempPlayer.append(player.getName() + "\",");
      tempPlayer.append("\"score\": \"");
      tempPlayer.append(player.getScore() + "\",");
      tempPlayer.append("\"color\": \"");
      tempPlayer.append(player.getColorHex() + "\"");
      tempPlayer.append("}");
      leaderboard.append(tempPlayer);
      leaderboard.append(",");
    }
    leaderboard.append("]");
    statistics.append("\"leaderboard\": ");
    statistics.append(leaderboard);
    statistics.append("}");
    my.append("\"statistics\": ");
    my.append(statistics);
    my.append(",");

    // base
    BaseBean mybb = myState.getMyBase();
    StringBuilder base = new StringBuilder();
    base.append("{");
    base.append("\"x\": \"");
    base.append(mybb.getX() + "\",");
    base.append("\"y\": \"");
    base.append(mybb.getY() + "\",");
    base.append("\"health\": \"");
    base.append(mybb.getHp() + "\",");
    base.append("\"maxHealth\": \"");
    base.append(mybb.getMaxHp() + "\",");
    base.append("\"color\": \"");
    base.append(mybb.getColorHex() + "\",");
    base.append("\"name\": \"");
    base.append(mybb.getName() + "\",");
    base.append("}");
    my.append("\"base\": ");
    my.append(base);
    my.append(",");

    StringBuilder myWalls = new StringBuilder();
    myWalls.append("[");
    List<WallBean> retWalls = myState.getMyWalls();
    for (WallBean w : retWalls) {
      StringBuilder tempWall = new StringBuilder();
      tempWall.append("{");
      tempWall.append("\"x\": \"");
      tempWall.append(w.getX() + "\",");
      tempWall.append("\"y\": \"");
      tempWall.append(w.getY() + "\",");
      tempWall.append("\"health\": \"");
      tempWall.append(w.getHp() + "\",");
      tempWall.append("\"maxHealth\": \"");
      tempWall.append(w.getMaxHp() + "\",");
      tempWall.append("\"color\": \"");
      tempWall.append(w.getColorHex() + "\",");
      tempWall.append("}");
      myWalls.append(tempWall);
      myWalls.append(",");
    }

    my.append("\"walls\": ");
    my.append(myWalls);
    my.append(",");

    StringBuilder myTurrets = new StringBuilder();
    myTurrets.append("[");
    List<TurretBean> retTurrets = myState.getMyTurrets();
    for (TurretBean t : retTurrets) {
      StringBuilder tempTurret = new StringBuilder();
      tempTurret.append("{");
      tempTurret.append("\"x\": \"");
      tempTurret.append(t.getX() + "\",");
      tempTurret.append("\"y\": \"");
      tempTurret.append(t.getY() + "\",");
      tempTurret.append("\"health\": \"");
      tempTurret.append(t.getHp() + "\",");
      tempTurret.append("\"maxHealth\": \"");
      tempTurret.append(t.getMaxHp() + "\",");
      tempTurret.append("\"color\": \"");
      tempTurret.append(t.getColorHex() + "\",");
      if (t.getAttackStatus()) {
        StringBuilder tempAttacking = new StringBuilder();
        tempAttacking.append("{");
        tempAttacking.append("\"x\": \"");
        tempAttacking.append(t.getTargetX() + "\",");
        tempAttacking.append("\"y\": \"");
        tempAttacking.append(t.getTargetY() + "\",");
        tempAttacking.append("\"direction\": \"");
        tempAttacking.append(t.getTargetDirection().encode() + "\",");
        tempAttacking.append("\"ratio\": \"");
        tempAttacking.append(t.getTargetRatio() + "\",");
        tempAttacking.append("}");
        tempTurret.append("\"attacking\": \"");
        tempTurret.append(tempAttacking);
      }
      tempTurret.append("}");
      myTurrets.append(tempTurret);
      myTurrets.append(",");
    }

    my.append("\"turrets\": ");
    my.append(myTurrets);
    my.append(",");

    StringBuilder myAttackers = new StringBuilder();
    myAttackers.append("[");
    List<AttackerBean> retAttackers = myState.getMyAttackers();
    for (AttackerBean a : retAttackers) {
      StringBuilder tempAttacker = new StringBuilder();
      tempAttacker.append("{");
      tempAttacker.append("\"x\": \"");
      tempAttacker.append(a.getX() + "\",");
      tempAttacker.append("\"y\": \"");
      tempAttacker.append(a.getY() + "\",");
      tempAttacker.append("\"health\": \"");
      tempAttacker.append(a.getHp() + "\",");
      tempAttacker.append("\"maxHealth\": \"");
      tempAttacker.append(a.getMaxHp() + "\",");
      tempAttacker.append("\"color\": \"");
      tempAttacker.append(a.getColorHex() + "\",");
      StringBuilder movement = new StringBuilder();
      movement.append("{");
      movement.append("\"direction\": \"");
      movement.append(a.getDirection() + "\",");
      movement.append("\"ratio\": \"");
      movement.append(a.getRatio() + "\"");
      movement.append("}");
      tempAttacker.append("\"movement\":");
      tempAttacker.append(movement);

      tempAttacker.append("}");
      myAttackers.append(tempAttacker);
      myAttackers.append(",");
    }

    my.append("\"attackers\": ");
    my.append(myAttackers);
    my.append(",");

    my.append("\"mines\": [],");
    my.append("\"scaffoldings\": []");
    my.append("}");

    payload.append(my);

    payload.append("\"resources\": [],");

    payload.append("\"id\": " + curSeshId);
    payload.append("}");

    StringBuilder toSend = new StringBuilder();
    toSend.append("{ \"payload\":");
    toSend.append(payload);
    toSend.append(",");

    toSend.append("\"type\": " + Constants.MESSAGE_TYPE.UPDATE.ordinal());
    toSend.append("}");

    return toSend.toString();
  }

  /**
   * Fill the payload for updates given a gamestate and player
   *
   * @param myState
   *          this player's game state
   * @param thisPlayer
   *          this player
   * @param curSeshId
   * @return
   */
  private static String fillUpdatePayloadAndSend(GameState myState,
      Player thisPlayer, Integer curSeshId) {
    JsonObject payload = new JsonObject();

    // me (contains all relevant information for me)
    JsonObject my = new JsonObject();

    // player stats
    JsonObject statistics = new JsonObject();
    statistics.addProperty("name", thisPlayer.getName());
    statistics.addProperty("resources", thisPlayer.getResourceCount());
    statistics.addProperty("score", thisPlayer.getScore());
    statistics.addProperty("mineCost",
        thisPlayer.multiplyByScoreLogistically(Constants.MINE_COST));
    statistics.addProperty("wallCost",
        thisPlayer.multiplyByScoreLogistically(Constants.WALL_COST));
    statistics.addProperty("attacker1Cost",
        thisPlayer.multiplyByScoreLogistically(Constants.ATTACKER_COST));
    statistics.addProperty("turret1Cost",
        thisPlayer.multiplyByScoreLogistically(Constants.TURRET_COST));
    // leaderboard
    JsonArray leaderboard = new JsonArray();
    List<Player> leaders = myState.getLeaders();
    for (Player player : leaders) {
      JsonObject tempPlayer = new JsonObject();
      tempPlayer.addProperty("name", player.getName());
      tempPlayer.addProperty("score", player.getScore());
      tempPlayer.addProperty("color", player.getColorHex());
      leaderboard.add(tempPlayer);
    }
    statistics.add("leaderboard", leaderboard);
    my.add("statistics", statistics);

    // base
    JsonObject base = new JsonObject();
    BaseBean mybb = myState.getMyBase();
    base.addProperty("x", mybb.getX());
    base.addProperty("y", mybb.getY());
    base.addProperty("health", mybb.getHp());
    base.addProperty("maxHealth", mybb.getMaxHp());
    base.addProperty("color", mybb.getColorHex());
    base.addProperty("name", thisPlayer.getName());
    my.add("base", base);

    // walls
    JsonArray walls = new JsonArray();
    List<WallBean> retWalls = myState.getMyWalls();
    for (WallBean w : retWalls) {
      JsonObject tempWall = new JsonObject();
      tempWall.addProperty("x", w.getX());
      tempWall.addProperty("y", w.getY());
      tempWall.addProperty("health", w.getHp());
      tempWall.addProperty("maxHealth", w.getMaxHp());
      tempWall.addProperty("id", w.getId());
      tempWall.addProperty("color", w.getColorHex());
      walls.add(tempWall);
    }
    my.add("walls", walls);

    // turrets
    JsonArray turrets = new JsonArray();
    List<TurretBean> retTurs = myState.getMyTurrets();
    for (TurretBean t : retTurs) {
      JsonObject tempTur = new JsonObject();
      tempTur.addProperty("x", t.getX());
      tempTur.addProperty("y", t.getY());
      tempTur.addProperty("health", t.getHp());
      tempTur.addProperty("maxHealth", t.getMaxHp());
      tempTur.addProperty("id", t.getId());
      tempTur.addProperty("color", t.getColorHex());
      if (t.getAttackStatus()) {
        JsonObject tempAttacking = new JsonObject();
        tempAttacking.addProperty("x", t.getTargetX());
        tempAttacking.addProperty("y", t.getTargetY());
        tempAttacking.addProperty("direction", t.getTargetDirection().encode());
        tempAttacking.addProperty("ratio", t.getTargetRatio());
        tempTur.add("attacking", tempAttacking);
      }
      turrets.add(tempTur);
    }
    my.add("turrets", turrets);

    // attackers
    JsonArray attackers = new JsonArray();
    List<AttackerBean> retAtts = myState.getMyAttackers();
    for (AttackerBean a : retAtts) {
      JsonObject tempAtt = new JsonObject();
      tempAtt.addProperty("targetX", a.getTargetX());
      tempAtt.addProperty("targetY", a.getTargetY());
      tempAtt.addProperty("x", a.getX());
      tempAtt.addProperty("y", a.getY());
      tempAtt.addProperty("health", a.getHp());
      tempAtt.addProperty("maxHealth", a.getMaxHp());
      tempAtt.addProperty("id", a.getId());
      tempAtt.addProperty("color", a.getColorHex());
      JsonObject movement = new JsonObject();
      movement.addProperty("direction", a.getDirection());
      movement.addProperty("ratio", a.getRatio());
      movement.addProperty("motion", a.inMotion());
      tempAtt.add("movement", movement);
      tempAtt.addProperty("isAttacking", a.attackStatus());
      attackers.add(tempAtt);
    }
    my.add("attackers", attackers);

    // mines
    JsonArray mines = new JsonArray();
    List<MineBean> retMines = myState.getMyMines();
    for (MineBean m : retMines) {
      JsonObject tempMine = new JsonObject();
      tempMine.addProperty("x", m.getX());
      tempMine.addProperty("y", m.getY());
      tempMine.addProperty("health", m.getHp());
      tempMine.addProperty("maxHealth", m.getMaxHp());
      tempMine.addProperty("id", m.getId());
      tempMine.addProperty("color", m.getColorHex());
      mines.add(tempMine);
    }
    my.add("mines", mines);

    // scaffolds
    JsonArray scaffoldings = new JsonArray();
    List<ScaffoldBean> retScaffolds = myState.getMyScaffolds();
    for (ScaffoldBean s : retScaffolds) {
      JsonObject tempScaf = new JsonObject();
      tempScaf.addProperty("x", s.getX());
      tempScaf.addProperty("y", s.getY());
      tempScaf.addProperty("health", s.getHp());
      tempScaf.addProperty("maxHealth", s.getMaxHp());
      tempScaf.addProperty("id", s.getId());
      tempScaf.addProperty("color", s.getColorHex());
      scaffoldings.add(tempScaf);
    }
    my.add("scaffoldings", scaffoldings);

    // others (contains all
    JsonObject others = new JsonObject();

    // enemy bases
    JsonArray enemyBases = new JsonArray();
    List<BaseBean> enemyBaseBeans = myState.getEnemyBases();
    for (BaseBean eb : enemyBaseBeans) {
      JsonObject tempEnemyBase = new JsonObject();
      tempEnemyBase.addProperty("x", eb.getX());
      tempEnemyBase.addProperty("y", eb.getY());
      tempEnemyBase.addProperty("health", eb.getHp());
      tempEnemyBase.addProperty("maxHealth", eb.getMaxHp());
      tempEnemyBase.addProperty("color", eb.getColorHex());
      tempEnemyBase.addProperty("name", eb.getName());
      enemyBases.add(tempEnemyBase);
    }
    others.add("bases", enemyBases);

    // enemy walls
    JsonArray enemyWalls = new JsonArray();
    List<WallBean> enemyWallBeans = myState.getEnemyWalls();
    for (WallBean ew : enemyWallBeans) {
      JsonObject tempEnemyWall = new JsonObject();
      tempEnemyWall.addProperty("x", ew.getX());
      tempEnemyWall.addProperty("y", ew.getY());
      tempEnemyWall.addProperty("health", ew.getHp());
      tempEnemyWall.addProperty("maxHealth", ew.getMaxHp());
      tempEnemyWall.addProperty("id", ew.getId());
      tempEnemyWall.addProperty("color", ew.getColorHex());
      enemyWalls.add(tempEnemyWall);
    }
    others.add("walls", enemyWalls);

    // enemy turrets
    JsonArray enemyTurrets = new JsonArray();
    List<TurretBean> enemyTurretBeans = myState.getEnemyTurrets();
    for (TurretBean et : enemyTurretBeans) {
      JsonObject tempEnemyTurret = new JsonObject();
      tempEnemyTurret.addProperty("x", et.getX());
      tempEnemyTurret.addProperty("y", et.getY());
      tempEnemyTurret.addProperty("health", et.getHp());
      tempEnemyTurret.addProperty("maxHealth", et.getMaxHp());
      tempEnemyTurret.addProperty("id", et.getId());
      tempEnemyTurret.addProperty("color", et.getColorHex());
      if (et.getAttackStatus()) {
        JsonObject tempAttacking = new JsonObject();
        tempAttacking.addProperty("x", et.getTargetX());
        tempAttacking.addProperty("y", et.getTargetY());
        tempAttacking.addProperty("direction",
            et.getTargetDirection().encode());
        tempAttacking.addProperty("ratio", et.getTargetRatio());
        tempEnemyTurret.add("attacking", tempAttacking);
      }
      enemyTurrets.add(tempEnemyTurret);
    }
    others.add("turrets", enemyTurrets);

    // enemy attackers
    JsonArray enemyAttackers = new JsonArray();
    List<AttackerBean> enemyAttackerBeans = myState.getEnemyAttackers();
    for (AttackerBean ea : enemyAttackerBeans) {
      JsonObject tempEnemyAttacker = new JsonObject();
      tempEnemyAttacker.addProperty("targetX", ea.getTargetX());
      tempEnemyAttacker.addProperty("targetY", ea.getTargetY());
      tempEnemyAttacker.addProperty("x", ea.getX());
      tempEnemyAttacker.addProperty("y", ea.getY());
      tempEnemyAttacker.addProperty("health", ea.getHp());
      tempEnemyAttacker.addProperty("maxHealth", ea.getMaxHp());
      tempEnemyAttacker.addProperty("id", ea.getId());
      tempEnemyAttacker.addProperty("color", ea.getColorHex());
      JsonObject movement = new JsonObject();
      movement.addProperty("direction", ea.getDirection());
      movement.addProperty("ratio", ea.getRatio());
      tempEnemyAttacker.add("movement", movement);
      enemyAttackers.add(tempEnemyAttacker);
      tempEnemyAttacker.addProperty("isAttacking", ea.attackStatus());
    }
    others.add("attackers", enemyAttackers);

    // enemy mines
    JsonArray enemyMines = new JsonArray();
    List<MineBean> enemyMineBeans = myState.getEnemyMines();
    for (MineBean em : enemyMineBeans) {
      JsonObject tempEnemyMine = new JsonObject();
      tempEnemyMine.addProperty("x", em.getX());
      tempEnemyMine.addProperty("y", em.getY());
      tempEnemyMine.addProperty("health", em.getHp());
      tempEnemyMine.addProperty("maxHealth", em.getMaxHp());
      tempEnemyMine.addProperty("id", em.getId());
      tempEnemyMine.addProperty("color", em.getColorHex());
      enemyMines.add(tempEnemyMine);
    }
    others.add("mines", enemyMines);

    // enemy scaffolds
    JsonArray enemyScaffoldings = new JsonArray();
    List<ScaffoldBean> enemyScafs = myState.getEnemyScaffolds();
    for (ScaffoldBean s : enemyScafs) {
      JsonObject tempEnemyScaf = new JsonObject();
      tempEnemyScaf.addProperty("x", s.getX());
      tempEnemyScaf.addProperty("y", s.getY());
      tempEnemyScaf.addProperty("health", s.getHp());
      tempEnemyScaf.addProperty("maxHealth", s.getMaxHp());
      tempEnemyScaf.addProperty("id", s.getId());
      tempEnemyScaf.addProperty("color", s.getColorHex());
      enemyScaffoldings.add(tempEnemyScaf);
    }
    others.add("scaffoldings", enemyScaffoldings);

    JsonArray resources = new JsonArray();
    List<ResourceBean> resourceBeans = myState.getResources();
    for (ResourceBean rb : resourceBeans) {
      JsonObject tempResource = new JsonObject();
      tempResource.addProperty("x", rb.getX());
      tempResource.addProperty("y", rb.getY());
      tempResource.addProperty("health", rb.getHp());
      tempResource.addProperty("maxHealth", rb.getMaxHp());
      resources.add(tempResource);
    }

    payload.add("resources", resources);
    payload.add("others", others);
    payload.add("my", my);

    payload.addProperty("id", curSeshId);

    // Put payload into return Json
    JsonObject Json = new JsonObject();
    Json.add("payload", payload);
    Json.addProperty("type", Constants.MESSAGE_TYPE.UPDATE.ordinal());

    return GSON.toJson(Json);
  }

  /**
   * Error sending protocol to the frontend.
   *
   * @param curSeshId
   *          current session id
   * @param errId
   *          error communication protocol
   * @param message
   *          the text of the error
   */
  public static void sendError(Integer curSeshId, Integer errId,
      String message) {
    // Error ids are as follows:
    // 1: error with creation
    // 2: error with attack
    // 3: error with coordinates

    Session curSesh = sessions.get(curSeshId);

    JsonObject payload = new JsonObject();
    payload.addProperty("id", curSeshId);
    payload.addProperty("errId", errId);
    payload.addProperty("message", message);

    JsonObject Json = new JsonObject();
    Json.add("payload", payload);
    Json.addProperty("type", Constants.MESSAGE_TYPE.ERROR.ordinal());
    curSesh.getRemote().sendStringByFuture(GSON.toJson(Json));
  }

  /**
   * A specific signal to be sent to frontend when a player dies.
   *
   * @param playerId
   *          the player id of the player who died.
   */
  public static void gameOver(String playerId) {
    // get the player
    Player tempPlayer = game.getPlayer(playerId);
    // get the session id
    Integer curSeshId = Integer
        .parseInt(playerId.substring(3, playerId.length()));
    // get the session based on session id
    Session curSesh = sessions.get(curSeshId);
    // send a message to the session that this player's game is over
    JsonObject Json = new JsonObject();
    Json.addProperty("type", Constants.MESSAGE_TYPE.GAMEOVER.ordinal());

    JsonObject payload = new JsonObject();
    payload.addProperty("maxScore", tempPlayer.getTopScore());
    Json.add("payload", payload);

    curSesh.getRemote().sendStringByFuture(GSON.toJson(Json));
    // Remove the session from the queue
    sessions.remove(curSeshId);
  }

}
