package com.ggp.cli;

import com.ggp.*;
import com.ggp.parsers.ParseUtils;
import com.ggp.parsers.exceptions.ConfigAssemblyException;
import com.ggp.players.continual_resolving.cfrd.AugmentedIS.CFRDAugmentedCISWrapper;
import com.ggp.players.continual_resolving.cfrd.AugmentedIS.CFRDAugmentedGameDescriptionWrapper;
import com.ggp.players.continual_resolving.cfrd.CFRDGadgetRoot;
import com.ggp.players.continual_resolving.trackers.CFRDTracker;
import com.ggp.players.continual_resolving.trackers.IGameTraversalTracker;
import com.ggp.players.continual_resolving.trackers.SimpleTracker;
import com.ggp.players.continual_resolving.utils.CISRange;
import com.ggp.players.continual_resolving.utils.SubgameMap;
import com.ggp.solvers.cfr.BaseCFRSolver;
import com.ggp.utils.PlayerHelpers;
import com.ggp.utils.exploitability.ExploitabilityUtils;
import com.ggp.utils.exploitability.ImperfectRecallExploitability;
import com.ggp.utils.strategy.NormalizingStrategyWrapper;
import com.ggp.utils.strategy.PlayerLimitedStrategy;
import com.ggp.utils.strategy.ReplacedStrategy;
import com.ggp.utils.strategy.Strategy;
import com.ggp.utils.time.StopWatch;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import picocli.CommandLine;

import java.io.*;
import java.util.*;

@CommandLine.Command(name = "cfrd-eval",
        mixinStandardHelpOptions = true,
        description = "Evaluate single CFR-D step",
        optionListHeading = "%nOptions:%n",
        sortOptions = false
)
class CFRDEvalCommand implements Runnable {
    @CommandLine.ParentCommand
    private MainCommand mainCommand;

    @CommandLine.Option(names={"-g", "--game"}, description="game to be played (IGameDescription)", required=true)
    private IGameDescription game;

    @CommandLine.Option(names={"--subgame"}, description="subgame - specified by array of action indices to reach it (int[])", required=true)
    private String subgame;

    @CommandLine.Option(names={"-s", "--solver"}, description="CFR solver used for resolving (and trunk computation if trunk strategy is not provided) (BaseCFRSolver.Factory)", required=true)
    private BaseCFRSolver.Factory solver;

    @CommandLine.Option(names={"-i", "--init"}, description="Time limit (s) to compute trunk strategy", required=true)
    private long init;

    @CommandLine.Option(names={"-t", "--time-limit"}, description="Time limit (s) to re-solve subgame", required=true)
    private long timeLimit;

    @CommandLine.Option(names={"-f", "--eval-freq"}, description="Evaluation frequency (ms) of combined strategy", defaultValue = "-1")
    private long evalFreq;

    @CommandLine.Option(names={"-c", "--count"}, description="How many times to repeat the evaluation", defaultValue="1")
    private int count;

    @CommandLine.Option(names={"-d", "--dry-run"}, description="Dry run - doesn't save output")
    private boolean dryRun;

    @CommandLine.Option(names={"-q", "--quiet"}, description="Quiet mode - doesn't print output")
    private boolean quiet;

    @CommandLine.Option(names={"--skip-warmup"}, description="Skip warm-up")
    private boolean skipWarmup;

    @CommandLine.Option(names={"--res-dir"}, description="Results directory", defaultValue="cfrd-results")
    private String resultsDirectory;

    @CommandLine.Option(names={"--trunk-strategy"}, description="Trunk strategy to load")
    private String trunkStratFile;

    @CommandLine.Option(names={"--res-postfix"}, description="Postfix for result files", defaultValue="0")
    private String resultPostfix;

    @CommandLine.Option(names={"--use-cbr"}, description="Compute exact CFV using best response instead of opponent's trunk strategy")
    private boolean useCBR;

    @CommandLine.Option(names={"--cfv-noise-std"}, description="Computed opponent's CFV will be multiplied by values drawn from normal distribution with mean 1 and given std.", defaultValue = "0")
    private double cfvNoiseStd;

    private String getDateKey() {
        return String.format("%1$tY%1$tm%1$td-%1$tH%1$tM%1$tS", new Date());
    }

    private String getCSVName() {
        return String.format("%d-%d-%s-%s.csv", timeLimit, evalFreq, getDateKey(), resultPostfix.replace("-", ""));
    }

    private void printUniformExp(IGameDescription gameDesc) {
        if (quiet) return;
        StopWatch expTimer = new StopWatch();
        expTimer.start();
        double exp = ExploitabilityUtils.computeExploitability(new Strategy(), gameDesc);
        expTimer.stop();
        System.out.println("Exploitability estimate for uniform strategy: " + exp + " in " + expTimer.getDurationMs() + " ms");
    }

    private void warmup(BaseCFRSolver.Factory usedSolverFactory, ICompleteInformationState root,
                        IGameDescription gameDesc, IStrategy trunkStrategy) {
        if (skipWarmup) return;
        if (!quiet) System.out.println("Warming up ...");

        StopWatch warmupTimer = new StopWatch();
        warmupTimer.start();
        do {
            runSubgameSolver(usedSolverFactory, root, gameDesc, trunkStrategy, 1000, 2, true, new StringWriter(), 1, 0);
        }  while (warmupTimer.getLiveDurationMs() < 30000);

        if (!quiet) System.out.println(String.format("Warm-up complete in %dms.", warmupTimer.getLiveDurationMs()));
    }

    private Strategy runTrunkSolver(BaseCFRSolver.Factory usedSolverFactory, IGameDescription gameDesc, long limitMs) {
        BaseCFRSolver cfrSolver = usedSolverFactory.create(null);
        IGameTraversalTracker tracker = SimpleTracker.createRoot(gameDesc.getInitialState());
        StopWatch timer = new StopWatch();
        timer.start();
        do {
            cfrSolver.runIteration(tracker);
        } while (timer.getLiveDurationMs() < limitMs);
        Strategy ret = cfrSolver.getFinalCumulativeStrat();
        ret.normalize();
        return ret;
    }

    private void findSubgames(CFRDTracker tracker, SubgameMap subgameMap) {
        ICompleteInformationState s = tracker.getCurrentState();
        if (s.isTerminal()) return;
        if (s.getActingPlayerId() == tracker.getMyId()) {
            subgameMap.addSubgameState(s, tracker.getLastSubgameRoot());
        }
        for (IAction a: s.getLegalActions()) {
            findSubgames(tracker.next(a), subgameMap);
        }

    }

    private String getSubgameStringSpecifier() {
        return subgame.replace(" ", "");
    }

    private void runSubgameSolver(BaseCFRSolver.Factory usedSolverFactory, ICompleteInformationState root,
                                      IGameDescription gameDesc, IStrategy trunkStrategy, long freqMs, long evalEntries,
                                      boolean quiet, Writer fileOutput, int myId, double trunkExp) {
        try {
            CSVPrinter csvOut = new CSVPrinter(fileOutput,
                    CSVFormat.EXCEL.withHeader("intended_time", "time", "iterations", "states", "exp", "avg_regret", "trunk_exp", "subgame_exp"));
            BaseCFRSolver cfrSolver = usedSolverFactory.create(null);
            IGameTraversalTracker tracker = SimpleTracker.createRoot(root);
            int entryIdx = 0;
            final long evaluateAfterMs = freqMs;
            StopWatch timer = new StopWatch(), evaluationTimer = new StopWatch();
            timer.start();
            evaluationTimer.start();
            long iter = 0, lastEvalIters = 0;
            while (entryIdx < evalEntries) {
                do {
                    iter++;
                    cfrSolver.runIteration(tracker);
                } while (timer.getLiveDurationMs() < (entryIdx+1)*evaluateAfterMs);

                timer.stop();
                evaluationTimer.stop();
                long visitedStates = cfrSolver.getVisitedStates();
                double subgameExp = ImperfectRecallExploitability.computeExploitability(new NormalizingStrategyWrapper(cfrSolver.getCumulativeStrat()), root);
                double exp = ExploitabilityUtils.computeExploitability(new NormalizingStrategyWrapper(new ReplacedStrategy(trunkStrategy, new PlayerLimitedStrategy(cfrSolver.getCumulativeStrat(), myId))), gameDesc);
                double avgRegret = cfrSolver.getTotalRegret() / iter;
                csvOut.printRecord((entryIdx+1) * evaluateAfterMs ,timer.getDurationMs(), iter, visitedStates, exp, avgRegret, trunkExp, subgameExp);
                csvOut.flush();

                String status = String.format("(%8d ms, %10d iterations, %12d states) -> (%10.4g exp, %10.4g subgame exp, %10.4g avg. regret) | %.4g iters/s",
                        timer.getDurationMs(), iter, visitedStates, exp, subgameExp, avgRegret, 1000*(iter - lastEvalIters)/((double)evaluationTimer.getDurationMs()));
                if (!quiet) {
                    System.out.println(status);
                }
                while (timer.getDurationMs() >= (entryIdx+1)*evaluateAfterMs) entryIdx++;
                lastEvalIters = iter;
                evaluationTimer.reset();
                timer.start();
            }
            csvOut.close();
        } catch (Exception e) {
        }
    }

    private ICompleteInformationState getSubgameState(IGameDescription gameDesc, int[] actions, boolean quiet) {
        ICompleteInformationState subgameState = gameDesc.getInitialState();
        for (int actionIdx: actions) {
            if (subgameState == null) {
                throw new CommandLine.ParameterException(new CommandLine(this), "Invalid subgame: null state", null, getSubgameStringSpecifier());
            }
            if (subgameState.isTerminal()) {
                throw new CommandLine.ParameterException(new CommandLine(this), "Invalid subgame: terminal state " + subgameState, null, getSubgameStringSpecifier());
            }
            List<IAction> legalActions = subgameState.getLegalActions();
            IAction a;
            if (actionIdx >= legalActions.size()) {
                throw new CommandLine.ParameterException(new CommandLine(this), "Invalid subgame: invalid action idx = " + actionIdx, null, getSubgameStringSpecifier());
            } else {
                a = legalActions.get(actionIdx);
                if (!quiet) System.out.println("Got action " + a);
            }
            subgameState = subgameState.next(a);
        }
        return subgameState;
    }

    private double findSubgameInfo(CFRDTracker tracker, Set<ICompleteInformationState> rootStates, IStrategy strat,
                                   HashMap<IInformationSet, Double> opponentCFV, HashMap<ICompleteInformationState, Double> reachProbs,
                                   double myProb, double oppProb) {
        ICompleteInformationState s = tracker.getCurrentState();
        if (s.isTerminal()) return tracker.getPayoff(PlayerHelpers.getOpponentId(tracker.getMyId()));
        double utility = 0;
        if (s.isRandomNode()) {
            for (IRandomNode.IRandomNodeAction rna: s.getRandomNode()) {
                utility += rna.getProb() * findSubgameInfo(tracker.next(rna.getAction()), rootStates, strat, opponentCFV, reachProbs, myProb, oppProb);
            }
        } else {
            IInfoSetStrategy isStrat = strat.getInfoSetStrategy(s.getInfoSetForActingPlayer());
            int actionIdx = 0;
            for (IAction a: s.getLegalActions()) {
                double aProb = isStrat.getProbability(actionIdx);
                double actionUtil;
                if (s.getActingPlayerId() == tracker.getMyId()) {
                    actionUtil = findSubgameInfo(tracker.next(a), rootStates, strat, opponentCFV, reachProbs,aProb* myProb, oppProb);
                } else {
                    actionUtil = findSubgameInfo(tracker.next(a), rootStates, strat, opponentCFV, reachProbs, myProb, aProb * oppProb);
                }
                utility += aProb * actionUtil;
                actionIdx++;
            }
        }

        double probWithoutOpponent = tracker.getRndProb() * myProb;
        if (rootStates.contains(s)) {
            opponentCFV.merge(((CFRDAugmentedCISWrapper)s).getOpponentsAugmentedIS(), probWithoutOpponent * utility, (oldV, newV) -> oldV + newV);
            reachProbs.merge(s, probWithoutOpponent, (oldV, newV) -> oldV + newV);
        }
        return utility;
    }

    @Override
    public void run() {
        if (evalFreq <= 0) {
            evalFreq = timeLimit*1000;
        }
        if (cfvNoiseStd < 0) {
            cfvNoiseStd = 0;
        }
        if (game == null) {
            System.out.println("Game can't be null!");
            return;
        }
        if (solver == null) {
            System.out.println("Solver can't be null!");
            return;
        }
        if (subgame == null) {
            System.out.println("Subgame can't be null!");
            return;
        }

        int[] actions = null;
        try {
            actions = mainCommand.getConfigurableFactory().create(int[].class, ParseUtils.parseConfigExpression(subgame));
        } catch (ConfigAssemblyException e) {
            System.out.println("Invalid subgame specifier!");
            return;
        }

        ICompleteInformationState subgameState = null;
        subgameState = getSubgameState(game, actions, quiet);
        String gameDir = resultsDirectory + "/" + game.getConfigString() + "-" + getSubgameStringSpecifier();
        String solverDir =  gameDir + "/" + solver.getConfigString() + "-" + (useCBR ? "T" : "F") + "-" + String.format("%.4f", cfvNoiseStd);
        if (!quiet) {
            if (dryRun) {
                System.out.println("Re-solving in subgame given by " + subgameState + " with " + solver.getConfigString());
            } else {
                System.out.println("Re-solving in subgame given by " + subgameState + " with " + solver.getConfigString() + " logged to " + solverDir);
            }
        }

        if (subgameState == null || subgameState.isTerminal() || subgameState.isRandomNode()) {
            if (subgameState == null) {
                System.out.println("Null state was reached - game implementation is incorrect!");
            } else if (subgameState.isTerminal()) {
                System.out.println("Subgame state can't be terminal!");
            } else if (subgameState.isRandomNode()) {
                System.out.println("Subgame state can't be random node!");
            }
            return;
        }

        final int myId = subgameState.getActingPlayerId();
        final int opponentId = PlayerHelpers.getOpponentId(myId);
        IGameDescription wrappedGame = new CFRDAugmentedGameDescriptionWrapper(game, opponentId);
        // get wrapped subgameState
        subgameState = getSubgameState(wrappedGame, actions, true);

        if (!dryRun)
            new File(solverDir).mkdirs();


        printUniformExp(wrappedGame);

        // find the subgame
        SubgameMap subgameMap = new SubgameMap(opponentId);
        CFRDTracker rootTracker = CFRDTracker.create(myId, wrappedGame.getInitialState(), 1);
        findSubgames(rootTracker, subgameMap);
        Set<ICompleteInformationState> subgameRootStates = subgameMap.getSubgame(subgameState.getActingPlayerId() == myId ? subgameState.getInfoSetForActingPlayer() : ((CFRDAugmentedCISWrapper)subgameState).getOpponentsAugmentedIS());

        if (!quiet) System.out.println("Subgame root size: " + subgameRootStates.size());

        Strategy trunkStrategy, trunkBestResponse = null;

        if (trunkStratFile != null) {
            try (FileInputStream fileInputStream = new FileInputStream(trunkStratFile)) {
                ObjectInputStream objectInputStream = new ObjectInputStream(fileInputStream);
                trunkStrategy = (Strategy) objectInputStream.readObject();
                trunkStrategy.normalize();
                if (trunkStrategy == null) throw new Exception("Loaded trunk strategy is null!");
            } catch (Exception e) {
                System.out.println("Loading trunk strategy '" + trunkStratFile + "' failed - " + e.getMessage());
                return;
            }
        } else {
            trunkStrategy = runTrunkSolver(solver, wrappedGame, init * 1000);
        }
        if (useCBR) {
            trunkBestResponse = new Strategy();
            if (!quiet) System.out.println("Using CBR strategy to compute opponent CFVs");
        }

        double trunkExp = ExploitabilityUtils.computeExploitability(trunkStrategy, wrappedGame, trunkBestResponse);
        if (!quiet) {
            System.out.println("Trunk strategy's exploitability " + trunkExp);
        }

        // compute opponentCFV and reachProbs using trunk
        HashMap<IInformationSet, Double> opponentCFV = new HashMap<>();
        HashMap<ICompleteInformationState, Double> reachProbs = new HashMap<>();
        IStrategy cfvStrategy = useCBR ? new ReplacedStrategy(trunkStrategy, new PlayerLimitedStrategy(trunkBestResponse, opponentId)) : trunkStrategy;
        findSubgameInfo(rootTracker, subgameRootStates, cfvStrategy, opponentCFV, reachProbs, 1, 1);
        if (cfvNoiseStd > 0) {
            if (!quiet) System.out.println(String.format("Multiplying opponent's CFV with Norm(1, %.4g)", cfvNoiseStd));
        }

        // resolve subgame
        CFRDGadgetRoot subgameRoot = new CFRDGadgetRoot(new CISRange(subgameRootStates, reachProbs, 1), opponentCFV, 1, opponentId);
        warmup(solver, subgameRoot, wrappedGame, trunkStrategy);

        final int evalEntriesCount = (int)(timeLimit*1000/evalFreq);
        for (int i = 0; i < count; ++i) {
            if (cfvNoiseStd > 0) {
                HashMap<IInformationSet, Double> iterOpponentCFV = new HashMap<>(opponentCFV);
                Random rnd = new Random();
                iterOpponentCFV.replaceAll((is, cfv) -> cfv * (1 + rnd.nextGaussian()* cfvNoiseStd));
                subgameRoot = new CFRDGadgetRoot(new CISRange(subgameRootStates, reachProbs, 1), iterOpponentCFV, 1, opponentId);
            }
            System.out.println();
            String csvFileName = solverDir + "/" + getCSVName();
            try {
                Writer output = dryRun ? new StringWriter() : new FileWriter(csvFileName);
                runSubgameSolver(solver, subgameRoot, wrappedGame, trunkStrategy, evalFreq, evalEntriesCount, quiet, output, myId, trunkExp);
            } catch (IOException e) {
                continue;
            }
        }
    }
}
