package com.ggp.player_evaluators.listeners;

import com.ggp.IInformationSet;
import com.ggp.IStrategy;
import com.ggp.player_evaluators.IEvaluablePlayer;
import com.ggp.player_evaluators.EvaluatorEntry;
import com.ggp.utils.strategy.Strategy;
import com.ggp.IInfoSetStrategy;
import com.ggp.utils.time.TimedCounter;

import java.util.ArrayList;
import java.util.List;

public class StrategyAggregatorListener extends BaseListener {
    private ArrayList<Integer> logPointsMs;
    private ArrayList<EvaluatorEntry> entries;
    private TimedCounter timedCounter;
    private int strategyIdx;
    private int resolves = 0;

    public StrategyAggregatorListener(double initMs, List<Integer> logPointsMs) {
        this.logPointsMs = new ArrayList<>(logPointsMs);
        this.timedCounter = new TimedCounter(logPointsMs);
        this.entries = new ArrayList<>(logPointsMs.size());
        for (int i = 0; i < logPointsMs.size(); ++i) {
            entries.add(new EvaluatorEntry(initMs, logPointsMs.get(i)));
        }
    }

    @Override
    public void initEnd(IEvaluablePlayer.IResolvingInfo resInfo) {
        super.initEnd(resInfo);
        if (resInfo == null) return;
        for (EvaluatorEntry entry: entries) {
            entry.addInitVisitedStates(resInfo.getVisitedStatesInCurrentResolving());
            entry.addInitTime(timedCounter.getLiveDurationMs());
        }
        resolves = 0;
    }

    @Override
    public void resolvingStart(IEvaluablePlayer.IResolvingInfo resInfo) {
        timedCounter.reset();
        strategyIdx = 0;
    }

    private void mergeStrategy(Strategy target, IStrategy source) {
        for (IInformationSet is: source.getDefinedInformationSets()) {
            IInfoSetStrategy isStrat = source.getInfoSetStrategy(is);
            target.addProbabilities(is, actionIdx -> isStrat.getProbability(actionIdx));
        }
    }

    private void updateCurrentEntry(IEvaluablePlayer.IResolvingInfo resInfo) {
        IStrategy strat = resInfo.getNormalizedSubgameStrategy();
        EvaluatorEntry entry = entries.get(strategyIdx);
        entry.addTime(timedCounter.getLiveDurationMs(), 1);
        Strategy target = entry.getAggregatedStrat();
        mergeStrategy(target, strat);

        IStrategy firstActStrategy = resInfo.getNormalizedCompleteStrategy();
        if (firstActStrategy != null && resolves < 1) {
            mergeStrategy(entry.getFirstActionStrat(), firstActStrategy);
        }
        entry.addVisitedStates(resInfo.getVisitedStatesInCurrentResolving());
    }

    @Override
    public void resolvingEnd(IEvaluablePlayer.IResolvingInfo resInfo) {
        if (!hasInitEnded()) return;
        strategyIdx = logPointsMs.size() - 1;
        updateCurrentEntry(resInfo);
        resolves++;
    }

    @Override
    public void resolvingIterationEnd(IEvaluablePlayer.IResolvingInfo resInfo) {
        if (!hasInitEnded()) return;
        if (strategyIdx >= logPointsMs.size() - 1) return;
        int counter = timedCounter.tryIncrement();
        if (strategyIdx != counter) {
            strategyIdx = counter - 1;
            updateCurrentEntry(resInfo);
            strategyIdx++;
        }
    }

    public List<EvaluatorEntry> getEntries() {
        return entries;
    }

}
