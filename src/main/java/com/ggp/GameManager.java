package com.ggp;

import com.ggp.utils.PlayerHelpers;
import com.ggp.utils.random.RandomSampler;

import java.util.ArrayList;
import java.util.Iterator;

/**
 * Game manager is given player factories for both players and a game description. Then it runs a match with given time-limits.
 */
public class GameManager {
    public interface IActionSelector {
        /**
         * Select action to play in given state
         * @param s
         * @return selected action or null, if player should decide
         */
        IAction select(ICompleteInformationState s);
    }

    private class ItertorActionSelector implements IActionSelector {
        private Iterator<IAction> iter;

        public ItertorActionSelector(Iterator<IAction> iter) {
            this.iter = iter;
        }

        @Override
        public IAction select(ICompleteInformationState s) {
            if (iter.hasNext()) return iter.next();
            return null;
        }
    }


    private IPlayer player1;
    private IPlayer player2;
    private ICompleteInformationState state;
    private RandomSampler randomActionSelector = new RandomSampler();
    private ArrayList<IGameListener> gameListeners = new ArrayList<>();

    public GameManager(IPlayerFactory playerFactory1, IPlayerFactory playerFactory2, IGameDescription game) {
        this.player1 = playerFactory1.create(game, 1);
        this.player2 = playerFactory2.create(game, 2);
        this.state = game.getInitialState();
    }

    /**
     * Run a match with given time limits
     * @param initTimeoutMillis init time
     * @param actTimeoutMillis time limit for each action selection
     */
    public void run(long initTimeoutMillis, long actTimeoutMillis) {
        run(initTimeoutMillis, actTimeoutMillis, s -> null);
    }

    /**
     * Run a match with given time limits and action selector
     * @param initTimeoutMillis init time
     * @param actTimeoutMillis time limit for each action selection
     * @param actionSelector action selector used to override player's decisions
     */
    public void run(long initTimeoutMillis, long actTimeoutMillis, IActionSelector actionSelector) {
        gameListeners.forEach((listener) -> listener.gameStart(player1, player2));
        gameListeners.forEach((listener) -> listener.playerInitStarted(1));
        player1.init(initTimeoutMillis);
        gameListeners.forEach((listener) -> listener.playerInitFinished(1));
        gameListeners.forEach((listener) -> listener.playerInitStarted(2));
        player2.init(initTimeoutMillis);
        gameListeners.forEach((listener) -> listener.playerInitFinished(2));

        while(!playOneTurn(actTimeoutMillis, actionSelector)) {}
        gameListeners.forEach((listener) -> listener.gameEnd(getPayoff(1), getPayoff(2)));
    }

    /**
     * Runs a match with given time limits and forced actions
     * @param initTimeoutMillis init time
     * @param actTimeoutMillis time limit for each action selection
     * @param forcedActions sequence of forced actions to override player's decisions
     */
    public void run(long initTimeoutMillis, long actTimeoutMillis, Iterator<IAction> forcedActions) {
        run(initTimeoutMillis, actTimeoutMillis, new ItertorActionSelector(forcedActions));
    }

    private boolean playOneTurn(long actTimeoutMillis, IActionSelector actionSelector) {
        if (player1 == null || player2 == null) return true;
        gameListeners.forEach((listener) -> listener.stateReached(state));
        if (state.isTerminal()) return true;
        IAction a = actionSelector.select(state);
        int turn = state.getActingPlayerId();
        if (a == null) {
            if (state.isRandomNode()) {
                IRandomNode rndNode = state.getRandomNode();
                a = randomActionSelector.select(state.getLegalActions(), action -> rndNode.getActionProb(action)).getResult();
            } else {
                a = PlayerHelpers.callWithSelectedParam(turn, player1, player2, p -> p.act(actTimeoutMillis));
                if (!state.isLegal(a)) {
                    throw new IllegalStateException(String.format("Player %d chose illegal action %s", turn, a));
                }
            }
        } else {
            if (!state.isLegal(a)) {
                throw new IllegalStateException(String.format("Illegal forced action %s", a));
            }
            if (!state.isRandomNode()) {
                final IAction forcedAction = a;
                PlayerHelpers.callWithSelectedParamVoid(turn, player1, player2, p -> p.forceAction(forcedAction, actTimeoutMillis));
            }
        }

        final IAction selectedAction = a;

        gameListeners.forEach((listener) -> listener.actionSelected(state, selectedAction));
        Iterable<IPercept> percepts = state.getPercepts(a);
        state = state.next(a);
        for (IPercept p: percepts) {
            PlayerHelpers.callWithSelectedParamVoid(p.getTargetPlayer(), player1, player2, player -> player.receivePercepts(p));
        }
        return false;
    }

    /**
     * Returns payoff after the match has finished.
     * @param role player for which to return the payoff (1/2)
     * @return payoff
     */
    public int getPayoff(int role) {
        return (int) state.getPayoff(role);
    }

    public void registerGameListener(IGameListener listener) {
        if (listener != null) gameListeners.add(listener);
    }
}
