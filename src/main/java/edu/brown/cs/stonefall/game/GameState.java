package edu.brown.cs.stonefall.game;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;

import com.google.common.collect.ImmutableList;

import edu.brown.cs.stonefall.gamebean.AttackerBean;
import edu.brown.cs.stonefall.gamebean.BaseBean;
import edu.brown.cs.stonefall.gamebean.MineBean;
import edu.brown.cs.stonefall.gamebean.PlayerStatsBean;
import edu.brown.cs.stonefall.gamebean.ResourceBean;
import edu.brown.cs.stonefall.gamebean.ScaffoldBean;
import edu.brown.cs.stonefall.gamebean.TurretBean;
import edu.brown.cs.stonefall.gamebean.WallBean;
import edu.brown.cs.stonefall.interfaces.Attacker;
import edu.brown.cs.stonefall.map.Grid;
import edu.brown.cs.stonefall.network.WebSockets;
import edu.brown.cs.stonefall.structure.Mine;
import edu.brown.cs.stonefall.structure.Resource;
import edu.brown.cs.stonefall.structure.Scaffold;
import edu.brown.cs.stonefall.structure.Turret;
import edu.brown.cs.stonefall.structure.Wall;

/**
 * A class representing the state of the Game class for one Player.
 */
public class GameState {

  private Game game;
  private Player me;

  private List<Player> leaders;

  private PlayerStatsBean stats;

  private List<WallBean> myWalls;
  private List<WallBean> enemyWalls;

  private List<TurretBean> myTurrets;
  private List<TurretBean> enemyTurrets;

  private List<AttackerBean> myAttackers;
  private List<AttackerBean> enemyAttackers;

  private List<MineBean> myMines;
  private List<MineBean> enemyMines;

  private List<ScaffoldBean> myScaffolds;
  private List<ScaffoldBean> enemyScaffolds;

  private BaseBean myBase;
  private List<BaseBean> enemyBases;

  private List<ResourceBean> resources;

  /**
   * Constructs a GameState in the specified Game, for the specified Player.
   *
   * @param game
   *          The Game to associate this GameState with.
   * @param me
   *          The Player to associate this GameState with.
   */
  GameState(Game game, Player me) {
    this.game = game;
    this.me = me;

    stats = new PlayerStatsBean(me);

    myWalls = new ArrayList<>();
    enemyWalls = new ArrayList<>();

    myAttackers = new ArrayList<>();
    enemyAttackers = new ArrayList<>();

    myTurrets = new ArrayList<>();
    enemyTurrets = new ArrayList<>();

    myMines = new ArrayList<>();
    enemyMines = new ArrayList<>();

    myScaffolds = new ArrayList<>();
    enemyScaffolds = new ArrayList<>();

    enemyBases = new ArrayList<>();

    resources = new ArrayList<>();

    update();
  }

  /**
   * Updates all Entities for this GameState.
   */
  public synchronized void update() {
    myWalls = new ArrayList<>();
    enemyWalls = new ArrayList<>();
    myAttackers = new ArrayList<>();
    enemyAttackers = new ArrayList<>();
    myTurrets = new ArrayList<>();
    enemyTurrets = new ArrayList<>();
    myMines = new ArrayList<>();
    enemyMines = new ArrayList<>();
    myScaffolds = new ArrayList<>();
    enemyScaffolds = new ArrayList<>();
    enemyBases = new ArrayList<>();
    resources = new ArrayList<>();
    leaders = new ArrayList<>();
    updateStats();
    updateBases();
    updateResources();
    updateWalls();
    updateScaffolds();
    updateAttackers();
    updateTurrets();
    updateMines();
    updateLeaders();
  }

  private synchronized void updateLeaders() {
    // priority queue based on player scores
    PriorityQueue<Player> pq = new PriorityQueue<>(Constants.LEADERBOARD_SIZE,
        Comparator.comparing((Player p) -> (p.getScore())));
    game.getPlayers().forEach((key, thisPlayer) -> {
      pq.add(thisPlayer);
      if (pq.size() > Constants.LEADERBOARD_SIZE) {
        pq.poll();
      }
    });

    while (!pq.isEmpty()) {
      leaders.add(pq.poll());
    }
    Collections.reverse(leaders);
  }

  private synchronized void updateStats() {
    stats.update();
  }

  private synchronized void updateResources() {
    for (Map.Entry<String, Resource> resource : game.getResources()
        .entrySet()) {
      resources.add(new ResourceBean(resource.getValue()));
    }
  }

  private synchronized void updateBases() {
    myBase = new BaseBean(me);

    for (Player player : game.getPlayers().values()) {

      // if player is dead, tell frontend to terminate game for them
      if (player.isDead()) {
        WebSockets.gameOver(player.getId());
        game.removePlayer(player);
        continue;
      }

      if (!player.equals(me)) {
        enemyBases.add(new BaseBean(player));
      }
    }
  }

  private synchronized void updateScaffolds() {
    game.getPlayers().forEach((key, player) -> {
      for (Map.Entry<String, Scaffold> scaffold : player.getScaffolds()
          .entrySet()) {
        if (player.getScaffolds().containsKey(scaffold.getKey())) {

          if (player.equals(me)) {
            myScaffolds.add(new ScaffoldBean(scaffold.getKey(), player));
          } else {
            enemyScaffolds.add(new ScaffoldBean(scaffold.getKey(), player));
          }
          scaffold.getValue().update();

          if (scaffold.getValue().isDead()) {
            player.remove(Constants.OBJECT_TYPE.SCAFFOLD.ordinal(),
                scaffold.getKey());
          }
        }
      }
    });
  }

  private synchronized void updateWalls() {
    for (Map.Entry<String, Player> player : game.getPlayers().entrySet()) {
      for (Map.Entry<String, Wall> wall : player.getValue().getWalls()
          .entrySet()) {
        if (player.getValue().getWalls().containsKey(wall.getKey())) {
          if (player.getValue().equals(me)) {
            myWalls.add(new WallBean(wall.getKey(), me));
          } else {
            enemyWalls.add(new WallBean(wall.getKey(), player.getValue()));
          }

          if (wall.getValue().isDead()) {
            player.getValue().remove(Constants.OBJECT_TYPE.WALL.ordinal(),
                wall.getKey());
          }
        }
      }
    }
  }

  private synchronized void updateMines() {
    for (Map.Entry<String, Player> player : game.getPlayers().entrySet()) {
      for (Map.Entry<String, Mine> mine : player.getValue().getMines()
          .entrySet()) {
        if (player.getValue().getMines().containsKey(mine.getKey())) {

          if (player.getValue().equals(me)) {
            myMines.add(new MineBean(mine.getKey(), player.getValue()));
          } else {
            enemyMines.add(new MineBean(mine.getKey(), player.getValue()));
          }

          if (mine.getValue().isDead()) {
            player.getValue().remove(Constants.OBJECT_TYPE.MINE.ordinal(),
                mine.getKey());
          }
        }
      }
    }
  }

  private synchronized void updateAttackers() {
    for (Map.Entry<String, Player> player : game.getPlayers().entrySet()) {
      player.getValue().getAttackers().forEach((key, attacker) -> {
        if (player.getValue().getAttackers().containsKey(key)) {
          if (player.getValue().equals(me)) {
            if (attacker.inMotion()) {
              attacker.updateMotion();
            }
            attacker.setAttackStatus(false);
            if (me.validateTarget(attacker.getTarget(), attacker)) {
              attacker.attack();
              attacker.setAttackStatus(true);
              if (attacker.getTarget().get().isDead()) {
                me.setResourceCount(
                    me.getResourceCount() + me.multiplyByScoreLogistically(
                        attacker.getTarget().get().getReward()));
                attacker.setAttackStatus(false);
                attacker.setTarget(Optional.empty());
              }
            } else {
              if (attacker.getTarget().isPresent()
                  && attacker.getTarget().get() instanceof Attacker
                  && !((Attacker) attacker.getTarget().get()).inMotion()
                  && !Grid.isWithinNBlocks(Constants.MELEE_ATTACKER_RANGE,
                      attacker.getBlock(),
                      attacker.getTarget().get().getBlock())
                  && !attacker.inMotion()) {
                attacker.startChase((Attacker) attacker.getTarget().get());
              }
            }
            myAttackers.add(new AttackerBean(key, me));
          } else {
            enemyAttackers.add(new AttackerBean(key, player.getValue()));
          }

          if (attacker.isDead()) {
            player.getValue().remove(Constants.OBJECT_TYPE.ATTACKER.ordinal(),
                key);
          }
        }
      });
    }
  }

  private synchronized void updateTurrets() {

    for (Map.Entry<String, Player> player : game.getPlayers().entrySet()) {
      for (Map.Entry<String, Turret> turret : player.getValue().getTurrets()
          .entrySet()) {
        if (player.getValue().getTurrets().containsKey(turret.getKey())) {
          if (player.getValue().equals(me)) {
            myTurrets.add(new TurretBean(turret.getKey(), me));
            turretAttack(turret.getValue());
          } else {
            enemyTurrets
                .add(new TurretBean(turret.getKey(), player.getValue()));
          }

          if (turret.getValue().isDead()) {
            player.getValue().remove(Constants.OBJECT_TYPE.TURRET.ordinal(),
                turret.getKey());
          }
        }
      }
    }
  }

  private void turretAttack(Turret turret) {
    boolean attacked = false;

    for (Player player : game.getPlayers().values()) {
      if (!player.equals(me)) {
        for (Attacker attacker : player.getAttackers().values()) {
          turret.setAttackStatus(false);
          if (Grid.isWithinNBlocks(Constants.TURRET_RANGE, attacker.getBlock(),
              turret.getBlock())) {
            turret.attack(attacker);
            turret.setAttackStatus(true);
            turret.setTarget(Optional.of(attacker));
            attacked = true;
            turret.setAttackStatus(true);
            turret.setTarget(Optional.of(attacker));
            if (attacker.isDead()) {
              turret.setAttackStatus(false);
              turret.setTarget(Optional.empty());
              me.setResourceCount(me.getResourceCount()
                  + me.multiplyByScoreLogistically(attacker.getReward()));
            }
            break;
          }
        }
        if (attacked) {
          return;
        }
      }
    }
    turret.setAttackStatus(false);
    turret.setTarget(Optional.empty());
  }

  /**
   * Returns the List of score leaders.
   *
   * @return The list of score leaders.
   */
  public List<Player> getLeaders() {
    return leaders;
  }

  /**
   * Returns the PlayerStatsBean for this GameState.
   *
   * @return The PlayerStatsBean for this GameState.
   */
  public synchronized PlayerStatsBean getPlayerStats() {
    return stats;
  }

  /**
   * Returns the List of this player's WallBeans for this GameState.
   *
   * @return The List of this player's WallBeans for this GameState.
   */
  public synchronized List<WallBean> getMyWalls() {
    return ImmutableList.copyOf(myWalls);
  }

  /**
   * Returns the List of enemy players' WallBeans for this GameState.
   *
   * @return The List of enemy players' WallBeans for this GameState.
   */
  public synchronized List<WallBean> getEnemyWalls() {
    return ImmutableList.copyOf(enemyWalls);
  }

  /**
   * Returns the List of this player's AttackerBeans for this GameState.
   *
   * @return The List of this player's AttackerBeans for this GameState.
   */
  public synchronized List<AttackerBean> getMyAttackers() {
    return ImmutableList.copyOf(myAttackers);
  }

  /**
   * Returns the List of enemy players' AttackerBeans for this GameState.
   *
   * @return The List of enemy players' AttackerBeans for this GameState.
   */
  public synchronized List<AttackerBean> getEnemyAttackers() {
    return ImmutableList.copyOf(enemyAttackers);
  }

  /**
   * Returns the List of this player's TurretBeans for this GameState.
   *
   * @return The List of this player's TurretBeans for this GameState.
   */
  public synchronized List<TurretBean> getMyTurrets() {
    return ImmutableList.copyOf(myTurrets);
  }

  /**
   * Returns the List of enemy players' TurretBeans for this GameState.
   *
   * @return The List of enemy players' TurretBeans for this GameState.
   */
  public synchronized List<TurretBean> getEnemyTurrets() {
    return ImmutableList.copyOf(enemyTurrets);
  }

  /**
   * Returns the List of this player's MineBeans for this GameState.
   *
   * @return The List of this player's MineBeans for this GameState.
   */
  public synchronized List<MineBean> getMyMines() {
    return ImmutableList.copyOf(myMines);
  }

  /**
   * Returns the List of enemy players' MineBeans for this GameState.
   *
   * @return The List of enemy players' MineBeans for this GameState.
   */
  public synchronized List<MineBean> getEnemyMines() {
    return ImmutableList.copyOf(enemyMines);
  }

  /**
   * Returns the BaseBean for this Player.
   *
   * @return The BaseBean for this Player.
   */
  public synchronized BaseBean getMyBase() {
    return myBase;
  }

  /**
   * Returns the List of enemy players' BaseBeans for this GameState.
   *
   * @return The List of enemy players' BaseBeans for this GameState.
   */
  public synchronized List<BaseBean> getEnemyBases() {
    return ImmutableList.copyOf(enemyBases);
  }

  /**
   * Returns the List of ResourceBeans for this GameState.
   *
   * @return The List of ResourceBeans for this GameState.
   */
  public synchronized List<ResourceBean> getResources() {
    return ImmutableList.copyOf(resources);
  }

  /**
   * Returns the List of this player's ScaffoldBeans for this GameState.
   *
   * @return The List of this player's ScaffoldBeans for this GameState.
   */
  public synchronized List<ScaffoldBean> getMyScaffolds() {
    return ImmutableList.copyOf(myScaffolds);
  }

  /**
   * Returns the List of enemy players' ScaffoldBeans for this GameState.
   *
   * @return The List of enemy players' ScaffoldBeans for this GameState.
   */
  public synchronized List<ScaffoldBean> getEnemyScaffolds() {
    return ImmutableList.copyOf(enemyScaffolds);
  }
}
