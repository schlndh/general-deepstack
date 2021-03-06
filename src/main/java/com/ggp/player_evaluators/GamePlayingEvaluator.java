package com.ggp.player_evaluators;

import com.ggp.*;
import com.ggp.player_evaluators.listeners.StrategyAggregatorListener;
import com.ggp.player_evaluators.savers.GamePlayingSaver;
import com.ggp.utils.exploitability.ExploitabilityUtils;
import com.ggp.utils.random.RandomSampler;
import com.ggp.utils.strategy.Strategy;
import com.ggp.utils.strategy.NormalizingStrategyWrapper;
import com.ggp.utils.time.TimeLimit;

import java.io.*;
import java.util.*;

/**
 * Evaluates player configuration by playing number of games against random opponent, while aggregating computed strategies
 * at encountered decision points.
 */
public class GamePlayingEvaluator implements IPlayerEvaluator {
    public static class Factory implements IFactory {
        private final int gameCount;
        private final int firstGameRoleAssignment;

        public Factory(int gameCount) {
            this(gameCount, 1);
        }

        public Factory(int gameCount, int firstGameRoleAssignment) {
            if (gameCount < 1) {
                throw new IllegalArgumentException("At least one match must be played!");
            }
            if (firstGameRoleAssignment != 1 && firstGameRoleAssignment != 2) {
                throw new IllegalArgumentException("First game role must be either 1 or 2!");
            }
            this.gameCount = gameCount;
            this.firstGameRoleAssignment = firstGameRoleAssignment;
        }

        @Override
        public IPlayerEvaluator create(int initMs, List<Integer> logPointsMs) {
            return new GamePlayingEvaluator(initMs, logPointsMs, gameCount, firstGameRoleAssignment);
        }

        @Override
        public String getConfigString() {
            return "GamePlayingEvaluator{" +
                    gameCount +
                    ',' + firstGameRoleAssignment +
                    '}';
        }

        @Override
        public IPlayerEvaluationSaver createSaver(String path, int initMs, String postfix) throws IOException {
            return new GamePlayingSaver(path, initMs, postfix, gameCount);
        }
    }

    private int initMs;
    private int timeoutMs;
    StrategyAggregatorListener stratAggregator;
    private final int gameCount;
    private final int firstGameRoleAssignment;
    private HashMap<IInformationSet, HashSet<IAction>> visitedActions = new HashMap<>();

    /**
     * Constructor
     * @param initMs timeout for player initialization
     * @param logPointsMs ASC ordered list of times when strategies should be aggregated
     * @param gameCount number of games to play
     * @param firstGameRoleAssignment player's role assignment for the first game (1/2)
     */
    public GamePlayingEvaluator(int initMs, List<Integer> logPointsMs, int gameCount, int firstGameRoleAssignment) {
        this.initMs = initMs;
        this.timeoutMs = logPointsMs.get(logPointsMs.size() - 1);
        this.stratAggregator = new StrategyAggregatorListener(initMs, logPointsMs);
        this.gameCount = gameCount;
        this.firstGameRoleAssignment = firstGameRoleAssignment;
    }

    private void visit(HashSet<IInformationSet> infoSets, ICompleteInformationState s) {
        if (s.isTerminal()) {
            return;
        }
        if (s.isRandomNode()) {
            for (IAction a: s.getLegalActions()) {
                visit(infoSets, s.next(a));
            }
        } else {
            infoSets.add(s.getInfoSetForActingPlayer());
            for (IAction a: s.getLegalActions()) {
                visit(infoSets, s.next(a));
            }
        }
    }

    private int countInfoSets(IGameDescription gameDesc) {
        HashSet<IInformationSet> infoSets = new HashSet<>();
        visit(infoSets, gameDesc.getInitialState());
        return infoSets.size();
    }

    private static class TerminalAvoidingRandomPlayer implements IPlayer {
        public static class Factory implements IPlayerFactory {
            @Override
            public IPlayer create(IGameDescription game, int role) {
                return new TerminalAvoidingRandomPlayer(role);
            }

            @Override
            public String getConfigString() {
                return "TerminalAvoidingRandomPlayer{}";
            }
        }
        private int role;
        private ICompleteInformationState state;
        private RandomSampler sampler = new RandomSampler();

        public TerminalAvoidingRandomPlayer(int role) {
            this.role = role;
        }

        @Override
        public void init(long timeoutMillis) {
        }

        @Override
        public IAction act(long timeoutMillis) {
            List<IAction> actions = new ArrayList<>();
            for (IAction a: state.getLegalActions()) {
                if (!state.next(a).isTerminal()) actions.add(a);
            }
            if (actions.isEmpty()) {
                actions = state.getLegalActions();
            }
            return sampler.select(actions);
        }

        @Override
        public void forceAction(IAction a, long timeoutMillis) {
        }

        @Override
        public int getRole() {
            return role;
        }

        @Override
        public void receivePercepts(IPercept percept) {
        }

        public void setState(ICompleteInformationState state) {
            this.state = state;
        }
    }

    private static class TerminalAvoidingGameListener implements IGameListener {
        private TerminalAvoidingRandomPlayer player;

        @Override
        public void playerInitStarted(int player) {
        }

        @Override
        public void playerInitFinished(int player) {
        }

        @Override
        public void gameStart(IPlayer player1, IPlayer player2) {
            if (player1.getClass() == TerminalAvoidingRandomPlayer.class) {
                player = (TerminalAvoidingRandomPlayer) player1;
            } else  {
                player = (TerminalAvoidingRandomPlayer) player2;
            }
        }

        @Override
        public void gameEnd(int payoff1, int payoff2) {
        }

        @Override
        public void stateReached(ICompleteInformationState s) {
            if (player != null) player.setState(s);
        }

        @Override
        public void actionSelected(ICompleteInformationState s, IAction a) {
        }
    }

    @Override
    public List<EvaluatorEntry> evaluate(IGameDescription gameDesc, IEvaluablePlayer.IFactory playerFactory, boolean quiet, TimeLimit evaluationTimeLimit) {
        IPlayerFactory random = new TerminalAvoidingRandomPlayer.Factory();
        long lastVisitedStates[] = new long[stratAggregator.getEntries().size()];
        playerFactory.registerResolvingListener(stratAggregator);
        final int evalEach = gameCount/Math.min(gameCount, 10);
        int evals = 0;
        final int totalInfoSets = countInfoSets(gameDesc);
        for (int i = 0; i < gameCount; ++i) {
            if (evaluationTimeLimit != null && evaluationTimeLimit.isFinished()) break;
            IPlayerFactory pl1 = playerFactory, pl2 = random;
            if ((i % 2) == (firstGameRoleAssignment % 2)) {
                pl1 = random;
                pl2 = playerFactory;
            }
            stratAggregator.reinit();
            GameManager manager = new GameManager(pl1, pl2, gameDesc);
            manager.registerGameListener(new TerminalAvoidingGameListener());
            manager.run(initMs, timeoutMs);
            List<EvaluatorEntry> entries = stratAggregator.getEntries();
            for (int j = 0; j < entries.size(); ++j) {
                EvaluatorEntry e = entries.get(j);
                e.addPathStates(e.getAvgVisitedStates() - lastVisitedStates[j], 1);
                lastVisitedStates[j] = e.getAvgVisitedStates();
            }
            if (!quiet) {
                EvaluatorEntry lastEntry = entries.get(entries.size() - 1);
                Strategy strat = lastEntry.getAggregatedStrat();
                if ((i+1) == evalEach*(evals + 1)) {
                    double exp = ExploitabilityUtils.computeExploitability(new NormalizingStrategyWrapper(strat), gameDesc);
                    System.out.println(String.format("\r[%4d]: (%d ms, %d avg. states, %d/%d IS defined) -> %.4f exp", i+1, (int) lastEntry.getEntryTimeMs(), lastEntry.getAvgVisitedStates()/(i+1), strat.size(), totalInfoSets, exp));
                    evals++;
                } else {
                    System.out.print(String.format("\r[%4d]: (%d ms, %d avg. states, %d/%d IS defined)", i+1, (int) lastEntry.getEntryTimeMs(), lastEntry.getAvgVisitedStates()/(i+1), strat.size(), totalInfoSets));
                }
            }

        }

        for (EvaluatorEntry entry: stratAggregator.getEntries()) {
            entry.setVisitedStatesNorm(gameCount);
        }
        return stratAggregator.getEntries();
    }
}
